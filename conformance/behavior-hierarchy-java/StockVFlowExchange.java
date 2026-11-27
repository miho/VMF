package eu.mihosoft.vmf.conformance.behaviorhierarchy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import eu.mihosoft.vmftest.complex.vflow.VFlow;
import eu.mihosoft.vmftest.complex.vflow.VNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class StockVFlowExchange {
    private static final JournalBinding FLOW = new JournalBinding() {
        @Override public String modelId() { return "stock-vflow"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) { return ((VNode) object).getId(); }
        @Override public String type(VObject object) { return object.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) {
            if (type.equals(VFlow.class.getName())) return VFlow.newBuilder().withId(id).build();
            if (type.equals(VNode.class.getName())) return VNode.newBuilder().withId(id).build();
            throw new IllegalArgumentException("Unknown stock flow type: " + type);
        }
    };

    private StockVFlowExchange() { }

    public static void verify(Path directory, boolean writing) throws IOException {
        var flow = createFlow();
        var working = checkpoint();
        var journal = JournalRecorder.recordFrame(working, FLOW, "start", 0, root -> {
            root.getNodes().get(0).setValue("ready");
            root.getNodes().get(1).setValue(true);
        });
        check(journal.wireVersion() == 1 && journal.transactions().size() == 1, "Stock flow uses one V1 frame");

        String producer = writing ? "java" : "csharp";
        for (var format : VMFDocumentFormat.values()) {
            String extension = "." + format.name().toLowerCase(Locale.ROOT);
            var snapshotPath = directory.resolve(producer + "-stock-vflow" + extension);
            var journalPath = directory.resolve(producer + "-stock-vflow-journal" + extension);
            var mapper = mapper(format);
            if (writing) {
                mapper.writeValue(snapshotPath.toFile(), flow);
                Files.writeString(journalPath, VMFJournalCodec.write(journal, format));
            }
            var restored = mapper.readValue(snapshotPath.toFile(), VFlow.class);
            verifyFlow(restored, format == VMFDocumentFormat.XML ? "42" : 42);
            var baseline = checkpoint();
            var replayed = JournalReplayer.replay(baseline, FLOW, "start",
                    VMFJournalCodec.read(Files.readString(journalPath), format));
            check(baseline.getNodes().get(0).getValue().equals((short) 2), "Replay preserves the original boxed checkpoint");
            check(replayed.getNodes().get(0).getValue().equals("ready")
                    && replayed.getNodes().get(1).getValue().equals(true), "Both stock values replay");
        }
    }

    private static VFlow createFlow() {
        var root = VFlow.newInstance();
        root.setId("root");
        var data = new LinkedHashMap<String, Object>();
        data.put("step", 42);
        var node = root.newNode(data);
        var subFlow = root.newSubFlow(null);
        check(node.getValue() == data && node.getParent() == root && subFlow.getParent() == root,
                "Both delegated calls keep the original caller and host object");
        var output = node.addOutput("pulse");
        output.setId("out");
        var input = subFlow.addInput("pulse");
        input.setId("in");
        check(root.tryConnect(output, input).isSuccessful(), "Delegation accepts the matching ports");
        check(root.connect(output, input).isSuccessful(), "Delegation constructs the connection");
        verifyFlow(root, 42);
        return root;
    }

    private static void verifyFlow(VFlow root, Object expectedStep) {
        check(root.getNodes().size() == 2 && root.getNodes().get(1) instanceof VFlow,
                "Both concrete node types survive");
        var node = root.getNodes().get(0);
        var child = (VFlow) root.getNodes().get(1);
        check(node.getParent() == root && child.getParent() == root && child.getValue() == null,
                "Owners and null argument survive");
        check(node.getValue() instanceof Map && ((Map<?, ?>) node.getValue()).get("step").equals(expectedStep),
                "Stock Object field follows the existing document rules");
        check(node.getOutputs().get(0).getParent() == node && child.getInputs().get(0).getParent() == child,
                "Inherited node behavior attaches ports");
        check(root.getConnections().size() == 1, "One stock connection");
        var connection = root.getConnections().get(0);
        check(connection.getFlow() == root && connection.getSender() == null && connection.getReceiver() == null
                && connection.getId().equals("<none> -> <none>")
                && node.getOutputs().get(0).getConnections().isEmpty() && child.getInputs().get(0).getConnections().isEmpty(),
                "The stock relationship keeps a single active owner");
    }

    private static VFlow checkpoint() {
        var root = VFlow.newInstance();
        root.setId("root");
        root.newNode((short) 2).setId("node");
        root.newSubFlow(null).setId("sub");
        return root;
    }

    private static ObjectMapper mapper(VMFDocumentFormat format) {
        ObjectMapper mapper;
        switch (format) {
            case XML: mapper = new XmlMapper(); break;
            case YAML: mapper = new YAMLMapper(); break;
            default: mapper = new ObjectMapper();
        }
        return mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(true));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
