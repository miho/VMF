package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.ArchiveRoot;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MembershipJournalConformance {
    private static final String MODEL = "conformance.membership-journal";
    private static final String VALUES = "values";
    private static final String ARCHIVE = "archive";
    private static final String HEAD = "main";
    private static final Value NULL = new Value(ValueKind.NULL, null);
    private static final Value POSITIVE_ZERO = floating(0.0);
    private static final Value NEGATIVE_ZERO = floating(-0.0);
    private static final Value NAN = floating(Double.NaN);
    private static final Value INFINITY = floating(Double.POSITIVE_INFINITY);

    private static final ObjectMapper PLAIN = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL));
    private static final ObjectMapper REFERENCES = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return MODEL; }
        @Override public int modelVersion() { return 1; }

        @Override public String id(VObject object) {
            return object instanceof ArchiveNode ? ((ArchiveNode) object).getName() : type(object);
        }

        @Override public String type(VObject object) {
            if (object instanceof CollectionProfile) return VALUES;
            if (object instanceof ArchiveRoot) return ARCHIVE;
            if (object instanceof ArchiveNode) return "node";
            throw new IllegalArgumentException("Unknown journal model");
        }

        @Override public VObject create(String type, String id) {
            throw new IllegalArgumentException("The membership journal uses existing checkpoint objects");
        }
    };

    private enum Scenario {
        STRING_MAP("string-map", "membership-v3-30", 30),
        TYPED_MEMBERSHIP("typed-membership", "membership-v4-40", 40),
        REFERENCE_SET("reference-set", "membership-v5-50", 50),
        SCALAR_LIST("scalar-list", "scalar-list-60", 60),
        DIRECT_MEMBERSHIP("direct-membership", "direct-membership-70", 70);

        private final String label;
        private final String checkpoint;
        private final long baseSequence;

        Scenario(String label, String checkpoint, long baseSequence) {
            this.label = label;
            this.checkpoint = checkpoint;
            this.baseSequence = baseSequence;
        }
    }

    private MembershipJournalConformance() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 3 && args[0].equals("write")) {
            var directory = Files.createDirectories(Path.of(args[1]));
            write(directory, Path.of(args[2]));
            verify(directory, "java");
            System.out.println("Java recorded and replayed its scalar lists, string maps, typed membership and reference sets in JSON and XML.");
        } else if (args.length == 2 && args[0].equals("read")) {
            var directory = Path.of(args[1]);
            verify(directory, "csharp");
            verifyDirectMembership(directory);
            System.out.println("Java replayed the C# scalar lists, string maps, typed membership and reference sets in JSON and XML, including reentrant direct membership edits.");
        } else {
            throw new IllegalArgumentException("Usage: MembershipJournalConformance write <directory> <fixturesDir> | read <directory>");
        }
    }

    private static void write(Path directory, Path fixtures) throws IOException {
        var valuesFile = fixtures.resolve("scalar-collections.json").toFile();
        var limits = PLAIN.readValue(valuesFile, CollectionProfile.class);
        PLAIN.writeValue(file(directory, "java", Scenario.STRING_MAP, "checkpoint").toFile(), limits);
        writeJournal(directory, Scenario.STRING_MAP, limits, () -> {
            var updates = new LinkedHashMap<String, Long>();
            updates.put("optional", Long.MIN_VALUE);
            updates.put("added", null);
            limits.getLimits().putAll(updates);
            limits.getLimits().remove("limit");
        });

        var collections = PLAIN.readValue(valuesFile, CollectionProfile.class);
        PLAIN.writeValue(file(directory, "java", Scenario.TYPED_MEMBERSHIP, "checkpoint").toFile(), collections);
        writeJournal(directory, Scenario.TYPED_MEMBERSHIP, collections, () -> {
            collections.getLevels().remove(+0.0);
            collections.getLevels().add(7.0);
            var updates = new LinkedHashMap<Double, Character>();
            updates.put(+0.0, 'p');
            updates.put(Double.NaN, null);
            updates.put(Double.POSITIVE_INFINITY, null);
            collections.getMarkers().putAll(updates);
            collections.getMarkers().remove(-0.0);
        });

        var causalLists = PLAIN.readValue(valuesFile, CollectionProfile.class);
        causalLists.getCounts().clear();
        causalLists.getMessages().clear();
        PLAIN.writeValue(file(directory, "java", Scenario.SCALAR_LIST, "checkpoint").toFile(), causalLists);
        var direct = causalLists.getCounts().addChangeListener(change -> {
            if (change.wasAdded() && change.added().elements().contains(1)) causalLists.getCounts().add(2);
        });
        var recursive = causalLists.vmf().changes().addRecursiveListener(change -> {
            if (change.propertyName().equals("messages")
                    && change.listChange().orElseThrow().added().elements().contains("outer")) {
                causalLists.getMessages().add("inner");
            }
        });
        try {
            writeJournal(directory, Scenario.SCALAR_LIST, causalLists, () -> {
                causalLists.getCounts().add(1);
                causalLists.getMessages().add("outer");
                var history = causalLists.vmf().changes().all();
                // A model listener's edit is recorded after its cause (portable contract H4). The raw list
                // listener edits before any model relay sees the counts event; raw listeners are outside the contract.
                check(history.size() == 4 && history.get(0).listChange().orElseThrow().added().indices()[0] == 1
                        && history.get(1).listChange().orElseThrow().added().indices()[0] == 0
                        && history.get(2).listChange().orElseThrow().added().indices()[0] == 0
                        && history.get(3).listChange().orElseThrow().added().indices()[0] == 1,
                        "Scalar list native history order");
            });
        } finally {
            direct.unsubscribe();
            recursive.unsubscribe();
        }

        var archive = REFERENCES.readValue(fixtures.resolve("archive-references.json").toFile(), ArchiveRoot.class);
        var head = archive.getHead();
        var left = head.getChildren().get(0);
        var right = head.getInset();
        head.getPeers().add(left);
        REFERENCES.writeValue(file(directory, "java", Scenario.REFERENCE_SET, "checkpoint").toFile(), archive);
        writeJournal(directory, Scenario.REFERENCE_SET, archive, () -> {
            head.getPeers().remove(left);
            head.getPeers().add(right);
        });
    }

    private static <T extends VObject> void writeJournal(Path directory, Scenario scenario, T working, Runnable edit)
            throws IOException {
        try (var recorder = JournalRecorder.start(working, BINDING, scenario.checkpoint, scenario.baseSequence)) {
            edit.run();
            var journal = recorder.finish();
            check(journal.wireVersion() == 1, "Membership records use journal version 1");
            Files.writeString(file(directory, "java", scenario, "journal"), VMFJournalCodec.write(journal));
            Files.writeString(journalFile(directory, "java", scenario, VMFDocumentFormat.XML), VMFJournalCodec.write(journal, VMFDocumentFormat.XML));
        }
    }

    private static void verify(Path directory, String author) throws IOException {
        for (var format : List.of(VMFDocumentFormat.JSON, VMFDocumentFormat.XML)) {
            verify(directory, author, format);
        }
    }

    private static void verify(Path directory, String author, VMFDocumentFormat format) throws IOException {
        var limits = PLAIN.readValue(file(directory, author, Scenario.STRING_MAP, "checkpoint").toFile(), CollectionProfile.class);
        var afterLimits = verifyReplay(directory, author, Scenario.STRING_MAP, limits, expectedStringMap(), format);
        check(List.copyOf(afterLimits.getLimits().keySet()).equals(List.of("optional", "added")),
                "String map replay removes the old key and preserves retained/new insertion order");
        check(Long.valueOf(Long.MIN_VALUE).equals(afterLimits.getLimits().get("optional"))
                && afterLimits.getLimits().containsKey("added") && afterLimits.getLimits().get("added") == null,
                "String map replay retains full precision and present null");
        rejectReplay(limits, Scenario.STRING_MAP, Operation.mapEdit(VALUES, "limits", Map.of("missing", NULL), Map.of()),
                "Map precondition differs");
        rejectReplay(limits, Scenario.STRING_MAP, Operation.mapEdit(VALUES, "limits", Map.of(), Map.of("optional", NULL)),
                "Map key already exists");
        check(List.copyOf(limits.getLimits().keySet()).equals(List.of("limit", "optional"))
                && Long.valueOf(Long.MAX_VALUE).equals(limits.getLimits().get("limit"))
                && limits.getLimits().get("optional") == null, "String map replay leaves the checkpoint unchanged");

        var collections = PLAIN.readValue(file(directory, author, Scenario.TYPED_MEMBERSHIP, "checkpoint").toFile(), CollectionProfile.class);
        var afterCollections = verifyReplay(directory, author, Scenario.TYPED_MEMBERSHIP, collections, expectedTypedMembership(), format);
        check(afterCollections.getLevels().size() == 3 && !afterCollections.getLevels().contains(+0.0)
                && afterCollections.getLevels().containsAll(List.of(-0.0, Double.NaN, 7.0)),
                "Scalar set membership distinguishes signed zero and retains NaN");
        check(List.copyOf(afterCollections.getMarkers().keySet()).equals(List.of(+0.0, Double.NaN, Double.POSITIVE_INFINITY))
                && Character.valueOf('p').equals(afterCollections.getMarkers().get(+0.0))
                && afterCollections.getMarkers().get(Double.NaN) == null
                && afterCollections.getMarkers().get(Double.POSITIVE_INFINITY) == null,
                "Typed map replay keeps typed keys, nullable values and insertion order");
        rejectReplay(collections, Scenario.TYPED_MEMBERSHIP, Operation.setEdit(VALUES, "levels", List.of(floating(8.0)), List.of()),
                "Set membership precondition differs");
        rejectReplay(collections, Scenario.TYPED_MEMBERSHIP, Operation.typedMapEdit(VALUES, "markers",
                Map.of(NEGATIVE_ZERO, character('+')), Map.of()), "Map precondition differs");
        check(collections.getLevels().containsAll(List.of(+0.0, -0.0, Double.NaN))
                && List.copyOf(collections.getMarkers().keySet()).equals(List.of(+0.0, -0.0, Double.NaN))
                && Character.valueOf('-').equals(collections.getMarkers().get(-0.0)), "Typed membership replay leaves the checkpoint unchanged");

        var causalLists = PLAIN.readValue(file(directory, author, Scenario.SCALAR_LIST, "checkpoint").toFile(), CollectionProfile.class);
        var afterLists = verifyReplay(directory, author, Scenario.SCALAR_LIST, causalLists, expectedScalarList(), format);
        check(afterLists.getCounts().equals(List.of(1, 2)) && afterLists.getMessages().equals(List.of("outer", "inner")),
                "Each initiating insertion precedes its reentrant insertion in a fresh replay");
        check(causalLists.getCounts().isEmpty() && causalLists.getMessages().isEmpty(), "Replay leaves its checkpoint unchanged");

        var archive = REFERENCES.readValue(file(directory, author, Scenario.REFERENCE_SET, "checkpoint").toFile(), ArchiveRoot.class);
        var afterArchive = verifyReplay(directory, author, Scenario.REFERENCE_SET, archive, expectedReferenceSet(), format);
        var head = afterArchive.getHead();
        var right = head.getInset();
        check(head.getPeers().size() == 1 && head.getPeers().iterator().next() == right
                && afterArchive.getReview().get(2) == right && right != archive.getHead().getInset(),
                "Reference set replay resolves the existing contained identity in the independent replay");
        rejectReplay(archive, Scenario.REFERENCE_SET, referenceEdit(List.of(reference("right")), List.of()),
                "Reference membership precondition differs");
        rejectReplay(archive, Scenario.REFERENCE_SET, referenceEdit(List.of(), List.of(reference("missing"))),
                "Unknown or changed object ID");
        check(archive.getHead().getPeers().size() == 1
                && archive.getHead().getPeers().iterator().next() == archive.getHead().getChildren().get(0),
                "Reference set replay leaves checkpoint identities unchanged");
    }

    private static void verifyDirectMembership(Path directory) throws IOException {
        var scenario = Scenario.DIRECT_MEMBERSHIP;
        for (var format : List.of(VMFDocumentFormat.JSON, VMFDocumentFormat.XML)) {
            var original = PLAIN.readValue(file(directory, "csharp", scenario, "checkpoint").toFile(), CollectionProfile.class);
            var restored = verifyReplay(directory, "csharp", scenario, original, expectedDirectMembership(), format);
            check(Long.valueOf(2).equals(restored.getLimits().get("optional"))
                    && Character.valueOf('c').equals(restored.getMarkers().get(-0.0))
                    && Character.valueOf('+').equals(restored.getMarkers().get(+0.0)),
                    "Reentrant updates replace the same map entries and distinguish signed zero");
            check(restored.getLevels().equals(original.getLevels()) && !restored.getLevels().contains(7.0),
                    "Reentrant set removal follows the initiating insertion");
            check(original.getLimits().containsKey("optional") && original.getLimits().get("optional") == null
                    && Character.valueOf('-').equals(original.getMarkers().get(-0.0)),
                    "Direct membership replay leaves the checkpoint unchanged");
        }
    }

    private static <T extends VObject> T verifyReplay(Path directory, String author, Scenario scenario, T checkpoint,
            List<Operation> expected, VMFDocumentFormat format) throws IOException {
        var wire = Files.readString(journalFile(directory, author, scenario, format));
        var journal = VMFJournalCodec.read(wire, format);
        var canonical = PLAIN.readTree(VMFJournalCodec.write(journal(checkpoint, scenario, expected)));
        var actual = PLAIN.readTree(format == VMFDocumentFormat.JSON ? wire : VMFJournalCodec.write(journal));
        check(journal.wireVersion() == 1 && canonical.equals(actual),
                "Exact " + format + " journal version, operation order and values\nExpected " + canonical + "\nActual " + actual);
        var replayed = JournalReplayer.replay(checkpoint, BINDING, scenario.checkpoint, journal);
        check(replayed != checkpoint, "Replay uses an independent graph");
        return replayed;
    }

    private static void rejectReplay(VObject checkpoint, Scenario scenario, Operation invalid, String message) throws IOException {
        var encoded = VMFJournalCodec.write(journal(checkpoint, scenario, List.of(invalid)));
        try {
            JournalReplayer.replay(checkpoint, BINDING, scenario.checkpoint, VMFJournalCodec.read(encoded));
        } catch (IllegalArgumentException failure) {
            if (failure.getMessage().contains(message)) return;
            throw failure;
        }
        throw new AssertionError("Expected replay rejection containing " + message);
    }

    private static List<Operation> expectedStringMap() {
        var assigned = new LinkedHashMap<String, Value>();
        assigned.put("optional", longValue(Long.MIN_VALUE));
        assigned.put("added", NULL);
        return List.of(
                Operation.mapEdit(VALUES, "limits", Map.of("optional", NULL), assigned),
                Operation.mapEdit(VALUES, "limits", Map.of("limit", longValue(Long.MAX_VALUE)), Map.of()));
    }

    private static List<Operation> expectedTypedMembership() {
        var prior = new LinkedHashMap<Value, Value>();
        prior.put(POSITIVE_ZERO, character('+'));
        prior.put(NAN, NULL);
        var assigned = new LinkedHashMap<Value, Value>();
        assigned.put(POSITIVE_ZERO, character('p'));
        assigned.put(NAN, NULL);
        assigned.put(INFINITY, NULL);
        return List.of(
                Operation.setEdit(VALUES, "levels", List.of(POSITIVE_ZERO), List.of()),
                Operation.setEdit(VALUES, "levels", List.of(), List.of(floating(7.0))),
                Operation.typedMapEdit(VALUES, "markers", prior, assigned),
                Operation.typedMapEdit(VALUES, "markers", Map.of(NEGATIVE_ZERO, character('-')), Map.of()));
    }

    private static List<Operation> expectedScalarList() {
        return List.of(
                listAdd("counts", 0, new Value(ValueKind.INT, "1")),
                listAdd("counts", 1, new Value(ValueKind.INT, "2")),
                listAdd("messages", 0, new Value(ValueKind.STRING, "outer")),
                listAdd("messages", 1, new Value(ValueKind.STRING, "inner")));
    }

    private static List<Operation> expectedDirectMembership() {
        var one = longValue(1);
        var two = longValue(2);
        var seven = List.of(floating(7.0));
        return List.of(
                Operation.mapEdit(VALUES, "limits", Map.of("optional", NULL), Map.of("optional", one)),
                Operation.mapEdit(VALUES, "limits", Map.of("optional", one), Map.of("optional", two)),
                Operation.typedMapEdit(VALUES, "markers", Map.of(NEGATIVE_ZERO, character('-')), Map.of(NEGATIVE_ZERO, character('b'))),
                Operation.typedMapEdit(VALUES, "markers", Map.of(NEGATIVE_ZERO, character('b')), Map.of(NEGATIVE_ZERO, character('c'))),
                Operation.setEdit(VALUES, "levels", List.of(), seven),
                Operation.setEdit(VALUES, "levels", seven, List.of()));
    }

    private static Operation listAdd(String property, int index, Value value) {
        return new Operation(Kind.LIST_ADD, VALUES, property, List.of(index), List.of(), List.of(value), List.of());
    }

    private static List<Operation> expectedReferenceSet() {
        return List.of(referenceEdit(List.of(reference("left")), List.of()),
                referenceEdit(List.of(), List.of(reference("right"))));
    }

    private static Operation referenceEdit(List<Value> before, List<Value> after) {
        return new Operation(Kind.REF_SET_EDIT, HEAD, "peers", List.of(), before, after, List.of());
    }

    private static ChangeJournal journal(VObject checkpoint, Scenario scenario, List<Operation> operations) {
        long base = scenario.baseSequence;
        return new ChangeJournal(MODEL, 1, BINDING.id(checkpoint), scenario.checkpoint, base,
                List.of(new Frame(base + 1, operations)));
    }

    private static Path file(Path directory, String author, Scenario scenario, String part) {
        return directory.resolve(author + "-" + scenario.label + "-" + part + ".json");
    }

    private static Path journalFile(Path directory, String author, Scenario scenario, VMFDocumentFormat format) {
        return format == VMFDocumentFormat.JSON ? file(directory, author, scenario, "journal")
                : directory.resolve(author + "-" + scenario.label + "-journal.xml");
    }

    private static Value floating(double value) { return new Value(ValueKind.DOUBLE, Double.toHexString(value)); }
    private static Value longValue(long value) { return new Value(ValueKind.LONG, Long.toString(value)); }
    private static Value character(char value) { return new Value(ValueKind.CHAR, Integer.toString(value)); }
    private static Value reference(String id) { return new Value(ValueKind.REFERENCE, id); }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
