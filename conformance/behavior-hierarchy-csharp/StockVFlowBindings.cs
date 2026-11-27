using Vmf.Runtime;

namespace Eu.Mihosoft.Vmftest.Complex.Vflow;

public abstract partial class Connector
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new StockConnectorBehavior();

    private sealed class StockConnectorBehavior : DefaultBehavior
    {
        public void OnConnectorInstantiated(Connector caller) =>
            caller.VmfChanges.AddPropertyListener("connections", _ => RequireUnique(caller.Connections));

        public ConnectionResult? Connect(Connector caller, Connector? other)
        {
            var result = TryConnect(caller, other);
            if (!result.Successful) return result;
            var (input, output) = Ends(caller, other);
            var connection = new Connection { Type = input!.Type };
            connection.Sender = output;
            connection.Receiver = input;
            input.Parent!.Parent!.Connections.Add(connection);
            return result;
        }

        public ConnectionResult TryConnect(Connector caller, Connector? other)
        {
            if (other is null)
                return Failure("cannot establish connection between 'null' connectors");
            if (caller.Parent is null || other.Parent is null)
                return Failure("cannot establish connection between connectors without parent node");
            if (caller.Parent.Parent is null || other.Parent.Parent is null)
                return Failure("cannot establish connection between nodes that don't belong to a flow object");

            var (input, output) = Ends(caller, other);
            if (input is null || output is null)
                return Failure("cannot establish a connection between two outputs or two inputs");
            if (input.Type != output.Type)
                return Failure("cannot establish a connection between connectors of incompatible types "
                    + $"[ input-type: {input.Type}, output-type: {output.Type}]");
            return new ConnectionResult { Successful = true };
        }

        private static (Input? Input, Output? Output) Ends(Connector first, Connector? second) => (first, second) switch
        {
            (Input input, Output output) => (input, output),
            (Output output, Input input) => (input, output),
            _ => (null, null),
        };

        private static ConnectionResult Failure(string message) => new() { Message = message };
    }

    internal static void RequireUnique<T>(IEnumerable<T?> entries) where T : class
    {
        var seen = new HashSet<T?>(ReferenceEqualityComparer.Instance);
        if (entries.Any(entry => !seen.Add(entry))) throw new InvalidOperationException("Duplicate flow member");
    }
}

public sealed partial class Connection
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new StockConnectionBehavior();

    private sealed class StockConnectionBehavior : DefaultBehavior
    {
        public void OnConnectionInstantiated(Connection caller)
        {
            void Update(ModelChange _) => caller.Id = $"{caller.Sender?.Id ?? "<none>"} -> {caller.Receiver?.Id ?? "<none>"}";
            caller.VmfChanges.AddPropertyListener("sender", Update);
            caller.VmfChanges.AddPropertyListener("receiver", Update);
        }
    }
}

public partial class VNode
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new StockNodeBehavior();

    private sealed class StockNodeBehavior : DefaultBehavior
    {
        public Input? AddInput(VNode caller, string? type)
        {
            var input = new Input { Type = type };
            caller.Inputs.Add(input);
            return input;
        }

        public Output? AddOutput(VNode caller, string? type)
        {
            var output = new Output { Type = type };
            caller.Outputs.Add(output);
            return output;
        }
    }
}

public sealed partial class VFlow
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new StockFlowBehavior();

    private sealed class StockFlowBehavior : DefaultBehavior
    {
        public void OnVFlowInstantiated(VFlow caller) =>
            caller.VmfChanges.AddPropertyListener("nodes", _ => Connector.RequireUnique(caller.Nodes));

        public VNode? NewNode(VFlow caller, object? value)
        {
            var node = new VNode { Value = value };
            caller.Nodes.Add(node);
            return node;
        }

        public VFlow? NewSubFlow(VFlow caller, object? value)
        {
            var node = new VFlow { Value = value };
            caller.Nodes.Add(node);
            return node;
        }

        public ConnectionResult? Connect(VFlow caller, Connector? first, Connector? second) => first!.Connect(second);

        public ConnectionResult? Connect(VFlow caller, VNode? first, VNode? second, string? type) => null;

        public ConnectionResult? TryConnect(VFlow caller, Connector? first, Connector? second) => first!.TryConnect(second);
    }
}
