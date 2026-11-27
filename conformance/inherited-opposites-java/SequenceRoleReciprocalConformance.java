package eu.mihosoft.vmf.conformance.inheritedopposites;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class SequenceRoleReciprocalConformance {
    private static final String CHECKPOINT = "sequence-roles-0";
    private static final ObjectMapper SNAPSHOTS = new ObjectMapper().registerModule(VMFJacksonModule
            .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "conformance.sequence-role-reciprocal"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) {
            if (object instanceof OrderedRoleTeam) return ((OrderedRoleTeam) object).getName();
            if (object instanceof OrderedRolePerson) return ((OrderedRolePerson) object).getName();
            if (object instanceof ListRoleTeam) return ((ListRoleTeam) object).getName();
            if (object instanceof ListRolePerson) return ((ListRolePerson) object).getName();
            if (object instanceof SequenceRoleBoard) return "board";
            throw new IllegalArgumentException("Unexpected model " + object.vmf().reflect().type().getName());
        }
        @Override public String type(VObject object) { return object.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new AssertionError("No creations."); }
    };

    private SequenceRoleReciprocalConformance() { }

    static void verify() throws IOException {
        var board = checkpoint();
        var ordered = board.getOrderedTeams().get(0).getMembers();
        var first = board.getOrderedPeople().get(0);
        var second = board.getOrderedPeople().get(1);
        check(!ordered.add(first), "Ordinary add on a unique end reports an existing identity");
        try {
            ordered.add(0, second);
            throw new AssertionError("Indexed insertion accepted an existing ordered-set member");
        } catch (IllegalArgumentException expected) { }
        ordered.move(1, 0);
        check(ordered.get(0) == second && ordered.get(1) == first && first.getTeam() == board.getOrderedTeams().get(0)
                        && ordered.indexOf(first.asReadOnly()) == 1, "Ordered moves preserve the inverse and view alias");

        var listTeam = board.getListTeams().get(0);
        var ordinary = listTeam.getMembers();
        var member = board.getListPeople().get(0);
        check(ordinary.add(member) && ordinary.size() == 3 && ordinary.get(0) == null && ordinary.get(2) == member,
                "Appending a duplicate retains the later occurrence with its correct last index");
        ordinary.add(0, member);
        check(ordinary.size() == 3 && ordinary.get(0) == null && ordinary.get(2) == member && member.getTeam() == listTeam,
                "Inserting a duplicate before the existing slot preserves the existing position");
        check(listTeam.asReadOnly().getMembers().get(2) == member.asReadOnly()
                        && ordinary.remove(member.asReadOnly()) && member.getTeam() == null,
                "Projected aliases preserve positions and can remove the actual ordinary member");

        var json = (ObjectNode) SNAPSHOTS.readTree(SNAPSHOTS.writeValueAsString(checkpoint()));
        ((ObjectNode) json.get("orderedPeople").get(0)).remove("team");
        ((ObjectNode) json.get("listPeople").get(1)).remove("team");
        checkBaseline(SNAPSHOTS.treeToValue(json, SequenceRoleBoard.class));
        verifySortObservation();
    }

    private static void verifySortObservation() {
        var board = checkpoint();
        var team = board.getListTeams().get(0);
        var a = board.getListPeople().get(0);
        var b = board.getListPeople().get(1);
        var members = team.getMembers();
        var view = team.asReadOnly().getMembers();
        var delivered = new ArrayList<String>();
        members.addChangeListener(edit -> delivered.add("mutable:" + Arrays.toString(edit.removed().indices())
                + ":" + Arrays.toString(edit.added().indices())));
        view.addChangeListener(edit -> delivered.add("read-only:" + Arrays.toString(edit.removed().indices())
                + ":" + Arrays.toString(edit.added().indices())));
        a.vmf().changes().addPropertyListener("team", edit -> delivered.add("inverse-a"));
        b.vmf().changes().addPropertyListener("team", edit -> delivered.add("inverse-b"));
        var history = team.vmf().changes();
        history.start();
        members.sort(Comparator.comparingInt(member -> member == a ? 2 : member == b ? 0 : 1));
        history.stop();
        checkReplacement(board, "swap");
        check(delivered.equals(List.of("read-only:[0, 2]:[0, 2]", "mutable:[0, 2]:[0, 2]"))
                        && history.all().size() == 1,
                "Ordinary sort sends one sparse positional edit to each list and keeps both inverses");

        var change = history.all().get(0);
        check(change.isUndoable(), "A native sort can be undone");
        delivered.clear();
        change.undo();
        checkBaseline(board);
        change.apply(team);
        checkReplacement(board, "swap");
        check(delivered.equals(List.of(
                "read-only:[0, 1, 2]:[0, 1, 2]", "mutable:[0, 1, 2]:[0, 1, 2]",
                "read-only:[0, 1, 2]:[0, 1, 2]", "mutable:[0, 1, 2]:[0, 1, 2]")),
                "Native undo and redo batch the positional edit without touching inverse membership");

        var third = ListRolePerson.newInstance();
        var fourth = ListRolePerson.newInstance();
        members.set(0, third);
        members.set(2, fourth);
        delivered.clear();
        change.apply(team);
        checkReplacement(board, "swap");
        check(third.getTeam() == null && fourth.getTeam() == null
                        && delivered.containsAll(List.of("mutable:[0]:[0]", "mutable:[2]:[2]", "inverse-a", "inverse-b")),
                "Applying a change with different membership still performs per-index relationship replacements");

        members.remove(2);
        delivered.clear();
        check(!change.isUndoable(), "A removed position prevents native undo");
        change.undo();
        check(members.size() == 2 && members.get(0) == b && members.get(1) == null
                        && a.getTeam() == null && delivered.isEmpty(),
                "The undo guard leaves a list with a missing position intact");
    }

    static void exchange(Path directory, boolean write) throws IOException {
        String author = write ? "java" : "csharp";
        var snapshot = directory.resolve(author + "-sequence-role-checkpoint.json");
        var journal = directory.resolve(author + "-sequence-role-journal.json");
        if (write) {
            var board = checkpoint();
            Files.writeString(snapshot, SNAPSHOTS.writeValueAsString(board));
            Files.writeString(journal, VMFJournalCodec.write(record(board)));
        }
        var baseline = SNAPSHOTS.readValue(snapshot.toFile(), SequenceRoleBoard.class);
        var changes = VMFJournalCodec.read(Files.readString(journal));
        check(changes.wireVersion() == 1 && changes.transactions().size() == 3, "Ordered edits publish three V1 frames");
        var replayed = JournalReplayer.replay(baseline, BINDING, CHECKPOINT, changes);
        checkBaseline(baseline);
        checkFinal(replayed);
        check(replayed.getOrderedPeople().get(0) != baseline.getOrderedPeople().get(0)
                        && replayed.getListPeople().get(0) != baseline.getListPeople().get(0),
                "Replay creates independent identities");
        verifyReinsertions(directory, write, snapshot);
        verifyReplacements(directory, write, snapshot);
        verifyInvalidListEdit(baseline);
    }

    private static void verifyReinsertions(Path directory, boolean write, Path snapshot) throws IOException {
        for (String scenario : List.of("append", "earlier", "batch")) {
            String author = write ? "java" : "csharp";
            var journal = directory.resolve(author + "-sequence-role-reinsert-" + scenario + ".json");
            var baseline = SNAPSHOTS.readValue(snapshot.toFile(), SequenceRoleBoard.class);
            if (write) {
                var edited = baseline.clone();
                var people = edited.getListPeople();
                var members = edited.getListTeams().get(0).getMembers();
                try (var recorder = JournalRecorder.start(edited, BINDING, CHECKPOINT, 0)) {
                    switch (scenario) {
                        case "append": members.add(people.get(0)); break;
                        case "earlier": members.add(0, people.get(1)); break;
                        case "batch": members.addAll(1, Arrays.asList(people.get(0), people.get(1), null)); break;
                        default: throw new AssertionError(scenario);
                    }
                    checkReinsertion(edited, scenario);
                    Files.writeString(journal, VMFJournalCodec.write(recorder.finish()));
                }
            }
            var changes = VMFJournalCodec.read(Files.readString(journal));
            var operations = changes.transactions().get(0).operations();
            var expectedIndices = scenario.equals("batch") ? List.of(1, 2, 3) : List.of(scenario.equals("append") ? 3 : 0);
            check(changes.wireVersion() == 1 && changes.transactions().size() == 1
                            && operations.size() == 1 && operations.get(0).kind() == ChangeJournal.Kind.LIST_ADD
                            && operations.get(0).indices().equals(expectedIndices),
                    "Reinsertion preserves the V1 LIST_ADD operation and original insertion positions");
            checkReinsertion(JournalReplayer.replay(baseline, BINDING, CHECKPOINT, changes), scenario);
            checkBaseline(baseline);
        }
    }

    private static void checkReinsertion(SequenceRoleBoard board, String scenario) {
        var team = board.getListTeams().get(0);
        var a = board.getListPeople().get(0);
        var b = board.getListPeople().get(1);
        var expected = scenario.equals("append") ? Arrays.asList(null, b, a)
                : scenario.equals("earlier") ? Arrays.asList(a, null, b) : Arrays.asList(a, null, null, b);
        var members = team.getMembers();
        check(members.size() == expected.size() && a.getTeam() == team && b.getTeam() == team,
                "Reinsertion retains one occurrence per linked person and both inverses");
        for (int index = 0; index < expected.size(); index++) {
            check(members.get(index) == expected.get(index), "Reinsertion preserves expected positional identity");
        }
    }

    private static void verifyReplacements(Path directory, boolean write, Path snapshot) throws IOException {
        String author = write ? "java" : "csharp";
        for (String scenario : write ? List.of("single", "contiguous", "swap", "batch", "operator")
                : List.of("single", "swap", "operator")) {
            var baseline = SNAPSHOTS.readValue(snapshot.toFile(), SequenceRoleBoard.class);
            ChangeJournal recorded = null;
            if (write) {
                var edited = baseline.clone();
                var a = edited.getListPeople().get(0);
                var b = edited.getListPeople().get(1);
                var members = edited.getListTeams().get(0).getMembers();
                try (var recorder = JournalRecorder.start(edited, BINDING, CHECKPOINT, 0)) {
                    switch (scenario) {
                        case "single": members.set(2, a); break;
                        case "contiguous": members.setAll(1, Arrays.asList(a, null)); break;
                        case "swap": members.sort(Comparator.comparingInt(member -> member == a ? 2 : member == b ? 0 : 1)); break;
                        case "batch": members.setAll(0, Arrays.asList(b, null, b)); break;
                        case "operator":
                            verifyReplaceAll(edited.getListTeams().get(0), a, b);
                            break;
                        default: throw new AssertionError(scenario);
                    }
                    checkReplacement(edited, scenario);
                    recorded = recorder.finish();
                }
            }
            List<Integer> indices;
            switch (scenario) {
                case "single": indices = List.of(2); break;
                case "contiguous": indices = List.of(1, 2); break;
                case "swap": indices = List.of(0, 2); break;
                case "batch":
                case "operator": indices = List.of(0, 1, 2); break;
                default: throw new AssertionError(scenario);
            }
            var formats = scenario.equals("swap") || scenario.equals("operator")
                    ? List.of(VMFDocumentFormat.values()) : List.of(VMFDocumentFormat.JSON);
            for (var format : formats) {
                var journal = directory.resolve(author + "-sequence-role-replace-" + scenario + "."
                        + format.name().toLowerCase(Locale.ROOT));
                if (write) Files.writeString(journal, VMFJournalCodec.write(recorded, format));
                var changes = VMFJournalCodec.read(Files.readString(journal), format);
                var operations = changes.transactions().get(0).operations();
                check(changes.wireVersion() == 1 && changes.transactions().size() == 1 && operations.size() == 1
                                && operations.get(0).kind() == ChangeJournal.Kind.LIST_SET
                                && operations.get(0).indices().equals(indices),
                        "Replacement preserves the V1 LIST_SET operation and original sparse or contiguous indices");
                checkReplacement(JournalReplayer.replay(baseline, BINDING, CHECKPOINT, changes), scenario);
                checkBaseline(baseline);
            }
        }
    }

    private static void verifyReplaceAll(ListRoleTeam team, ListRoleMember a, ListRoleMember b) {
        var events = new ArrayList<String>();
        team.asReadOnly().getMembers().addChangeListener(edit -> events.add("view:"
                + Arrays.toString(edit.removed().indices()) + "/" + Arrays.toString(edit.added().indices())));
        team.getMembers().addChangeListener(edit -> events.add("mutable:"
                + Arrays.toString(edit.removed().indices()) + "/" + Arrays.toString(edit.added().indices())));
        team.getMembers().replaceAll(member -> member == a ? b : member);
        check(events.equals(List.of("view:[0]/[]", "mutable:[0]/[]",
                        "view:[0, 1, 2]/[0, 1, 2]", "mutable:[0, 1, 2]/[0, 1, 2]")),
                "Singular inverse normalization precedes full-range replacement observers");
    }

    private static void checkReplacement(SequenceRoleBoard board, String scenario) {
        var team = board.getListTeams().get(0);
        var a = board.getListPeople().get(0);
        var b = board.getListPeople().get(1);
        List<ListRoleMember> expected;
        switch (scenario) {
            case "single": expected = Arrays.asList(null, a); break;
            case "contiguous": expected = Arrays.asList(a, null); break;
            case "swap": expected = Arrays.asList(b, null, a); break;
            case "batch":
            case "operator": expected = Arrays.asList(null, b); break;
            default: throw new AssertionError(scenario);
        }
        boolean normalized = scenario.equals("batch") || scenario.equals("operator");
        check(team.getMembers().size() == expected.size() && a.getTeam() == (normalized ? null : team)
                        && b.getTeam() == (normalized || scenario.equals("swap") ? team : null),
                "A replacement leaves the intended opposite assignments");
        for (int index = 0; index < expected.size(); index++) {
            check(team.getMembers().get(index) == expected.get(index), "A replacement preserves expected positional identities");
        }
    }

    private static void verifyInvalidListEdit(SequenceRoleBoard baseline) {
        var value = new ChangeJournal.Value(ChangeJournal.ValueKind.REFERENCE, "l-a");
        var operations = List.of(
                new ChangeJournal.Operation(ChangeJournal.Kind.LIST_ADD, "l-first", "members",
                        List.of(4), List.of(), List.of(value), List.of()),
                new ChangeJournal.Operation(ChangeJournal.Kind.LIST_REMOVE, "l-first", "members",
                        List.of(1), List.of(value), List.of(), List.of()),
                new ChangeJournal.Operation(ChangeJournal.Kind.LIST_SET, "l-first", "members",
                        List.of(0, 2), List.of(value, value), List.of(value, value), List.of()));
        for (var operation : operations) {
            var invalid = new ChangeJournal(BINDING.modelId(), 1, "board", CHECKPOINT, 0,
                    List.of(new ChangeJournal.Frame(1, List.of(operation))));
            try {
                JournalReplayer.replay(baseline, BINDING, CHECKPOINT, invalid);
                throw new AssertionError("Replay accepted an invalid position or old value");
            } catch (IllegalArgumentException expected) { }
        }
        checkBaseline(baseline);
    }

    private static SequenceRoleBoard checkpoint() {
        var board = SequenceRoleBoard.newInstance();
        var ordered = OrderedRoleTeam.newBuilder().withName("o-first").build();
        var list = ListRoleTeam.newBuilder().withName("l-first").build();
        board.getOrderedTeams().addAll(List.of(ordered, OrderedRoleTeam.newBuilder().withName("o-second").build()));
        board.getListTeams().addAll(List.of(list, ListRoleTeam.newBuilder().withName("l-second").build()));
        board.getOrderedPeople().add(OrderedRolePerson.newBuilder().withName("o-a").build());
        board.getOrderedPeople().add(OrderedRolePerson.newBuilder().withName("o-b").build());
        board.getListPeople().add(ListRolePerson.newBuilder().withName("l-a").build());
        board.getListPeople().add(ListRolePerson.newBuilder().withName("l-b").build());
        ordered.getMembers().addAll(board.getOrderedPeople());
        list.getMembers().add(board.getListPeople().get(0));
        list.getMembers().add(null);
        list.getMembers().add(board.getListPeople().get(1));
        return board;
    }

    private static ChangeJournal record(SequenceRoleBoard board) {
        var ordered = board.getOrderedTeams().get(0);
        var nextOrdered = board.getOrderedTeams().get(1);
        var list = board.getListTeams().get(0);
        var nextList = board.getListTeams().get(1);
        var orderedPerson = board.getOrderedPeople().get(1);
        var listPerson = board.getListPeople().get(1);
        try (var recorder = JournalRecorder.start(board, BINDING, CHECKPOINT, 0)) {
            nextOrdered.getMembers().add(0, orderedPerson);
            nextList.getMembers().add(0, listPerson);
            check(recorder.publishFrame().orElseThrow().sequence() == 1, "Publish indexed transfers");
            orderedPerson.setTeam(ordered);
            listPerson.setTeam(list);
            check(recorder.publishFrame().orElseThrow().sequence() == 2, "Publish concrete scalar assignments");
            ordered.getMembers().move(1, 0);
            list.getMembers().add(0, null);
            checkFinal(board);
            return recorder.finish();
        }
    }

    private static void checkBaseline(SequenceRoleBoard board) {
        var ordered = board.getOrderedTeams().get(0);
        var list = board.getListTeams().get(0);
        var a = board.getOrderedPeople().get(0);
        var b = board.getOrderedPeople().get(1);
        var x = board.getListPeople().get(0);
        var y = board.getListPeople().get(1);
        check(ordered.getMembers().size() == 2 && ordered.getMembers().get(0) == a && ordered.getMembers().get(1) == b
                        && a.getTeam() == ordered && b.getTeam() == ordered, "Ordered snapshot keeps position and inverses");
        check(list.getMembers().size() == 3 && list.getMembers().get(0) == x && list.getMembers().get(1) == null
                        && list.getMembers().get(2) == y && x.getTeam() == list && y.getTeam() == list,
                "Ordinary snapshot keeps the null position and both inverses");
    }

    private static void checkFinal(SequenceRoleBoard board) {
        var ordered = board.getOrderedTeams().get(0);
        var list = board.getListTeams().get(0);
        var a = board.getOrderedPeople().get(0);
        var b = board.getOrderedPeople().get(1);
        var x = board.getListPeople().get(0);
        var y = board.getListPeople().get(1);
        check(ordered.getMembers().get(0) == b && ordered.getMembers().get(1) == a && a.getTeam() == ordered
                        && b.getTeam() == ordered && board.getOrderedTeams().get(1).getMembers().isEmpty(),
                "The ordered positional replay preserves both inverses");
        check(list.getMembers().size() == 4 && list.getMembers().get(0) == null && list.getMembers().get(1) == x
                        && list.getMembers().get(2) == null && list.getMembers().get(3) == y && x.getTeam() == list
                        && y.getTeam() == list && board.getListTeams().get(1).getMembers().isEmpty(),
                "The ordinary positional replay preserves null slots and concrete-setter append position");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
