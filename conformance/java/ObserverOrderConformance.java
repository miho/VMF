package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.Changes;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import vjavax.observer.Subscription;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

final class ObserverOrderConformance {
    private ObserverOrderConformance() {}

    public static void main(String[] args) throws IOException {
        for (boolean ancestorFirst : List.of(true, false)) {
            checkRegistrationOrder(ancestorFirst, true);
            checkRegistrationOrder(ancestorFirst, false);
            checkSameSourceGroups(ancestorFirst);
            checkNativeReentrantUndo(ancestorFirst, false);
            checkNativeReentrantUndo(ancestorFirst, true);
        }
        checkSubscriptionChanges(false, false,
                List.of("root outer", "root nested", "late nested", "root again", "late again"));
        checkSubscriptionChanges(true, false, List.of("root outer", "root nested", "old nested", "late nested",
                "old outer", "late outer", "root again", "old again", "late again"));
        checkSubscriptionChanges(true, true,
                List.of("root outer", "root nested", "late nested", "late outer", "root again", "late again"));
        checkReparentingOrder();
        checkSubscriptionDuringInverse(false);
        checkSubscriptionDuringInverse(true);
        checkReentrantRecording();
        checkPublicationWhileScalarRelayIsPending();
        for (var action : RecordingAction.values()) {
            checkRecordingLifecycle(action, false, false);
            checkRecordingLifecycle(action, false, true);
            checkRecordingLifecycle(action, true, true);
        }
        checkRestartBetweenTwoRelaysOfTheSameStream();
        checkPortableStartWithAnExistingRelay();
        checkPortableModelPropertyStartWithAnExistingRelay(false);
        checkPortableModelPropertyStartWithAnExistingRelay(true);
        checkReentrantDirectReferenceRecording();
        checkCallbackInitializationOfCreatedReferences();
        System.out.println("Java verified live ordering of recursively attached and local observer groups.");
    }

    private static void checkRegistrationOrder(boolean ancestorFirst, boolean attachedFirst) {
        var root = ArchiveNode.newInstance();
        var child = ArchiveNode.newInstance();
        var trace = new ArrayList<String>();
        if (attachedFirst) root.getChildren().add(child);

        Supplier<Subscription> ancestor = () -> root.vmf().changes().addFilteredListener(
                ObserverOrderConformance::isObserved, ignored -> trace.add("root"), Changes.Scope.RECURSIVE);
        Supplier<Subscription> local = () -> child.vmf().changes().addFilteredListener(
                ObserverOrderConformance::isObserved, ignored -> trace.add("child"), Changes.Scope.LOCAL);
        var first = (ancestorFirst ? ancestor : local).get();
        var second = (ancestorFirst ? local : ancestor).get();
        try {
            if (!attachedFirst) root.getChildren().add(child);
            var expected = ancestorFirst && attachedFirst ? List.of("root", "child") : List.of("child", "root");
            child.setName("edit");
            same(expected, trace, "scalar scope registration");
            trace.clear();
            child.getTrail().add(ArchiveNode.newInstance());
            same(expected, trace, "collection scope registration");
        } finally {
            first.unsubscribe();
            second.unsubscribe();
        }
    }

    private static boolean isObserved(Change change) {
        return change.propertyName().equals("name") || change.propertyName().equals("trail");
    }

    private static void checkSameSourceGroups(boolean localFirst) {
        var root = ArchiveNode.newInstance();
        var trace = new ArrayList<String>();
        var firstName = localFirst ? "local" : "recursive";
        var secondName = localFirst ? "recursive" : "local";
        var changes = root.vmf().changes();
        var first = changes.addListener(ignored -> trace.add(firstName + " 1"), !localFirst);
        var second = changes.addListener(ignored -> trace.add(secondName + " 1"), localFirst);
        var third = changes.addListener(ignored -> trace.add(firstName + " 2"), !localFirst);
        var fourth = changes.addListener(ignored -> trace.add(secondName + " 2"), localFirst);
        try {
            root.setName("edit");
            same(List.of(firstName + " 1", firstName + " 2", secondName + " 1", secondName + " 2"),
                    trace, "same-source scope grouping");
        } finally {
            first.unsubscribe();
            second.unsubscribe();
            third.unsubscribe();
            fourth.unsubscribe();
        }
    }

    private static void checkSubscriptionChanges(boolean initiallyActive, boolean replaceOld, List<String> expected) {
        var root = ArchiveNode.newInstance();
        var child = ArchiveNode.newInstance();
        root.getChildren().add(child);
        var trace = new ArrayList<String>();
        var later = new Subscription[2];
        var ancestor = observeName(root, "root", trace, name -> {
            if (!name.equals("outer")) return;
            if (replaceOld) later[0].unsubscribe();
            later[1] = child.vmf().changes().addLocalListener(change -> trace.add("late " + value(change)));
            child.setName("nested");
        });
        if (initiallyActive) {
            later[0] = child.vmf().changes().addLocalListener(change -> trace.add("old " + value(change)));
        }
        try {
            child.setName("outer");
            child.setName("again");
            same(expected, trace, "registration changes before later scope");
        } finally {
            ancestor.unsubscribe();
            for (var item : later) {
                if (item != null) item.unsubscribe();
            }
        }
    }

    private static void checkReparentingOrder() {
        var root = ArchiveNode.newInstance();
        var left = ArchiveNode.newInstance();
        var right = ArchiveNode.newInstance();
        var child = ArchiveNode.newInstance();
        root.getChildren().addAll(List.of(left, right));
        left.getChildren().add(child);
        var trace = new ArrayList<String>();
        var rootNames = observeName(root, "root", trace, ignored -> {});
        var leftNames = observeName(left, "left", trace, ignored -> {});
        var rightNames = observeName(right, "right", trace, ignored -> {});
        var local = child.vmf().changes().addPropertyListener("name", change -> trace.add("child " + value(change)));
        var during = right.vmf().changes().addPropertyListener("children", change -> child.setName("during"));
        try {
            child.setName("before");
            right.getChildren().add(child);
            child.setName("after");
            same(List.of("root before", "left before", "child before", "child during", "root during", "right during",
                    "child after", "root after", "right after"), trace, "recursive scopes through reparenting");
        } finally {
            rootNames.unsubscribe();
            leftNames.unsubscribe();
            rightNames.unsubscribe();
            local.unsubscribe();
            during.unsubscribe();
        }
    }

    private static void checkSubscriptionDuringInverse(boolean singular) {
        var owner = ArchiveNode.newInstance();
        var child = ArchiveNode.newInstance();
        String ownerProperty = singular ? "inset" : "children";
        String inverseProperty = singular ? "insetOf" : "parent";
        var trace = new ArrayList<String>();
        var added = new Subscription[2];
        var inverse = child.vmf().changes().addPropertyListener(inverseProperty, change -> {
            if (change.propertyChange().orElseThrow().newValue() != owner) return;
            trace.add("inverse delivered");
            added[0] = owner.vmf().changes().addPropertyListener(ownerProperty, ignored -> trace.add("owner delivered"));
            added[1] = observeName(owner, "name", trace, ignored -> {});
            child.setName("inverse");
        });
        try {
            if (singular) owner.setInset(child);
            else owner.getChildren().add(child);
            child.setName("after");
            if (!singular) owner.getChildren().remove(child);

            var expected = singular
                    ? List.of("inverse delivered", "owner delivered", "name after")
                    : List.of("inverse delivered", "name inverse", "name after", "owner delivered");
            same(expected, trace, "scope dispatch boundary for " + ownerProperty);
        } finally {
            inverse.unsubscribe();
            for (var item : added) {
                if (item != null) item.unsubscribe();
            }
        }
    }

    private static void checkReentrantRecording() {
        var root = ArchiveNode.newInstance();
        var child = ArchiveNode.newInstance();
        child.setName("initial");
        root.getChildren().add(child);
        var checkpoint = root.clone();
        var binding = new JournalBinding() {
            @Override public String modelId() { return "conformance.observer-order"; }
            @Override public int modelVersion() { return 1; }
            @Override public String id(VObject value) { return ((ArchiveNode) value).getParent() == null ? "root" : "child"; }
            @Override public String type(VObject value) { return value.vmf().reflect().type().getName(); }
            @Override public VObject create(String type, String id) { throw new AssertionError("The scalar fixture creates no nodes"); }
        };
        var trace = new ArrayList<String>();
        var recording = root.vmf().changes();
        var local = child.vmf().changes().addPropertyListener("name", change -> {
            var name = value(change);
            trace.add("child " + name);
            if (name.equals("outer")) child.setName("nested");
        });
        try (var recorder = JournalRecorder.start(root, binding, "initial", 0)) {
            var ancestor = observeName(root, "root", trace, ignored -> {});
            try {
                child.setName("outer");
                same(List.of("child outer", "child nested", "root nested", "root outer"), trace, "live reentrant order");
                same(List.of("nested", "outer"), recording.all().stream().map(ObserverOrderConformance::value)
                        .collect(Collectors.toList()), "history from an ancestor group reached after a local callback");
                var replayed = JournalReplayer.replay(checkpoint, binding, "initial", recorder.finish());
                same(List.of("initial"), List.of(checkpoint.getChildren().get(0).getName()), "the checkpoint remains unchanged");
                same(List.of("nested"), List.of(replayed.getChildren().get(0).getName()), "V1 replays both values in mutation order");
            } finally {
                ancestor.unsubscribe();
            }
        } finally {
            local.unsubscribe();
        }
    }

    private static void checkNativeReentrantUndo(boolean ancestorFirst, boolean separateSlot) {
        var root = namedNode("root");
        var child = namedNode("initial");
        var target = separateSlot ? namedNode("other") : child;
        root.getChildren().add(child);
        if (separateSlot) root.getChildren().add(target);

        var recording = root.vmf().changes();
        if (ancestorFirst) recording.start();
        var local = child.vmf().changes().addPropertyListener("name", change -> {
            if (!value(change).equals("outer")) return;
            same(ancestorFirst ? List.of("outer") : List.of(), recordedNames(recording.all()),
                    "native history visible from the local callback");
            target.setName("nested");
        });
        try {
            if (!ancestorFirst) recording.start();
            child.setName("outer");
        } finally {
            local.unsubscribe();
            recording.stop();
        }

        if (recording.transactions().size() != 1) throw new AssertionError("Expected one native transaction");
        var transaction = recording.transactions().get(0);
        same(ancestorFirst ? List.of("outer", "nested") : List.of("nested", "outer"),
                recordedNames(transaction.changes()), "native transaction order");
        boolean undoable = ancestorFirst || separateSlot;
        if (transaction.isUndoable() != undoable) throw new AssertionError("Unexpected native undoability");
        transaction.undo();
        if (!(undoable ? "initial" : "nested").equals(child.getName())
                || separateSlot && !"other".equals(target.getName())) {
            throw new AssertionError("Undo must match the native transaction's recorded order");
        }
    }

    private static void checkPublicationWhileScalarRelayIsPending() {
        var root = namedNode("root");
        var child = namedNode("initial");
        root.getChildren().add(child);
        var binding = new JournalBinding() {
            @Override public String modelId() { return "conformance.observer-order"; }
            @Override public int modelVersion() { return 1; }
            @Override public String id(VObject node) { return ((ArchiveNode) node).getParent() == null ? "root" : "child"; }
            @Override public String type(VObject node) { return node.vmf().reflect().type().getName(); }
            @Override public VObject create(String type, String id) { throw new AssertionError("No nodes are created"); }
        };
        var current = new JournalRecorder[1];
        var local = child.vmf().changes().addPropertyListener("name", change -> {
            if (!value(change).equals("outer")) return;
            child.setName("nested");
            same(List.of("nested"), recordedNames(root.vmf().changes().all()), "the outer scalar relay is pending");
            try {
                current[0].publishFrame();
                throw new AssertionError("A pending scalar relay must prevent frame publication");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("pending in native history")) throw expected;
            }
        });
        try (var recorder = JournalRecorder.start(root, binding, "initial", 0)) {
            current[0] = recorder;
            child.setName("outer");
            if (!"nested".equals(child.getName()) || root.vmf().changes().isModelVersioningEnabled()) {
                throw new AssertionError("Failed frame publication must stop recording and preserve the committed edits");
            }
        } finally {
            local.unsubscribe();
        }
    }

    private static List<String> recordedNames(List<? extends Change> changes) {
        return changes.stream().map(ObserverOrderConformance::value).collect(Collectors.toList());
    }

    private enum RecordingAction { START, STOP, RESTART, CLEAR }

    private static void applyRecordingAction(Changes recording, RecordingAction action) {
        switch (action) {
            case START: recording.start(); break;
            case STOP: recording.stop(); break;
            case RESTART: recording.stop(); recording.start(); break;
            case CLEAR: recording.clear(); break;
        }
    }

    private static void checkRecordingLifecycle(RecordingAction action, boolean insideAncestor, boolean existingRelay) {
        var root = namedNode("root");
        var child = namedNode("initial");
        root.getChildren().add(child);
        var recording = root.vmf().changes();
        var live = new ArrayList<String>();
        var duringCallback = new ArrayList<String>();
        Consumer<Change> edit = change -> {
            if (!value(change).equals("outer")) return;
            applyRecordingAction(recording, action);
            child.setName("nested");
            duringCallback.addAll(recordedNames(recording.all()));
        };
        var local = insideAncestor ? null : child.vmf().changes().addPropertyListener("name", edit::accept);
        var ancestor = existingRelay ? recording.addRecursiveListener(change -> {
            if (change.object() != child) return;
            live.add(value(change));
            if (insideAncestor) edit.accept(change);
        }) : null;

        try {
            if (action != RecordingAction.START) {
                recording.start();
                root.setName("seed");
            }
            child.setName("outer");
            var expected = action == RecordingAction.STOP ? List.of("seed")
                    : insideAncestor || (action == RecordingAction.START && !existingRelay) ? List.of("nested")
                    : List.of("nested", "outer");
            var visible = action == RecordingAction.STOP ? List.of("seed")
                    : insideAncestor && action != RecordingAction.START ? List.<String>of() : List.of("nested");
            same(visible, duringCallback, "history visible inside the callback");
            same(expected, recordedNames(recording.all()), "native history after changing its lifecycle during delivery");
            same(insideAncestor ? List.of("outer", "nested") : existingRelay ? List.of("nested", "outer") : List.of(),
                    live, "live delivery during changes to the native recording lifecycle");
            recording.stop();
            if (recording.transactions().size() != 1) throw new AssertionError("Expected one native transaction");
            same(expected, recording.transactions().get(0).changes().stream().map(ObserverOrderConformance::value)
                    .collect(Collectors.toList()), "transaction membership follows the current recording");
        } finally {
            if (local != null) local.unsubscribe();
            if (ancestor != null) ancestor.unsubscribe();
            recording.stop();
        }
    }

    private static void checkRestartBetweenTwoRelaysOfTheSameStream() {
        var node = namedNode("initial");
        var recording = node.vmf().changes();
        var local = recording.addLocalListener(change -> {
            if (!value(change).equals("outer")) return;
            applyRecordingAction(recording, RecordingAction.RESTART);
            node.setName("nested");
            same(List.of(), recordedNames(recording.all()), "the active original relay still delays nested history");
        });
        var recursive = recording.addRecursiveListener(change -> {});
        try {
            recording.start();
            node.setName("outer");
            same(List.of("nested"), recordedNames(recording.all()), "the second relay cannot rerecord the original edit");
        } finally {
            local.unsubscribe();
            recursive.unsubscribe();
            recording.stop();
        }
    }

    private static void checkPortableStartWithAnExistingRelay() throws IOException {
        var root = namedNode("root");
        var child = namedNode("initial");
        root.getChildren().add(child);
        var recording = root.vmf().changes();
        var binding = new JournalBinding() {
            @Override public String modelId() { return "conformance.observer-order"; }
            @Override public int modelVersion() { return 1; }
            @Override public String id(VObject node) { return ((ArchiveNode) node).getParent() == null ? "root" : "child"; }
            @Override public String type(VObject node) { return node.vmf().reflect().type().getName(); }
            @Override public VObject create(String type, String id) { throw new AssertionError("The scalar fixture creates no nodes"); }
        };
        var late = new JournalRecorder[1];
        var checkpoint = new ArchiveNode[1];
        var local = child.vmf().changes().addPropertyListener("name", change -> {
            if (!value(change).equals("outer")) return;
            checkpoint[0] = root.clone();
            late[0] = JournalRecorder.start(root, binding, "after-outer", 0);
            child.setName("nested");
        });
        var live = new ArrayList<String>();
        var recursive = recording.addRecursiveListener(change -> live.add(value(change)));
        try {
            child.setName("outer");
            same(List.of("nested", "outer"), live, "both live notifications reach the existing relay");
            same(List.of("nested"), recordedNames(recording.all()), "the portable session starts after the initiating commit");
            var journal = late[0].finish();
            for (var format : VMFDocumentFormat.values()) {
                var restored = VMFJournalCodec.read(VMFJournalCodec.write(journal, format), format);
                if (restored.wireVersion() != 1 || restored.transactions().size() != 1
                        || restored.transactions().get(0).operations().size() != 1) {
                    throw new AssertionError("Expected a single post-checkpoint edit in V1 " + format);
                }
                var edit = restored.transactions().get(0).operations().get(0);
                if (edit.kind() != ChangeJournal.Kind.SET) throw new AssertionError("Expected a scalar replacement");
                same(List.of("outer", "nested"), List.of(edit.before().get(0).text(), edit.after().get(0).text()),
                        "the replacement's prior value comes from the checkpoint");
                var replayed = JournalReplayer.replay(checkpoint[0], binding, "after-outer", restored);
                same(List.of("outer"), List.of(checkpoint[0].getChildren().get(0).getName()), "checkpoint stays unchanged");
                same(List.of("nested"), List.of(replayed.getChildren().get(0).getName()), "V1 replays the post-checkpoint edit");
            }
        } finally {
            local.unsubscribe();
            recursive.unsubscribe();
            if (late[0] != null) late[0].close();
        }
    }

    private static void checkPortableModelPropertyStartWithAnExistingRelay(boolean owning) throws IOException {
        var root = namedNode("root");
        var editor = namedNode("editor");
        var outer = namedNode("outer");
        var nested = namedNode("nested");
        var property = owning ? "inset" : "anchor";
        root.getChildren().addAll(owning ? List.of(editor, nested) : List.of(editor, outer, nested));
        var binding = new JournalBinding() {
            @Override public String modelId() { return "conformance.observer.reference-start"; }
            @Override public int modelVersion() { return 1; }
            @Override public String id(VObject value) { return ((ArchiveNode) value).getName(); }
            @Override public String type(VObject value) { return value.vmf().reflect().type().getName(); }
            @Override public VObject create(String type, String id) { throw new AssertionError("The checkpoint owns every target"); }
        };
        var late = new JournalRecorder[1];
        var checkpoint = new ArchiveNode[1];
        var local = editor.vmf().changes().addPropertyListener(property, change -> {
            if (change.propertyChange().orElseThrow().newValue() != outer) return;
            checkpoint[0] = root.clone();
            late[0] = JournalRecorder.start(root, binding, "after-outer", 0);
            editor.setAnchor(nested);
        });
        var live = new ArrayList<String>();
        var recursive = root.vmf().changes().addPropertyListener(property, change ->
                live.add(((ArchiveNode) change.propertyChange().orElseThrow().newValue()).getName()), Changes.Scope.RECURSIVE);
        try {
            if (owning) editor.setInset(outer);
            else editor.setAnchor(outer);
            same(owning ? List.of("outer") : List.of("nested", "outer"), live,
                    "Live notifications reach the already registered relay for " + property);
            var history = root.vmf().changes().all();
            if (history.size() != 1 || history.get(0).propertyChange().orElseThrow().newValue() != nested) {
                throw new AssertionError("Only the reference edit after the checkpoint belongs to the journal session");
            }
            var journal = late[0].finish();
            var mapper = new ObjectMapper().registerModule(
                    VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
            var snapshot = mapper.writeValueAsString(checkpoint[0]);
            for (var format : VMFDocumentFormat.values()) {
                var restored = VMFJournalCodec.read(VMFJournalCodec.write(journal, format), format);
                if (restored.wireVersion() != 1 || restored.transactions().size() != 1
                        || restored.transactions().get(0).operations().size() != 1) {
                    throw new AssertionError("Expected one V1 reference edit in " + format);
                }
                var edit = restored.transactions().get(0).operations().get(0);
                var before = edit.before().get(0);
                var beforeMatches = owning ? before.kind() == ChangeJournal.ValueKind.NULL
                        : before.kind() == ChangeJournal.ValueKind.REFERENCE && before.text().equals("outer");
                if (edit.kind() != ChangeJournal.Kind.SET || !edit.property().equals("anchor") || !beforeMatches
                        || edit.after().get(0).kind() != ChangeJournal.ValueKind.REFERENCE
                        || !edit.after().get(0).text().equals("nested")) {
                    throw new AssertionError("Expected the post-checkpoint transition to nested");
                }
                var fresh = mapper.readValue(snapshot, ArchiveNode.class);
                var replay = JournalReplayer.replay(fresh, binding, "after-outer", restored);
                var freshEditor = fresh.getChildren().get(0);
                var replayEditor = replay.getChildren().get(0);
                boolean checkpointMatches = owning ? freshEditor.getAnchor() == null
                        && freshEditor.getInset().getInsetOf() == freshEditor
                        && replayEditor.getInset().getInsetOf() == replayEditor
                        : freshEditor.getAnchor() == fresh.getChildren().get(1);
                if (!checkpointMatches || replayEditor.getAnchor() != replay.getChildren().get(owning ? 1 : 2)) {
                    throw new AssertionError("The fresh checkpoint stays unchanged and replay resolves the nested identity");
                }
            }
        } finally {
            local.unsubscribe();
            recursive.unsubscribe();
            if (late[0] != null) late[0].close();
        }
    }

    private static void checkReentrantDirectReferenceRecording() {
        var root = namedNode("root");
        var editor = namedNode("editor");
        var initial = namedNode("initial");
        var outer = namedNode("outer");
        var nested = namedNode("nested");
        root.getChildren().addAll(List.of(editor, initial, outer, nested));
        editor.setAnchor(initial);
        var checkpoint = root.clone();
        var binding = new JournalBinding() {
            @Override public String modelId() { return "conformance.observer.direct-reference"; }
            @Override public int modelVersion() { return 1; }
            @Override public String id(VObject value) { return ((ArchiveNode) value).getName(); }
            @Override public String type(VObject value) { return value.vmf().reflect().type().getName(); }
            @Override public VObject create(String type, String id) {
                throw new AssertionError("A reference to a checkpoint node requires no creation");
            }
        };
        var edits = new ArrayList<String>();
        var local = editor.vmf().changes().addPropertyListener("anchor", change -> {
            var target = (ArchiveNode) change.propertyChange().orElseThrow().newValue();
            edits.add(target.getName());
            if (target == outer) editor.setAnchor(nested);
        });
        try (var recorder = JournalRecorder.start(root, binding, "initial", 0)) {
            editor.setAnchor(outer);
            same(List.of("outer", "nested"), edits, "direct reference callbacks");
            same(List.of("nested", "outer"), root.vmf().changes().all().stream()
                    .map(change -> ((ArchiveNode) change.propertyChange().orElseThrow().newValue()).getName())
                    .collect(Collectors.toList()), "native history retains the callback order across scopes");
            var replayed = JournalReplayer.replay(checkpoint, binding, "initial", recorder.finish());
            if (checkpoint.getChildren().get(0).getAnchor() != checkpoint.getChildren().get(1)
                    || replayed.getChildren().get(0).getAnchor() != replayed.getChildren().get(3)) {
                throw new AssertionError("Replay must resolve the new reference within its own graph");
            }
        } finally {
            local.unsubscribe();
        }
    }

    private static void checkCallbackInitializationOfCreatedReferences() {
        var binding = new JournalBinding() {
            @Override public String modelId() { return "conformance.observer.created-reference"; }
            @Override public int modelVersion() { return 1; }
            @Override public String id(VObject value) { return ((ArchiveNode) value).getName(); }
            @Override public String type(VObject value) { return "node"; }
            @Override public VObject create(String type, String id) { return namedNode(id); }
        };
        for (boolean owns : new boolean[]{false, true}) {
            var working = namedNode("root");
            var existing = namedNode("existing");
            var introduced = namedNode("introduced");
            working.getChildren().add(existing);
            var checkpoint = working.clone();
            String property = owns ? "inset" : "anchor";
            var listener = working.vmf().changes().addPropertyListener(property, change -> {
                if (change.propertyChange().orElseThrow().newValue() == introduced) {
                    introduced.setAnchor(existing);
                    existing.setAnchor(working);
                }
            });
            try (var recorder = JournalRecorder.start(working, binding, "initial", 0)) {
                if (owns) {
                    working.setInset(introduced);
                } else {
                    working.setAnchor(introduced);
                    working.getChildren().add(introduced);
                }
                var replayed = JournalReplayer.replay(checkpoint, binding, "initial", recorder.finish());
                var fresh = owns ? replayed.getInset() : replayed.getAnchor();
                var known = replayed.getChildren().get(0);
                if (fresh.getAnchor() != known || known.getAnchor() != replayed
                        || (owns ? fresh.getInsetOf() != replayed : replayed.getChildren().get(1) != fresh)
                        || checkpoint.getAnchor() != null || checkpoint.getInset() != null) {
                    throw new AssertionError("Replay must retain both callback-initialized and recorded references for " + property);
                }
            } finally {
                listener.unsubscribe();
            }
        }
    }

    private static ArchiveNode namedNode(String name) {
        var node = ArchiveNode.newInstance();
        node.setName(name);
        return node;
    }

    private static Subscription observeName(ArchiveNode owner, String prefix, List<String> trace, Consumer<String> seen) {
        return owner.vmf().changes().addPropertyListener("name", change -> {
            String name = value(change);
            trace.add(prefix + " " + name);
            seen.accept(name);
        }, Changes.Scope.RECURSIVE);
    }

    private static String value(Change change) {
        return (String) change.propertyChange().orElseThrow().newValue();
    }

    private static void same(List<String> expected, List<String> actual, String description) {
        if (!expected.equals(actual)) throw new AssertionError(description + " expected " + expected + " but got " + actual);
    }
}
