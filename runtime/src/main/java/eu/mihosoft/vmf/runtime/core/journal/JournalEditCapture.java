package eu.mihosoft.vmf.runtime.core.journal;

import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.internal.ChangesImpl;
import eu.mihosoft.vmf.runtime.core.internal.ReflectImpl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.*;

/** Coordinates the journal's committed edit, native delivery and frame publication phases. */
final class JournalEditCapture {
    private final Predicate<VObject> knownIdentity;
    private final Function<Change, List<Operation>> encode;
    private final Map<PreparedEditKey, CapturedOperations> preparedEdits = new LinkedHashMap<>();
    private final List<CapturedOperations> operations = new ArrayList<>();
    private long captureOrder;
    private long capturingOrder;

    JournalEditCapture(Predicate<VObject> knownIdentity, Function<Change, List<Operation>> encode) {
        this.knownIdentity = knownIdentity;
        this.encode = encode;
    }

    void reserveCommittedEdit(Change change) {
        materializePendingIntroductions(change);
        // Keep the causal position while allowing callbacks to finish initializing
        // a new instance that the native stream does not observe yet.
        var capture = introducedValues(change).findAny().isPresent()
                ? new CapturedOperations(captureOrder++, change)
                : captureNow(change);
        preparedEdits.put(PreparedEditKey.from(change), capture);
    }

    /** Keeps the operations of a delivered change at its position in native history. */
    void finishNativeDelivery(Change change, int index) {
        var prepared = preparedEdits.remove(PreparedEditKey.from(change));
        if (prepared == null) {
            materializePendingIntroductions(change);
            prepared = captureNow(change);
        } else {
            prepared.materialize(this);
        }
        operations.add(Math.min(index, operations.size()), prepared);
    }

    boolean preparedDelivery(Change change) {
        var key = PreparedEditKey.from(change);
        return key != null && preparedEdits.containsKey(key);
    }

    boolean hasPendingEdit() {
        return !preparedEdits.isEmpty();
    }

    void discardReservations() {
        preparedEdits.clear();
    }

    List<Operation> operationsForFrame(int from, int to) {
        List<Operation> edits = new ArrayList<>();
        // Native transaction membership stays intact. Within that group, replay the
        // committed edit before any operations caused by its public callbacks.
        operations.subList(from, to).stream().sorted(Comparator.comparingLong(edit -> edit.order))
                .forEachOrdered(edit -> edits.addAll(edit.entries));
        return JournalCreationPlacement.order(edits);
    }

    private CapturedOperations captureNow(Change change) {
        long order = captureOrder++;
        return new CapturedOperations(order, capture(change, order));
    }

    private List<Operation> capture(Change change, long order) {
        long previous = capturingOrder;
        capturingOrder = order;
        try {
            return encode.apply(change);
        } finally {
            capturingOrder = previous;
        }
    }

    private void materializePendingIntroductions(Change next) {
        if (preparedEdits.isEmpty() || JournalSupport.isContainer(next.object(), next.propertyName())) return;
        for (var prepared : preparedEdits.values()) {
            var deferred = prepared.deferred;
            if (deferred == null) continue;
            if (deferred.object() == next.object() && deferred.propertyName().equals(next.propertyName())
                    || isUnknownModel(next.object()) && introducesObject(deferred, next.object())) {
                prepared.materialize(this);
            }
        }
    }

    private Stream<VObject> introducedValues(Change change) {
        var property = change.propertyChange().orElse(null);
        if (property != null) return Stream.of(property.newValue()).filter(this::isUnknownModel).map(VObject.class::cast);
        var list = change.listChange().orElse(null);
        if (list != null) return list.added().elements().stream().filter(this::isUnknownModel).map(VObject.class::cast);
        var set = change.referenceSetChange().orElse(null);
        return set == null ? Stream.empty() : set.added().stream().filter(this::isUnknownModel);
    }

    private boolean introducesObject(Change deferred, VObject required) {
        Set<VObject> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new ArrayDeque<VObject>();
        introducedValues(deferred).forEach(pending::addLast);
        while (!pending.isEmpty()) {
            var model = pending.removeFirst();
            if (!visited.add(model)) continue;
            if (model == required) return true;
            // Match the graph of new definitions Freeze would encode, avoiding inverse ownership and already known objects.
            for (var property : ReflectImpl.readProperties(model.vmf().reflect())) {
                if (JournalSupport.isContainer(model, property.getName())) continue;
                var value = property.get();
                if (isUnknownModel(value)) pending.addLast((VObject) value);
                else if (value instanceof Iterable<?>) {
                    for (var entry : (Iterable<?>) value) {
                        if (isUnknownModel(entry)) pending.addLast((VObject) entry);
                    }
                }
            }
        }
        return false;
    }

    private boolean isUnknownModel(Object value) {
        return value instanceof VObject && !knownIdentity.test((VObject) value);
    }

    boolean reservedByEarlierIntroduction(VObject object) {
        for (var prepared : preparedEdits.values()) {
            if (prepared.order < capturingOrder && prepared.deferred != null
                    && introducesObject(prepared.deferred, object)) return true;
        }
        return false;
    }

    private static final class PreparedEditKey {
        final Object delivery;
        final VObject owner;
        final String property;

        private PreparedEditKey(Object delivery, Change change) {
            this.delivery = delivery;
            this.owner = change.object();
            this.property = change.propertyName();
        }

        static PreparedEditKey from(Change change) {
            Object delivery = ChangesImpl.deliveryIdentity(change);
            return delivery == null ? null : new PreparedEditKey(delivery, change);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof PreparedEditKey)) return false;
            var key = (PreparedEditKey) other;
            return delivery == key.delivery && owner == key.owner && property.equals(key.property);
        }

        @Override public int hashCode() {
            return 31 * (31 * System.identityHashCode(delivery) + System.identityHashCode(owner)) + property.hashCode();
        }
    }

    private static final class CapturedOperations {
        final long order;
        private List<Operation> entries;
        private Change deferred;

        CapturedOperations(long order, List<Operation> entries) {
            this.order = order;
            this.entries = entries;
        }

        CapturedOperations(long order, Change deferred) {
            this.order = order;
            this.deferred = deferred;
        }

        void materialize(JournalEditCapture owner) {
            if (deferred == null) return;
            entries = owner.capture(deferred, order);
            deferred = null;
        }
    }
}
