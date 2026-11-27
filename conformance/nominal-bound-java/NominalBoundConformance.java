package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import eu.mihosoft.vmf.conformance.nominalbounds.ComposedRelations;
import eu.mihosoft.vmf.conformance.nominalbounds.DetailedLabel;
import eu.mihosoft.vmf.conformance.nominalbounds.DetailedRelations;
import eu.mihosoft.vmf.conformance.nominalbounds.FrozenLabel;
import eu.mihosoft.vmf.conformance.nominalbounds.LabelNode;
import eu.mihosoft.vmf.conformance.nominalbounds.Leaf;
import eu.mihosoft.vmf.conformance.nominalbounds.Library;
import eu.mihosoft.vmf.conformance.nominalbounds.Other;
import eu.mihosoft.vmf.conformance.nominalbounds.ReadOnlyDetailedLabel;
import eu.mihosoft.vmf.conformance.nominalbounds.ReadOnlyLabelNode;
import eu.mihosoft.vmf.conformance.nominalbounds.ReadOnlyLeaf;
import eu.mihosoft.vmf.conformance.nominalbounds.ReadOnlyRelated;
import eu.mihosoft.vmf.conformance.nominalbounds.ReadOnlyShelf;
import eu.mihosoft.vmf.conformance.nominalbounds.ReadOnlyTraitRelations;
import eu.mihosoft.vmf.conformance.nominalbounds.Related;
import eu.mihosoft.vmf.conformance.nominalbounds.Relations;
import eu.mihosoft.vmf.conformance.nominalbounds.RankedSelection;
import eu.mihosoft.vmf.conformance.nominalbounds.RankedWorkBook;
import eu.mihosoft.vmf.conformance.nominalbounds.Review;
import eu.mihosoft.vmf.conformance.nominalbounds.Shelf;
import eu.mihosoft.vmf.conformance.nominalbounds.TaggedLeaf;
import eu.mihosoft.vmf.conformance.nominalbounds.TraitRelations;
import eu.mihosoft.vmf.conformance.nominalbounds.WorkItem;
import eu.mihosoft.vmf.conformance.nominalbounds.WorkReview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class NominalBoundConformance {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "conformance.nominal-bounds"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject value) {
            if (value instanceof LabelNode) return ((LabelNode)value).getId();
            if (value instanceof FrozenLabel) return ((FrozenLabel)value).getId();
            if (value instanceof Relations) return "relations";
            if (value instanceof Review) return "review";
            if (value instanceof Library) return "library";
            throw new IllegalArgumentException("Unknown model");
        }
        @Override public String type(VObject value) { return value.getClass().getName(); }
        @Override public String propertyKey(VObject value, String key) { return key; }
        @Override public String propertyName(VObject value, String key) { return key; }
        @Override public VObject create(String type, String id) { throw new IllegalArgumentException("Unused factory"); }
    };

    private NominalBoundConformance() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        Path directory = Path.of(args[1]);
        boolean write = args[0].equals("write");
        String author = write ? "java" : "csharp";
        var checkpointFile = directory.resolve(author + "-checkpoint.json");
        var journalFile = directory.resolve(author + "-journal.json");
        if (write) {
            Files.createDirectories(directory);
            var first = Leaf.newBuilder().withId("n1").withText("first").build();
            var second = TaggedLeaf.newBuilder().withId("n2").withText("second").withTag("subtype").build();
            var other = Other.newBuilder().withId("n3").withText("other").build();
            var related = Relations.newBuilder().withBase(other).withSelected(first)
                    .withHistory(Arrays.asList(first, null, second)).build();
            var stable = FrozenLabel.newBuilder().withId("tag1").withText("same").build();
            var twin = FrozenLabel.newBuilder().withId("tag2").withText("same").build();
            var review = Review.newBuilder().withSelected(stable).withHistory(Arrays.asList(stable, null, twin)).withPinned(stable, twin).build();
            var library = Library.newBuilder().withNodes(first,second,other).withRelations(related).withReview(review).build();
            Related<LabelNode,Leaf> mutable = related;
            ReadOnlyRelated<ReadOnlyLabelNode,ReadOnlyLeaf> exact = related.asReadOnly();
            var widened = mutable.asReadOnly();
            Shelf<FrozenLabel> shelf = review;
            ReadOnlyShelf<FrozenLabel> frozen = shelf.asReadOnly();
            check(exact.getSelected() == first.asReadOnly() && widened.getSelected() == exact.getSelected()
                    && exact.getBase() == other.asReadOnly() && exact.getHistory().get(1) == null, "dependent projection");
            first.setText("live");
            check(exact.getSelected().getText().equals("live"), "live readonly alias");
            check(frozen.getSelected() == stable && frozen.getHistory().get(2) == twin && stable.equals(twin) && stable != twin
                    && frozen.getPinned().size() == 2 && frozen.getPinned().contains(stable) && frozen.getPinned().contains(twin),
                    "immutable typed readonly identity");
            check(related.vmf().reflect().propertyByName("selected").orElseThrow().getType().getDescriptor().getName()
                    .equals(Leaf.class.getName()), "closed dependent field");
            var application = Relations.type().superTypes().get(0).superTypes().get(0).getDescriptor();
            check(application.getArguments().get(0).getName().equals(LabelNode.class.getName())
                    && application.getArguments().get(1).getName().equals(Leaf.class.getName()), "forwarded descriptor");
            Library copy = library.vmf().content().deepCopy();
            check(copy.getNodes().get(0) == copy.getRelations().getSelected() && copy.getNodes().get(0) != first
                    && copy.getReview().getSelected() == stable, "copy identities");
            verifyAlternativeContracts(first);
            MAPPER.writeValue(checkpointFile.toFile(), library);
            var journal = JournalRecorder.recordFrame(library, BINDING, "origin", 8, working -> {
                working.getRelations().setBase(second);
                working.getRelations().setSelected(second);
                working.getRelations().getHistory().set(0, second);
                working.getReview().setSelected(twin);
                working.getReview().getHistory().set(0, twin);
            });
            Files.writeString(journalFile, VMFJournalCodec.write(journal));
        }
        var input = MAPPER.readValue(checkpointFile.toFile(), Library.class);
        var journal = VMFJournalCodec.read(Files.readString(journalFile));
        check(journal.wireVersion() == 1 && input.getNodes().get(1) instanceof TaggedLeaf
                && input.getNodes().get(0) == input.getRelations().getSelected()
                && input.getReview().getSelected() == input.getReview().getHistory().get(0), "decoded aliases");
        var restored = JournalReplayer.replay(input, BINDING, "origin", journal);
        check(restored.getNodes().get(1) == restored.getRelations().getBase()
                && restored.getNodes().get(1) == restored.getRelations().getSelected()
                && restored.getNodes().get(1) == restored.getRelations().getHistory().get(0)
                && restored.getReview().getSelected().getId().equals("tag2")
                && restored.getReview().getSelected() == restored.getReview().getHistory().get(0)
                && restored.getReview().getSelected() == restored.getReview().getHistory().get(2)
                && input.getReview().getSelected().getId().equals("tag1"), "fresh replay aliases");
        exchangeCompoundBound(directory, author, write);
        System.out.println("Java verified " + author + " dependent and immutable bound snapshot and immutable reference journal.");
    }

    private static void exchangeCompoundBound(Path directory, String author, boolean write) throws IOException {
        for (String format : List.of("json", "xml", "yaml")) {
            ObjectMapper mapper = format.equals("xml") ? new XmlMapper()
                    : format.equals("yaml") ? new ObjectMapper(new YAMLFactory()) : new ObjectMapper();
            mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
            var file = directory.resolve(author + "-composed-bound." + format);
            if (write) {
                var item = WorkItem.newBuilder().withTitle("draft").withRank(7).build();
                var review = WorkReview.newBuilder().withSelected(item).build();
                var book = RankedWorkBook.newBuilder().withWork(item).withReview(review).build();
                verifyCompoundBound(book);
                mapper.writeValue(file.toFile(), book);
            }
            check(Files.readString(file).contains("selected_item"), "Inherited wire name through the composed nominal bound");
            verifyCompoundBound(mapper.readValue(file.toFile(), RankedWorkBook.class));
        }
    }

    private static void verifyCompoundBound(RankedWorkBook book) {
        var item = book.getWork();
        RankedSelection<WorkItem> selection = book.getReview();
        var projected = selection.asReadOnly().getSelected();
        check(selection.getSelected() == item && projected == item.asReadOnly()
                && projected.getTitle().equals("draft") && projected.getRank() == 7,
                "Both parent roles retain access to one canonical projected identity");
        check(selection.vmf().reflect().propertyByName("selected").orElseThrow().getType().getDescriptor().getName()
                        .equals(WorkItem.class.getName()), "The concrete specialization supplies the stored type");
        item.setTitle("reviewed");
        check(projected.getTitle().equals("reviewed"), "A held projection reads edits through the composed bound");
        item.setTitle("draft");
    }

    private static void verifyAlternativeContracts(Leaf leaf) {
        var composed = ComposedRelations.newInstance();
        Related<LabelNode, Leaf> viaSecondary = composed;
        viaSecondary.setSelected(leaf);
        viaSecondary.getHistory().add(leaf);
        check(viaSecondary.asReadOnly().getSelected() == leaf.asReadOnly(), "dependent secondary wildcard bound");
        var model = DetailedLabel.newBuilder().withText("shared").withNote("details").build();
        var details = DetailedRelations.newBuilder().withSelected(model).withHistory(model).build();
        TraitRelations<DetailedLabel, DetailedLabel> viaTrait = details;
        ReadOnlyTraitRelations<ReadOnlyDetailedLabel, ReadOnlyDetailedLabel> view = details.asReadOnly();
        check(viaTrait.getSelected() == model && view.getSelected() == model.asReadOnly()
                && view.getHistory().get(0) == model.asReadOnly(), "forwarded getter-only bound");
    }

    private static void check(boolean passed, String name) {
        if (!passed) throw new AssertionError(name);
    }
}
