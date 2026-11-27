package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.conformance.values.DefaultSettings;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Frame;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Kind;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Operation;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Value;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.ValueKind;
import eu.mihosoft.vmf.runtime.core.journal.History;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@SuppressWarnings("deprecation")
final class JournalHistoryConformance {
    private static final String MODEL = "conformance.journal-history";
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return MODEL; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) { return ((ArchiveNode) object).getName(); }
        @Override public String type(VObject object) { return "node"; }
        @Override public VObject create(String type, String id) {
            if (!type.equals("node")) throw new IllegalArgumentException("Unknown archive type " + type);
            return ArchiveNode.newBuilder().withName(id).build();
        }
    };

    private JournalHistoryConformance() {}

    public static void main(String[] args) throws Exception {
        checkOverlapAndIndependence();
        checkInvalidSegments();
        checkCheckpointCopyBindings();
        checkStaleOverlap();
        checkSequenceLimit();
        checkDeepCheckpointIsolation();
        checkManagedDefaultCopies();
        checkScalarDefaultCopies();
        checkConcurrentNavigation();
        System.out.println("Java verified journal history overlap, checkpoint isolation, validation, and concurrent navigation.");
    }

    private static void checkOverlapAndIndependence() {
        var earlier = archive("main");
        var longer = record(earlier, "earlier", 20,
                root -> root.setAnchor(root.getChildren().get(0)),
                root -> root.setAnchor(root.getInset()),
                root -> root.setAnchor(null));
        var later = archive("main");
        later.setAnchor(later.getInset());
        var history = new History<ArchiveNode>(BINDING);
        history.addSegment("later", later, journal("later", 22));
        history.addSegment("earlier", earlier, longer);

        var middle = history.seek(21);
        var overlap = history.seek(22);
        check(middle.getAnchor() == middle.getChildren().get(0), "seek to earlier frame");
        check(overlap.getAnchor() == overlap.getInset(), "seek to overlap");
        check(history.seek(23).getAnchor() == null, "fall back to earlier covering segment");
        check(middle.getChildren().get(0).getParent() == middle, "list opposite owns copied child");
        check(middle.getInset().getInsetOf() == middle, "slot opposite owns copied child");
        check(middle.getTrail().get(0) == middle.getAnchor() && middle.getTrail().get(3) == middle.getAnchor()
                && middle.getTrail().get(1) == null, "repeated aliases and null sequence members");
        check(middle.getChildren().get(0) != earlier.getChildren().get(0)
                && overlap.getInset() != later.getInset() && middle.getInset() != overlap.getInset(),
                "different private mutable graph identities");

        earlier.setAnchor(earlier.getInset());
        later.setInset(null);
        middle.getChildren().clear();
        overlap.setAnchor(null);

        check(history.seek(20).getAnchor() == null, "independent earlier checkpoint");
        check(history.seek(21).getAnchor().getName().equals("left"), "independent earlier frame");
        check(history.seek(22).getAnchor().getName().equals("right"), "independent later checkpoint");
        reject(() -> history.seek(19));
        reject(() -> history.seek(24));
    }

    private static void checkInvalidSegments() {
        var checkpoint = archive("main");
        var journal = journal("known", 20);
        var history = new History<ArchiveNode>(BINDING);
        reject(() -> history.seek(20));
        reject(() -> history.addSegment("wrong", checkpoint, journal));
        reject(() -> history.addSegment("known", archive("other"), journal));
        reject(() -> history.addSegment("known", checkpoint,
                new ChangeJournal("another-model", 1, "main", "known", 20, List.of())));
        reject(() -> history.addSegment("known", checkpoint,
                new ChangeJournal(MODEL, 2, "main", "known", 20, List.of())));
        reject(() -> history.seek(20));

        history.addSegment("known", checkpoint, journal);
        reject(() -> history.addSegment("duplicate", checkpoint, journal("duplicate", 20)));
        var other = ArchiveNode.newBuilder().withName("other").build();
        var differentRoot = new ChangeJournal(MODEL, 1, "other", "foreign-root", 21, List.of());
        reject(() -> history.addSegment("foreign-root", other, differentRoot));
        var independent = new History<ArchiveNode>(BINDING);
        independent.addSegment("foreign-root", other, differentRoot);
        check(independent.seek(21).getName().equals("other"), "independent root timeline");
        check(history.seek(20).getAnchor() == null, "invalid segments leave earlier history unchanged");
        reject(() -> history.seek(21));
    }

    private static void checkCheckpointCopyBindings() {
        for (boolean changeId : new boolean[]{ true, false }) {
            var checkpoint = archive("main");
            var originalChild = checkpoint.getChildren().get(0);
            var binding = new JournalBinding() {
                @Override public String modelId() { return MODEL; }
                @Override public int modelVersion() { return 1; }
                @Override public String id(VObject object) {
                    return changeId && object == originalChild ? "left-live" : ((ArchiveNode) object).getName();
                }
                @Override public String type(VObject object) {
                    return !changeId && object == originalChild ? "live-node" : "node";
                }
                @Override public VObject create(String type, String id) {
                    throw new AssertionError("An empty journal cannot create objects.");
                }
            };
            String error = changeId ? "changed its IDs" : "changed an object type";
            rejectWithMessage(error, () -> JournalReplayer.replay(checkpoint, binding, "known", journal("known", 20)));
            String recordingError = changeId ? "different set of object IDs" : "different type for";
            rejectWithMessage(recordingError, () -> {
                try (var unexpected = JournalRecorder.start(checkpoint, binding, "known", 20)) {
                    throw new AssertionError("A recorder accepted a changed checkpoint copy.");
                }
            });
            check(!checkpoint.vmf().changes().isModelVersioningEnabled() && checkpoint.getChildren().get(0) == originalChild
                            && originalChild.getParent() == checkpoint,
                    "A rejected copy leaves the original graph and recording state intact");
        }
    }

    private static void rejectWithMessage(String expected, Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("An invalid journal operation was accepted.");
        } catch (IllegalArgumentException failure) {
            check(failure.getMessage().contains(expected), "Unexpected journal rejection: " + failure.getMessage());
        }
    }

    private static void checkStaleOverlap() {
        var earlier = archive("main");
        var history = new History<ArchiveNode>(BINDING);
        history.addSegment("earlier", earlier, record(earlier, "earlier", 30,
                root -> root.setAnchor(root.getChildren().get(0)), root -> root.setAnchor(root.getInset())));
        check(history.seek(32).getAnchor().getName().equals("right"), "valid earlier segment covers the later frame");

        var later = history.seek(31);
        var stale = new Operation(Kind.SET, "main", "anchor", List.of(), List.of(new Value(ValueKind.REFERENCE, "right")),
                List.of(new Value(ValueKind.NULL, null)), List.of());
        history.addSegment("later", later, journal("later", 31, new Frame(32, List.of(stale))));

        check(history.seek(31).getAnchor().getName().equals("left"), "registration and seek validate the selected prefix");
        reject(() -> history.seek(32));
        check(history.seek(31).getAnchor().getName().equals("left"), "failed seek leaves checkpoints unchanged");
    }

    private static void checkSequenceLimit() {
        var checkpoint = archive("main");
        var history = new History<ArchiveNode>(BINDING);
        var journal = record(checkpoint, "near-limit", Long.MAX_VALUE - 1, root -> root.setAnchor(root.getInset()));
        history.addSegment("near-limit", checkpoint, journal);
        check(history.seek(Long.MAX_VALUE - 1).getAnchor() == null, "checkpoint near the upper sequence limit");
        check(history.seek(Long.MAX_VALUE).getAnchor().getName().equals("right"), "frame at the upper sequence limit");
        reject(() -> history.seek(Long.MIN_VALUE));

        var atLimit = history.seek(Long.MAX_VALUE);
        history.addSegment("limit", atLimit, journal("limit", Long.MAX_VALUE));
        check(history.seek(Long.MAX_VALUE).getAnchor().getName().equals("right"), "checkpoint at the upper sequence limit");
    }

    private static void checkManagedDefaultCopies() throws IOException {
        String id = "managed-defaults";
        long sequence = 70;
        var checkpoint = CollectionProfile.newInstance();
        checkpoint.getMessages().addAll(Arrays.asList("kept", null));
        field(checkpoint, "messages").setDefault(List.of("unused"));
        field(checkpoint, "counts").setDefault(List.of(2, 3));
        field(checkpoint, "levels").setDefault(Set.of(2.5));
        field(checkpoint, "phases").setDefault(List.of("prepare", "finish"));
        var options = new LinkedHashMap<String, Boolean>();
        options.put("ready", true);
        options.put("absent", null);
        field(checkpoint, "options").setDefault(options);
        var binding = collectionBinding("profile", model -> { });
        var empty = new ChangeJournal(MODEL, 1, "profile", id, sequence, List.of());
        var direct = JournalReplayer.replay(checkpoint, binding, id, empty);
        verifyManagedDefaults(direct);
        check(List.of("unused").equals(field(checkpoint, "messages").getDefault())
                        && field(direct, "messages").getDefault() == null,
                "The ordinary list's live data is copied without the source's per-instance default");

        var initial = new History<CollectionProfile>(binding);
        initial.addSegment(id, checkpoint, empty);
        var working = initial.seek(sequence);
        verifyManagedDefaults(working);
        int[] shadowInspections = {0};
        var recordingBinding = collectionBinding("profile", candidate -> {
            if (candidate == working) return;
            verifyManagedDefaults((CollectionProfile) candidate);
            shadowInspections[0]++;
        });
        ChangeJournal journal;
        try (var recorder = JournalRecorder.start(working, recordingBinding, id, sequence)) {
            working.getCounts().add(9);
            journal = recorder.finish();
        }
        check(shadowInspections[0] > 0 && journal.wireVersion() == 1, "The recorder verified an independent V1 shadow");
        var ordinary = CollectionProfile.newBuilder().appendCollections(false).applyFrom(checkpoint).build();
        check(field(ordinary, "counts").isSet(), "Building the same live data alone uses the declaration default");
        var plain = JournalRecorder.recordFrame(ordinary, binding, id, sequence, profile -> profile.getCounts().add(9));
        for (var format : VMFDocumentFormat.values()) {
            check(VMFJournalCodec.write(plain, format).equals(VMFJournalCodec.write(journal, format)),
                    "Per-instance defaults do not change V1 document bytes in " + format);
        }

        var history = new History<CollectionProfile>(binding);
        history.addSegment(id, checkpoint, VMFJournalCodec.read(VMFJournalCodec.write(journal)));
        field(checkpoint, "counts").setDefault(List.of(12));
        var replayed = history.seek(sequence + 1);
        check(replayed.getCounts().equals(List.of(2, 3, 9)) && field(replayed.asReadOnly(), "counts").isSet(),
                "A frame changes live storage independently of its preserved default");
        field(replayed, "counts").unset();
        check(replayed.getCounts().equals(List.of(2, 3)) && !field(replayed.asReadOnly(), "counts").isSet(),
                "Reset restores the original per-instance default");
        replayed.getOptions().clear();
        check(checkpoint.getOptions().containsKey("ready") && history.seek(sequence + 1).getCounts().equals(List.of(2, 3, 9))
                        && checkpoint.getCounts().equals(List.of(12)), "Fresh seeks and source storage remain independent");
        verifyManagedDefaults(history.seek(sequence));
    }

    private static void checkScalarDefaultCopies() {
        var checkpoint = DefaultSettings.newInstance();
        field(checkpoint, "limit").setDefault(8);
        var binding = collectionBinding("settings", model -> { });
        var empty = new ChangeJournal(MODEL, 1, "settings", "scalar", 0, List.of());
        var copy = JournalReplayer.replay(checkpoint, binding, "scalar", empty);
        check(copy.getLimit() == 8 && field(copy, "limit").getDefault().equals(7) && field(copy, "limit").isSet(),
                "A scalar copy retains the value and restores its declared default");
        field(copy, "limit").unset();
        check(copy.getLimit() == 7 && checkpoint.getLimit() == 8 && !field(checkpoint, "limit").isSet(),
                "Resetting the scalar copy does not affect the original");
    }

    private static void verifyManagedDefaults(CollectionProfile profile) {
        for (VObject model : List.of(profile, profile.asReadOnly())) {
            check(field(model, "counts").getDefault().equals(List.of(2, 3))
                            && field(model, "levels").getDefault().equals(Set.of(2.5))
                            && field(model, "phases").getDefault().equals(List.of("prepare", "finish")),
                    "The live checkpoint copies retain managed defaults");
            var values = (java.util.Map<?, ?>) field(model, "options").getDefault();
            check(Boolean.TRUE.equals(values.get("ready")) && values.containsKey("absent") && values.get("absent") == null,
                    "Managed map defaults keep the present null entry");
            for (String name : List.of("levels", "phases", "options")) {
                check(!field(model, name).isSet(), "Read-only and mutable reflection agree on " + name);
            }
        }
        check(profile.getMessages().equals(Arrays.asList("kept", null)), "The ordinary list retains its live slots");
    }

    private static Property field(VObject model, String name) {
        return model.vmf().reflect().propertyByName(name).orElseThrow();
    }

    private static JournalBinding collectionBinding(String root, Consumer<VObject> inspect) {
        return new JournalBinding() {
            @Override public String modelId() { return MODEL; }
            @Override public int modelVersion() { return 1; }
            @Override public String id(VObject model) { inspect.accept(model); return root; }
            @Override public String type(VObject model) { return root; }
            @Override public VObject create(String type, String id) { throw new AssertionError("No created objects"); }
        };
    }

    private static void checkDeepCheckpointIsolation() {
        for (int depth : new int[]{499, 500, 750}) {
            var tail = ArchiveNode.newBuilder().withName("child-" + depth).build();
            var root = tail;
            for (int index = depth - 1; index >= 0; index--) {
                var parent = ArchiveNode.newBuilder().withName(index == 0 ? "main" : "child-" + index).build();
                parent.getChildren().add(root);
                root = parent;
            }
            final var checkpoint = root;
            checkpoint.setAnchor(tail);
            checkpoint.getTrail().addAll(Arrays.asList(tail, null, tail));

            String id = "deep";
            long sequence = 50;
            var initial = new History<ArchiveNode>(BINDING);
            initial.addSegment(id, checkpoint, journal(id, sequence));
            var working = initial.seek(sequence);
            var workingTail = checkDeepGraph(working, depth);
            check(workingTail != tail && working.getAnchor() == workingTail, "independent deep working graph");

            ChangeJournal recorded;
            try (var recorder = JournalRecorder.start(working, BINDING, id, sequence)) {
                working.setAnchor(working);
                recorded = recorder.finish();
            }
            check(recorded.wireVersion() == 1 && recorded.transactions().size() == 1, "one V1 frame");
            var validFrame = recorded.transactions().get(0);
            check(validFrame.operations().size() == 1 && validFrame.operations().get(0).kind() == Kind.SET,
                    "one portable scalar-reference edit");
            var stale = new Operation(Kind.SET, "main", "anchor", List.of(),
                    List.of(new Value(ValueKind.REFERENCE, tail.getName())), List.of(new Value(ValueKind.NULL, null)), List.of());
            var invalid = journal(id, sequence, validFrame, new Frame(sequence + 2, List.of(stale)));
            var history = new History<ArchiveNode>(BINDING);
            history.addSegment(id, checkpoint, invalid);
            for (int attempt = 0; attempt < 2; attempt++) {
                rejectWithMessage("Old value differs for anchor", () ->
                        JournalReplayer.replay(checkpoint, BINDING, id, invalid));
                rejectWithMessage("Old value differs for anchor", () -> history.seek(sequence + 2));
                check(checkDeepGraph(checkpoint, depth) == tail && checkpoint.getAnchor() == tail,
                        "failed replay preserves the original deep checkpoint");
                var baseline = history.seek(sequence);
                check(checkDeepGraph(baseline, depth) == baseline.getAnchor() && baseline.getAnchor() != tail,
                        "failed seek preserves the registered checkpoint");
                var replayed = history.seek(sequence + 1);
                check(replayed.getAnchor() == replayed && checkDeepGraph(replayed, depth) != baseline.getAnchor(),
                        "a later retry returns a fresh graph with the valid prefix");
                replayed.getTrail().clear();
            }
        }
    }

    private static ArchiveNode checkDeepGraph(ArchiveNode root, int depth) {
        var tail = root;
        for (int edge = 0; edge < depth; edge++) {
            var parent = tail;
            check(parent.getChildren().size() == 1, "each deep edge remains present");
            tail = parent.getChildren().get(0);
            check(tail.getParent() == parent, "deep parent inverse remains present");
        }
        check(tail.getChildren().isEmpty() && tail.getName().equals("child-" + depth), "deep tail identity");
        check(root.getTrail().size() == 3 && root.getTrail().get(0) == tail && root.getTrail().get(1) == null
                && root.getTrail().get(2) == tail, "deep trail preserves duplicate aliases and null slots");
        return tail;
    }

    private static void checkConcurrentNavigation() throws Exception {
        var history = new History<ArchiveNode>(BINDING);
        var baseline = archive("main");
        history.addSegment("initial", baseline, journal("initial", 0));
        var executor = Executors.newFixedThreadPool(4);
        try {
            var operations = new ArrayList<Callable<Void>>();
            for (int sequence = 1; sequence <= 16; sequence++) {
                int current = sequence;
                operations.add(() -> {
                    var checkpoint = history.seek(0);
                    checkpoint.setAnchor(checkpoint.getInset());
                    history.addSegment("parallel", checkpoint, journal("parallel", current));
                    checkpoint.setAnchor(null);
                    var restored = history.seek(current);
                    check(restored.getAnchor() == restored.getInset(), "concurrent registration takes an independent copy");
                    check(history.seek(0).getAnchor() == null, "concurrent seeks never expose the stored checkpoint");
                    return null;
                });
            }
            for (var future : executor.invokeAll(operations)) future.get();

            Callable<Boolean> attempt = () -> {
                try {
                    history.addSegment("contested", baseline, journal("contested", 100));
                    return true;
                } catch (IllegalArgumentException duplicate) {
                    return false;
                }
            };
            int accepted = 0;
            for (var future : executor.invokeAll(List.of(attempt, attempt, attempt, attempt))) {
                if (future.get()) accepted++;
            }
            check(accepted == 1 && history.seek(100).getAnchor() == null, "one registration wins for a contested base");
        } finally {
            executor.shutdownNow();
        }
    }

    private static ChangeJournal journal(String id, long sequence, Frame... frames) {
        return new ChangeJournal(MODEL, 1, "main", id, sequence, List.of(frames));
    }

    @SafeVarargs
    private static ChangeJournal record(ArchiveNode checkpoint, String id, long sequence, Consumer<ArchiveNode>... edits) {
        var working = checkpoint.clone();
        try (var recorder = JournalRecorder.start(working, BINDING, id, sequence)) {
            for (var edit : edits) {
                edit.accept(working);
                recorder.publishFrame();
            }
            return recorder.finish();
        }
    }

    private static ArchiveNode archive(String id) {
        var root = ArchiveNode.newBuilder().withName(id).build();
        var left = ArchiveNode.newBuilder().withName("left").build();
        var right = ArchiveNode.newBuilder().withName("right").build();
        root.getChildren().add(left);
        root.setInset(right);
        root.getTrail().addAll(Arrays.asList(left, null, right, left));
        return root;
    }

    private static void reject(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid history operation was accepted");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
