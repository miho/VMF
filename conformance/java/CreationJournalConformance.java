package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.ArchiveRoot;
import eu.mihosoft.vmf.conformance.values.ValueLabel;
import eu.mihosoft.vmf.conformance.values.ValueNode;
import eu.mihosoft.vmf.conformance.values.ValueSelection;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Creation;
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
import java.util.Map;

final class CreationJournalConformance {
    private static final String ARCHIVE_CHECKPOINT = "archive-creation-10";
    private static final String SELECTION_CHECKPOINT = "selection-creation-30";
    private static final String ARCHIVE = "archive";
    private static final String HEAD = "main";
    private static final String LEFT = "new-left";
    private static final String RIGHT = "new-right";
    private static final String SELECTION = "selection";
    private static final String BASELINE = "baseline";
    private static final String LABEL = "fresh";
    private static final String TAIL = "fresh-tail";
    private static final String CHAIN = "fresh-chain";
    private static final Value NULL = new Value(ValueKind.NULL, null);
    private static final ObjectMapper REFERENCES = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private static final JournalBinding MUTABLE = new Binding("conformance.creation-journal.mutable") {
        @Override public String id(VObject object) {
            if (object instanceof ArchiveRoot) return ARCHIVE;
            if (object instanceof ArchiveNode) return ((ArchiveNode) object).getName();
            throw new IllegalArgumentException("Unknown archive object");
        }

        @Override public VObject create(String type, String id) {
            if (type.equals(ArchiveRoot.class.getName()) && id.equals(ARCHIVE)) return ArchiveRoot.newInstance();
            if (type.equals(ArchiveNode.class.getName())) return node(id);
            throw new IllegalArgumentException("Unknown archive creation type " + type);
        }
    };

    private static final JournalBinding IMMUTABLE = new Binding("conformance.creation-journal.immutable") {
        @Override public String id(VObject object) {
            if (object instanceof ValueSelection) return SELECTION;
            if (object instanceof ValueLabel) return ((ValueLabel) object).getText();
            if (object instanceof ValueNode) return ((ValueNode) object).getNext() == null ? TAIL : CHAIN;
            throw new IllegalArgumentException("Unknown selection object");
        }

        @Override public VObject create(String type, String id) {
            if (type.equals(ValueSelection.class.getName()) && id.equals(SELECTION)) return ValueSelection.newInstance();
            if (type.equals(ValueLabel.class.getName())) return label(id);
            if (type.equals(ValueNode.class.getName())) return ValueNode.newBuilder().build();
            throw new IllegalArgumentException("Unknown selection creation type " + type);
        }
    };

    private CreationJournalConformance() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Usage: CreationJournalConformance (write|read) <interchange-directory>");
        }
        var directory = Path.of(args[1]);
        if (args[0].equals("write")) {
            Files.createDirectories(directory);
            writeMutable(directory);
            writeImmutable(directory);
            verify(directory, "java");
            System.out.println("Java recorded and replayed its mutable and immutable creation journals in JSON and XML.");
        } else {
            verify(directory, "csharp");
            System.out.println("Java replayed C# mutable creations and immutable aliases from JSON and XML journals.");
        }
        verifyFreshObserverInitialization(directory, args[0].equals("write"));
        verifyObserverCreationDependencies(directory, args[0].equals("write"));
        verifyCrossDefinitions(directory, args[0].equals("write"));
        JournalBindingConformance.main(args);
    }

    private static void verifyCrossDefinitions(Path directory, boolean write) throws IOException {
        JournalBinding binding = new Binding("conformance.creation-journal.cross-definitions") {
            @Override public String id(VObject object) { return ((ArchiveNode) object).getName(); }
            @Override public VObject create(String type, String id) {
                check(type.equals(ArchiveNode.class.getName()), "expected ArchiveNode");
                return node(id);
            }
        };
        for (String mode : List.of("one-way", "late-one-way", "mutual", "late-tracked", "tracked")) {
            boolean late = mode.startsWith("late-") || mode.equals("mutual");
            boolean mutual = mode.equals("mutual");
            boolean tracked = mode.endsWith("tracked");
            ChangeJournal journal = null;
            if (write) {
                var working = dependencyCheckpoint();
                var left = working.getChildren().get(0);
                var right = working.getChildren().get(1);
                var a = node("a");
                var b = node("b");
                var watchLeft = left.vmf().changes().addPropertyListener("inset", change -> {
                    right.setInset(b);
                    if (tracked) b.setAnchor(working);
                    if (late) a.setAnchor(mutual ? b : working);
                });
                var watchRight = right.vmf().changes().addPropertyListener("inset", change -> b.setAnchor(a));
                try (var recorder = JournalRecorder.start(working, binding, "cross-definitions", 0)) {
                    left.setInset(a);
                    var nativeHistory = working.vmf().changes().all();
                    check(nativeHistory.stream().map(change -> ((ArchiveNode) change.object()).getName())
                            .collect(java.util.stream.Collectors.toList()).equals(tracked
                                    ? List.of("right", "b", "left") : List.of("right", "left")),
                            "native attachment callbacks still record before the initiating attachment");
                    journal = recorder.finish();
                } finally {
                    watchLeft.unsubscribe();
                    watchRight.unsubscribe();
                }
            }
            for (var format : VMFDocumentFormat.values()) {
                var name = (write ? "java-" : "csharp-") + "cross-" + mode + "."
                        + format.name().toLowerCase(java.util.Locale.ROOT);
                var path = directory.resolve(name);
                if (write) Files.writeString(path, VMFJournalCodec.write(journal, format));
                var recorded = VMFJournalCodec.read(Files.readString(path), format);
                var operations = recorded.transactions().get(0).operations();
                check(recorded.wireVersion() == 1 && recorded.transactions().size() == 1
                                && operations.size() == (tracked ? 3 : 2)
                                && operations.get(0).target().equals("left") && operations.get(1).target().equals("right"),
                        "V1 attachment operations keep their causal order");
                check(operations.get(0).created().stream().map(Creation::id).collect(java.util.stream.Collectors.toList())
                                .equals(mutual ? List.of("b", "a") : List.of("a"))
                                && operations.get(1).created().size() == (mutual ? 0 : 1),
                        "only mutual definitions share the first operation");
                if (tracked) {
                    var edit = operations.get(2);
                    check(edit.target().equals("b") && edit.property().equals("anchor")
                                    && edit.before().get(0).equals(new Value(ValueKind.REFERENCE, "a"))
                                    && edit.after().get(0).equals(new Value(ValueKind.REFERENCE, "root")),
                            "the tracked edit keeps the old reference captured on B's native publication");
                }
                var restored = JournalReplayer.replay(dependencyCheckpoint(), binding, "cross-definitions", recorded);
                var first = restored.getChildren().get(0).getInset();
                var second = restored.getChildren().get(1).getInset();
                check(first.getAnchor() == (mutual ? second : late ? restored : null)
                                && second.getAnchor() == (tracked ? restored : first)
                                && first.getInsetOf() == restored.getChildren().get(0)
                                && second.getInsetOf() == restored.getChildren().get(1),
                        "causal replay restores initial references, tracked edits, and both owners");
            }
        }
    }

    private static ArchiveNode dependencyCheckpoint() {
        return ArchiveNode.newBuilder().withName("root").withChildren(node("left"), node("right")).build();
    }

    private static void verifyObserverCreationDependencies(Path directory, boolean write) throws IOException {
        JournalBinding binding = new Binding("conformance.creation-journal.observer-dependencies") {
            @Override public String id(VObject object) {
                String name = ((ArchiveNode) object).getName();
                return name.equals("outer-edited") ? "outer" : name;
            }
            @Override public VObject create(String type, String id) {
                check(type.equals(ArchiveNode.class.getName()), "expected ArchiveNode");
                return node(id);
            }
        };
        for (boolean dependent : new boolean[] { false, true }) {
            var filename = (write ? "java-" : "csharp-") + (dependent ? "dependent" : "independent") + "-observer-init.json";
            var path = directory.resolve(filename);
            if (write) {
                var working = dependencyCheckpoint();
                var left = working.getChildren().get(0);
                var right = working.getChildren().get(1);
                var outer = node("outer");
                var inner = node("inner");
                if (dependent) outer.setInset(inner);
                var listener = left.vmf().changes().addListener(change -> {
                    if (!change.propertyName().equals("inset")) return;
                    if (dependent) {
                        right.setAnchor(inner);
                    } else {
                        right.setInset(inner);
                        outer.setName("outer-edited");
                    }
                });
                try (var recorder = JournalRecorder.start(working, binding, "observer-dependencies", 0)) {
                    left.setInset(outer);
                    var history = working.vmf().changes().all();
                    check(history.size() == 2 && history.get(0).object() == right && history.get(1).object() == left,
                            "independent and dependent creations retain native callback order");
                    Files.writeString(path, VMFJournalCodec.write(recorder.finish(), VMFDocumentFormat.JSON));
                } finally {
                    listener.unsubscribe();
                }
            }
            var journal = VMFJournalCodec.read(Files.readString(path), VMFDocumentFormat.JSON);
            var operations = journal.transactions().get(0).operations();
            check(journal.wireVersion() == 1 && journal.transactions().size() == 1 && operations.size() == 2
                            && operations.get(0).target().equals("left") && operations.get(1).target().equals("right")
                            && operations.get(0).created().size() == (dependent ? 2 : 1),
                    "the left edge retains its causal position, with transitive definitions when needed");
            var checkpoint = dependencyCheckpoint();
            var replay = JournalReplayer.replay(checkpoint, binding, "observer-dependencies", journal);
            var first = replay.getChildren().get(0).getInset();
            var second = replay.getChildren().get(1);
            check(dependent ? first.getInset() == second.getAnchor()
                            : first.getName().equals("outer-edited") && second.getInset().getName().equals("inner"),
                    "independent initialization remains late and dependent references retain the introduced identity");
        }
    }

    private static void verifyFreshObserverInitialization(Path directory, boolean write) throws IOException {
        JournalBinding binding = new Binding("conformance.creation-journal.observer-init") {
            @Override public String id(VObject object) {
                return ((ArchiveNode) object).getName().startsWith("root") ? "root" : "fresh";
            }
            @Override public VObject create(String type, String id) {
                check(type.equals(ArchiveNode.class.getName()), "fresh observer initialization uses ArchiveNode");
                return node(id);
            }
        };
        for (String edge : List.of("inset", "anchor", "trail", "peers")) {
            boolean ownedByEdge = edge.equals("inset");
            for (boolean recursive : new boolean[] { false, true }) {
                var path = directory.resolve((write ? "java-" : "csharp-") + edge
                        + (recursive ? "-recursive" : "-local") + "-observer-init.json");
                if (write) {
                    var root = node("root");
                    var fresh = node("fresh");
                    var watch = root.vmf().changes().addListener(change -> {
                        if (change.object() != root || !edge.equals(change.propertyName())) return;
                        root.setName("root-edited");
                        fresh.setName("fresh-edited");
                        if (!ownedByEdge) root.setInset(fresh);
                    }, recursive);
                    try (var recorder = JournalRecorder.start(root, binding, "observer-init", 0)) {
                        switch (edge) {
                            case "inset": root.setInset(fresh); break;
                            case "anchor": root.setAnchor(fresh); break;
                            case "trail": root.getTrail().add(fresh); break;
                            case "peers": root.getPeers().add(fresh); break;
                            default: throw new AssertionError(edge);
                        }
                        var history = root.vmf().changes().all();
                        check(history.stream().map(change -> change.propertyName()).collect(java.util.stream.Collectors.toList())
                                .equals(ownedByEdge ? List.of("name", edge) : List.of("name", "inset", edge)),
                                "native callback history order");
                        Files.writeString(path, VMFJournalCodec.write(recorder.finish(), VMFDocumentFormat.JSON));
                    } finally {
                        watch.unsubscribe();
                    }
                }
                var recorded = VMFJournalCodec.read(Files.readString(path), VMFDocumentFormat.JSON);
                var ops = recorded.transactions().get(0).operations();
                check(recorded.wireVersion() == 1 && recorded.transactions().size() == 1
                        && ops.size() == (ownedByEdge ? 2 : 3)
                        && ops.get(0).property().equals(edge) && ops.get(0).created().get(0).id().equals("fresh")
                        && ops.get(1).property().equals("name") && (ownedByEdge || ops.get(2).property().equals("inset")),
                        "causal edge replay precedes the observer's scalar edit and any resulting ownership");
                var checkpoint = node("root");
                var replay = JournalReplayer.replay(checkpoint, binding, "observer-init", recorded);
                Object value = replay.vmf().reflect().propertyByName(edge).orElseThrow().get();
                var child = value instanceof Iterable<?> ? ((Iterable<?>) value).iterator().next() : value;
                check(replay.getName().equals("root-edited") && replay.getInset() == child
                        && ((ArchiveNode) child).getName().equals("fresh-edited")
                        && checkpoint.getName().equals("root"), "fresh creation preserves unobserved initialization");
            }
        }
    }

    private static void writeMutable(Path directory) throws IOException {
        var working = ArchiveRoot.newBuilder().withHead(node(HEAD)).build();
        var head = working.getHead();
        working.getReview().addAll(Arrays.asList(head, null));
        REFERENCES.writeValue(directory.resolve("java-mutable-checkpoint.json").toFile(), working);
        var left = node(LEFT);
        var right = node(RIGHT);
        left.setAnchor(right);

        try (var recorder = JournalRecorder.start(working, MUTABLE, ARCHIVE_CHECKPOINT, 10)) {
            head.getChildren().addAll(List.of(left, right));
            right.setAnchor(left);
            head.getTrail().addAll(Arrays.asList(left, null, left));
            check(recorder.publishFrame().orElseThrow().sequence() == 11, "mutable first frame sequence");

            working.getReview().add(right);
            head.setAnchor(right);
            verifyArchive(working, true);
            writeJournal(directory, "mutable", recorder.finish());
        }
    }

    private static void writeImmutable(Path directory) throws IOException {
        var baseline = label(BASELINE);
        var working = ValueSelection.newBuilder().withCurrent(baseline).withSecond(baseline).build();
        REFERENCES.writeValue(directory.resolve("java-immutable-checkpoint.json").toFile(), working);
        var fresh = label(LABEL);
        var tail = ValueNode.newBuilder().withLabel(fresh).build();
        var chain = ValueNode.newBuilder().withLabel(fresh).withNext(tail).build();

        try (var recorder = JournalRecorder.start(working, IMMUTABLE, SELECTION_CHECKPOINT, 30)) {
            working.setFirst(chain);
            working.setCurrent(fresh);
            working.setSecond(fresh);
            verifySelection(working);
            writeJournal(directory, "immutable", recorder.finish());
        }
    }

    private static void writeJournal(Path directory, String model, ChangeJournal journal) throws IOException {
        Files.writeString(journalFile(directory, "java", model, VMFDocumentFormat.JSON), VMFJournalCodec.write(journal));
        Files.writeString(journalFile(directory, "java", model, VMFDocumentFormat.XML), VMFJournalCodec.write(journal, VMFDocumentFormat.XML));
    }

    private static ChangeJournal expectedMutable() {
        var left = reference(LEFT);
        var right = reference(RIGHT);
        return new ChangeJournal(MUTABLE.modelId(), 1, ARCHIVE, ARCHIVE_CHECKPOINT, 10, List.of(
                new Frame(11, List.of(
                        edit(Kind.LIST_ADD, HEAD, "children", List.of(0, 1), List.of(), List.of(left, right),
                                archiveCreation(RIGHT, NULL), archiveCreation(LEFT, right)),
                        edit(Kind.SET, RIGHT, "anchor", List.of(), List.of(NULL), List.of(left)),
                        edit(Kind.LIST_ADD, HEAD, "trail", List.of(0, 1, 2), List.of(), List.of(left, NULL, left)))),
                new Frame(12, List.of(
                        edit(Kind.LIST_ADD, ARCHIVE, "review", List.of(2), List.of(), List.of(right)),
                        edit(Kind.SET, HEAD, "anchor", List.of(), List.of(NULL), List.of(right))))));
    }

    private static ChangeJournal expectedImmutable() {
        var fresh = immutable(LABEL);
        var baseline = immutable(BASELINE);
        var leaf = new Creation(LABEL, ValueLabel.class.getName(), Map.of("text", List.of(string(LABEL))));
        var tail = new Creation(TAIL, ValueNode.class.getName(), Map.of("label", List.of(fresh), "next", List.of(NULL)));
        var chain = new Creation(CHAIN, ValueNode.class.getName(), Map.of("label", List.of(fresh), "next", List.of(immutable(TAIL))));
        return new ChangeJournal(IMMUTABLE.modelId(), 1, SELECTION, SELECTION_CHECKPOINT, 30, List.of(
                new Frame(31, List.of(
                        edit(Kind.SET, SELECTION, "first", List.of(), List.of(NULL), List.of(immutable(CHAIN)), leaf, tail, chain),
                        edit(Kind.SET, SELECTION, "current", List.of(), List.of(baseline), List.of(fresh)),
                        edit(Kind.SET, SELECTION, "second", List.of(), List.of(baseline), List.of(fresh))))));
    }

    private static Creation archiveCreation(String name, Value anchor) {
        return new Creation(name, ArchiveNode.class.getName(), Map.of(
                "anchor", List.of(anchor), "children", List.of(), "featured", List.of(), "inset", List.of(NULL),
                "name", List.of(string(name)), "peers", List.of(), "trail", List.of()));
    }

    private static Operation edit(Kind kind, String target, String property, List<Integer> indices,
                                  List<Value> before, List<Value> after, Creation... created) {
        return new Operation(kind, target, property, indices, before, after, List.of(created));
    }

    private static void verify(Path directory, String producer) throws IOException {
        for (var format : List.of(VMFDocumentFormat.JSON, VMFDocumentFormat.XML)) {
            verify(directory, producer, format);
        }
    }

    private static void verify(Path directory, String producer, VMFDocumentFormat format) throws IOException {
        var archive = REFERENCES.readValue(directory.resolve(producer + "-mutable-checkpoint.json").toFile(), ArchiveRoot.class);
        var mutable = checkWire(journalFile(directory, producer, "mutable", format), expectedMutable(), format);
        var prefix = new ChangeJournal(mutable.model(), mutable.modelVersion(), mutable.root(), mutable.checkpoint(),
                mutable.baseSequence(), List.of(mutable.transactions().get(0)));
        verifyArchive(JournalReplayer.replay(archive, MUTABLE, ARCHIVE_CHECKPOINT, prefix), false);
        var replayed = JournalReplayer.replay(archive, MUTABLE, ARCHIVE_CHECKPOINT, mutable);
        check(replayed != archive && replayed.getHead() != archive.getHead(), "mutable replay returns an independent graph");
        verifyArchive(replayed, true);
        var reusedId = wireTree(mutable);
        ((ObjectNode) creationRecords(reusedId).get(0)).put("id", HEAD);
        rejectReplay(archive, MUTABLE, ARCHIVE_CHECKPOINT, reusedId, "Reused creation ID " + HEAD);
        check(archive.getHead().getChildren().isEmpty() && archive.getHead().getAnchor() == null
                && archive.getHead().getTrail().isEmpty() && archive.getReview().size() == 2
                && archive.getReview().get(0) == archive.getHead() && archive.getReview().get(1) == null,
                "mutable checkpoint untouched after successful and failed replay");

        var selection = REFERENCES.readValue(directory.resolve(producer + "-immutable-checkpoint.json").toFile(), ValueSelection.class);
        var immutable = checkWire(journalFile(directory, producer, "immutable", format), expectedImmutable(), format);
        var copied = JournalReplayer.replay(selection, IMMUTABLE, SELECTION_CHECKPOINT, immutable);
        check(copied != selection && copied.getCurrent() != selection.getCurrent(), "fresh immutable objects are not checkpoint objects");
        verifySelection(copied);
        verifyOrderIndependentImmutableCreations(selection, wireTree(immutable));
        check(selection.getFirst() == null && selection.getCurrent() == selection.getSecond()
                && BASELINE.equals(selection.getCurrent().getText()), "immutable checkpoint retains its original shared label");
    }

    private static Path journalFile(Path directory, String author, String model, VMFDocumentFormat format) {
        return directory.resolve(author + "-" + model + "-journal" + (format == VMFDocumentFormat.JSON ? ".json" : ".xml"));
    }

    private static ChangeJournal checkWire(Path file, ChangeJournal expected, VMFDocumentFormat format) throws IOException {
        var bytes = Files.readString(file);
        var journal = VMFJournalCodec.read(bytes, format);
        var actual = format == VMFDocumentFormat.JSON ? REFERENCES.readTree(bytes) : wireTree(journal);
        var wanted = wireTree(expected);
        check(journal.wireVersion() == 1 && wanted.equals(actual),
                "canonical " + format + " creation framing and properties\nExpected " + wanted + "\nActual " + actual);
        return journal;
    }

    private static ObjectNode wireTree(ChangeJournal journal) throws IOException {
        return (ObjectNode) REFERENCES.readTree(VMFJournalCodec.write(journal));
    }

    private static void verifyOrderIndependentImmutableCreations(ValueSelection checkpoint, ObjectNode wire) throws IOException {
        var reordered = wire.deepCopy();
        var forward = creationRecords(reordered);
        var leaf = forward.remove(0);
        var chain = forward.remove(1);
        forward.insert(0, chain);
        forward.add(leaf);
        var replayed = JournalReplayer.replay(checkpoint, IMMUTABLE, SELECTION_CHECKPOINT, VMFJournalCodec.read(reordered.toString()));
        verifySelection(replayed);

        var cyclic = wire.deepCopy();
        var tailNext = (ObjectNode) creationRecords(cyclic).get(1).path("properties").path("next").path(0);
        tailNext.put("kind", "IMMUTABLE_REFERENCE").put("text", CHAIN);
        rejectReplay(checkpoint, IMMUTABLE, SELECTION_CHECKPOINT, cyclic, "Cyclic immutable");
    }

    private static ArrayNode creationRecords(ObjectNode envelope) {
        return (ArrayNode) envelope.path("transactions").path(0).path("operations").path(0).path("created");
    }

    private static void verifyArchive(ArchiveRoot archive, boolean completed) {
        var head = archive.getHead();
        check(HEAD.equals(head.getName()) && head.getChildren().size() == 2, "mutable new nodes occupy their recorded slots");
        var left = head.getChildren().get(0);
        var right = head.getChildren().get(1);
        check(LEFT.equals(left.getName()) && RIGHT.equals(right.getName()) && left.getParent() == head && right.getParent() == head,
                "creation binds stable IDs and installs reciprocal containment");
        check(left.getAnchor() == right && right.getAnchor() == left, "initial sibling link and following edit use the same new instances");
        check(head.getTrail().size() == 3 && head.getTrail().get(0) == left && head.getTrail().get(1) == null
                && head.getTrail().get(2) == left, "trailing reference slots preserve duplicate identity and null");
        check(archive.getReview().get(0) == head && archive.getReview().get(1) == null, "baseline review identities are preserved");
        check(completed ? archive.getReview().size() == 3 && archive.getReview().get(2) == right && head.getAnchor() == right
                : archive.getReview().size() == 2 && head.getAnchor() == null, "later frame reuses the previously created node");
    }

    private static void verifySelection(ValueSelection selection) {
        var label = selection.getCurrent();
        var head = selection.getFirst();
        var tail = head.getNext();
        check(LABEL.equals(label.getText()) && selection.getSecond() == label && head.getLabel() == label
                && head != tail && tail.getLabel() == label && tail.getNext() == null, "one immutable leaf is shared by all four properties");
    }

    private static <T extends VObject> void rejectReplay(T checkpoint, JournalBinding binding, String checkpointId,
                                                         ObjectNode wire, String expectedReason) throws IOException {
        var journal = VMFJournalCodec.read(wire.toString());
        try {
            JournalReplayer.replay(checkpoint, binding, checkpointId, journal);
        } catch (IllegalArgumentException failure) {
            if (failure.getMessage().contains(expectedReason)) return;
            throw failure;
        }
        throw new AssertionError("Expected replay failure containing " + expectedReason);
    }

    private static ArchiveNode node(String name) { return ArchiveNode.newBuilder().withName(name).build(); }
    private static ValueLabel label(String text) { return ValueLabel.newBuilder().withText(text).build(); }
    private static Value string(String text) { return new Value(ValueKind.STRING, text); }
    private static Value reference(String id) { return new Value(ValueKind.REFERENCE, id); }
    private static Value immutable(String id) { return new Value(ValueKind.IMMUTABLE_REFERENCE, id); }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }

    private abstract static class Binding implements JournalBinding {
        private final String model;

        Binding(String model) { this.model = model; }

        @Override public String modelId() { return model; }
        @Override public int modelVersion() { return 1; }
        @Override public String type(VObject object) { return object.vmf().reflect().type().getName(); }
    }
}
