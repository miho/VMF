using System.Collections;
using Eu.Mihosoft.Vmftest.Complex.Vflow;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class StockVFlowTests
{
    private static readonly JournalBinding Binding = new("stock-vflow", 1, model => ((VNode)model).Id!);

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void OriginalVFlowDelegatesExchangeSnapshotsAndReplayFromTheLiveBoxedCheckpoint(string format)
    {
        var flow = CreateFlow();
        var before = Checkpoint();
        var journal = JournalRecorder<VFlow>.RecordFrame(Checkpoint(), ModelSnapshot.Registry, Binding, "start", 0, root =>
        {
            root.Nodes[0]!.Value = "ready";
            root.Nodes[1]!.Value = true;
        });
        Assert.Equal(1, journal.WireVersion);
        Assert.Single(journal.Transactions);

        (Func<VFlow, string> write, Func<string, VFlow> read,
            Func<ChangeJournal, string> writeJournal, Func<string, ChangeJournal> readJournal) = Codecs(format);
        string snapshot = write(flow);
        string changes = writeJournal(journal);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR_HIERARCHY") is { } directory)
        {
            File.WriteAllText(Path.Combine(directory, $"csharp-stock-vflow.{format}"), snapshot);
            File.WriteAllText(Path.Combine(directory, $"csharp-stock-vflow-journal.{format}"), changes);
            snapshot = File.ReadAllText(Path.Combine(directory, $"java-stock-vflow.{format}"));
            changes = File.ReadAllText(Path.Combine(directory, $"java-stock-vflow-journal.{format}"));
        }

        VerifyFlow(read(snapshot), format == "xml" ? "42" : 42);
        var restored = JournalReplayer.Replay(before, Binding, "start", readJournal(changes), ModelSnapshot.Registry);
        Assert.Equal("ready", restored.Nodes[0]!.Value);
        Assert.Equal(true, restored.Nodes[1]!.Value);
        Assert.Equal((short)2, before.Nodes[0]!.Value);
        Assert.Null(before.Nodes[1]!.Value);
    }

    private static VFlow CreateFlow()
    {
        var root = new VFlow { Id = "root" };
        var payload = new Dictionary<string, object?> { ["step"] = 42 };
        var node = root.NewNode(payload)!;
        var child = root.NewSubFlow(null)!;
        Assert.Same(payload, node.Value);
        var output = node.AddOutput("pulse")!;
        output.Id = "out";
        var input = child.AddInput("pulse")!;
        input.Id = "in";
        Assert.True(root.TryConnect(output, input)!.Successful);
        Assert.True(root.Connect(output, input)!.Successful);
        Assert.Null(root.Connect(node, child, "pulse"));
        VerifyFlow(root, 42);
        return root;
    }

    private static void VerifyFlow(VFlow root, object expectedStep)
    {
        Assert.Equal(2, root.Nodes.Count);
        var node = Assert.IsType<VNode>(root.Nodes[0]);
        var child = Assert.IsType<VFlow>(root.Nodes[1]);
        Assert.Same(root, node.Parent);
        Assert.Same(root, child.Parent);
        Assert.Null(child.Value);
        Assert.Equal(expectedStep, Assert.IsAssignableFrom<IDictionary>(node.Value)["step"]);
        var output = Assert.Single(node.Outputs)!;
        var input = Assert.Single(child.Inputs)!;
        Assert.Same(node, output.Parent);
        Assert.Same(child, input.Parent);
        var connection = Assert.Single(root.Connections)!;
        Assert.Same(root, connection.Flow);
        Assert.Null(connection.Sender);
        Assert.Null(connection.Receiver);
        Assert.Equal("<none> -> <none>", connection.Id);
        Assert.Empty(output.Connections);
        Assert.Empty(input.Connections);
    }

    private static VFlow Checkpoint()
    {
        var root = new VFlow { Id = "root" };
        root.NewNode((short)2)!.Id = "node";
        root.NewSubFlow(null)!.Id = "sub";
        return root;
    }

    private static (Func<VFlow, string> Write, Func<string, VFlow> Read,
        Func<ChangeJournal, string> WriteJournal, Func<string, ChangeJournal> ReadJournal) Codecs(string format) => format switch
    {
        "xml" => (ModelSnapshot.WriteXml, ModelSnapshot.ReadXml<VFlow>, JournalXml.Write, JournalXml.Read),
        "yaml" => (ModelSnapshot.WriteYaml, ModelSnapshot.ReadYaml<VFlow>, JournalYaml.Write, JournalYaml.Read),
        _ => (ModelSnapshot.Write, ModelSnapshot.Read<VFlow>, JournalJson.Write, JournalJson.Read),
    };
}
