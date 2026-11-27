package eu.mihosoft.vmf.conformance.ordinarycontainment;

import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
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
import java.util.List;
import java.util.Locale;

final class OwningPredicateConformance {
    private static final String CHECKPOINT = "owning-predicate-0";
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "ordinary-owning-predicate"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject model) {
            if (model instanceof LegacyLayout) return "layout";
            return model instanceof LegacyContainer ? ((LegacyContainer) model).getName() : ((LegacyChild) model).getName();
        }
        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new AssertionError("No creations."); }
    };

    private OwningPredicateConformance() {}

    static void verify(boolean write, Path directory) throws IOException {
        ChangeJournal recorded = null;
        if (write) {
            verifyNative();
            recorded = record();
        }
        String author = write ? "java" : "csharp";
        for (var format : VMFDocumentFormat.values()) {
            var file = directory.resolve(author + "-owning-remove-where." + format.name().toLowerCase(Locale.ROOT));
            if (write) Files.writeString(file, VMFJournalCodec.write(recorded, format));
            var journal = VMFJournalCodec.read(Files.readString(file), format);
            check(journal.wireVersion() == 1 && journal.transactions().size() == 1, "One V1 temporary-alias frame");
            var edits = journal.transactions().get(0).operations();
            check(edits.size() == 2 && edits.stream().allMatch(edit -> edit.target().equals("owner"))
                            && edits.get(0).kind() == ChangeJournal.Kind.LIST_SET && edits.get(0).indices().equals(List.of(0))
                            && edits.get(1).kind() == ChangeJournal.Kind.LIST_REMOVE && edits.get(1).indices().equals(List.of(0, 3)),
                    "The removal retains the original selected indices after alias creation");
            var original = fixture();
            checkFinal(JournalReplayer.replay(original, BINDING, CHECKPOINT, journal));
            var baseline = original.getContainers().get(0).getChildren();
            check(baseline.size() == 4 && baseline.get(0).getName().equals("original")
                            && baseline.get(2) == original.getFeatured(),
                    "Replay leaves the checkpoint's independent list unchanged");
        }
    }

    private static void verifyNative() {
        var graph = fixture();
        var owner = graph.getContainers().get(0);
        var alias = graph.getFeatured();
        var children = owner.getChildren();
        var cut = children.get(3);
        children.set(0, alias);
        var delivered = new ArrayList<String>();
        alias.vmf().changes().addPropertyListener("owner", event -> delivered.add("alias"));
        cut.vmf().changes().addPropertyListener("owner", event -> delivered.add(cut.getOwner() == null ? "cut:detach" : "cut:attach"));
        owner.asReadOnly().getChildren().addChangeListener(edit -> delivered.add("view:"
                + slots(edit.removed().indices(), edit.added().indices())));
        children.addChangeListener(edit -> delivered.add("mutable:"
                + slots(edit.removed().indices(), edit.added().indices())));
        var history = owner.vmf().changes();
        history.start();
        removeEnds(owner);
        history.stop();
        var removal = List.of("cut:detach", "view:[0, 3]/[]", "mutable:[0, 3]/[]");
        checkFinal(graph);
        check(cut.getOwner() == null && delivered.equals(removal) && history.all().size() == 1,
                "The sparse native edit only detaches the child with no remaining slot");
        var edit = history.all().get(0);
        delivered.clear();
        check(edit.isUndoable(), "The sparse edit is undoable");
        edit.undo();
        check(children.size() == 4 && children.get(0) == alias && children.get(1) == null && children.get(2) == alias
                        && children.get(3) == cut && cut.getOwner() == owner
                        && delivered.equals(List.of("cut:attach", "view:[]/[0, 3]", "mutable:[]/[0, 3]")),
                "Undo restores both original slots together without detaching the retained alias");
        delivered.clear();
        edit.apply(owner);
        check(delivered.equals(removal), "Redo repeats the single modeled sparse removal");
        checkFinal(graph);
    }

    private static ChangeJournal record() {
        var graph = fixture();
        var owner = graph.getContainers().get(0);
        var events = new ArrayList<String>();
        owner.getChildren().addChangeListener(edit -> events.add(slots(edit.removed().indices(), edit.added().indices())));
        try (var recorder = JournalRecorder.start(graph, BINDING, CHECKPOINT, 0)) {
            try {
                owner.getChildren().removeIf(value -> {
                    if (value == null) throw new IllegalStateException("predicate failed");
                    return true;
                });
                throw new AssertionError("Expected a predicate error.");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().equals("predicate failed"), "Original predicate error is preserved");
            }
            check(!owner.getChildren().removeIf(value -> false) && events.isEmpty()
                            && graph.vmf().changes().all().isEmpty()
                            && owner.getChildren().get(0).getName().equals("original")
                            && owner.getChildren().get(3).getName().equals("cut"),
                    "A failing or non-matching predicate commits no modeled edits");
            owner.getChildren().set(0, graph.getFeatured());
            removeEnds(owner);
            checkFinal(graph);
            return recorder.finish();
        }
    }

    private static void removeEnds(LegacyContainer owner) {
        int[] visits = {0};
        check(owner.getChildren().removeIf(value -> {
            int index = visits[0]++;
            return index == 0 || index == 3;
        }) && visits[0] == 4, "The predicate visits each original slot");
    }

    private static LegacyLayout fixture() {
        var alias = child("alias");
        var owner = LegacyContainer.newBuilder().withName("owner").build();
        owner.getChildren().addAll(Arrays.asList(child("original"), null, alias, child("cut")));
        return LegacyLayout.newBuilder().withFeatured(alias).withContainers(owner).build();
    }

    private static LegacyChild child(String name) {
        return LegacyChild.newBuilder().withName(name).build();
    }

    private static String slots(int[] removed, int[] added) {
        return Arrays.toString(removed) + "/" + Arrays.toString(added);
    }

    private static void checkFinal(LegacyLayout layout) {
        var owner = layout.getContainers().get(0);
        var children = owner.getChildren();
        check(children.size() == 2 && children.get(0) == null && children.get(1) == layout.getFeatured()
                        && layout.getFeatured().getOwner() == owner,
                "The selected removal leaves the unselected null and live owned alias");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
