package eu.mihosoft.vmf.conformance.inheritedopposites;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
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
import java.util.List;
import java.util.Locale;

final class GetterRoleReciprocalConformance {
    private static final String CHECKPOINT = "getter-role-0";
    private static final ObjectMapper SNAPSHOTS = snapshots(VMFDocumentFormat.JSON);
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "conformance.getter-reciprocal"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) {
            if (object instanceof GetterRoleTeam) return ((GetterRoleTeam) object).getName();
            if (object instanceof GetterRolePerson) return ((GetterRolePerson) object).getName();
            if (object instanceof GetterRoleBoard) return "board";
            throw new IllegalArgumentException("Unexpected model " + object.vmf().reflect().type().getName());
        }
        @Override public String type(VObject object) { return object.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new AssertionError("No creations."); }
    };

    private GetterRoleReciprocalConformance() { }

    static void verify() throws IOException {
        var first = GetterRoleTeam.newInstance();
        var second = GetterRoleTeam.newInstance();
        var person = GetterRolePerson.newInstance();
        GetterRoleMember member = person;
        ReadOnlyGetterRoleMember view = person.asReadOnly();
        var scalarTrace = new ArrayList<String>();
        var projectedTrace = new ArrayList<String>();
        var teamTrace = new ArrayList<String>();
        var projectedTeamTrace = new ArrayList<String>();
        var scalarSubscription = person.vmf().changes().addLocalListener(change -> scalarTrace.add(change.propertyName()));
        var projectedSubscription = view.vmf().changes().addLocalListener(change -> projectedTrace.add(change.propertyName()));
        var teamSubscription = first.vmf().changes().addLocalListener(change -> teamTrace.add(change.propertyName()));
        var projectedTeamSubscription = first.asReadOnly().vmf().changes()
                .addLocalListener(change -> projectedTeamTrace.add(change.propertyName()));
        try {
            check(first.getMembers().add(member) && member.getTeam() == first && view.getTeam() == first.asReadOnly(),
                    "A membership edit updates the abstract getter and its live projection");
            check(first.getMembers().contains(view) && first.asReadOnly().getMembers().contains(member)
                            && first.asReadOnly().getMembers().iterator().next() == view,
                    "Mutable and projected sets query the same identity");
            check(second.getMembers().add(member) && !first.getMembers().contains(member)
                            && second.getMembers().iterator().next() == person && view.getTeam() == second.asReadOnly(),
                    "Reassignment through a set removes the old opposite");
            person.setTeam(first);
            check(first.getMembers().contains(member) && second.getMembers().isEmpty()
                            && first.getMembers().remove(view) && member.getTeam() == null && view.getTeam() == null,
                    "The concrete setter and removal by projected alias update both ends");
        } finally {
            scalarSubscription.unsubscribe();
            projectedSubscription.unsubscribe();
            teamSubscription.unsubscribe();
            projectedTeamSubscription.unsubscribe();
        }
        check(scalarTrace.equals(List.of("team", "team", "team", "team", "team"))
                        && scalarTrace.equals(projectedTrace), "Mutable and read-only scalar observers see all five changes");
        check(teamTrace.equals(List.of("members", "members", "members", "members"))
                        && teamTrace.equals(projectedTeamTrace), "Mutable and read-only set observers see all four changes");

        var json = (ObjectNode) SNAPSHOTS.readTree(SNAPSHOTS.writeValueAsString(checkpoint()));
        var setOnly = json.deepCopy();
        ((ObjectNode) setOnly.get("people").get(0)).remove("team");
        checkBaseline(SNAPSHOTS.treeToValue(setOnly, GetterRoleBoard.class));
        var scalarOnly = json.deepCopy();
        ((ObjectNode) scalarOnly.get("teams").get(0)).remove("members");
        checkBaseline(SNAPSHOTS.treeToValue(scalarOnly, GetterRoleBoard.class));
    }

    static void exchange(Path directory, boolean write) throws IOException {
        String author = write ? "java" : "csharp";
        ChangeJournal recorded = write ? record(checkpoint()) : null;
        for (var format : VMFDocumentFormat.values()) {
            String extension = format.name().toLowerCase(Locale.ROOT);
            var mapper = snapshots(format);
            var snapshot = directory.resolve(author + "-getter-role-checkpoint." + extension);
            var journal = directory.resolve(author + "-getter-role-journal." + extension);
            if (write) {
                Files.writeString(snapshot, mapper.writeValueAsString(checkpoint()));
                Files.writeString(journal, VMFJournalCodec.write(recorded, format));
            }
            var baseline = mapper.readValue(snapshot.toFile(), GetterRoleBoard.class);
            var changes = VMFJournalCodec.read(Files.readString(journal), format);
            check(changes.wireVersion() == 1 && changes.transactions().size() == 3, "The three published changes use V1");
            var replayed = JournalReplayer.replay(baseline, BINDING, CHECKPOINT, changes);
            checkBaseline(baseline);
            checkDetached(replayed);
            check(replayed.getPeople().get(0) != baseline.getPeople().get(0), "Replay starts from a fresh graph");
        }
    }

    private static ObjectMapper snapshots(VMFDocumentFormat format) {
        ObjectMapper mapper = format == VMFDocumentFormat.XML ? new XmlMapper()
                : format == VMFDocumentFormat.YAML ? new YAMLMapper() : new ObjectMapper();
        return mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(true));
    }

    private static GetterRoleBoard checkpoint() {
        var board = GetterRoleBoard.newInstance();
        var first = GetterRoleTeam.newBuilder().withName("first").build();
        var second = GetterRoleTeam.newBuilder().withName("second").build();
        var person = GetterRolePerson.newBuilder().withName("person").build();
        board.getTeams().addAll(List.of(first, second));
        board.getPeople().add(person);
        person.setTeam(first);
        return board;
    }

    private static ChangeJournal record(GetterRoleBoard board) {
        var first = board.getTeams().get(0);
        var second = board.getTeams().get(1);
        var person = board.getPeople().get(0);
        try (var recorder = JournalRecorder.start(board, BINDING, CHECKPOINT, 0)) {
            check(second.getMembers().add(person), "Collection transfer");
            check(recorder.publishFrame().orElseThrow().sequence() == 1, "Publish collection transfer");
            person.setTeam(first);
            check(recorder.publishFrame().orElseThrow().sequence() == 2, "Publish scalar transfer");
            check(first.getMembers().remove(person.asReadOnly()), "Remove by projected alias");
            checkDetached(board);
            return recorder.finish();
        }
    }

    private static void checkBaseline(GetterRoleBoard board) {
        var first = board.getTeams().get(0);
        var person = board.getPeople().get(0);
        check(person.getTeam() == first && first.getMembers().iterator().next() == person
                        && board.getTeams().get(1).getMembers().isEmpty(), "Snapshot restores both exact opposite identities");
    }

    private static void checkDetached(GetterRoleBoard board) {
        check(board.getPeople().get(0).getTeam() == null && board.getTeams().stream()
                .allMatch(team -> team.getMembers().isEmpty()), "All opposite ends are cleared");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
