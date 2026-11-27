package eu.mihosoft.vmf.runtime.core.diff;

import eu.mihosoft.vmf.runtime.core.Immutable;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.VIterator;
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Establishes owned identities before ordinary model references can use them. */
final class DiffOwnership {
    private final Map<VObject, Slot> sourceOwners = new IdentityHashMap<>();
    private final Map<VObject, Slot> desiredOwners = new IdentityHashMap<>();
    private final Map<VObject, Map<String, Slot>> sourceSlots = new IdentityHashMap<>();
    private final Map<VObject, Map<String, Slot>> desiredSlots = new IdentityHashMap<>();
    private final List<VObject> sourceMembers = new ArrayList<>();
    private final List<VObject> desiredMembers = new ArrayList<>();
    private final IdentityHashMap<Object, Object> targets;
    private final Set<Object> paired = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ArrayDeque<Pair> pending = new ArrayDeque<>();
    private final List<SlotPair> keyed = new ArrayList<>();
    private final List<Pair> singular = new ArrayList<>();

    DiffOwnership(VObject source, VObject desired, IdentityHashMap<Object, Object> targets) {
        this.targets = targets;
        index(source, sourceSlots, sourceOwners, sourceMembers);
        index(desired, desiredSlots, desiredOwners, desiredMembers);
        pair(source, desired);
        Set<VObject> reachable = Collections.newSetFromMap(new IdentityHashMap<>());
        VIterator.of(source).forEachRemaining(reachable::add);
        VIterator.of(desired).forEachRemaining(member -> {
            if (!(member instanceof Immutable) && reachable.contains(member)) pair(member, member);
        });
        establish();
    }

    boolean pairDetached(VObject source, VObject desired) {
        if (source.getClass() != desired.getClass() || targets.containsKey(desired) || paired.contains(source)) return false;
        index(source, sourceSlots, sourceOwners, sourceMembers);
        index(desired, desiredSlots, desiredOwners, desiredMembers);
        pair(source, desired);
        establish();
        return true;
    }

    private void establish() {
        do {
            walk();
            for (SlotPair slots : keyed) matchKeyed(slots);
            if (!pending.isEmpty()) continue;
            matchTransfers();
            if (!pending.isEmpty()) continue;
            for (Pair slot : singular) pair(slot.source, slot.desired);
            singular.clear();
        } while (!pending.isEmpty());
    }

    boolean ownsSource(Object member) { return sourceOwners.containsKey(member); }
    boolean ownsDesired(Object member) { return desiredOwners.containsKey(member); }

    boolean pair(VObject source, VObject desired) {
        if (source.getClass() != desired.getClass() || targets.containsKey(desired) || !paired.add(source)) return false;
        targets.put(desired, source);
        // Non-owning detached pairs join later and have no slots in this ownership index.
        if (sourceSlots.containsKey(source) && desiredSlots.containsKey(desired)) pending.add(new Pair(source, desired));
        return true;
    }

    private void walk() {
        while (!pending.isEmpty()) {
            Pair pair = pending.remove();
            var nextSlots = desiredSlots.get(pair.desired);
            for (Slot before : sourceSlots.get(pair.source).values()) {
                Slot after = nextSlots.get(before.property.getName());
                if (after == null || before.property.getType().isListType() != after.property.getType().isListType()
                        || before.property.getType().isReferenceSetType() != after.property.getType().isReferenceSetType()) continue;
                if (before.members.isEmpty() || after.members.isEmpty()) continue;
                if (after.key != null && after.key.equals(before.key)) {
                    keyed.add(new SlotPair(before, after));
                } else if (!after.property.getType().isListType() && !after.property.getType().isReferenceSetType()) {
                    VObject previous = before.members.get(0);
                    VObject next = after.members.get(0);
                    if (sameContents(previous, next)) pair(previous, next);
                    else singular.add(new Pair(previous, next));
                } else {
                    for (VObject next : after.members) {
                        if (targets.containsKey(next)) continue;
                        for (VObject previous : before.members) {
                            if (!paired.contains(previous) && sameContents(previous, next)) {
                                pair(previous, next);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private void matchKeyed(SlotPair slots) {
        for (VObject next : slots.after.members) {
            if (targets.containsKey(next)) continue;
            Object key = keyOf(next, slots.after.key);
            if (key == null) continue;
            for (VObject previous : slots.before.members) {
                if (paired.contains(previous) || previous.getClass() != next.getClass()) continue;
                Object available = keyOf(previous, slots.before.key);
                if (key instanceof VObject ? available == targets.getOrDefault(key, key) : Objects.equals(available, key)) {
                    pair(previous, next);
                    break;
                }
            }
        }
    }

    private void matchTransfers() {
        var available = new ArrayList<VObject>();
        for (VObject member : sourceMembers) {
            Slot owner = sourceOwners.get(member);
            if (owner.key == null && paired.contains(owner.owner) && !paired.contains(member)) available.add(member);
        }
        var unmatched = new ArrayList<VObject>();
        for (VObject member : desiredMembers) {
            if (desiredOwners.get(member).key == null && !targets.containsKey(member)) unmatched.add(member);
        }
        // Java content hashes can differ for equal cyclic or external values. Match by content and require uniqueness.
        for (VObject next : unmatched) {
            VObject previous = uniqueMatch(next, available);
            if (previous != null && uniqueMatch(previous, unmatched) == next) pair(previous, next);
        }
    }

    private static VObject uniqueMatch(VObject model, List<VObject> candidates) {
        VObject matched = null;
        for (VObject candidate : candidates) {
            if (!sameContents(model, candidate)) continue;
            if (matched != null) return null;
            matched = candidate;
        }
        return matched;
    }

    private static boolean sameContents(VObject first, VObject second) {
        return first.getClass() == second.getClass() && first.vmf().content().equals(second);
    }

    private static Object keyOf(VObject model, String property) {
        return model.vmf().reflect().propertyByName(property).orElseThrow().get();
    }

    private static void index(VObject root, Map<VObject, Map<String, Slot>> slots, Map<VObject, Slot> owners,
                              List<VObject> members) {
        var pending = new ArrayDeque<VObject>();
        pending.add(root);
        while (!pending.isEmpty()) {
            var owner = pending.remove();
            if (slots.containsKey(owner)) continue;
            var internal = (VObjectInternal) owner;
            var byName = new LinkedHashMap<String, Slot>();
            slots.put(owner, byName);
            for (int child : internal._vmf_getChildrenIndices()) {
                String name = internal._vmf_getPropertyNames()[child];
                var property = owner.vmf().reflect().propertyByName(name);
                if (property.isEmpty()) continue;
                Object value = property.get().get();
                var values = new ArrayList<VObject>();
                if (value instanceof Iterable) {
                    for (Object item : (Iterable<?>) value) {
                        if (item instanceof VObject) values.add((VObject) item);
                    }
                } else if (value instanceof VObject) {
                    values.add((VObject) value);
                }
                String key = property.get().annotationByKey("vmf:collection:key").map(annotation -> annotation.getValue())
                        .orElse(null);
                var slot = new Slot(owner, property.get(), values, key);
                byName.put(name, slot);
                for (VObject member : values) {
                    if (owners.putIfAbsent(member, slot) == null) {
                        members.add(member);
                        pending.add(member);
                    }
                }
            }
        }
    }

    private static final class Pair {
        final VObject source;
        final VObject desired;
        Pair(VObject source, VObject desired) { this.source = source; this.desired = desired; }
    }

    private static final class Slot {
        final VObject owner;
        final Property property;
        final List<VObject> members;
        final String key;

        Slot(VObject owner, Property property, List<VObject> members, String key) {
            this.owner = owner;
            this.property = property;
            this.members = members;
            this.key = key;
        }
    }

    private static final class SlotPair {
        final Slot before;
        final Slot after;
        SlotPair(Slot before, Slot after) { this.before = before; this.after = after; }
    }
}
