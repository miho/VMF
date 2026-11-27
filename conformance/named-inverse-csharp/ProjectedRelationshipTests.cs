using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Namedinverse;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ProjectedRelationshipTests
{
    private const string Checkpoint = "connector-15";
    private static readonly JournalBinding Binding = new("conformance.connector", 1, model => model switch
    {
        Flow => "flow",
        Node => "node",
        Input => "receiver",
        Output => "sender",
        Edge { Name: { } name } => name,
        _ => throw new ArgumentException("Unknown connector journal object")
    });

    [Fact]
    public void AbstractGetterProjectsTheSameInitializedOwningList()
    {
        var flow = Specimen();
        VerifyGraph(flow);
        VerifyGraph(ModelSnapshot.ReadXml<Flow>(ModelSnapshot.WriteXml(flow)));
        VerifyGraph(ModelSnapshot.ReadYaml<Flow>(ModelSnapshot.WriteYaml(flow)));
    }

    [Fact]
    public void TransferViaBaseGetterReplaysV1AgainstFreshJavaAndCSharpCheckpoints()
    {
        var root = Specimen();
        string checkpoint = ModelSnapshot.Write(root);

        using var recorder = JournalRecorder<Flow>.Start(root, ModelSnapshot.Registry, Binding, Checkpoint, 15);
        var node = root.Nodes[0]!;
        Connector sender = node.Outputs[0]!;
        sender.Links.Add(node.Inputs[0]!.Links[0]);
        var journal = recorder.Finish();

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_NAMED_INVERSE");
        if (exchange is not null)
            File.WriteAllText(Path.Combine(exchange, "csharp-connector-checkpoint.json"), checkpoint);
        var codecs = new (string Extension, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read)
        };
        foreach (var codec in codecs)
        {
            string output = codec.Write(journal);
            VerifyReplay(checkpoint, codec.Read(output));
            if (exchange is null) continue;
            VerifyReplay(File.ReadAllText(Path.Combine(exchange, "java-connector-checkpoint.json")),
                codec.Read(File.ReadAllText(Path.Combine(exchange, $"java-connector-journal.{codec.Extension}"))));
            File.WriteAllText(Path.Combine(exchange, $"csharp-connector-journal.{codec.Extension}"), output);
        }
    }

    private static Flow Specimen()
    {
        var root = new Flow();
        var node = new Node();
        var input = new Input();
        var output = new Output();
        root.Nodes.Add(node);
        input.Parent = node;
        node.Outputs.Add(output);
        Connector connector = input;
        var edge = new Edge();

        edge.Sender = output;
        Assert.Same(output, edge.Sender);
        Assert.Same(edge, Assert.Single(output.Links));
        var observations = new List<ModelChange>();
        using var subscription = connector.VmfChanges.AddPropertyListener("links", observations.Add);
        connector.Links.Add(edge);
        Assert.Same(input, edge.Receiver);
        Assert.Same(edge, Assert.Single(input.Links));
        Assert.Null(edge.Sender);
        Assert.Empty(output.Links);
        Assert.True(Assert.Single(observations).IsContainment);
        root.Links.Add(edge);
        Assert.Same(root, edge.Flow);
        Assert.Same(edge, Assert.Single(root.Links));
        Assert.Null(edge.Receiver);
        Assert.Empty(input.Links);
        edge.Sender = null;
        Assert.Null(edge.Flow);
        Assert.Empty(root.Links);

        root.Links.Add(new Edge { Name = "flow-edge" });
        connector.Links.Add(new Edge { Name = "input-edge" });
        output.Links.Add(new Edge { Name = "output-edge" });
        return root;
    }

    private static void VerifyGraph(Flow root)
    {
        var node = Assert.Single(root.Nodes)!;
        var input = Assert.Single(node.Inputs)!;
        var output = Assert.Single(node.Outputs)!;
        Connector connector = input;
        Assert.Same(node, connector.Parent);
        Assert.Same(input.Links, connector.Links);

        IReadOnlyConnector view = connector.AsReadOnly();
        Assert.Same(input.AsReadOnly(), view);
        Assert.Same(node.AsReadOnly(), view.Parent);
        Assert.Same(input.AsReadOnly().Links, view.Links);
        Assert.True(Connector.ObservedLinks.TryGetValue(input, out var receiverHookLinks));
        Assert.Same(input.Links, receiverHookLinks);
        Assert.True(Connector.ObservedLinks.TryGetValue(output, out var senderHookLinks));
        Assert.Same(output.Links, senderHookLinks);
        var reflected = ModelSnapshot.Reflection.Reflect(connector);
        Assert.True(reflected.FindProperty("links")!.IsContainment);
        Assert.True(reflected.FindProperty("parent")!.IsContainer);

        var local = Assert.Single(input.Links)!;
        Assert.Equal("input-edge", local.Name);
        Assert.Same(input, local.Receiver);
        Assert.Null(local.Sender);
        Assert.Null(local.Flow);
        var remote = Assert.Single(output.Links)!;
        Assert.Equal("output-edge", remote.Name);
        Assert.Same(output, remote.Sender);
        Assert.Null(remote.Receiver);
        Assert.Null(remote.Flow);
        var owned = Assert.Single(root.Links)!;
        Assert.Equal("flow-edge", owned.Name);
        Assert.Same(root, owned.Flow);
        Assert.Null(owned.Sender);
        Assert.Null(owned.Receiver);
    }

    private static void VerifyReplay(string checkpoint, ChangeJournal journal)
    {
        using var document = JsonDocument.Parse(checkpoint);
        var owned = document.RootElement.GetProperty("nodes")[0].GetProperty("inputs")[0].GetProperty("links")[0];
        Assert.True(owned.TryGetProperty("name", out _));
        Assert.False(owned.TryGetProperty("receiver", out _));
        Assert.False(owned.TryGetProperty("sender", out _));
        Assert.False(owned.TryGetProperty("flow", out _));

        var initial = ModelSnapshot.Read<Flow>(checkpoint);
        VerifyGraph(initial);
        Assert.Equal(1, journal.WireVersion);
        var operations = Assert.Single(journal.Transactions).Operations;
        Assert.Equal([JournalListEditKind.Remove, JournalListEditKind.Add],
            operations.Select(edit => Assert.IsType<JournalListEdit>(edit).Kind));
        Assert.Equal(["receiver", "sender"], operations.Select(edit => edit.Target));
        Assert.All(operations, edit => Assert.Equal("links", edit.Property));

        var replay = JournalReplayer.Replay(initial, Binding, Checkpoint, journal, ModelSnapshot.Registry);
        var node = replay.Nodes[0]!;
        var sender = node.Outputs[0]!;
        Assert.Empty(node.Inputs[0]!.Links);
        Assert.Equal(2, sender.Links.Count);
        var edge = sender.Links[1]!;
        Assert.Equal("input-edge", edge.Name);
        Assert.Same(sender, edge.Sender);
        Assert.Null(edge.Receiver);
        Assert.Single(initial.Nodes[0]!.Inputs[0]!.Links);
    }
}
