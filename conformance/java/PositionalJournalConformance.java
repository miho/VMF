package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.ArchiveRoot;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.RefSet;
import eu.mihosoft.vmf.runtime.core.RefSetChange;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Frame;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Kind;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Operation;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Value;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.ValueKind;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

final class PositionalJournalConformance {
    private static final String MODEL = "conformance.positional-journal";
    private static final String ROOT = "archive";
    private static final String HEAD = "main";
    private static final String CHECKPOINT = "archive-checkpoint-20";
    private static final long BASE_SEQUENCE = 20L;

    private static final ObjectMapper REFERENCES = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return MODEL; }
        @Override public int modelVersion() { return 1; }

        @Override public String id(VObject object) {
            if (object instanceof ArchiveRoot) return ROOT;
            if (object instanceof ArchiveNode) return ((ArchiveNode) object).getName();
            throw new IllegalArgumentException("Unknown archive journal object");
        }

        @Override public String type(VObject object) {
            if (object instanceof ArchiveRoot) return ROOT;
            if (object instanceof ArchiveNode) return "node";
            throw new IllegalArgumentException("Unknown archive journal object");
        }

        @Override public VObject create(String type, String id) {
            if (type.equals(ROOT) && id.equals(ROOT)) return ArchiveRoot.newInstance();
            if (type.equals("node")) return ArchiveNode.newBuilder().withName(id).build();
            throw new IllegalArgumentException("Unknown archive journal type or ID");
        }
    };

    private PositionalJournalConformance() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 3 && args[0].equals("write")) {
            var directory = Files.createDirectories(Path.of(args[1]));
            var checkpoint = directory.resolve("java-checkpoint.json");
            var journal = directory.resolve("java-journal.json");
            write(Path.of(args[2]), checkpoint, journal);
            verify(checkpoint, journal);
            verifyNestedTrailExchange(directory, "java", true);
            verifyNestedSlotExchange(directory, "java", true);
            verifyNestedMembershipExchange(directory, "java", true);
            System.out.println("Java recorded and replayed its V1 positional journal from a fresh archive checkpoint.");
        } else if (args.length == 2 && args[0].equals("read")) {
            var directory = Path.of(args[1]);
            verify(directory.resolve("csharp-checkpoint.json"), directory.resolve("csharp-journal.json"));
            verifyNestedTrailExchange(directory, "csharp", false);
            verifyNestedSlotExchange(directory, "csharp", false);
            verifyNestedMembershipExchange(directory, "csharp", false);
            System.out.println("Java replayed the C# V1 positional journal from its archive checkpoint.");
        } else {
            throw new IllegalArgumentException("Usage: PositionalJournalConformance write <directory> <fixture> | read <directory>");
        }
    }

    private static void write(Path fixture, Path checkpoint, Path journalFile) throws IOException {
        var working = REFERENCES.readValue(fixture.toFile(), ArchiveRoot.class);
        verifyBase(working);
        REFERENCES.writeValue(checkpoint.toFile(), working);
        var head = working.getHead();
        var left = head.getChildren().get(0);
        var right = head.getInset();

        ChangeJournal journal;
        try (var recorder = JournalRecorder.start(working, BINDING, CHECKPOINT, BASE_SEQUENCE)) {
            head.setAnchor(left);
            head.getTrail().remove(1);
            head.getTrail().addAll(1, Arrays.asList(right, null));
            head.getFeatured().move(1, 0);
            check(recorder.publishFrame().orElseThrow().sequence() == 21, "first native frame sequence");

            head.setAnchor(right);
            head.getTrail().set(0, left);
            head.getFeatured().remove(1);
            journal = recorder.finish();
        }
        verifyFinal(working);
        Files.writeString(journalFile, VMFJournalCodec.write(journal));
    }

    private static ChangeJournal expected() {
        var nothing = new Value(ValueKind.NULL, null);
        var left = new Value(ValueKind.REFERENCE, "left");
        var right = new Value(ValueKind.REFERENCE, "right");
        return new ChangeJournal(MODEL, 1, ROOT, CHECKPOINT, BASE_SEQUENCE, List.of(
                new Frame(21, List.of(
                        edit(Kind.SET, "anchor", List.of(), List.of(nothing), List.of(left)),
                        edit(Kind.LIST_REMOVE, "trail", List.of(1), List.of(nothing), List.of()),
                        edit(Kind.LIST_ADD, "trail", List.of(1, 2), List.of(), List.of(right, nothing)),
                        edit(Kind.LIST_SET, "featured", List.of(0, 1), List.of(left, right), List.of(right, left)))),
                new Frame(22, List.of(
                        edit(Kind.SET, "anchor", List.of(), List.of(left), List.of(right)),
                        edit(Kind.LIST_SET, "trail", List.of(0), List.of(right), List.of(left)),
                        edit(Kind.LIST_REMOVE, "featured", List.of(1), List.of(left), List.of())))));
    }

    private static void verifyNestedTrailExchange(Path directory, String producer, boolean write) throws IOException {
        var checkpoint = REFERENCES.readValue(directory.resolve(producer + "-checkpoint.json").toFile(), ArchiveRoot.class);
        var journal = write ? recordNestedTrail(checkpoint.clone()) : null;
        var head = checkpoint.getHead();
        int start = head.getTrail().size();
        var leftId = new Value(ValueKind.REFERENCE, "left");
        var expected = new ChangeJournal(MODEL, 1, ROOT, CHECKPOINT, BASE_SEQUENCE, List.of(
                new Frame(21, List.of(
                        edit(Kind.LIST_ADD, "trail", List.of(start), List.of(), List.of(leftId)),
                        edit(Kind.LIST_ADD, "trail", List.of(start + 1, start + 2), List.of(),
                                List.of(new Value(ValueKind.NULL, null), leftId))))));
        for (var format : VMFDocumentFormat.values()) {
            var path = directory.resolve(producer + "-nested-trail-journal." + format.name().toLowerCase(java.util.Locale.ROOT));
            if (write) Files.writeString(path, VMFJournalCodec.write(journal, format));
            var read = VMFJournalCodec.read(Files.readString(path), format);
            check(REFERENCES.readTree(VMFJournalCodec.write(read)).equals(REFERENCES.readTree(VMFJournalCodec.write(expected))),
                    "nested reference append is recorded in mutation order");
            var restored = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, read).getHead();
            var left = restored.getChildren().get(0);
            checkSlots(restored.getTrail().subList(start, restored.getTrail().size()), left, null, left);
            check(head.getTrail().size() == start, "replay keeps the checkpoint unchanged");
        }
    }

    private static ChangeJournal recordNestedTrail(ArchiveRoot working) {
        var head = working.getHead();
        var left = head.getChildren().get(0);
        int start = head.getTrail().size();
        var local = head.vmf().changes().addPropertyListener("trail", change -> {
            if (change.listChange().orElseThrow().added().indices()[0] == start) {
                head.getTrail().addAll(Arrays.asList(null, left));
            }
        });
        try (var recorder = JournalRecorder.start(working, BINDING, CHECKPOINT, BASE_SEQUENCE)) {
            head.getTrail().add(left);
            check(working.vmf().changes().all().stream()
                            .map(change -> change.listChange().orElseThrow().added().indices()[0])
                            .collect(java.util.stream.Collectors.toList()).equals(List.of(start, start + 1)),
                    "native history records the outer edit before the callback's edit (H4)");
            return recorder.finish();
        } finally {
            local.unsubscribe();
        }
    }

    private static void verifyNestedSlotExchange(Path directory, String producer, boolean write) throws IOException {
        var checkpointPath = directory.resolve(producer + "-nested-slot-checkpoint.json");
        ChangeJournal journal = null;
        if (write) {
            var working = ArchiveNode.newBuilder().withName("slot").build();
            working.setInset(ArchiveNode.newBuilder().withName("initial").build());
            REFERENCES.writeValue(checkpointPath.toFile(), working);
            journal = recordNestedSlot(working);
        }
        var checkpoint = REFERENCES.readValue(checkpointPath.toFile(), ArchiveNode.class);
        for (var format : VMFDocumentFormat.values()) {
            var path = directory.resolve(producer + "-nested-slot-journal." + format.name().toLowerCase(java.util.Locale.ROOT));
            if (write) Files.writeString(path, VMFJournalCodec.write(journal, format));
            var restored = VMFJournalCodec.read(Files.readString(path), format);
            var operations = restored.transactions().get(0).operations();
            check(restored.wireVersion() == 1 && restored.transactions().size() == 1 && operations.size() == 4,
                    "One V1 frame contains both owning replacements");
            var labels = new java.util.ArrayList<String>();
            for (var operation : operations) {
                check(operation.kind() == Kind.SET && operation.target().equals("slot") && operation.property().equals("inset"),
                        "V1 carries only changes to the owning slot");
                var value = operation.after().get(0);
                labels.add(value.kind() == ValueKind.NULL ? "-" : value.text());
            }
            check(labels.equals(List.of("-", "outer", "-", "nested")), "V1 orders the replacements by mutation");
            var replayed = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, restored);
            check(replayed.getInset().getName().equals("nested") && replayed.getInset().getInsetOf() == replayed,
                    "Replay restores the nested child and its inverse");
            check(checkpoint.getInset().getName().equals("initial") && checkpoint.getInset().getInsetOf() == checkpoint,
                    "The checkpoint and its inverse stay intact");
        }
    }

    private static ChangeJournal recordNestedSlot(ArchiveNode working) {
        var outer = ArchiveNode.newBuilder().withName("outer").build();
        var nested = ArchiveNode.newBuilder().withName("nested").build();
        var trace = new java.util.ArrayList<String>();
        var local = working.vmf().changes().addPropertyListener("inset", change -> {
            var newChild = (ArchiveNode) change.propertyChange().orElseThrow().newValue();
            trace.add(newChild == null ? "-" : newChild.getName());
            if (newChild == outer) working.setInset(nested);
        });
        try (var recorder = JournalRecorder.start(working, BINDING, CHECKPOINT, BASE_SEQUENCE)) {
            working.setInset(outer);
            check(working.getInset() == nested && outer.getInsetOf() == null && nested.getInsetOf() == working,
                    "The live graph retains the nested replacement");
            check(trace.equals(List.of("-", "outer", "-", "nested")), "Public owner callbacks remain synchronous");
            var history = working.vmf().changes().all().stream().filter(change -> change.propertyName().equals("inset"))
                    .map(change -> (ArchiveNode) change.propertyChange().orElseThrow().newValue())
                    .map(child -> child == null ? "-" : child.getName()).collect(java.util.stream.Collectors.toList());
            // The triggering edit is recorded before the edits its listeners made (portable contract H4).
            check(history.equals(List.of("-", "outer", "-", "nested")), "Native history records edits in mutation order");
            return recorder.finish();
        } finally {
            local.unsubscribe();
        }
    }

    private static void verifyNestedMembershipExchange(Path directory, String producer, boolean write) throws IOException {
        for (boolean ordered : new boolean[]{true, false}) {
            String property = ordered ? "featured" : "peers";
            String fixture = producer + "-nested-" + property;
            var checkpointPath = directory.resolve(fixture + "-checkpoint.json");
            ChangeJournal journal = null;
            if (write) {
                var working = ArchiveNode.newBuilder().withName("references").build();
                var first = ArchiveNode.newBuilder().withName("first").build();
                var second = ArchiveNode.newBuilder().withName("second").build();
                working.getChildren().addAll(List.of(first, second));
                REFERENCES.writeValue(checkpointPath.toFile(), working);
                journal = recordNestedMembership(working, ordered, property);
            }
            var checkpoint = REFERENCES.readValue(checkpointPath.toFile(), ArchiveNode.class);
            var first = new Value(ValueKind.REFERENCE, "first");
            var second = new Value(ValueKind.REFERENCE, "second");
            var expected = new ChangeJournal(MODEL, 1, "references", CHECKPOINT, BASE_SEQUENCE, List.of(
                    new Frame(21, List.of(
                            membershipEdit(ordered, property, List.of(), List.of(first)),
                            membershipEdit(ordered, property, List.of(first), List.of()),
                            membershipEdit(ordered, property, List.of(), List.of(second))))));
            var expectedWire = REFERENCES.readTree(VMFJournalCodec.write(expected));
            for (var format : VMFDocumentFormat.values()) {
                var path = directory.resolve(fixture + "-journal." + format.name().toLowerCase(java.util.Locale.ROOT));
                if (write) Files.writeString(path, VMFJournalCodec.write(journal, format));
                var restored = VMFJournalCodec.read(Files.readString(path), format);
                check(REFERENCES.readTree(VMFJournalCodec.write(restored)).equals(expectedWire),
                        "V1 orders reference membership edits by mutation");
                var replayed = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, restored);
                var membership = membership(replayed, ordered);
                check(membership.size() == 1 && membership.iterator().next() == replayed.getChildren().get(1),
                        "Replay resolves the second member inside the new graph");
                check(membership(checkpoint, ordered).isEmpty(), "The original membership stays empty");
            }
        }
    }

    private static Operation membershipEdit(boolean ordered, String property, List<Value> before, List<Value> after) {
        Kind kind = ordered ? (before.isEmpty() ? Kind.LIST_ADD : Kind.LIST_REMOVE) : Kind.REF_SET_EDIT;
        return new Operation(kind, "references", property, ordered ? List.of(0) : List.of(), before, after, List.of());
    }

    private static ChangeJournal recordNestedMembership(ArchiveNode working, boolean ordered, String property) {
        var first = working.getChildren().get(0);
        var second = working.getChildren().get(1);
        var members = membership(working, ordered);
        var callbacks = new java.util.ArrayList<String>();
        var subscription = working.vmf().changes().addPropertyListener(property, change -> {
            var edit = change.referenceSetChange().orElseThrow();
            callbacks.add(membershipLabel(edit));
            if (edit.added().stream().anyMatch(value -> value == first)) {
                members.remove(first);
                members.add(second);
            }
        });
        try (var recorder = JournalRecorder.start(working, BINDING, CHECKPOINT, BASE_SEQUENCE)) {
            members.add(first);
            check(members.size() == 1 && members.iterator().next() == second, "Live membership resolves the second child");
            check(callbacks.equals(List.of("+first", "-first", "+second")), "Membership callbacks stay synchronous");
            var history = working.vmf().changes().all().stream()
                    .map(change -> membershipLabel(change.referenceSetChange().orElseThrow()))
                    .collect(java.util.stream.Collectors.toList());
            // The triggering edit is recorded before the edits its listeners made (portable contract H4).
            check(history.equals(List.of("+first", "-first", "+second")),
                    "Native history records the initiating add first: " + history);
            return recorder.finish();
        } finally {
            subscription.unsubscribe();
        }
    }

    private static RefSet<ArchiveNode> membership(ArchiveNode owner, boolean ordered) {
        return ordered ? owner.getFeatured() : owner.getPeers();
    }

    private static String membershipLabel(RefSetChange change) {
        return change.added().isEmpty() ? "-" + ((ArchiveNode) change.removed().get(0)).getName()
                : "+" + ((ArchiveNode) change.added().get(0)).getName();
    }

    private static Operation edit(Kind kind, String property, List<Integer> indices, List<Value> before, List<Value> after) {
        return new Operation(kind, HEAD, property, indices, before, after, List.of());
    }

    private static void verify(Path checkpointFile, Path journalFile) throws IOException {
        var checkpoint = REFERENCES.readValue(checkpointFile.toFile(), ArchiveRoot.class);
        verifyBase(checkpoint);
        var wire = Files.readString(journalFile);
        var journal = VMFJournalCodec.read(wire);
        var envelope = (ObjectNode) REFERENCES.readTree(wire);
        var canonical = REFERENCES.readTree(VMFJournalCodec.write(expected()));
        check(journal.wireVersion() == 1 && canonical.equals(envelope),
                "exact V1 operation order, stable IDs, indices and tagged values\nExpected " + canonical + "\nActual " + envelope);

        var prefix = new ChangeJournal(journal.model(), journal.modelVersion(), journal.root(),
                journal.checkpoint(), journal.baseSequence(), List.of(journal.transactions().get(0)));
        var intermediate = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, prefix);
        var head = verifyContainmentAndReview(intermediate);
        var left = head.getChildren().get(0);
        var right = head.getInset();
        check(head != checkpoint.getHead() && left != checkpoint.getHead().getChildren().get(0)
                && right != checkpoint.getHead().getInset(), "prefix replay resolves IDs on independently loaded graph instances");
        check(head.getAnchor() == left, "first scalar assignment resolves left's identity");
        checkSlots(head.getTrail(), right, right, null, left, right, head);
        check(head.getFeatured().size() == 2 && head.getFeatured().get(0) == right && head.getFeatured().get(1) == left,
                "ordered set move keeps identities at permuted positions");

        var replayed = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, journal);
        check(replayed != checkpoint && replayed != intermediate, "each replay has an independent root");
        verifyFinal(replayed);

        var wrongOldSlot = envelope.deepCopy();
        var removalBefore = (ObjectNode) wrongOldSlot.path("transactions").path(0).path("operations").path(1).path("before").path(0);
        removalBefore.put("kind", "REFERENCE").put("text", "right");
        rejectReplay("List precondition differs at position 1", checkpoint, wrongOldSlot);

        var unknownReference = envelope.deepCopy();
        var anchorAfter = (ObjectNode) unknownReference.path("transactions").path(1).path("operations").path(0).path("after").path(0);
        anchorAfter.put("text", "unknown");
        rejectReplay("Unknown or changed object ID unknown", checkpoint, unknownReference);
        verifyBase(checkpoint);
    }

    private static void verifyBase(ArchiveRoot root) {
        var head = verifyContainmentAndReview(root);
        var left = head.getChildren().get(0);
        var right = head.getInset();
        check(head.getAnchor() == null, "checkpoint scalar reference is missing");
        checkSlots(head.getTrail(), right, null, left, right, head);
        check(head.getFeatured().size() == 2 && head.getFeatured().get(0) == left && head.getFeatured().get(1) == right,
                "checkpoint ordered references");
    }

    private static void verifyFinal(ArchiveRoot root) {
        var head = verifyContainmentAndReview(root);
        var left = head.getChildren().get(0);
        var right = head.getInset();
        check(head.getAnchor() == right, "second scalar assignment resolves right's identity");
        checkSlots(head.getTrail(), left, right, null, left, right, head);
        check(head.getFeatured().size() == 1 && head.getFeatured().get(0) == right, "ordered set removes the recorded position");
    }

    private static ArchiveNode verifyContainmentAndReview(ArchiveRoot root) {
        var head = root.getHead();
        var left = head.getChildren().get(0);
        var right = head.getInset();
        check(HEAD.equals(head.getName()) && "left".equals(left.getName()) && "right".equals(right.getName()), "stable archive node IDs");
        check(head.getChildren().size() == 1 && left.getParent() == head && left.getInsetOf() == null
                && right.getInsetOf() == head && right.getParent() == null, "untouched containment and inverse identities");
        checkSlots(root.getReview(), left, null, right);
        return head;
    }

    private static void checkSlots(List<ArchiveNode> actual, ArchiveNode... expected) {
        check(actual.size() == expected.length, "reference list slot count");
        for (int i = 0; i < expected.length; i++) {
            check(actual.get(i) == expected[i], "exact reference or null at position " + i);
        }
    }

    private static void rejectReplay(String reason, ArchiveRoot checkpoint, ObjectNode envelope) throws IOException {
        var journal = VMFJournalCodec.read(envelope.toString());
        try {
            JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, journal);
        } catch (IllegalArgumentException problem) {
            if (problem.getMessage().contains(reason)) return;
            throw problem;
        }
        throw new AssertionError("Expected replay rejection containing " + reason);
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
