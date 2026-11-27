package eu.mihosoft.vmf.conformance.inheritedopposites;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
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
import java.util.List;
import java.util.Locale;

final class SecondaryReciprocalJournalConformance {
    private static final String MODEL = "conformance.secondary-reciprocal";
    private static final String ROOT = "board";
    private static final String CHECKPOINT = "dispatch-40";
    private static final Value NONE = new Value(ValueKind.NULL, null);
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return MODEL; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) {
            return object instanceof DispatchHeader ? ((DispatchHeader) object).getName() : ROOT;
        }
        @Override public String type(VObject object) { return object.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new AssertionError("No creations"); }
    };

    private SecondaryReciprocalJournalConformance() { }

    static void exchange(Path directory, boolean write, DispatchBoard checkpoint) throws IOException {
        var recorded = write ? record(checkpoint.clone()) : null;
        var json = new ObjectMapper();
        var expected = json.readTree(VMFJournalCodec.write(expected()));
        String author = write ? "java" : "csharp";
        for (var format : VMFDocumentFormat.values()) {
            String extension = format.name().toLowerCase(Locale.ROOT);
            var baseline = directory.resolve(author + "-dispatch-checkpoint." + extension);
            var changes = directory.resolve(author + "-dispatch-journal." + extension);
            var mapper = mapper(format);
            if (write) {
                Files.writeString(baseline, mapper.writeValueAsString(checkpoint));
                Files.writeString(changes, VMFJournalCodec.write(recorded, format));
            }
            var fresh = mapper.readValue(baseline.toFile(), DispatchBoard.class);
            var journal = VMFJournalCodec.read(Files.readString(changes), format);
            var normalized = json.readTree(VMFJournalCodec.write(journal));
            check(journal.wireVersion() == 1 && normalized.equals(expected),
                    "Exact V1 kind, operands and order in " + format + ": " + normalized);
            var replayed = JournalReplayer.replay(fresh, BINDING, CHECKPOINT, journal);
            check(replayed.getTeams().get(0) != fresh.getTeams().get(0)
                            && replayed.getPeople().get(0) != fresh.getPeople().get(0), "Replay uses new graph identities");
            checkFinal(replayed);
            checkBaseline(fresh);
        }
    }

    private static ChangeJournal record(DispatchBoard board) {
        var north = board.getTeams().get(0);
        var south = board.getTeams().get(1);
        var first = board.getPeople().get(0);
        var second = board.getPeople().get(1);
        try (var recorder = JournalRecorder.start(board, BINDING, CHECKPOINT, 40)) {
            second.setTeam(south);
            check(recorder.publishFrame().orElseThrow().sequence() == 41, "Membership reassignment frame");
            south.setLead(first);
            check(recorder.publishFrame().orElseThrow().sequence() == 42, "Singular displacement frame");
            check(north.getMembers().add(first), "Collection-driven transfer");
            checkFinal(board);
            return recorder.finish();
        }
    }

    private static ChangeJournal expected() {
        var north = reference("north");
        var south = reference("south");
        var first = reference("first");
        var second = reference("second");
        return new ChangeJournal(MODEL, 1, ROOT, CHECKPOINT, 40, List.of(
                new Frame(41, List.of(membership("north", List.of(second), List.of()), set("second", "team", NONE, south))),
                new Frame(42, List.of(set("north", "lead", first, NONE), set("south", "lead", second, first))),
                new Frame(43, List.of(membership("south", List.of(first), List.of()), membership("north", List.of(), List.of(first))))));
    }

    private static Operation membership(String target, List<Value> removed, List<Value> added) {
        return new Operation(Kind.REF_SET_EDIT, target, "members", List.of(), removed, added, List.of());
    }

    private static Operation set(String target, String property, Value before, Value after) {
        return new Operation(Kind.SET, target, property, List.of(), List.of(before), List.of(after), List.of());
    }

    private static Value reference(String id) { return new Value(ValueKind.REFERENCE, id); }

    private static void checkBaseline(DispatchBoard board) {
        var north = board.getTeams().get(0);
        var south = board.getTeams().get(1);
        var first = board.getPeople().get(0);
        var second = board.getPeople().get(1);
        check(north.getMembers().size() == 1 && north.getMembers().contains(second) && second.getTeam() == north
                        && south.getMembers().size() == 1 && south.getMembers().contains(first) && first.getTeam() == south
                        && north.getLead() == first && first.getLeadOf() == north && south.getLead() == second && second.getLeadOf() == south,
                "Independent checkpoint retains both original reciprocal pairs");
    }

    private static void checkFinal(DispatchBoard board) {
        var north = board.getTeams().get(0);
        var south = board.getTeams().get(1);
        var first = board.getPeople().get(0);
        var second = board.getPeople().get(1);
        check(north instanceof SpecializedDispatchTeam && south instanceof SpecializedDispatchTeam
                        && first instanceof SpecializedDispatchMember && second instanceof SpecializedDispatchMember,
                "Portable replay preserves the generated types");
        check(north.getMembers().size() == 1 && north.getMembers().contains(first) && first.getTeam() == north
                        && south.getMembers().size() == 1 && south.getMembers().contains(second) && second.getTeam() == south
                        && north.getLead() == null && south.getLead() == first && first.getLeadOf() == south && second.getLeadOf() == null,
                "Both relationships resolve by identity to the final endpoints");
    }

    private static ObjectMapper mapper(VMFDocumentFormat format) {
        ObjectMapper mapper;
        switch (format) {
            case JSON: mapper = new ObjectMapper(); break;
            case XML: mapper = new XmlMapper(); break;
            case YAML: mapper = new YAMLMapper(); break;
            default: throw new IllegalArgumentException("Unexpected format " + format);
        }
        return mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
