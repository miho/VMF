package eu.mihosoft.vmf.conformance.behaviorhierarchy;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class PrimarySpineExchange {
    private PrimarySpineExchange() { }

    public static void verify(Path exchange, boolean writing) throws IOException {
        NodeBinding.instances = 0;
        ConnectorBinding.instances = 0;
        EndpointBinding.instances = 0;
        FlowBinding.instances = 0;
        var flow = SpineFlow.newInstance();
        var endpoint = SpineEndpoint.newBuilder().withName("route").build();
        check(FlowBinding.instances == 1 && FlowBinding.channelAtHook == null && NodeBinding.instances == 0
                && EndpointBinding.instances == 1, "The local hook and hook-only declaration bind before inherited defaults");

        var node = flow.newNode("nested");
        SpineNode asNode = flow;
        var first = asNode.addPort("number");
        var other = node.addPort("number");
        check(first.getOwner() == flow && other.getOwner() == node && NodeBinding.instances == 2
                && asNode.portsCreated() == 1 && node.portsCreated() == 1,
                "Inherited calls share a lazy delegate on each concrete caller");
        check(ConnectorBinding.instances == 2, "The inherited no-action hook still eagerly selects each connector binding");
        SpineConnector asConnector = first;
        check(flow.connect(asConnector, other) && !flow.connect(flow, node, "number")
                && asConnector.connectionsTried() == 1 && other.connectionsTried() == 0
                && ConnectorBinding.instances == 2, "Named abstract arguments keep same-owner calls and distinct overloads");

        flow.setEndpoint(endpoint);
        flow.setFeatured(other);
        for (boolean references : new boolean[]{true, false}) {
            var mapper = snapshot(references);
            String suffix = references ? "-primary-spine-reference.json" : "-primary-spine-by-value.json";
            String wire = exchange(mapper, exchange, suffix, flow, writing);
            var restored = mapper.readValue(wire, SpineFlow.class);
            var restoredNode = restored.getNodes().get(0);
            var restoredPort = restoredNode.getPorts().get(0);
            check(restoredPort.getOwner() == restoredNode && restored.getEndpoint().getName().equals("route")
                    && restored.portsCreated() == 0 && restoredPort.connectionsTried() == 0
                    && restored.connect(restored.getPorts().get(0), restoredPort),
                    "Fresh delegates dispatch through restored concrete graph nodes");
            check(references ? restored.getFeatured() == restoredPort : restored.getFeatured() == null,
                    "Each existing JSON mode keeps its alias contract");
        }

        var plainMapper = snapshot(false);
        String plain = exchange(plainMapper, exchange, "-primary-spine-plain.json", endpoint, writing);
        check(!plainMapper.readTree(plain).has("@vmf-type") && !plainMapper.readTree(plain).has("@vmf-id"),
                "The exact-type plain snapshot needs only modeled properties");
        var plainEndpoint = plainMapper.readValue(plain, SpineEndpoint.class);
        check(plainEndpoint.getName().equals("route") && plainEndpoint.getChannel().equals("default"),
                "The hook-only endpoint exposes the same plain data");
    }

    private static ObjectMapper snapshot(boolean references) {
        return new ObjectMapper().registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withModelWireTypeAliases(SpineFlow.class).withReferences(references));
    }

    private static String exchange(ObjectMapper mapper, Path directory, String suffix, Object model, boolean writing) throws IOException {
        String expected = mapper.writeValueAsString(model);
        if (writing) {
            Files.writeString(directory.resolve("java" + suffix), expected);
            return expected;
        }

        String nativeWire = Files.readString(directory.resolve("csharp" + suffix));
        check(mapper.readTree(expected).equals(mapper.readTree(nativeWire)), "Java and C# write the same modeled graph");
        return nativeWire;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class ConnectorBinding implements DelegatedBehavior<SpineConnector> {
        static int instances;
        private SpineConnector caller;
        private int tried;

        public ConnectorBinding() { instances++; }
        @Override public void setCaller(SpineConnector caller) { this.caller = caller; }
        public void onSpineConnectorInstantiated() { }
        public int connectionsTried() { return tried; }
        public boolean connect(SpineConnector other) {
            tried++;
            return other != null && caller.getOwner() != other.getOwner()
                    && Objects.equals(caller.getChannel(), other.getChannel());
        }
    }

    public static final class NodeBinding implements DelegatedBehavior<SpineNode> {
        static int instances;
        private SpineNode caller;
        private int created;

        public NodeBinding() { instances++; }
        @Override public void setCaller(SpineNode caller) { this.caller = caller; }
        public int portsCreated() { return created; }
        public SpinePort addPort(String channel) {
            var port = SpinePort.newBuilder().withChannel(channel).build();
            caller.getPorts().add(port);
            created++;
            return port;
        }
    }

    public static final class EndpointBinding implements DelegatedBehavior<SpineEndpoint> {
        static int instances;
        public EndpointBinding() { instances++; }
        @Override public void setCaller(SpineEndpoint caller) { }
        public void onSpineEndpointInstantiated() { }
    }

    public static final class FlowBinding implements DelegatedBehavior<SpineFlow> {
        static int instances;
        static String channelAtHook;
        private SpineFlow caller;

        public FlowBinding() { instances++; }
        @Override public void setCaller(SpineFlow caller) { this.caller = caller; }
        public void onSpineFlowInstantiated() { channelAtHook = caller.getChannel(); }
        public SpineNode newNode(String name) {
            var node = SpineNode.newBuilder().withName(name).build();
            caller.getNodes().add(node);
            return node;
        }
        public boolean connect(SpineConnector from, SpineConnector to) { return from != null && from.connect(to); }
        public boolean connect(SpineNode from, SpineNode to, String channel) { return false; }
    }
}
