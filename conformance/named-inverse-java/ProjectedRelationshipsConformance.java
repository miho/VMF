package eu.mihosoft.vmf.conformance.namedinverse;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Locale;

final class ProjectedRelationshipsConformance {
    private static final String CHECKPOINT = "connector-15";
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "conformance.connector"; }
        @Override public int modelVersion() { return 1; }

        @Override public String id(VObject model) {
            if (model instanceof Flow) return "flow";
            if (model instanceof Node) return "node";
            if (model instanceof Input) return "receiver";
            if (model instanceof Output) return "sender";
            if (model instanceof Edge) return ((Edge) model).getName();
            throw new IllegalArgumentException("Unknown connector journal object");
        }

        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) {
            throw new IllegalArgumentException("The connector journal uses checkpoint objects");
        }
    };

    private ProjectedRelationshipsConformance() {}

    static void write(Path directory, ObjectMapper mapper) throws IOException {
        var model = specimen();
        verifyGraph(model);
        mapper.writeValue(path(directory, "java", "checkpoint.json").toFile(), model);
        var journal = JournalRecorder.recordFrame(model, BINDING, CHECKPOINT, 15, root -> {
            var node = root.getNodes().get(0);
            Connector sender = node.getOutputs().get(0);
            sender.getLinks().add(node.getInputs().get(0).getLinks().get(0));
        });
        for (var format : VMFDocumentFormat.values()) {
            Files.writeString(path(directory, "java", journalFile(format)), VMFJournalCodec.write(journal, format));
        }
        verify(directory, "java", mapper);
    }

    static void verify(Path directory, String producer, ObjectMapper mapper) throws IOException {
        String checkpoint = Files.readString(path(directory, producer, "checkpoint.json"));
        var edge = mapper.readTree(checkpoint).path("nodes").get(0).path("inputs").get(0).path("links").get(0);
        check(edge.has("name") && !edge.has("receiver") && !edge.has("sender") && !edge.has("flow"),
                "A connection persists once without its inferred inverses");
        var initial = mapper.readValue(checkpoint, Flow.class);
        verifyGraph(initial);
        for (var format : VMFDocumentFormat.values()) {
            var journal = VMFJournalCodec.read(Files.readString(path(directory, producer, journalFile(format))), format);
            verifyReplay(initial, journal);
        }
    }

    private static Flow specimen() {
        var root = Flow.newInstance();
        var node = Node.newInstance();
        var input = Input.newInstance();
        var output = Output.newInstance();
        root.getNodes().add(node);
        input.setParent(node);
        node.getOutputs().add(output);
        Connector connector = input;

        var edge = Edge.newInstance();
        edge.setSender(output);
        check(edge.getSender() == output && output.getLinks().get(0) == edge, "The sender owns the edge");
        connector.getLinks().add(edge);
        check(edge.getReceiver() == input && input.getLinks().get(0) == edge
                && edge.getSender() == null && output.getLinks().isEmpty(), "The receiver takes ownership through the base view");
        root.getLinks().add(edge);
        check(edge.getFlow() == root && root.getLinks().get(0) == edge
                && edge.getReceiver() == null && input.getLinks().isEmpty(), "The flow takes ownership");
        edge.setSender(null);
        check(root.getLinks().isEmpty() && edge.getFlow() == null, "Clearing an inactive inverse detaches the current owner");

        root.getLinks().add(Edge.newBuilder().withName("flow-edge").build());
        connector.getLinks().add(Edge.newBuilder().withName("input-edge").build());
        output.getLinks().add(Edge.newBuilder().withName("output-edge").build());
        return root;
    }

    private static void verifyGraph(Flow root) {
        var node = root.getNodes().get(0);
        var input = node.getInputs().get(0);
        var output = node.getOutputs().get(0);
        Connector base = input;
        check(base.getParent() == node && base.getLinks() == input.getLinks(),
                "The mutable connector projects the concrete relationship");
        check(base.asReadOnly() == input.asReadOnly() && base.asReadOnly().getParent() == node.asReadOnly()
                && base.asReadOnly().getLinks() == input.asReadOnly().getLinks(),
                "The read-only connector projects the same relationships");
        check(ConnectorConstruction.OBSERVED.get(input) == input.getLinks()
                && ConnectorConstruction.OBSERVED.get(output) == output.getLinks(),
                "The inherited hook receives the initialized live collection for both implementations");

        var received = input.getLinks().get(0);
        check("input-edge".equals(received.getName()) && received.getReceiver() == input
                && received.getSender() == null && received.getFlow() == null, "Only the receiver inverse is populated");
        var sent = output.getLinks().get(0);
        check("output-edge".equals(sent.getName()) && sent.getSender() == output
                && sent.getReceiver() == null && sent.getFlow() == null, "Only the sender inverse is populated");
        var owned = root.getLinks().get(0);
        check("flow-edge".equals(owned.getName()) && owned.getFlow() == root
                && owned.getReceiver() == null && owned.getSender() == null, "Only the flow inverse is populated");
    }

    private static void verifyReplay(Flow initial, ChangeJournal journal) {
        check(journal.wireVersion() == 1 && journal.transactions().size() == 1, "The transfer uses journal V1");
        var operations = journal.transactions().get(0).operations();
        check(operations.size() == 2 && operations.get(0).kind() == ChangeJournal.Kind.LIST_REMOVE
                && operations.get(1).kind() == ChangeJournal.Kind.LIST_ADD
                && operations.get(0).target().equals("receiver")
                && operations.get(1).target().equals("sender")
                && operations.stream().allMatch(edit -> "links".equals(edit.property())),
                "The journal records the removal and addition on their actual owners");
        var replay = JournalReplayer.replay(initial, BINDING, CHECKPOINT, journal);
        var node = replay.getNodes().get(0);
        var receiver = node.getInputs().get(0);
        var sender = node.getOutputs().get(0);
        var edge = sender.getLinks().get(1);
        check(receiver.getLinks().isEmpty() && sender.getLinks().size() == 2
                && "input-edge".equals(edge.getName()) && edge.getSender() == sender && edge.getReceiver() == null,
                "Replay restores the destination inverse on the independent graph");
        check(initial.getNodes().get(0).getInputs().get(0).getLinks().size() == 1,
                "Replay preserves the input checkpoint");
    }

    private static String journalFile(VMFDocumentFormat format) {
        return "journal." + format.name().toLowerCase(Locale.ROOT);
    }

    private static Path path(Path directory, String producer, String suffix) {
        return directory.resolve(producer + "-connector-" + suffix);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
