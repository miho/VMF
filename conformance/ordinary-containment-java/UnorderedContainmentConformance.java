package eu.mihosoft.vmf.conformance.ordinarycontainment;

import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.ModelValidator;
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

final class UnorderedContainmentConformance {
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "unordered-containment"; }
        @Override public int modelVersion() { return 1; }

        @Override public String id(VObject model) {
            if (model instanceof ReadingRoom) return "room";
            return model instanceof Shelf ? ((Shelf) model).getName() : ((Volume) model).getName();
        }

        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }

        @Override public VObject create(String type, String id) {
            if (!type.equals(Volume.class.getName())) throw new IllegalArgumentException("Unexpected type " + type);
            return volume(id);
        }
    };

    private UnorderedContainmentConformance() { }

    static void verify(boolean write, Path directory) throws IOException {
        var baseline = checkpoint();
        ChangeJournal journal = null;
        if (write) {
            verifyBuilderTransfers();
            var aliasGraph = checkpoint();
            var aliasOwner = aliasGraph.getShelves().get(0);
            var aliased = aliasGraph.getFeatured();
            var alias = aliased.asReadOnly();
            check(aliasOwner.getVolumes().contains(alias) && aliasOwner.getVolumes().remove(alias)
                            && !aliasOwner.getVolumes().contains(alias) && !aliasOwner.getVolumes().remove(alias)
                            && aliased.getShelf() == null && aliasGraph.getFeatured() == aliased,
                    "A held view removes the same owned identity without affecting ordinary aliases");
            var source = baseline.getShelves().get(0);
            var extra = volume("extra");
            check(!source.getVolumes().add(named(source, "A")), "same identity is a no-op");
            check(source.getVolumes().add(extra) && !ModelValidator.builder().build().validate(baseline).isValid(),
                    "multiplicity is checked after mutation");
            source.getVolumes().remove(extra);
            var copied = baseline.clone();
            check(copied.getFeatured() != baseline.getFeatured() && copied.getFeatured() == named(copied.getShelves().get(0), "A"),
                    "deep copy retains the alias into the owning set");
            journal = JournalRecorder.recordFrame(checkpoint(), BINDING, "base", 0, model -> {
                var target = model.getShelves().get(1);
                model.getFeatured().setShelf(target);
                target.getVolumes().add(volume("C"));
                checkMoved(model);
            });
        }

        for (var format : VMFDocumentFormat.values()) {
            String base = (write ? "java-" : "csharp-") + "unordered-";
            String extension = format.name().toLowerCase(Locale.ROOT);
            var snapshot = directory.resolve(base + "checkpoint." + extension);
            var changes = directory.resolve(base + "journal." + extension);
            var mapper = OrdinaryContainmentConformance.mapper(format, true);
            if (write) {
                Files.writeString(snapshot, mapper.writeValueAsString(baseline));
                Files.writeString(changes, VMFJournalCodec.write(journal, format));
            }
            var fresh = mapper.readValue(snapshot.toFile(), ReadingRoom.class);
            var source = fresh.getShelves().get(0);
            check(fresh.getFeatured() == named(source, "A") && fresh.getFeatured().getShelf() == source,
                    "snapshot restores ownership and alias");
            var records = VMFJournalCodec.read(Files.readString(changes), format);
            check(records.wireVersion() == 1 && records.transactions().get(0).operations().stream()
                    .filter(operation -> operation.kind() == ChangeJournal.Kind.REF_SET_EDIT)
                    .allMatch(operation -> operation.indices().isEmpty()), "unindexed reference membership records");
            checkMoved(JournalReplayer.replay(fresh, BINDING, "base", records));
            check(source.getVolumes().size() == 2 && fresh.getShelves().get(1).getVolumes().isEmpty(),
                    "journal replay preserves its checkpoint");
        }
        verifyReentrantTransfers(write, directory);
        System.out.println("Java verified unordered containment and membership transfer and creation replay.");
    }

    private static void verifyReentrantTransfers(boolean write, Path directory) throws IOException {
        for (String scope : List.of("local", "recursive")) {
            var recorded = write ? recordReentrantTransfer(scope.equals("recursive")) : null;
            for (var format : VMFDocumentFormat.values()) {
                var file = directory.resolve((write ? "java-" : "csharp-") + "unordered-reentrant-" + scope + "-journal."
                        + format.name().toLowerCase(Locale.ROOT));
                if (write) Files.writeString(file, VMFJournalCodec.write(recorded, format));
                var journal = VMFJournalCodec.read(Files.readString(file), format);
                check(journal.wireVersion() == 1 && journal.transactions().size() == 1, "one V1 transfer frame");
                var operations = journal.transactions().get(0).operations();
                var ids = List.of("source", "target", "target", "third");
                check(operations.size() == ids.size(), "one membership edit per ownership phase");
                for (int i = 0; i < ids.size(); i++) {
                    var edit = operations.get(i);
                    var member = List.of(new ChangeJournal.Value(ChangeJournal.ValueKind.REFERENCE, "A"));
                    boolean removal = i == 0 || i == 2;
                    check(edit.kind() == ChangeJournal.Kind.REF_SET_EDIT && edit.target().equals(ids.get(i))
                                    && edit.property().equals("volumes") && edit.indices().isEmpty() && edit.created().isEmpty()
                                    && edit.before().equals(removal ? member : List.of())
                                    && edit.after().equals(removal ? List.of() : member),
                            "portable membership preserves causal ownership order");
                }
                var checkpoint = reentrantCheckpoint();
                var replayed = JournalReplayer.replay(checkpoint, BINDING, "base", journal);
                check(replayed.getShelves().get(0).getVolumes().isEmpty() && replayed.getShelves().get(1).getVolumes().isEmpty()
                                && replayed.getFeatured() == named(replayed.getShelves().get(2), "A")
                                && replayed.getFeatured().getShelf() == replayed.getShelves().get(2)
                                && checkpoint.getFeatured().getShelf() == checkpoint.getShelves().get(0),
                        "replay restores the final inverse and alias on an independent graph");
            }
        }
    }

    private static ChangeJournal recordReentrantTransfer(boolean recursive) {
        var working = reentrantCheckpoint();
        var source = working.getShelves().get(0);
        var target = working.getShelves().get(1);
        var third = working.getShelves().get(2);
        var moving = working.getFeatured();
        var trace = new ArrayList<String>();
        var rootChanges = working.vmf().changes();
        var scope = recursive ? rootChanges : target.vmf().changes();
        var reentry = scope.addListener(change -> {
            if (change.object() != target || !change.propertyName().equals("volumes")
                    || change.referenceSetChange().orElseThrow().added().isEmpty()) return;
            check(source.getVolumes().isEmpty() && target.getVolumes().contains(moving) && moving.getShelf() == target,
                    "target observer sees the first attachment installed");
            trace.add("target callback");
            third.getVolumes().add(moving);
        }, recursive);
        var inverse = moving.vmf().changes().addPropertyListener("shelf", change -> {
            var edit = change.propertyChange().orElseThrow();
            trace.add(edit.newValue() == null ? "detach " + ((Shelf) edit.oldValue()).getName()
                    : "attach " + ((Shelf) edit.newValue()).getName());
        });
        var traceObserver = rootChanges.addRecursiveListener(change -> {
            if (!change.propertyName().equals("volumes")) return;
            if (change.object() == source) trace.add("source remove");
            if (change.object() == target && !change.referenceSetChange().orElseThrow().removed().isEmpty()) trace.add("target remove");
            if (change.object() == third) trace.add("third add");
        });
        try (var recorder = JournalRecorder.start(working, BINDING, "base", 0)) {
            target.getVolumes().add(moving);
            check(trace.equals(List.of("source remove", "detach source", "attach target", "target callback",
                            "target remove", "detach target", "attach third", "third add")),
                    "native source, inverse and destination delivery remain synchronous");
            var history = rootChanges.all().stream().map(change -> ((Shelf) change.object()).getName()
                    + (change.referenceSetChange().orElseThrow().added().isEmpty() ? ":remove" : ":add"))
                    .collect(java.util.stream.Collectors.toList());
            // The triggering edit is recorded before the edits its listeners made (portable contract H4).
            check(history.equals(List.of("source:remove", "target:add", "target:remove", "third:add")),
                    "native history records the target's attachment before the callback's move: " + history);
            return recorder.finish();
        } finally {
            reentry.unsubscribe();
            inverse.unsubscribe();
            traceObserver.unsubscribe();
        }
    }

    private static ReadingRoom reentrantCheckpoint() {
        var moving = volume("A");
        return ReadingRoom.newBuilder().withShelves(
                Shelf.newBuilder().withName("source").withVolumes(moving).build(),
                Shelf.newBuilder().withName("target").build(),
                Shelf.newBuilder().withName("third").build()).withFeatured(moving).build();
    }

    private static ReadingRoom checkpoint() {
        var first = volume("A");
        var source = Shelf.newBuilder().withName("source").withVolumes(first, volume("B")).build();
        var target = Shelf.newBuilder().withName("target").build();
        return ReadingRoom.newBuilder().withShelves(source, target).withFeatured(first).build();
    }

    private static void checkMoved(ReadingRoom model) {
        var source = model.getShelves().get(0);
        var target = model.getShelves().get(1);
        check(source.getVolumes().size() == 1 && named(source, "B").getShelf() == source, "retained child and inverse");
        check(target.getVolumes().size() == 2 && named(target, "A") == model.getFeatured()
                && model.getFeatured().getShelf() == target && named(target, "C").getShelf() == target,
                "moved alias and attached creation");
    }

    private static Volume named(Shelf shelf, String name) {
        return shelf.getVolumes().stream().filter(item -> name.equals(item.getName())).findFirst().orElseThrow();
    }

    private static void verifyBuilderTransfers() {
        var source = Shelf.newBuilder().withName("source").build();
        var movedA = volume("a");
        var movedB = volume("b");
        source.getVolumes().addAll(List.of(movedA, movedB));
        var staged = Shelf.newBuilder().applyFrom(source);
        check(source.getVolumes().size() == 2 && movedA.getShelf() == source, "Builder staging is nondestructive");
        var imported = staged.build();
        check(movedA.getShelf() == imported && movedB.getShelf() == imported && source.getVolumes().isEmpty(),
                "Builder construction transfers the staged members");

        var target = Shelf.newBuilder().withName("target").build();
        var retained = volume("retained");
        var obsolete = volume("obsolete");
        target.getVolumes().addAll(List.of(retained, obsolete));
        var membership = target.getVolumes();
        var changes = new ArrayList<String>();
        var observer = target.vmf().changes().addPropertyListener("volumes", change -> {
            var edit = change.referenceSetChange().orElseThrow();
            changes.add(edit.indices() + ": +" + edit.added().size() + " -" + edit.removed().size());
        });
        try {
            Shelf.newBuilder().withName("target").appendCollections(false)
                    .withVolumes(retained, movedA, movedB).applyTo(target);
        } finally {
            observer.unsubscribe();
        }
        check(membership == target.getVolumes() && membership.size() == 3 && movedA.getShelf() == target
                        && movedB.getShelf() == target && retained.getShelf() == target && obsolete.getShelf() == null
                        && imported.getVolumes().isEmpty(),
                "Builder replacement preserves the live set, retains existing members, and repairs the inverse");
        check(changes.equals(List.of("[]: +0 -1", "[]: +2 -0")), "Builder replacement emits unindexed membership edits");
        check(target.vmf().content().validate().getIssues().stream().anyMatch(issue ->
                        "vmf.multiplicity".equals(issue.getCode()) && "/volumes".equals(issue.getPath())),
                "The declared upper bound remains a validation constraint during builder transfer");
    }

    private static Volume volume(String name) { return Volume.newBuilder().withName(name).build(); }

    private static void check(boolean valid, String detail) {
        if (!valid) throw new AssertionError(detail);
    }
}
