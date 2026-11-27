package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.ArchiveRoot;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.Changes;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

final class ContainmentObservationConformance {
    private ContainmentObservationConformance() {}

    public static void main(String[] args) {
        checkListReparent();
        checkOrderedTransferJournal();
        checkSingularReplacement();
        checkObserverFailures(false);
        checkObserverFailures(true);
        checkScopeOverlap();
        checkSeparateSingularRecordingRelays(false);
        checkSeparateSingularRecordingRelays(true);
        System.out.println("Java verified containment reparenting order, guards, history, and observer failures.");
    }

    private static void checkSeparateSingularRecordingRelays(boolean throwing) {
        var sourceRoot = node("source-root");
        var targetRoot = node("target-root");
        var source = node("source");
        var target = node("target");
        var moving = node("moving");
        sourceRoot.getChildren().add(source);
        targetRoot.getChildren().add(target);
        source.setInset(moving);
        var sourceChanges = sourceRoot.vmf().changes();
        var targetChanges = targetRoot.vmf().changes();
        var order = new ArrayList<String>();
        var failure = new IllegalStateException("source observer");
        var sourceLocal = source.vmf().changes().addPropertyListener("inset", change -> {
            order.add("source local");
            sourceChanges.start();
            if (throwing) throw failure;
        });
        var targetLocal = target.vmf().changes().addPropertyListener("inset", change -> {
            order.add("target local");
            targetChanges.start();
        });
        var sourceRecursive = sourceChanges.addPropertyListener("inset", change -> {
            order.add("source recursive");
            var edit = change.propertyChange().orElseThrow();
            check(edit.oldValue() == moving && edit.newValue() == null, "separate source relay records removal");
        }, Changes.Scope.RECURSIVE);
        var targetRecursive = targetChanges.addPropertyListener("inset", change -> {
            order.add("target recursive");
            var edit = change.propertyChange().orElseThrow();
            check(edit.oldValue() == null && edit.newValue() == moving, "separate target relay records attachment");
        }, Changes.Scope.RECURSIVE);
        var inverse = moving.vmf().changes().addPropertyListener("insetOf",
                change -> order.add(change.propertyChange().orElseThrow().newValue() == null ? "detach" : "attach"));
        try {
            if (throwing) {
                check(reject(IllegalStateException.class, () -> target.setInset(moving)) == failure,
                        "source failure still comes from the original observer");
            } else {
                target.setInset(moving);
            }
            moving.setName("after transfer");
        } finally {
            sourceLocal.unsubscribe();
            targetLocal.unsubscribe();
            sourceRecursive.unsubscribe();
            targetRecursive.unsubscribe();
            inverse.unsubscribe();
            sourceChanges.stop();
            targetChanges.stop();
        }
        check(order.equals(List.of("source local", "source recursive", "detach", "attach", "target local", "target recursive")),
                "separate singular relay order: " + order);
        check(history(sourceChanges).equals(List.of("source:inset"))
                        && history(targetChanges).equals(List.of("target:inset", "name:after transfer")),
                "ownership scopes record their own edge and the adopted child only after transfer");
        assertUndoable(targetChanges);
        assertUndoable(sourceChanges);
        check(source.getInset() == moving && moving.getInsetOf() == source
                        && target.getInset() == null && moving.getName().equals("moving"),
                "separate undo groups restore one owning edge and its inverse");
    }

    private static void checkOrderedTransferJournal() {
        for (boolean recursive : new boolean[] { false, true }) {
            var source = node("source");
            var destination = node("destination");
            var moving = node("moving");
            var kept = node("kept");
            var root = ArchiveNode.newBuilder().withName("root").withChildren(source, destination).build();
            source.getChildren().addAll(List.of(moving, kept));
            var checkpoint = root.clone();
            var binding = new JournalBinding() {
                public String modelId() { return "ordered-transfer-journal"; }
                public int modelVersion() { return 1; }
                public String id(VObject value) { return ((ArchiveNode) value).getName(); }
                public String type(VObject value) { return ArchiveNode.class.getName(); }
                public VObject create(String type, String id) { return node(id); }
            };
            var nested = node("nested");
            var changes = root.vmf().changes();
            var scope = recursive ? changes : source.vmf().changes();
            var observer = scope.addListener(change -> {
                if (change.object() == source && change.propertyName().equals("children")
                        && source.getChildren().get(0) == kept) {
                    check(moving.getParent() == source && destination.getChildren().get(0) == moving, "pending ordered adoption");
                    source.getChildren().set(0, nested);
                }
            }, recursive);
            try (var recorder = JournalRecorder.start(root, binding, "initial", 0)) {
                destination.getChildren().add(moving);
                check(changes.all().size() == 3 && changes.all().get(0).referenceSetChange().orElseThrow().added().get(0) == nested,
                        "ordered native history remains nested-first");
                var journal = recorder.finish();
                var ops = journal.transactions().get(0).operations();
                check(ops.stream().map(ChangeJournal.Operation::kind).collect(Collectors.toList()).equals(List.of(
                                ChangeJournal.Kind.LIST_REMOVE, ChangeJournal.Kind.LIST_SET, ChangeJournal.Kind.LIST_ADD)),
                        "ordered RefSet transfers keep the same positional wire vocabulary");
                var result = JournalReplayer.replay(checkpoint, binding, "initial", journal);
                check(result.getChildren().get(0).getChildren().get(0).getName().equals("nested")
                                && result.getChildren().get(1).getChildren().get(0).getName().equals("moving"),
                        "ordered transfer journals replay both owning positions");
            } finally {
                observer.unsubscribe();
            }
        }
    }

    private static void checkListReparent() {
        var graph = new Fixture();
        var source = graph.source;
        var destination = graph.destination;
        var child = graph.child;
        var other = node("other");
        source.getChildren().add(child);
        var changes = graph.archive.vmf().changes();
        var order = new ArrayList<String>();
        var recursive = changes.addRecursiveListener(change -> {
            if (change.object() == source) {
                order.add("source removed");
                check(source.getChildren().isEmpty() && child.getParent() == source, "source removal precedes inverse detach");
                check(destination.getChildren().get(0) == child, "destination list already contains incoming member");
                var removal = change.referenceSetChange().orElseThrow();
                check(removal.source() == source.getChildren() && removal.indices().equals(List.of(0))
                        && removal.removed().size() == 1 && removal.removed().get(0) == child && removal.added().isEmpty(),
                        "source event identifies exact removed member and position");
                reject(IllegalArgumentException.class, () -> other.getChildren().add(child));
                reject(IllegalArgumentException.class, () -> destination.getChildren().remove(child));
                reject(IllegalArgumentException.class, () -> child.setParent(null));
                check(other.getChildren().isEmpty() && child.getParent() == source, "blocked competitors leave the graph intact");
                child.setName("during removal");
            } else if (change.object() == destination) {
                order.add("destination added");
                var addition = change.referenceSetChange().orElseThrow();
                check(child.getParent() == destination && destination.getChildren().get(0) == child
                        && addition.added().get(0) == child && addition.removed().isEmpty(), "destination event follows inverse attach");
            } else {
                order.add("recursive " + change.propertyName() + " " + change.propertyChange().orElseThrow().newValue());
            }
        });
        var local = child.vmf().changes().addPropertyListener("parent", change -> {
            var edit = change.propertyChange().orElseThrow();
            if (edit.newValue() == null) {
                check(edit.oldValue() == source && child.getParent() == null, "inverse detach payload");
                order.add("child detached");
            } else {
                check(edit.oldValue() == null && edit.newValue() == destination && child.getParent() == destination,
                        "inverse attach payload");
                order.add("child attached");
            }
        });
        changes.start();
        try {
            destination.getChildren().add(child);
            child.setName("after move");
        } finally {
            recursive.unsubscribe();
            local.unsubscribe();
            changes.stop();
        }
        check(order.equals(List.of("source removed", "recursive name during removal", "child detached", "child attached",
                "destination added", "recursive name after move")), "list move deliveries: " + order);
        check(history(changes).equals(List.of("name:during removal", "source:children", "destination:children", "name:after move")),
                "root list history orders source-callback edit before removal and excludes inverse events: " + history(changes));
        assertUndoable(changes);
        check(source.getChildren().size() == 1 && source.getChildren().get(0) == child && child.getParent() == source
                && destination.getChildren().isEmpty() && child.getName().equals("child"), "list move transaction restores the original graph");
    }

    private static void checkSingularReplacement() {
        var graph = new Fixture();
        var source = graph.source;
        var destination = graph.destination;
        var child = graph.child;
        var former = node("former");
        var competitor = node("competitor");
        source.setInset(child);
        destination.setInset(former);
        var changes = graph.archive.vmf().changes();
        var order = new ArrayList<String>();
        var recursive = changes.addRecursiveListener(change -> {
            var edit = change.propertyChange().orElseThrow();
            check(change.propertyName().equals("inset"), "root only observes live containment owner events on this move");
            if (change.object() == source) {
                order.add("source removed");
                check(edit.oldValue() == child && edit.newValue() == null && source.getInset() == null
                        && child.getInsetOf() == source, "singular source is cleared before its inverse");
                reject(IllegalArgumentException.class, () -> destination.setInset(competitor));
                reject(IllegalArgumentException.class, () -> child.setInsetOf(null));
                check(destination.getInset() == null && competitor.getInsetOf() == null, "pending singular slot rejects edits atomically");
            } else if (edit.newValue() == null) {
                order.add("destination cleared");
                check(change.object() == destination && edit.oldValue() == former && former.getInsetOf() == destination
                        && destination.getInset() == null, "outgoing destination child is cleared first");
            } else {
                order.add("destination added");
                check(change.object() == destination && edit.oldValue() == null && edit.newValue() == child
                        && destination.getInset() == child && child.getInsetOf() == destination,
                        "singular addition is a separate committed owner event");
            }
        });
        var outgoing = former.vmf().changes().addPropertyListener("insetOf", change -> {
            order.add("former detached");
            check(former.getInsetOf() == null, "former child inverse cleared");
        });
        var incoming = child.vmf().changes().addPropertyListener("insetOf", change -> {
            var edit = change.propertyChange().orElseThrow();
            if (edit.newValue() == null) {
                order.add("child detached");
                check(edit.oldValue() == source && child.getInsetOf() == null, "incoming child inverse detach");
            } else {
                order.add("child attached");
                check(edit.oldValue() == null && edit.newValue() == destination && child.getInsetOf() == destination
                        && destination.getInset() == null, "singular attach inverse fires before destination slot assignment");
                reject(IllegalArgumentException.class, () -> destination.setInset(competitor));
                reject(IllegalArgumentException.class, () -> child.setInsetOf(null));
            }
        });
        changes.start();
        try {
            child.setInsetOf(destination);
        } finally {
            recursive.unsubscribe();
            outgoing.unsubscribe();
            incoming.unsubscribe();
            changes.stop();
        }
        check(order.equals(List.of("destination cleared", "former detached", "source removed", "child detached", "child attached",
                "destination added")), "singular replacement deliveries: " + order);
        check(history(changes).equals(List.of("destination:inset", "source:inset", "destination:inset")),
                "root records both scalar removals and the final addition: " + history(changes));
        assertUndoable(changes);
        check(source.getInset() == child && child.getInsetOf() == source && destination.getInset() == former
                && former.getInsetOf() == destination, "singular transaction undo restores both owning slots and reciprocals");
    }

    private static void checkObserverFailures(boolean singular) {
        var graph = new Fixture();
        var source = graph.source;
        var destination = graph.destination;
        var child = graph.child;
        if (singular) source.setInset(child);
        else source.getChildren().add(child);
        String forward = singular ? "inset" : "children";
        String reverse = singular ? "insetOf" : "parent";
        var changes = graph.archive.vmf().changes();
        var order = new ArrayList<String>();
        var firstFailure = new IllegalStateException("first source callback");
        var secondFailure = new UnsupportedOperationException("second source callback");
        var inverseFailure = new IllegalArgumentException("inverse detach callback");
        var destinationFailure = new IllegalStateException("destination callback");
        var first = changes.addRecursiveListener(change -> {
            if (change.object() == source) {
                order.add("first source");
                throw firstFailure;
            }
            if (change.object() == destination) {
                order.add("first destination");
                throw destinationFailure;
            }
            order.add("recursive " + change.propertyName());
        });
        var second = changes.addRecursiveListener(change -> {
            if (change.object() == source) {
                order.add("second source");
                throw secondFailure;
            }
            if (change.object() == destination) order.add("second destination");
        });
        var inverse = child.vmf().changes().addPropertyListener(reverse, change -> {
            if (change.propertyChange().orElseThrow().newValue() == null) {
                order.add("child detached");
                throw inverseFailure;
            }
            order.add("child attached");
        });
        changes.start();
        try {
            var failure = reject(IllegalStateException.class, () -> {
                if (singular) destination.setInset(child);
                else destination.getChildren().add(child);
            });
            check(failure == firstFailure && Arrays.equals(failure.getSuppressed(), new Throwable[]{secondFailure, inverseFailure, destinationFailure}),
                    "exception preserves first failure and suppresses later ones in callback order: " + Arrays.toString(failure.getSuppressed()));
            check(singular ? source.getInset() == null && destination.getInset() == child && child.getInsetOf() == destination
                    : source.getChildren().isEmpty() && destination.getChildren().get(0) == child && child.getParent() == destination,
                    "throwing observers leave the completed move applied");
            child.setName("verified");
        } finally {
            first.unsubscribe();
            second.unsubscribe();
            inverse.unsubscribe();
            changes.stop();
        }
        check(order.equals(List.of("first source", "second source", "child detached", "child attached", "first destination",
                "second destination", "recursive name")), "failure leaves later listeners and descendant scope active: " + order);
        check(history(changes).equals(List.of("source:" + forward, "destination:" + forward, "name:verified")),
                "failure preserves primary history only: " + history(changes));
    }

    private static void checkScopeOverlap() {
        var former = node("former");
        var destination = node("destination");
        var child = node("child");
        former.getChildren().add(child);
        var formerNames = new ArrayList<Object>();
        var destinationNames = new ArrayList<Object>();
        var owningEdits = new int[2];
        var watchFormer = former.vmf().changes().addRecursiveListener(change -> {
            if (change.propertyName().equals("name")) formerNames.add(change.propertyChange().orElseThrow().newValue());
            if (change.object() == former && change.propertyName().equals("children")) {
                owningEdits[0]++;
                child.setName("during old owner callback");
            }
        });
        var watchDestination = destination.vmf().changes().addRecursiveListener(change -> {
            if (change.propertyName().equals("name")) destinationNames.add(change.propertyChange().orElseThrow().newValue());
            if (change.object() == destination && change.propertyName().equals("children")) owningEdits[1]++;
        });
        var reattach = child.vmf().changes().addPropertyListener("parent", change -> {
            if (change.propertyChange().orElseThrow().newValue() == null) {
                destination.getChildren().add(child);
                child.setName("after nested attachment");
            }
        });
        try {
            check(former.getChildren().remove(child), "direct removal succeeds");
            child.setName("after old scope settled");
            check(former.getChildren().isEmpty() && destination.getChildren().get(0) == child
                    && child.getParent() == destination, "nested reattachment finishes");
            check(Arrays.equals(owningEdits, new int[] {1, 1}), "old and new ownership each reported once");
            check(formerNames.equals(List.of("after nested attachment", "during old owner callback")),
                    "former recursive observer remains until its owning event");
            check(destinationNames.equals(List.of("after nested attachment", "during old owner callback", "after old scope settled")),
                    "destination recursive observer starts on reattachment");
        } finally {
            watchFormer.unsubscribe();
            watchDestination.unsubscribe();
            reattach.unsubscribe();
        }
    }

    private static void assertUndoable(Changes changes) {
        check(changes.transactions().size() == 1, "stop publishes one transaction");
        var transaction = changes.transactions().get(0);
        check(transaction.changes().equals(changes.all()) && transaction.isUndoable(), "the entire recorded move can be undone");
        transaction.undo();
    }

    private static List<String> history(Changes changes) {
        return changes.all().stream().map(ContainmentObservationConformance::identify).collect(Collectors.toList());
    }

    private static String identify(Change change) {
        if (change.propertyName().equals("name")) {
            return "name:" + change.propertyChange().orElseThrow().newValue();
        }
        return ((ArchiveNode) change.object()).getName() + ":" + change.propertyName();
    }

    private static ArchiveNode node(String name) {
        return ArchiveNode.newBuilder().withName(name).build();
    }

    private static final class Fixture {
        final ArchiveRoot archive = ArchiveRoot.newInstance();
        final ArchiveNode source = node("source");
        final ArchiveNode destination = node("destination");
        final ArchiveNode child = node("child");

        Fixture() {
            var head = node("head");
            archive.setHead(head);
            head.getChildren().addAll(List.of(source, destination));
        }
    }

    private static <T extends RuntimeException> T reject(Class<T> type, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            if (type.isInstance(failure)) return type.cast(failure);
            throw failure;
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
