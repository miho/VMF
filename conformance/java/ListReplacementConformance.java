package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Locale;

final class ListReplacementConformance {
    private static final String CHECKPOINT = "list-replacement-0";
    private static final ObjectMapper SNAPSHOTS = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "conformance.list-replacement"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject model) { return "profile"; }
        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new AssertionError("No creations."); }
    };

    private ListReplacementConformance() {}

    static void verifyNative() {
        verifyOrdinaryFailureAndEmptyReplacement();
        var profile = profile();
        var observed = new ArrayList<String>();
        observe(profile.getPhases(), "phases", observed);
        var visits = new ArrayList<String>();
        try (var recorder = JournalRecorder.start(profile, BINDING, CHECKPOINT, 0)) {
            try {
                profile.getPhases().replaceAll(value -> {
                    visits.add(value);
                    return value.equals("a") ? null : value;
                });
                throw new AssertionError("Null replacement accepted.");
            } catch (NullPointerException expected) { }
            try {
                profile.getPhases().replaceAll(value -> "duplicate");
                throw new AssertionError("Duplicate replacement accepted.");
            } catch (IllegalArgumentException expected) { }
            profile.getCounts().replaceAll(value -> { throw new AssertionError("An empty list has no slots."); });
            check(visits.equals(List.of("a", "b", "c")) && profile.getPhases().equals(visits)
                            && observed.isEmpty() && profile.vmf().changes().all().isEmpty()
                            && recorder.finish().transactions().isEmpty(),
                    "Checked null/duplicate validation and empty replacement do not mutate or publish");
        }

        var node = ArchiveNode.newBuilder().withName("head").build();
        var alias = ArchiveNode.newBuilder().withName("alias").build();
        var trail = node.getTrail();
        trail.addAll(Arrays.asList(alias, null, alias, node));
        var history = node.vmf().changes();
        observe(node.asReadOnly().getTrail(), "view", observed);
        observe(trail, "mutable", observed);
        history.start();
        trail.replaceAll(value -> value == alias ? node : value);
        history.stop();
        check(trail.equals(Arrays.asList(node, null, node, node))
                        && observed.equals(List.of("view:[0, 1, 2, 3]", "mutable:[0, 1, 2, 3]"))
                        && history.all().size() == 1, "Ordinary reference replacement is one full-range edit");
        var edit = history.all().get(0);
        edit.undo();
        check(trail.get(0) == alias && trail.get(2) == alias && trail.get(1) == null, "Undo restores the original aliases");
        edit.apply(node);
        check(trail.get(0) == node && trail.get(2) == node && trail.get(1) == null, "Redo restores the replacement aliases");
    }

    private static void verifyOrdinaryFailureAndEmptyReplacement() {
        var profile = profile();
        var messages = profile.getMessages();
        var observed = new ArrayList<String>();
        observe(messages, "messages", observed);
        var failure = new IllegalStateException("transform failed");
        try (var recorder = JournalRecorder.start(profile, BINDING, CHECKPOINT, 0)) {
            try {
                messages.replaceAll(value -> {
                    if (value == null) throw failure;
                    return "changed";
                });
                throw new AssertionError("The transform failure was lost.");
            } catch (IllegalStateException error) {
                check(error == failure, "The original transform error is propagated");
            }
            try {
                messages.asUnmodifiable().replaceAll(value -> {
                    throw new AssertionError("Unmodifiable list replacement reached the operator.");
                });
                throw new AssertionError("Replacement through an unmodifiable list was accepted.");
            } catch (UnsupportedOperationException expected) { }
            try {
                profile.asReadOnly().getMessages().replaceAll(value -> value);
                throw new AssertionError("Replacement through a read-only model was accepted.");
            } catch (UnsupportedOperationException expected) { }
            check(messages.equals(Arrays.asList("a", null, "c")) && observed.isEmpty()
                            && profile.vmf().changes().all().isEmpty() && recorder.finish().transactions().isEmpty(),
                    "A throwing ordinary transform leaves the live list and recorder unchanged");
        }

        var empty = CollectionProfile.newInstance();
        observe(empty.getMessages(), "empty messages", observed);
        try (var recorder = JournalRecorder.start(empty, BINDING, CHECKPOINT, 0)) {
            empty.getMessages().replaceAll(value -> { throw new AssertionError("An empty list has no slots."); });
            check(observed.isEmpty() && empty.vmf().changes().all().isEmpty()
                            && recorder.finish().transactions().isEmpty(),
                    "Replacing an empty ordinary list is a valid no-op during recording");
        }

        var reentrant = profile();
        var reentrantMessages = reentrant.getMessages();
        try (var recorder = JournalRecorder.start(reentrant, BINDING, CHECKPOINT, 0)) {
            try {
                reentrantMessages.replaceAll(value -> {
                    if ("a".equals(value)) reentrantMessages.add("explicit");
                    return "replacement";
                });
                throw new AssertionError("A stale prepared replacement was committed.");
            } catch (ConcurrentModificationException expected) { }
            var journal = recorder.finish();
            check(reentrantMessages.equals(Arrays.asList("a", null, "c", "explicit")) && journal.transactions().size() == 1,
                    "A transform cannot overwrite its own explicit edit");
            var edits = journal.transactions().get(0).operations();
            check(edits.size() == 1 && edits.get(0).kind() == ChangeJournal.Kind.LIST_ADD
                            && edits.get(0).indices().equals(List.of(3)),
                    "Only the explicit edit is captured");
        }
    }

    static void exchange(Path directory, boolean write) throws IOException {
        String author = write ? "java" : "csharp";
        var snapshot = directory.resolve(author + "-list-replacement-checkpoint.json");
        ChangeJournal recorded = null;
        if (write) {
            var profile = profile();
            SNAPSHOTS.writeValue(snapshot.toFile(), profile);
            var observed = new ArrayList<String>();
            observe(profile.asReadOnly().getMessages(), "view", observed);
            var failure = new IllegalStateException("observer failed");
            profile.getMessages().addChangeListener(edit -> { throw failure; });
            observe(profile.getMessages(), "mutable", observed);
            try (var recorder = JournalRecorder.start(profile, BINDING, CHECKPOINT, 0)) {
                try {
                    profile.getMessages().replaceAll(value -> value == null ? null : value.toUpperCase(Locale.ROOT));
                    throw new AssertionError("The observer failure was lost.");
                } catch (IllegalStateException error) {
                    check(error == failure, "The original observer error is propagated");
                }
                check(observed.equals(List.of("view:[0, 1, 2]", "mutable:[0, 1, 2]")),
                        "Remaining observers receive the committed full-range edit");
                check(recorder.publishFrame().orElseThrow().sequence() == 1, "Commit survives the observer error");
                profile.getPhases().replaceAll(value -> value.equals("a") ? "c" : value.equals("c") ? "a" : value);
                check(recorder.publishFrame().orElseThrow().sequence() == 2, "Checked replacement is one edit");
                profile.getPhases().replaceAll(value -> value);
                recorded = recorder.finish();
            }
        }
        for (var format : VMFDocumentFormat.values()) {
            var path = directory.resolve(author + "-list-replacement." + format.name().toLowerCase(Locale.ROOT));
            if (write) Files.writeString(path, VMFJournalCodec.write(recorded, format));
            var journal = VMFJournalCodec.read(Files.readString(path), format);
            check(journal.wireVersion() == 1 && journal.transactions().size() == 3, "Three full-range V1 frames");
            for (var frame : journal.transactions()) {
                check(frame.operations().size() == 1, "One operation per full replacement");
                var edit = frame.operations().get(0);
                check(edit.kind() == ChangeJournal.Kind.LIST_SET && edit.indices().equals(List.of(0, 1, 2)),
                        "Replacement and identity transform both retain the full range");
            }
            var original = SNAPSHOTS.readValue(snapshot.toFile(), CollectionProfile.class);
            var replaced = JournalReplayer.replay(original, BINDING, CHECKPOINT, journal);
            check(original.getMessages().equals(Arrays.asList("a", null, "c"))
                            && original.getPhases().equals(List.of("a", "b", "c"))
                            && replaced.getMessages().equals(Arrays.asList("A", null, "C"))
                            && replaced.getPhases().equals(List.of("c", "b", "a")),
                    "Replay recreates the replacement values and preserves the independent checkpoint");
        }
    }

    private static void observe(VList<?> list, String scope, List<String> observed) {
        list.addChangeListener(edit -> {
            check(Arrays.equals(edit.removed().indices(), edit.added().indices()), "Replacement reports both sides");
            observed.add(scope + ":" + Arrays.toString(edit.added().indices()));
        });
    }

    private static CollectionProfile profile() {
        var profile = CollectionProfile.newInstance();
        profile.getMessages().addAll(Arrays.asList("a", null, "c"));
        profile.getPhases().addAll(List.of("a", "b", "c"));
        profile.getLimits().put("one", 1L);
        profile.getLevels().add(1.0);
        return profile;
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
