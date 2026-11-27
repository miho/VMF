namespace Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;

public abstract partial class SpineConnector
{
    internal static int BindingCount { get; set; }

    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        BindingCount++;
        return new ConnectorBinding();
    }

    private sealed class ConnectorBinding : DefaultBehavior
    {
        private int tried;

        public int ConnectionsTried(SpineConnector caller) => tried;

        public bool Connect(SpineConnector caller, SpineConnector? other)
        {
            tried++;
            return other is not null && !ReferenceEquals(caller.Owner, other.Owner) && caller.Channel == other.Channel;
        }
    }
}

public partial class SpineNode
{
    internal static int BindingCount { get; set; }

    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        BindingCount++;
        return new NodeBinding();
    }

    private sealed class NodeBinding : DefaultBehavior
    {
        private int created;

        public int PortsCreated(SpineNode caller) => created;

        public SpinePort? AddPort(SpineNode caller, string? channel)
        {
            var port = new SpinePort { Channel = channel };
            caller.Ports.Add(port);
            created++;
            return port;
        }
    }
}

public partial class SpineEndpoint
{
    internal static int BindingCount { get; set; }

    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        BindingCount++;
        return new EndpointBinding();
    }

    private sealed class EndpointBinding : DefaultBehavior;
}

public partial class SpineFlow
{
    internal static int FlowBindingCount { get; set; }
    internal static string? ChannelAtHook { get; private set; }

    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        FlowBindingCount++;
        return new FlowBinding();
    }

    private sealed class FlowBinding : DefaultBehavior
    {
        public void OnSpineFlowInstantiated(SpineFlow caller) => ChannelAtHook = caller.Channel;

        public SpineNode? NewNode(SpineFlow caller, string? name)
        {
            var node = new SpineNode { Name = name };
            caller.Nodes.Add(node);
            return node;
        }

        public bool Connect(SpineFlow caller, SpineConnector? from, SpineConnector? to) => from?.Connect(to) ?? false;
        public bool Connect(SpineFlow caller, SpineNode? from, SpineNode? to, string? channel) => false;
    }
}
