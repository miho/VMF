package eu.mihosoft.vmf.conformance.inheritedopposites;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class InheritedOppositeConformance {
    private static final List<String> INHERITED_LIVE = List.of("a.parts:REFERENCE_SET", "b.parts:REFERENCE_SET",
            "part.team:PROPERTY", "a.featured:REFERENCE_SET", "part.team:PROPERTY", "b.featured:REFERENCE_SET");
    private static final List<String> INHERITED_HISTORY = List.of("a.parts", "b.parts", "a.featured", "part.team");
    private static final ObjectMapper REFERENCE_SNAPSHOTS = new ObjectMapper().registerModule(VMFJacksonModule
            .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private InheritedOppositeConformance() {}

    public static void main(String[] args) throws IOException {
        GetterRoleReciprocalConformance.verify();
        SequenceRoleReciprocalConformance.verify();
        if (args.length != 0) {
            check(args.length == 2 && (args[0].equals("write") || args[0].equals("read")), "Expected (write|read) <directory>");
            boolean write = args[0].equals("write");
            var directory = Path.of(args[1]);
            var file = directory.resolve((write ? "java" : "csharp") + "-dispatch-board.json");
            if (write) {
                Files.createDirectories(directory);
                Files.writeString(file, REFERENCE_SNAPSHOTS.writeValueAsString(checkSecondaryReciprocal()));
            }
            var checkpoint = REFERENCE_SNAPSHOTS.readValue(file.toFile(), DispatchBoard.class);
            checkSecondarySnapshot(checkpoint);
            SecondaryReciprocalJournalConformance.exchange(directory, write, checkpoint);
            GetterRoleReciprocalConformance.exchange(directory, write);
            SequenceRoleReciprocalConformance.exchange(directory, write);
            System.out.println("Java replayed " + (write ? "its" : "the C#")
                    + " secondary reciprocal V1 journals in JSON, XML, and YAML.");
            return;
        }
        checkPrimaryInheritance();
        checkAbstractEndpoints();
        checkClosedGenericEndpoints();
        checkExistingCompanionContainment();
        var snapshot = REFERENCE_SNAPSHOTS.writeValueAsString(checkSecondaryReciprocal());
        checkSecondarySnapshot(REFERENCE_SNAPSHOTS.readValue(snapshot, DispatchBoard.class));
        System.out.println("Java verified inherited and named generic opposite links, observation, and undo.");
    }

    private static void checkPrimaryInheritance() {
        var root = RelationshipSite.newInstance();
        PrimaryTeam a = PrimaryTeamSpecialization.newInstance();
        PrimaryTeam b = PrimaryTeamSpecialization.newInstance();
        PrimaryPart part = PrimaryPartSpecialization.newInstance();
        root.getPrimaryTeams().addAll(List.of(a, b));
        a.getParts().add(part);
        a.getFeatured().add(part);
        var trace = new Trace(root, Map.of(a, "a", b, "b", part, "part"));

        part.setOwner(b);
        part.setTeam(b);
        check(a.getParts().isEmpty() && a.getFeatured().isEmpty() && b.getParts().get(0) == part
                && b.getFeatured().get(0) == part && part.getOwner() == b && part.getTeam() == b, "primary transfer");
        check(part.asReadOnly().getTeam() == b.asReadOnly(), "primary read-only alias");
        trace.verifyAndUndo(INHERITED_LIVE, INHERITED_HISTORY);
        check(a.getParts().get(0) == part && a.getFeatured().get(0) == part && b.getParts().isEmpty()
                && b.getFeatured().isEmpty() && part.getOwner() == a && part.getTeam() == a, "primary undo");
    }

    private static void checkAbstractEndpoints() {
        var root = RelationshipSite.newInstance();
        AbstractTeam a = ConcreteTeam.newInstance();
        AbstractTeam b = ConcreteTeam.newInstance();
        AbstractPart part = ConcretePart.newInstance();
        root.getAbstractTeams().addAll(List.of(a, b));
        a.getParts().add(part);
        a.getFeatured().add(part);
        var trace = new Trace(root, Map.of(a, "a", b, "b", part, "part"));

        part.setOwner(b);
        part.setTeam(b);
        check(a.getParts().isEmpty() && a.getFeatured().isEmpty() && b.getParts().get(0) == part
                && b.getFeatured().get(0) == part && part.getOwner() == b && part.getTeam() == b, "abstract transfer");
        check(part.asReadOnly().getTeam() == b.asReadOnly(), "abstract read-only alias");
        trace.verifyAndUndo(INHERITED_LIVE, INHERITED_HISTORY);
        check(a.getParts().get(0) == part && a.getFeatured().get(0) == part && b.getParts().isEmpty()
                && b.getFeatured().isEmpty() && part.getOwner() == a && part.getTeam() == a, "abstract undo");
    }

    private static void checkClosedGenericEndpoints() throws IOException {
        var root = RelationshipSite.newInstance();
        var a = Owner.newInstance();
        var b = Owner.newInstance();
        Owned target = SpecializedOwned.newInstance();
        OwnerOf<Owned> owningSlot = a;
        owningSlot.setPrimary(target);
        owningSlot.setHighlighted(target);
        root.getOwners().addAll(List.of(a, b));
        var t1 = GenericTeam.newInstance();
        var t2 = GenericTeam.newInstance();
        var member = SpecializedMember.newInstance();
        root.getGenericTeams().addAll(List.of(t1, t2));
        root.getPeople().add(member);
        TeamOf<GenericMember> membership = t1;
        membership.getMembers().add(member);

        var trace = new Trace(root, Map.of(a, "a", b, "b", target, "target", t1, "t1", t2, "t2", member, "member"));
        target.setOwner(b);
        target.setSelector(b);
        member.setTeam(t2);
        check(a.getPrimary() == null && b.getPrimary() == target && target.getOwner() == b
                && a.getHighlighted() == null && b.getHighlighted() == target && target.getSelector() == b,
                "closed generic scalar opposite links");
        check(t1.getMembers().isEmpty() && t2.getMembers().contains(member) && member.getTeam() == t2,
                "closed generic identity-set opposite link");
        check(target.asReadOnly().getOwner() == b.asReadOnly() && b.asReadOnly().getPrimary() == target.asReadOnly(),
                "closed generic read-only aliases");
        check(b.vmf().reflect().propertyByName("primary").orElseThrow().getType().getDescriptor().toString()
                .endsWith(".Owned"), "concrete containment descriptor");
        check(t2.vmf().reflect().propertyByName("members").orElseThrow().getType().getDescriptor().toString()
                .endsWith(".GenericMember>"), "concrete inverse descriptor");

        trace.verifyAndUndo(List.of("a.primary:PROPERTY", "b.primary:PROPERTY", "a.highlighted:PROPERTY",
                "target.selector:PROPERTY", "b.highlighted:PROPERTY", "member.team:PROPERTY", "t1.members:REFERENCE_SET",
                "member.team:PROPERTY", "t2.members:REFERENCE_SET"),
                List.of("a.primary", "b.primary", "target.selector", "t1.members", "member.team"));
        check(a.getPrimary() == target && target.getOwner() == a && b.getPrimary() == null
                && a.getHighlighted() == target && target.getSelector() == a && b.getHighlighted() == null
                && member.getTeam() == t1 && t1.getMembers().contains(member) && t2.getMembers().isEmpty(), "generic undo");

        var io = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(true));
        String snapshot = io.writeValueAsString(root);
        var firstOwner = io.readTree(snapshot).get("owners").get(0);
        check(firstOwner.has("owned_item") && !firstOwner.has("primary"), "redeclared primary slot uses the effective alias");
        var restored = io.readValue(snapshot, RelationshipSite.class).getOwners().get(0);
        check(restored.getPrimary() instanceof SpecializedOwned && restored.getPrimary() == restored.getHighlighted()
                && restored.getPrimary().getOwner() == restored && restored.getHighlighted().getSelector() == restored,
                "redeclared generic slots restore one owned identity and both inverses");
    }

    private static void checkExistingCompanionContainment() {
        CompanionHolder first = ConcreteHolder.newInstance();
        CompanionHolder second = ConcreteHolder.newInstance();
        var listed = CompanionChild.newInstance();
        var direct = CompanionChild.newInstance();
        listed.setHolder(first);
        direct.setDirectHolder(first);
        check(first.getParts().get(0) == listed && first.getChild() == direct, "companion attachment");

        listed.setHolder(second);
        direct.setDirectHolder(second);
        check(first.getParts().isEmpty() && first.getChild() == null && second.getParts().get(0) == listed
                && second.getChild() == direct && listed.getHolder() == second && direct.getDirectHolder() == second,
                "companion transfer");
    }


    private static DispatchBoard checkSecondaryReciprocal() {
        var a = SpecializedDispatchTeam.newBuilder().withName("north").build();
        DispatchTeam b = SpecializedDispatchTeam.newBuilder().withName("south").build();
        var first = SpecializedDispatchMember.newBuilder().withName("first").build();
        DispatchMember second = SpecializedDispatchMember.newBuilder().withName("second").build();
        var board = DispatchBoard.newBuilder().withTeams(a, b).withPeople(first, second).build();

        DispatchTeam secondary = a;
        DispatchHeader primary = a;
        var view = secondary.asReadOnly();
        check(primary == (Object) secondary && view == a.asReadOnly() && secondary.getMembers() == a.getMembers(),
                "Secondary reciprocal projections share one owner, view and set");

        check(secondary.getMembers().add(first) && first.getTeam() == secondary && !a.getMembers().add(first),
                "The collection sets the opposite once by identity");
        first.setTeam(b);
        check(view.getMembers().isEmpty() && b.getMembers().contains(first) && first.asReadOnly().getTeam() == b.asReadOnly(),
                "The inverse moves membership and updates both read-only projections");
        second.setTeam(secondary);

        secondary.setLead(first);
        check(first.getLeadOf() == secondary && view.getLead() == first.asReadOnly(), "Set both singular ends through secondary roles");
        first.setLeadOf(b);
        check(secondary.getLead() == null && b.getLead() == first, "The singular inverse displaces its former owner");
        b.setLead(second);
        check(first.getLeadOf() == null && second.getLeadOf() == b && second.getTeam() == secondary,
                "Replacing a singular opposite does not touch independent membership");
        secondary.setLead(first);

        var copy = board.clone();
        checkSecondarySnapshot(copy);
        check(copy.getTeams().get(0) != secondary && copy.getPeople().get(0) != first, "Copy uses private model identities");
        return board;
    }

    private static void checkSecondarySnapshot(DispatchBoard board) {
        var north = board.getTeams().get(0);
        var south = board.getTeams().get(1);
        var first = board.getPeople().get(0);
        var second = board.getPeople().get(1);
        check(north instanceof SpecializedDispatchTeam && south instanceof SpecializedDispatchTeam
                        && first instanceof SpecializedDispatchMember && second instanceof SpecializedDispatchMember,
                "The secondary-declared links keep the concrete endpoint types");
        check(north.getMembers().size() == 1 && north.getMembers().contains(second) && second.getTeam() == north
                        && south.getMembers().size() == 1 && south.getMembers().contains(first) && first.getTeam() == south,
                "Both collection reciprocals resolve to the existing graph identities");
        check(north.getLead() == first && first.getLeadOf() == north && south.getLead() == second && second.getLeadOf() == south,
                "Both singular reciprocals resolve independently");
    }

    private static final class Trace {
        private final eu.mihosoft.vmf.runtime.core.Changes changes;
        private final vjavax.observer.Subscription listening;
        private final IdentityHashMap<VObject, String> names = new IdentityHashMap<>();
        private final List<String> live = new ArrayList<>();

        private Trace(VObject root, Map<VObject, String> owners) {
            names.putAll(owners);
            changes = root.vmf().changes();
            listening = changes.addRecursiveListener(change -> live.add(key(change) + ":" + change.getType()));
            changes.start();
            changes.startTransaction();
        }

        private String key(Change change) { return names.get(change.object()) + "." + change.propertyName(); }

        private void verifyAndUndo(List<String> expectedLive, List<String> expectedHistory) {
            try {
                changes.publishTransaction();
                var history = changes.all().stream().map(this::key).collect(Collectors.toList());
                check(live.equals(expectedLive), "live order " + live);
                check(history.equals(expectedHistory), "history " + history);
            } finally {
                listening.unsubscribe();
                changes.stop();
            }
            changes.transactions().get(0).undo();
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
