package eu.mihosoft.vmf.conformance.multiplebases;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.ModelRegistry;
import eu.mihosoft.vmf.runtime.core.ModelValidator;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ConcreteBasesConformance {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(VMFJacksonModule
            .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(false));

    private ConcreteBasesConformance() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2 || !(arguments[0].equals("write") || arguments[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>.");
        }
        checkInheritance();
        checkScalarHistoryThroughSecondaryRole();
        checkCollectionHistoryThroughSecondaryRole();
        checkBuilderCopiesAcrossRoles();

        var joined = JoinedRecord.newBuilder().withId("joined").withWeight(14).build();
        String wire = MAPPER.writeValueAsString(RecordEnvelope.newBuilder().withRecord(joined).build());
        var directory = Path.of(arguments[1]);
        if (arguments[0].equals("write")) {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("java-record.json"), wire);
        } else {
            String csharp = Files.readString(directory.resolve("csharp-record.json"));
            check(MAPPER.readTree(wire).equals(MAPPER.readTree(csharp)), "The secondary-typed slot uses identical snapshot fields");
            var restored = MAPPER.readValue(csharp, RecordEnvelope.class).getRecord();
            check(restored instanceof JoinedRecord && ((JoinedRecord) restored).getLevel() == 5
                    && restored.getWeight() == 14 && restored.getTitle().equals("shared"),
                    "Loading through the secondary contract preserves the concrete instance and both bases");
        }
        checkSecondaryOwnership(arguments[0].equals("write"), directory);
        checkSecondaryOwnershipJournal(arguments[0].equals("write"), directory);
        System.out.println("Java verified concrete multiple inheritance, default storage, reflection and snapshot exchange.");
    }


    private static final ObjectMapper REFERENCES = new ObjectMapper().registerModule(VMFJacksonModule
            .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private static void checkBuilderCopiesAcrossRoles() {
        var header = CollectionHeader.newBuilder().withSequence(12).build();
        var flat = CollectionHeader.newBuilder().applyFrom(header);
        header.setSequence(99);
        var targetHeader = CollectionHeader.newInstance();
        flat.applyTo(targetHeader);
        check(targetHeader.getSequence() == 12, "A scalar-only builder copies its staged value");

        var source = JoinedCollections.newBuilder().withSequence(7).withLabels("staged", null).build();
        source.getLimits().put("cap", 4L);
        source.getLimits().put("optional", null);
        source.getLevels().addAll(List.of(-0.0, 2.0));
        var builder = JoinedCollections.newBuilder().applyFrom(source);
        source.setSequence(99);
        source.getLabels().set(0, "changed");
        source.getLimits().put("cap", 99L);
        source.getLevels().remove(2.0);

        var target = JoinedCollections.newBuilder().withSequence(1).withLabels("existing").build();
        target.getLimits().put("existing", 3L);
        target.getLevels().add(0.0);
        var labels = target.getLabels();
        var limits = target.getLimits();
        var levels = target.getLevels();
        builder.applyTo(target);
        check(target.getSequence() == 7 && labels.equals(java.util.Arrays.asList("existing", "staged", null))
                        && limits.size() == 3 && Long.valueOf(4).equals(limits.get("cap")) && limits.containsKey("optional")
                        && limits.get("optional") == null && levels.equals(Set.of(-0.0, 0.0, 2.0)),
                "Composed builders snapshot source fields and append to live target collections");
        builder.appendCollections(false).applyTo(target);
        check(labels == target.getLabels() && limits == target.getLimits() && levels == target.getLevels()
                        && labels.equals(java.util.Arrays.asList("staged", null)) && limits.size() == 2
                        && levels.equals(Set.of(-0.0, 2.0)),
                "Replacing composed fields preserves the receiving collections");

        CollectionRole role = source;
        var mixed = JoinedCollections.newBuilder().applyFrom((CollectionHeader) targetHeader).applyFrom(role).build();
        check(mixed.getSequence() == 12 && mixed.getLabels().equals(java.util.Arrays.asList("changed", null))
                        && Long.valueOf(99).equals(mixed.getLimits().get("cap")),
                "Primary and secondary imports can stage their own fields independently");
    }

    private static void checkScalarHistoryThroughSecondaryRole() {
        var source = SecondaryRecord.newInstance();
        SecondaryRecord target = JoinedRecord.newBuilder().withWeight(99).build();
        var original = source.vmf().changes();
        var other = target.vmf().changes();
        original.start();
        other.start();

        source.setWeight(17);
        var edit = original.all().get(0);
        check(edit.object() == source, "Scalar history retains its original source");
        edit.apply(target);
        var transferred = other.all().get(0);
        check(target.getWeight() == 17 && transferred.object() == target
                        && Integer.valueOf(99).equals(transferred.propertyChange().orElseThrow().oldValue()),
                "Applying to a compatible implementation uses its live scalar setter");

        edit.undo();
        check(source.getWeight() == 11 && target.getWeight() == 17, "Undo stays on the original source");
        transferred.apply(source);
        check(source.getWeight() == 17, "Scalar changes apply in either direction through the modeled role");

        source.setPeer(PrimaryRecord.newInstance());
        try {
            original.all().get(original.all().size() - 1).apply(target);
            throw new AssertionError("Model-valued changes require their original source");
        } catch (IllegalArgumentException expected) {
            check(target.getPeer() == null, "Rejected model-valued change leaves the target unchanged");
        }
        other.stop();
        original.stop();
    }

    private static void checkCollectionHistoryThroughSecondaryRole() {
        var source = CollectionRole.newInstance();
        CollectionRole target = JoinedCollections.newInstance();
        source.getLabels().addAll(List.of("first", "old", "discard"));
        target.getLabels().addAll(List.of("other", "target-old", "target-discard"));
        source.getLimits().put("present", null);
        target.getLimits().put("present", 99L);
        target.getLimits().put("untouched", 42L);
        source.getLevels().add(-0.0);
        target.getLevels().addAll(List.of(-0.0, 0.0));
        var original = source.vmf().changes();
        var receiver = target.vmf().changes();
        original.start();
        receiver.start();

        source.getLabels().set(1, "changed");
        source.getLabels().remove(2);
        source.getLabels().add(1, "inserted");
        source.getLimits().put("present", 7L);
        source.getLevels().remove(-0.0);
        source.getLevels().add(7.0);
        var edits = List.copyOf(original.all());
        check(edits.size() == 6, "The original history tracks each list, map and set edit");
        edits.forEach(edit -> edit.apply(target));

        var applied = List.copyOf(receiver.all());
        check(applied.size() == 6 && applied.stream().allMatch(edit -> edit.object() == target)
                        && target.getLabels().equals(List.of("other", "inserted", "changed"))
                        && applied.get(0).listChange().orElseThrow().removed().elements().equals(List.of("target-old"))
                        && applied.get(1).listChange().orElseThrow().removed().elements().equals(List.of("target-discard"))
                        && Long.valueOf(99).equals(applied.get(3).mapChange().orElseThrow().removed().entries().get("present")),
                "Apply uses the receiver's positions, previous values and native history");
        check(Long.valueOf(7).equals(target.getLimits().get("present"))
                        && Long.valueOf(42).equals(target.getLimits().get("untouched"))
                        && target.getLevels().equals(Set.of(0.0, 7.0)),
                "Collection apply preserves untouched entries and Java scalar equality");

        for (int index = edits.size() - 1; index >= 0; index--) edits.get(index).undo();
        check(source.getLabels().equals(List.of("first", "old", "discard"))
                        && source.getLimits().containsKey("present") && source.getLimits().get("present") == null
                        && source.getLevels().equals(Set.of(-0.0))
                        && target.getLabels().equals(List.of("other", "inserted", "changed")),
                "Undo uses the old values and collection of the original owner");
        applied.forEach(edit -> edit.apply(source));
        check(source.getLabels().equals(List.of("first", "inserted", "changed"))
                        && Long.valueOf(7).equals(source.getLimits().get("present"))
                        && source.getLevels().equals(Set.of(7.0)),
                "The composed role's changes also apply to its original declaration");

        target.getLimits().put("occupied", 2L);
        source.getLimits().put("new", 8L);
        int historySize = receiver.all().size();
        try {
            original.all().get(original.all().size() - 1).apply(target);
            throw new AssertionError("Applying to a full map must reject an additional key");
        } catch (IllegalArgumentException expected) {
            check(!target.getLimits().containsKey("new") && receiver.all().size() == historySize,
                    "An invalid edit leaves the receiving collection and history unchanged");
        }

        original.stop();
        receiver.stop();
    }

    private static void checkSecondaryOwnership(boolean write, Path directory) throws Exception {
        var source = JoinedOwner.newBuilder().withId("source").withTitle("header").build();
        OwnerHeader primary = source;
        RecordOwner ownerRole = source;
        var child = OwnedRecord.newBuilder().withName("same").build();
        var peer = OwnedRecord.newBuilder().withName("same").build();

        var view = ownerRole.asReadOnly();
        check(primary == (Object) ownerRole && view == source.asReadOnly(), "Both roles retain the same owner and view");
        check(child != peer && child.equals(peer)
                && ownerRole.getRecords().add(child) && !source.getRecords().add(child)
                && source.getRecords().add(peer) && source.getRecords().size() == 2,
                "Both writable projections share one set with identity membership");
        check(child.getOwner() == ownerRole && child.asReadOnly().getOwner() == view
                && view.getRecords().contains(child.asReadOnly()), "The inverse and live read-only set share the projection");
        var excess = OwnedRecord.newBuilder().withName("same").build();
        check(!view.getRecords().contains(excess.asReadOnly()) && ownerRole.getRecords().add(excess)
                && !ModelValidator.builder().build().validate(source).isValid(), "Membership is by identity and bounds are checked after mutation");
        check(ownerRole.getRecords().remove(excess) && excess.getOwner() == null, "Removing an excess member clears its inverse");

        var target = RecordOwner.newInstance();
        child.setOwner(target);
        check(!view.getRecords().contains(child.asReadOnly()) && target.getRecords().contains(child)
                && child.getOwner() == target, "The writable inverse reparents from the secondary set");
        ownerRole.getRecords().add(child);
        check(target.getRecords().isEmpty() && child.getOwner() == source, "Collection writes reparent back into the derived owner");
        child.setOwner(null);
        check(child.getOwner() == null && !source.getRecords().contains(child), "Inverse clearing detaches the member");
        ownerRole.getRecords().add(child);

        var graph = OwnershipEnvelope.newBuilder().withOwner(source).withFeatured(child).build();
        var copied = graph.clone();
        checkOwnedSnapshot(copied);
        check(copied.getOwner() != source && copied.getFeatured() != child, "Deep copy uses newly owned identities");

        var file = directory.resolve((write ? "java" : "csharp") + "-secondary-ownership.json");
        if (write) Files.writeString(file, REFERENCES.writeValueAsString(graph));
        var restored = REFERENCES.readValue(Files.readString(file), OwnershipEnvelope.class);
        checkOwnedSnapshot(restored);
    }

    private static void checkSecondaryOwnershipJournal(boolean write, Path directory) throws Exception {
        var binding = new JournalBinding() {
            @Override public String modelId() { return "conformance.secondary-ownership"; }
            @Override public int modelVersion() { return 1; }
            @Override public String id(VObject value) {
                if (value instanceof JoinedOwner) return ((JoinedOwner) value).getId();
                if (value instanceof OwnedRecord) return ((OwnedRecord) value).getName();
                return "envelope";
            }
            @Override public String type(VObject value) { return value.vmf().reflect().type().getName(); }
            @Override public VObject create(String type, String id) {
                if (!type.equals(OwnedRecord.class.getName())) throw new IllegalArgumentException("Unexpected type " + type);
                return OwnedRecord.newBuilder().withName(id).build();
            }
        };
        String producer = write ? "java" : "csharp";
        var checkpointFile = directory.resolve(producer + "-secondary-journal-checkpoint.json");
        ChangeJournal journal = null;
        if (write) {
            var original = OwnedRecord.newBuilder().withName("original").build();
            var owner = JoinedOwner.newBuilder().withId("joined").build();
            ((RecordOwner) owner).getRecords().add(original);
            var checkpoint = OwnershipEnvelope.newBuilder().withOwner(owner).withFeatured(original).build();
            Files.writeString(checkpointFile, REFERENCES.writeValueAsString(checkpoint));
            journal = JournalRecorder.recordFrame(checkpoint.clone(), binding, "ownership-base", 0, working -> {
                var records = working.getOwner().getRecords();
                var former = working.getFeatured();
                var replacement = OwnedRecord.newBuilder().withName("replacement").build();
                check(records.add(replacement), "A companion owning set accepts the new child");
                working.setFeatured(replacement);
                check(records.remove(former), "The companion owning set removes its former child");
            });
        }
        String snapshot = Files.readString(checkpointFile);
        for (var format : VMFDocumentFormat.values()) {
            String extension = format.name().toLowerCase(Locale.ROOT);
            var journalFile = directory.resolve(producer + "-secondary-ownership-journal." + extension);
            if (write) Files.writeString(journalFile, VMFJournalCodec.write(journal, format));
            var decoded = VMFJournalCodec.read(Files.readString(journalFile), format);
            check(decoded.wireVersion() == 1 && decoded.transactions().size() == 1, "Secondary ownership uses one V1 frame");
            var edits = decoded.transactions().get(0).operations();
            check(edits.size() == 3 && edits.get(0).kind() == ChangeJournal.Kind.REF_SET_EDIT
                            && edits.get(1).kind() == ChangeJournal.Kind.SET
                            && edits.get(2).kind() == ChangeJournal.Kind.REF_SET_EDIT,
                    "The journal preserves addition, alias update and removal order");
            var fresh = REFERENCES.readValue(snapshot, OwnershipEnvelope.class);
            var result = JournalReplayer.replay(fresh, binding, "ownership-base", decoded);
            var child = result.getFeatured();
            check(result.getOwner() instanceof JoinedOwner && result.getOwner().getRecords().size() == 1
                            && result.getOwner().getRecords().contains(child) && child.getOwner() == result.getOwner()
                            && child.getName().equals("replacement"),
                    "Replay restores the concrete owner, replacement membership, inverse and alias");
            check(fresh.getOwner().getRecords().size() == 1 && fresh.getOwner().getRecords().contains(fresh.getFeatured())
                            && fresh.getFeatured().getOwner() == fresh.getOwner() && fresh.getFeatured().getName().equals("original"),
                    "Replay leaves the loaded checkpoint independent");
        }
    }

    private static void checkOwnedSnapshot(OwnershipEnvelope graph) {
        check(graph.getOwner() instanceof JoinedOwner, "The secondary nominal slot retains the derived owner");
        var owner = (JoinedOwner) graph.getOwner();
        var featured = graph.getFeatured();
        check(owner.getId().equals("source") && owner.getTitle().equals("header") && owner.getRecords().size() == 2
                && owner.getRecords().contains(featured) && featured.getOwner() == owner
                && owner.getRecords().stream().allMatch(member -> member.getOwner() == owner && member.getName().equals("same")),
                "Snapshot restores both distinct members, the inverse and the featured alias");
    }

    private static void checkInheritance() {
        var value = JoinedRecord.newInstance();
        PrimaryRecord primary = value;
        SecondaryRecord secondary = value;
        check(primary == (Trunk) secondary && primary.getLevel() == 5 && secondary.getWeight() == 11,
                "Distinct base casts access the same instance and each inherited field default");
        var reflection = value.vmf().reflect();
        var title = reflection.propertyByName("title").orElseThrow();
        var weight = reflection.propertyByName("weight").orElseThrow();
        check(reflection.properties().size() == 5 && title.getDefault().equals("shared") && weight.getDefault().equals(11),
                "Effective reflection merges the common ancestor and retains the secondary default");
        weight.set(13);
        secondary.setTitle("changed");
        secondary.setPeer(primary);
        check(primary.getTitle().equals("changed") && value.getWeight() == 13 && secondary.getPeer() == value
                && secondary.asReadOnly().getWeight() == 13, "Reflection and both casts use live slots");
        check(JoinedRecord.type().superTypes().equals(List.of(PrimaryRecord.type(), SecondaryRecord.type())),
                "Static reflection preserves the declared order of both bases");
        var registry = new ModelRegistry(List.of(reflection.allTypes()));
        check(new HashSet<>(registry.typesAssignableTo(SecondaryRecord.class))
                .equals(Set.of(SecondaryRecord.type(), JoinedRecord.type())), "Catalog recognizes the concrete secondary contract");

        var point = CoordinateXY.newBuilder().withX(3).withY(4).build();
        CoordinateX x = point;
        CoordinateY y = point;
        WithXY pair = point;
        x.setX(8);
        y.setY(9);
        check(pair.getX() == 8 && pair.getY() == 9 && point.asReadOnly().getY() == 9
                && point.vmf().reflect().properties().size() == 2, "Concrete coordinate casts share the trait's writable data");
        check(CoordinateXY.type().superTypes().equals(List.of(WithXY.type(), CoordinateX.type(), CoordinateY.type())),
                "A leading getter-only trait can precede both concrete bases");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
