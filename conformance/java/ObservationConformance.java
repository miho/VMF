package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.ArchiveRoot;
import eu.mihosoft.vmf.conformance.values.ScalarSample;
import eu.mihosoft.vmf.conformance.values.ValueLabel;
import eu.mihosoft.vmf.conformance.values.ValueSelection;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.ChangeListener;
import eu.mihosoft.vmf.runtime.core.Changes;
import eu.mihosoft.vmf.runtime.core.Transaction;
import vjavax.observer.Subscription;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

final class ObservationConformance {
    private ObservationConformance() {}

    public static void main(String[] args) {
        checkScopesAndSubscriptionOwnership();
        checkNonOwningScalarChanges();
        checkReentrantScalarDeliveryAndHistory();
        checkReentrantReferenceDeliveryAndHistory();
        checkFloatingPointEdits();
        checkLiveObserverFailures();
        checkTransactionRanges();
        checkTransactionPublicationFailures();
        checkModelPropertyRecordingRelay("reference");
        checkModelPropertyRecordingRelay("attach");
        checkModelPropertyRecordingRelay("detach");
        checkHistoryPositions(false);
        checkHistoryPositions(true);
        checkHistoryClear(false);
        checkHistoryClear(true);
        checkScalarUndoAndApply();
        System.out.println("Java verified observer scopes, dispatch, transaction publication, and scalar undo and replay.");
    }

    private static void checkHistoryPositions(boolean readOnly) {
        var sample = ScalarSample.newInstance();
        var changes = readOnly ? sample.asReadOnly().vmf().changes() : sample.vmf().changes();
        var history = changes.all();
        var groups = changes.transactions();
        var edits = new ArrayList<Integer>();
        var published = new ArrayList<Integer>();
        var groupCounts = new ArrayList<Integer>();
        var cleared = new ArrayList<String>();
        changes.start();
        var historyObserver = history.addChangeListener(event -> {
            check(event.source() == history, "An edit event has the observed history list as its source");
            if (event.wasAdded()) edits.add(event.added().indices()[0]);
            if (event.wasRemoved()) {
                check(Arrays.equals(event.removed().indices(), new int[]{0, 1})
                        && values(event.removed().elements()).equals(List.of("outer", 7)),
                        "History clear retains removed values and their former positions");
                cleared.add("edits");
            }
        });
        var first = groups.addChangeListener(event -> {
            if (event.wasAdded() && event.added().indices()[0] == 0) {
                sample.setIntValue(7);
                changes.publishTransaction();
            }
        });
        var later = groups.addChangeListener(event -> {
            check(event.source() == groups, "A publication event has the observed transaction list as its source");
            if (event.wasAdded()) {
                int index = event.added().indices()[0];
                check(event.added().elements().get(0) == groups.get(index), "Transaction slot identifies its exact value");
                published.add(index);
                groupCounts.add(groups.size());
            }
            if (event.wasRemoved()) {
                check(Arrays.equals(event.removed().indices(), new int[]{0, 1})
                        && values(event.removed().elements().get(0).changes()).equals(List.of("outer"))
                        && values(event.removed().elements().get(1).changes()).equals(List.of(7)),
                        "Transaction clear retains removed values and their former positions");
                cleared.add("transactions");
            }
        });
        try {
            sample.setDescription("outer");
            changes.publishTransaction();
            same(List.of(0, 1), edits, "Scalar edits carry append positions");
            same(List.of(1, 0), published, "Reentrant publication preserves the outer event's original slot");
            same(List.of(2, 2), groupCounts, "Both deliveries see the same live length");
            changes.clear();
            same(List.of("edits", "transactions"), cleared, "Each history reports the range removed by clear");
            check(history.isEmpty() && groups.isEmpty(), "Clearing preserves the observed list identities");
        } finally {
            historyObserver.unsubscribe();
            first.unsubscribe();
            later.unsubscribe();
            changes.stop();
        }
    }

    private static void checkHistoryClear(boolean readOnly) {
        var sample = ScalarSample.newInstance();
        var changes = readOnly ? sample.asReadOnly().vmf().changes() : sample.vmf().changes();
        changes.start();
        sample.setDescription("old group");
        changes.publishTransaction();

        var observed = new ArrayList<String>();
        var historyListener = changes.all().addChangeListener(change -> {
            if (!change.wasRemoved()) return;
            observed.add("clear edits:" + changes.all().size() + "/" + changes.transactions().size());
            sample.setOptionalInt(88);
            changes.publishTransaction();
        });
        var transactionListener = changes.transactions().addChangeListener(change -> observed.add("groups:+"
                + change.added().elements().size() + "/-" + change.removed().elements().size()
                + "; current=" + changes.transactions().size()));
        try {
            changes.clear();
            check(observed.equals(List.of("clear edits:0/1", "groups:+1/-0; current=2", "groups:+0/-2; current=0"))
                            && changes.all().size() == 1 && changes.transactions().isEmpty()
                            && sample.getOptionalInt() == 88,
                    "History clear runs before clearing transactions, including any group published by its listener");
            changes.stop();
            check(changes.transactions().isEmpty(), "Stopping does not republish the cleared transaction");
        } finally {
            historyListener.unsubscribe();
            transactionListener.unsubscribe();
        }

        changes.start();
        sample.setDescription("retained group");
        changes.publishTransaction();
        var removed = new ArrayList<Transaction>();
        var groupsListener = changes.transactions().addChangeListener(change -> removed.addAll(change.removed().elements()));
        var failure = new IllegalStateException("history listener");
        var throwing = changes.all().addChangeListener(change -> {
            if (change.wasRemoved()) throw failure;
        });
        try {
            try {
                changes.clear();
                throw new AssertionError("The history observer must fail");
            } catch (IllegalStateException propagated) {
                check(propagated == failure, "History clear propagates the original observer failure");
            }
            check(changes.all().isEmpty() && changes.transactions().size() == 1 && removed.isEmpty()
                            && "retained group".equals(sample.getDescription()),
                    "A history listener failure leaves the second phase and live state untouched");
        } finally {
            throwing.unsubscribe();
        }
        try {
            changes.clear();
            check(changes.transactions().isEmpty() && removed.size() == 1,
                    "Retry clears the one retained transaction and notifies its removal");
        } finally {
            groupsListener.unsubscribe();
            changes.stop();
        }
    }

    private static void checkScopesAndSubscriptionOwnership() {
        var archive = ArchiveRoot.newInstance();
        var head = node("head");
        var child = node("child");
        var unowned = node("unowned");
        archive.setHead(head);
        archive.getReview().add(unowned);
        head.getTrail().add(unowned);
        head.getChildren().add(child);

        var local = new ArrayList<Object>();
        ChangeListener collectLocal = change -> collectNamedValue(change, local);
        var first = head.vmf().changes().addLocalListener(collectLocal);
        var second = head.vmf().changes().addLocalListener(collectLocal);
        var recursive = new ArrayList<Object>();
        var selected = new ArrayList<Object>();
        var namedOnRoot = archive.vmf().changes().addPropertyListener("name",
                change -> { throw new AssertionError("local property listener observed a descendant"); });
        var byName = archive.vmf().changes().addPropertyListener("name",
                change -> recursive.add(newValue(change)), Changes.Scope.RECURSIVE);
        var filtered = archive.vmf().changes().addFilteredListener(
                change -> change.propertyName().equals("name") && newValue(change).toString().startsWith("keep "),
                change -> selected.add(newValue(change)), Changes.Scope.RECURSIVE);
        try {
            head.setName("keep head");
            child.setName("keep child");
            unowned.setName("keep outside");
            head.getChildren().remove(child);
            child.setName("keep detached");
            head.getChildren().add(child);
            child.setName("reattached");
            first.unsubscribe();
            first.unsubscribe();
            head.setName("once");
            head.setName(new String("once"));

            same(List.of("keep head", "keep head", "once"), local, "local deliveries and independent subscriptions");
            same(List.of("keep head", "keep child", "reattached", "once"), recursive, "recursive containment membership");
            same(List.of("keep head", "keep child"), selected, "recursive predicate filtering");
            check(head.vmf().changes().all().isEmpty(), "live listeners do not enable recording");
        } finally {
            first.unsubscribe();
            second.unsubscribe();
            namedOnRoot.unsubscribe();
            byName.unsubscribe();
            filtered.unsubscribe();
        }
    }

    private static void checkNonOwningScalarChanges() {
        var owner = ValueSelection.newInstance();
        var first = ValueLabel.newBuilder().withText("same").build();
        var second = ValueLabel.newBuilder().withText("same").build();
        check(first != second && first.equals(second), "fixtures differ by identity only");

        var events = new ArrayList<Change>();
        var observation = owner.vmf().changes().addLocalListener(events::add);
        try {
            owner.setCurrent(first);
            owner.setCurrent(first);
            owner.setCurrent(second);
            owner.setCurrent(null);
            check(events.size() == 3, "same-identity assignment is a no-op");
            assertSnapshot(events.get(0), owner, "current", null, first);
            assertSnapshot(events.get(1), owner, "current", first, second);
            assertSnapshot(events.get(2), owner, "current", second, null);
            var other = ValueSelection.newInstance();
            reject(IllegalArgumentException.class, () -> events.get(1).apply(other));
            check(other.getCurrent() == null, "model-valued replay to another owner is rejected before assignment");
        } finally {
            observation.unsubscribe();
        }
    }

    private static void checkReentrantScalarDeliveryAndHistory() {
        var archive = ArchiveRoot.newInstance();
        var head = node("head");
        var child = node("child");
        archive.setHead(head);
        head.getChildren().add(child);

        var changes = archive.vmf().changes();
        var history = changes.all();
        var deliveries = new ArrayList<String>();
        var second = new Subscription[1];
        var replacement = new Subscription[1];
        changes.start();
        var first = changes.addRecursiveListener(change -> {
            Object value = newValue(change);
            deliveries.add("first " + value);
            check(history.isEmpty(), "scalar history becomes visible after outermost live callbacks");
            if (change.object() == head) {
                check(head.getName().equals("outer"), "callback observes the applied outer edit");
                second[0].unsubscribe();
                replacement[0] = changes.addRecursiveListener(nested -> deliveries.add("replacement " + newValue(nested)));
                child.setName("nested");
                check(child.getName().equals("nested"), "nested callbacks complete before the mutator returns");
            }
        });
        second[0] = changes.addRecursiveListener(change -> deliveries.add("second " + newValue(change)));
        try {
            head.setName("outer");
            same(List.of("first outer", "first nested", "replacement nested", "second outer"), deliveries,
                    "synchronous nested delivery with immutable registration snapshots");
            same(List.of("outer", "nested"), values(history), "recorder preserves initiating-before-nested order");
            check(history.get(0).object() == head && history.get(1).object() == child, "recorded source identities");
        } finally {
            first.unsubscribe();
            second[0].unsubscribe();
            if (replacement[0] != null) replacement[0].unsubscribe();
            changes.stop();
        }
    }

    private static void checkReentrantReferenceDeliveryAndHistory() {
        var owner = ValueSelection.newInstance();
        var previous = ValueLabel.newBuilder().withText("outer").build();
        var outer = ValueLabel.newBuilder().withText("outer").build();
        var nested = ValueLabel.newBuilder().withText("nested").build();
        owner.setCurrent(previous);
        var changes = owner.vmf().changes();
        var history = changes.all();
        var deliveries = new ArrayList<String>();
        changes.start();
        var first = changes.addRecursiveListener(change -> {
            deliveries.add("first " + change.propertyName());
            check(history.isEmpty(), "both reference callbacks start before their respective history append");
            if (change.propertyName().equals("current")) {
                check(owner.getCurrent() == outer, "replacement is applied before live callbacks");
                owner.setSecond(nested);
                check(history.size() == 1 && history.get(0).propertyName().equals("second"),
                        "nested reference history is already visible inside the initiating callback");
            }
        });
        var second = changes.addRecursiveListener(change -> deliveries.add("second " + change.propertyName()));
        try {
            owner.setCurrent(outer);
            same(List.of("first current", "first second", "second second", "second current"), deliveries,
                    "reference dispatch also completes nested callbacks before later initiating observers");
            check(history.size() == 2, "record both property edits exactly once");
            // The triggering edit is recorded before the edits its listeners made (portable contract H4).
            assertSnapshot(history.get(0), owner, "current", previous, outer);
            assertSnapshot(history.get(1), owner, "second", null, nested);
        } finally {
            first.unsubscribe();
            second.unsubscribe();
            changes.stop();
        }
    }

    private static void checkFloatingPointEdits() {
        var sample = ScalarSample.newInstance();
        var firstNaN = Double.longBitsToDouble(0x7ff8000000000001L);
        var secondNaN = Double.longBitsToDouble(0x7ff8000000000002L);
        var changes = sample.vmf().changes();
        var edits = new ArrayList<Change>();
        var observation = changes.addRecursiveListener(edits::add);
        changes.start();
        try {
            sample.setDoubleValue(-0.0);
            check(Double.doubleToRawLongBits(sample.getDoubleValue()) == 0L,
                    "primitive equality suppresses assignment of a differently signed zero");
            sample.setDoubleValue(firstNaN);
            sample.setDoubleValue(secondNaN);
            check(Double.doubleToRawLongBits(sample.getDoubleValue()) == Double.doubleToRawLongBits(secondNaN),
                    "repeated primitive NaN assigns the incoming payload");

            sample.setOptionalDouble(0.0);
            sample.setOptionalDouble(-0.0);
            check(Double.doubleToRawLongBits(sample.getOptionalDouble()) == Long.MIN_VALUE,
                    "boxed equality distinguishes signed zero");
            sample.setOptionalDouble(firstNaN);
            sample.setOptionalDouble(secondNaN);
            check(Double.doubleToRawLongBits(sample.getOptionalDouble()) == Double.doubleToRawLongBits(firstNaN),
                    "repeated boxed NaN retains the original payload");

            same(List.of("doubleValue", "optionalDouble", "optionalDouble", "optionalDouble"),
                    edits.stream().map(Change::propertyName).collect(Collectors.toList()),
                    "both property dispatches suppress repeated NaN and only boxed zero changes emit");
            same(List.of(firstNaN, 0.0, -0.0, firstNaN), values(edits), "observed floating point edits");
            same(values(edits), values(changes.all()), "floating point history matches observed edits");
        } finally {
            observation.unsubscribe();
            changes.stop();
        }
    }

    private static void checkLiveObserverFailures() {
        var sample = ScalarSample.newInstance();
        var changes = sample.vmf().changes();
        var firstFailure = new IllegalStateException("first live observer failure");
        var secondFailure = new IllegalArgumentException("second live observer failure");
        var laterEvents = new ArrayList<Change>();
        var first = changes.addLocalListener(change -> { throw firstFailure; });
        var later = changes.addLocalListener(change -> {
            check(sample.getOptionalInt() == 8, "later listener sees the applied edit");
            laterEvents.add(change);
        });
        var last = changes.addLocalListener(change -> { throw secondFailure; });
        changes.start();
        try {
            var actual = reject(IllegalStateException.class, () -> sample.setOptionalInt(8));
            check(actual == firstFailure, "first live observer exception is returned unchanged");
            check(actual.getSuppressed().length == 1 && actual.getSuppressed()[0] == secondFailure,
                    "second live observer exception is suppressed");
            check(laterEvents.size() == 1 && sample.getOptionalInt() == 8, "failure preserves later delivery and state");
            same(List.of(8), values(changes.all()), "failed live delivery still records the applied edit");
        } finally {
            first.unsubscribe();
            later.unsubscribe();
            last.unsubscribe();
            changes.stop();
        }
    }

    private static void checkTransactionRanges() {
        var sample = ScalarSample.newInstance();
        var changes = sample.vmf().changes();
        var history = changes.all();
        var transactions = changes.transactions();
        var deliveries = new ArrayList<String>();
        changes.start();
        var first = transactions.addChangeListener(event -> {
            if (!event.wasAdded()) return;
            var transaction = event.added().elements().get(0);
            Object name = newValue(transaction.changes().get(0));
            deliveries.add("first " + name);
            if (name.equals("outer")) {
                check(transactions.get(0) == transaction, "published range is committed before callback");
                changes.publishTransaction();
                sample.setDescription("nested");
                changes.publishTransaction();
                sample.setDescription("next");
            }
        });
        var second = transactions.addChangeListener(event -> {
            if (event.wasAdded()) deliveries.add("second " + newValue(event.added().elements().get(0).changes().get(0)));
        });
        try {
            sample.setDescription("before range");
            changes.startTransaction();
            sample.setDescription("outer");
            sample.setIntValue(7);
            changes.publishTransaction();
            same(List.of("first outer", "first nested", "second nested", "second outer"), deliveries,
                    "nested publication of the next range");
            same(List.of(List.of("outer", 7), List.of("nested")), transactionValues(changes), "published range boundaries");
            changes.publishTransaction();
            same(List.of("first outer", "first nested", "second nested", "second outer", "first next", "second next"),
                    deliveries, "publication after nested callbacks");
            same(List.of("before range", "outer", 7, "nested", "next"), values(history), "all edits remain in history");
            same(List.of(List.of("outer", 7), List.of("nested"), List.of("next")), transactionValues(changes),
                    "each publication consumes only its own range");
            var saved = transactions.get(0);
            check(saved.changes().get(0) == history.get(1), "transaction holds the original history entry");
            reject(UnsupportedOperationException.class, () -> saved.changes().clear());
            changes.clear();
            check(transactions.isEmpty() && history.isEmpty(), "read-only history views stay live");
            same(List.of("outer", 7), values(saved.changes()), "published range snapshots survive history clear");
        } finally {
            first.unsubscribe();
            second.unsubscribe();
            changes.stop();
        }
    }

    private static void checkTransactionPublicationFailures() {
        var sample = ScalarSample.newInstance();
        var changes = sample.vmf().changes();
        var expected = new IllegalStateException("publication failure");
        var later = new ArrayList<Transaction>();
        changes.start();
        var throwing = changes.transactions().addChangeListener(event -> { if (event.wasAdded()) throw expected; });
        var observer = changes.transactions().addChangeListener(event -> {
            if (event.wasAdded()) later.add(event.added().elements().get(0));
        });
        try {
            sample.setDescription("published");
            check(reject(IllegalStateException.class, changes::publishTransaction) == expected, "publication callback exception");
            same(List.of(List.of("published")), transactionValues(changes), "throwing callback leaves its range committed");
            check(later.isEmpty(), "Java history-list publication stops dispatch at a failing listener");
            check(changes.isModelVersioningEnabled(), "explicit publication failure leaves recording enabled");
            changes.publishTransaction();
            check(later.isEmpty(), "empty retry cannot republish a committed range");
            throwing.unsubscribe();

            sample.setDescription("unpublished");
            var stopping = changes.transactions().addChangeListener(event -> {
                if (event.wasAdded()) {
                    sample.setDescription("during stop");
                    throw expected;
                }
            });
            try {
                check(reject(IllegalStateException.class, changes::stop) == expected, "stop returns the publication failure");
            } finally {
                stopping.unsubscribe();
            }
            check(later.size() == 1, "later successful dispatch before a failing stop subscriber");
            check(!changes.isModelVersioningEnabled(), "stop disables recording even on failure");
            same(List.of(List.of("published"), List.of("unpublished")), transactionValues(changes), "stop publishes pending range once");
            same(List.of("published", "unpublished", "during stop"), values(changes.all()),
                    "a stop callback's edit is recorded but remains outside a transaction");
            sample.setDescription("after stop");
            check(changes.all().size() == 3, "edits after failed stop are unrecorded");
            changes.start();
            sample.setDescription("restarted");
            changes.stop();
            same(List.of(List.of("restarted")), transactionValues(changes), "recording restarts with fresh ranges");
        } finally {
            throwing.unsubscribe();
            observer.unsubscribe();
            changes.stop();
        }
    }

    private static void checkModelPropertyRecordingRelay(String edit) {
        for (var lifecycle : List.of("start", "clear", "stop")) {
            var root = ArchiveNode.newInstance();
            var child = ArchiveNode.newInstance();
            var target = ArchiveNode.newInstance();
            root.getChildren().add(child);
            var owning = !edit.equals("reference");
            var property = owning ? "inset" : "anchor";
            var before = edit.equals("detach") ? target : null;
            var after = before == null ? target : null;
            if (before != null) child.setInset(before);
            var changes = root.vmf().changes();
            var trace = new ArrayList<String>();
            var local = child.vmf().changes().addPropertyListener(property, change -> {
                trace.add("child:" + changes.all().size());
                switch (lifecycle) {
                    case "start": changes.start(); break;
                    case "clear": changes.clear(); break;
                    case "stop": changes.stop(); break;
                }
            });
            var ancestor = changes.addPropertyListener(property, change -> {
                var delta = change.propertyChange().orElseThrow();
                check(change.object() == child && delta.oldValue() == before && delta.newValue() == after,
                        "The ancestor sees the live model property edit");
                trace.add("root:" + changes.all().size());
            }, Changes.Scope.RECURSIVE);
            try {
                if (!lifecycle.equals("start")) {
                    changes.start();
                    root.setName("recorded before the reference");
                }
                if (owning) child.setInset(after);
                else child.setAnchor(after);
                changes.stop();
                same(List.of("child:" + (lifecycle.equals("start") ? 0 : 1),
                                "root:" + (lifecycle.equals("stop") ? 1 : 0)), trace,
                        "Native stream callbacks precede its recording relay's history append");
            } finally {
                local.unsubscribe();
                ancestor.unsubscribe();
            }
            check(changes.all().size() == 1 && changes.transactions().size() == 1
                            && changes.transactions().get(0).changes().size() == 1,
                    "The active recording range contains exactly one edit");
            var recorded = changes.all().get(0);
            var transaction = changes.transactions().get(0);
            check(recorded == transaction.changes().get(0) && (owning ? child.getInset() : child.getAnchor()) == after
                            && target.getInsetOf() == (owning && after != null ? child : null),
                    "The published group refers to the exact live recording");
            if (lifecycle.equals("stop")) {
                check(recorded.propertyName().equals("name"), "Stopping before delivery excludes the property edit");
            } else {
                check(recorded.propertyName().equals(property) && recorded.object() == child && transaction.isUndoable(),
                        "Starting or clearing before delivery records the original property edit in the active range");
                transaction.undo();
                check((owning ? child.getInset() : child.getAnchor()) == before
                                && target.getInsetOf() == (owning && before != null ? child : null)
                                && root.getChildren().get(0) == child,
                        "Transaction undo restores the property and keeps the inverse coherent");
            }
        }
    }

    private static void checkScalarUndoAndApply() {
        var sample = ScalarSample.newInstance();
        sample.setDescription("before");
        var changes = sample.vmf().changes();
        changes.start();
        sample.setDescription("recorded");
        changes.stop();

        var recorded = changes.all().get(0);
        check(recorded.isUndoable(), "scalar undo checks the current value");
        sample.setDescription("intervening");
        check(!recorded.isUndoable(), "intervening edit blocks undo");
        recorded.undo();
        same("intervening", sample.getDescription(), "blocked undo does not edit the model");
        recorded.apply(sample);
        same("recorded", sample.getDescription(), "apply writes the recorded new value without a checkpoint check");
        recorded.undo();
        same("before", sample.getDescription(), "undo restores the recorded old value");
        recorded.apply(sample);
        same("recorded", sample.getDescription(), "apply performs scalar redo");

        var other = ScalarSample.newInstance();
        other.setDescription("other graph");
        recorded.apply(other);
        same("recorded", other.getDescription(), "scalar change applies to the corresponding object of another graph");
        check(changes.all().size() == 1, "undo and apply while stopped leave recorded history unchanged");
    }

    private static ArchiveNode node(String name) {
        return ArchiveNode.newBuilder().withName(name).build();
    }

    private static void collectNamedValue(Change change, List<Object> names) {
        if (change.propertyName().equals("name")) names.add(newValue(change));
    }

    private static void assertSnapshot(Change change, ValueSelection owner, String property, Object oldValue, Object newValue) {
        check(change.object() == owner && change.propertyName().equals(property), "property change source and name");
        var payload = change.propertyChange().orElseThrow();
        check(payload.oldValue() == oldValue && payload.newValue() == newValue, "immutable reference payload keeps identity");
    }

    private static Object newValue(Change change) {
        return change.propertyChange().orElseThrow().newValue();
    }

    private static List<Object> values(List<Change> changes) {
        return changes.stream().map(ObservationConformance::newValue).collect(Collectors.toList());
    }

    private static List<List<Object>> transactionValues(Changes changes) {
        return changes.transactions().stream().map(transaction -> values(transaction.changes())).collect(Collectors.toList());
    }

    private static <T extends RuntimeException> T reject(Class<T> failure, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException caught) {
            if (failure.isInstance(caught)) return failure.cast(caught);
            throw caught;
        }
        throw new AssertionError("expected " + failure.getSimpleName());
    }

    private static void same(Object expected, Object actual, String behavior) {
        check(expected.equals(actual), behavior + ". Expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
