package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.opposites.OrderedPanel;
import eu.mihosoft.vmf.conformance.opposites.OrderedPanelGallery;
import eu.mihosoft.vmf.conformance.opposites.OrderedPanelLink;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Kind;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

final class OrderedReferenceBuilderConformance {
    private static final String MODEL = "conformance.ordered-builder";
    private static final String CHECKPOINT = "ordered-panel-checkpoint";
    private static final String ROOT = "gallery";
    private static final ObjectMapper REFERENCES = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return MODEL; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject model) {
            if (model instanceof OrderedPanel) return ((OrderedPanel) model).getId();
            if (model instanceof OrderedPanelLink) return ((OrderedPanelLink) model).getId();
            return ROOT;
        }
        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) {
            throw new AssertionError("Ordered builder edits reuse the contained checkpoint identities.");
        }
    };

    private OrderedReferenceBuilderConformance() {}

    static void exchange(Path directory, boolean writing) throws IOException {
        String target = writing ? "java" : "csharp";
        var checkpointFile = directory.resolve(target + "-ordered-builder-checkpoint.json");
        var journalFile = directory.resolve(target + "-ordered-builder.json");
        if (writing) {
            var graph = checkpoint();
            REFERENCES.writeValue(checkpointFile.toFile(), graph);
            try (var record = JournalRecorder.start(graph, BINDING, CHECKPOINT, 0)) {
                var entries = graph.getEntries();
                OrderedPanel.newBuilder().withId("panel")
                        .withLinks(List.of(entries.get(4), entries.get(5), entries.get(1), entries.get(6)))
                        .appendCollections(false).applyTo(graph.getPanel());
                Files.writeString(journalFile, VMFJournalCodec.write(record.finish()));
            }
            verifyGraph(graph, List.of("e", "x", "b", "y"));
        }
        var checkpoint = REFERENCES.readValue(checkpointFile.toFile(), OrderedPanelGallery.class);
        var journal = VMFJournalCodec.read(Files.readString(journalFile));
        verifyV1Ordering(journal);
        var replayed = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, journal);
        check(replayed != checkpoint && replayed.getEntries().get(4) != checkpoint.getEntries().get(4),
                "Replay uses an independent graph");
        verifyGraph(checkpoint, List.of("a", "b", "c", "d", "e"));
        verifyGraph(replayed, List.of("e", "x", "b", "y"));
        System.out.println("Java verified " + target + " V1 ordered builder positions and reciprocal links.");
    }

    private static OrderedPanelGallery checkpoint() {
        var graph = OrderedPanelGallery.newInstance();
        var panel = OrderedPanel.newBuilder().withId("panel").build();
        graph.setPanel(panel);
        var links = List.of("a", "b", "c", "d", "e", "x", "y").stream()
                .map(id -> OrderedPanelLink.newBuilder().withId(id).build()).collect(Collectors.toList());
        graph.getEntries().addAll(links);
        panel.getLinks().addAll(links.subList(0, 5));
        return graph;
    }

    private static void verifyGraph(OrderedPanelGallery graph, List<String> ids) {
        var panel = graph.getPanel();
        var entries = graph.getEntries();
        check(panel.getLinks().size() == ids.size(), "Links have expected size");
        for (int index = 0; index < ids.size(); index++) {
            int offset = index;
            check(panel.getLinks().get(index) == entries.stream()
                    .filter(entry -> ids.get(offset).equals(entry.getId())).findFirst().orElseThrow(),
                    "Links retain positional identity");
        }
        for (var entry : entries) {
            check(entry.getPanels().contains(panel) == ids.contains(entry.getId()), "Inverse membership matches");
        }
    }

    private static void verifyV1Ordering(ChangeJournal journal) throws IOException {
        check(journal.wireVersion() == 1 && journal.transactions().size() == 1, "One V1 frame");
        var edits = journal.transactions().get(0).operations();
        check(edits.size() == 3, "Exactly three positional edits: " + VMFJournalCodec.write(journal));
        verifyEdit(edits.get(0), Kind.LIST_REMOVE, List.of(0, 2, 3), List.of("a", "c", "d"), List.of());
        verifyEdit(edits.get(1), Kind.LIST_SET, List.of(0, 1), List.of("b", "e"), List.of("e", "b"));
        verifyEdit(edits.get(2), Kind.LIST_ADD, List.of(1, 3), List.of(), List.of("x", "y"));
    }

    private static void verifyEdit(ChangeJournal.Operation edit, Kind kind, List<Integer> indices,
                                   List<String> before, List<String> after) {
        check(edit.kind() == kind && edit.target().equals("panel") && edit.property().equals("links")
                && edit.indices().equals(indices), "Ordered edit identity and positions");
        check(edit.before().stream().map(ChangeJournal.Value::text).collect(Collectors.toList()).equals(before)
                && edit.after().stream().map(ChangeJournal.Value::text).collect(Collectors.toList()).equals(after),
                "Ordered edit identity payloads");
    }

    private static void check(boolean condition, String note) {
        if (!condition) throw new AssertionError(note);
    }
}
