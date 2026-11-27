using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Opposites;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ReciprocalPropertyHistoryTests
{
    private const string CheckpointId = "peer-base";
    private static readonly JournalBinding Binding = new("conformance.reciprocal-property-history", 1,
        model => model is Endpoint endpoint ? endpoint.Id!.Split('#', 2)[0] : "graph");

    [Theory]
    [InlineData("early-primary")]
    [InlineData("named-primary")]
    [InlineData("late-primary")]
    [InlineData("old-inverse")]
    public void DirectReciprocalListenersPreserveRelayOrderAndReplay(string timing)
    {
        var graph = Checkpoint();
        var (a, b, c, d) = (graph.Nodes[0], graph.Nodes[1], graph.Nodes[2], graph.Nodes[3]);
        bool inverse = timing == "old-inverse";
        var watched = inverse ? d : c;
        using var removed = inverse ? c.VmfChanges.AddDirectPropertyListener("peer",
            _ => throw new InvalidOperationException("Departed callback was delivered")) : null;
        using var removedOpposite = inverse ? b.VmfChanges.AddDirectPropertyListener("peer",
            _ => throw new InvalidOperationException("Departed inverse callback was delivered")) : null;
        var callbacks = new List<string>();
        var installed = new List<IDisposable>();
        void Reenter(ModelPropertyNotification edit)
        {
            if (edit.PropertyName != "peer") return;
            AssertPayload(edit, watched, inverse ? c : d, inverse ? null : b);
            callbacks.Add(timing);
            if (inverse)
            {
                removed!.Dispose();
                installed.Add(c.VmfChanges.AddDirectPropertyListener("peer", sourceEdit =>
                {
                    AssertPayload(sourceEdit, c, d, b);
                    callbacks.Add("installed-primary");
                    removedOpposite!.Dispose();
                    installed.Add(b.VmfChanges.AddDirectPropertyListener("peer", inverseEdit =>
                    {
                        AssertPayload(inverseEdit, b, a, c);
                        callbacks.Add("installed-final-inverse");
                    }));
                }));
            }
            c.Id = "c#observed";
        }

        if (timing != "late-primary")
        {
            installed.Add(timing == "named-primary" ? watched.VmfChanges.AddDirectPropertyListener("peer", Reenter)
                : watched.VmfChanges.AddDirectPropertyListener(Reenter));
        }
        ChangeJournal journal;
        try
        {
            using var recorder = JournalRecorder<ReciprocalGraph>.Start(graph, ModelSnapshot.Registry, Binding, CheckpointId, 0);
            if (timing == "late-primary") installed.Add(watched.VmfChanges.AddDirectPropertyListener(Reenter));
            c.Peer = b;
            Assert.Equal(inverse ? [timing, "installed-primary", "installed-final-inverse"] : new[] { timing }, callbacks);
            Assert.Equal(Expected(timing), graph.VmfChanges.History.Select(change => Binding.Id(change.Source) + "." + change.PropertyName));
            journal = recorder.Finish();
        }
        finally
        {
            foreach (var subscription in installed) subscription.Dispose();
        }

        var formats = new (string Extension, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
        };
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_RECIPROCAL");
        foreach (var format in formats)
        {
            string document = format.Write(journal);
            Verify(format.Read(document), timing);
            if (exchange is null) continue;
            string name = $"reciprocal-property-{timing}-journal.{format.Extension}";
            var peer = format.Read(File.ReadAllText(Path.Combine(exchange, "java-" + name)));
            Verify(peer, timing);
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(journal)), JsonNode.Parse(JournalJson.Write(peer))));
            File.WriteAllText(Path.Combine(exchange, "csharp-" + name), document);
        }
    }

    private static string[] Expected(string timing) => timing is "early-primary" or "old-inverse"
        ? ["a.peer", "c.id", "c.peer"] : ["a.peer", "c.peer", "c.id"];

    private static void AssertPayload(ModelPropertyNotification edit, Endpoint source, Endpoint oldValue, Endpoint? newValue)
    {
        Assert.Equal("peer", edit.PropertyName);
        Assert.Same(source, edit.Source);
        Assert.Same(oldValue, edit.OldValue);
        Assert.Same(newValue, edit.NewValue);
        Assert.True(edit.IsModelReference);
    }

    private static void Verify(ChangeJournal journal, string timing)
    {
        Assert.Equal(1, journal.WireVersion);
        var operations = Assert.Single(journal.Transactions).Operations;
        Assert.All(operations, operation => Assert.Empty(Assert.IsType<JournalSet>(operation).Created));
        Assert.Equal(Expected(timing), operations.Select(operation => operation.Target + "." + operation.Property));
        var original = Checkpoint();
        var restored = JournalReplayer.Replay(original, Binding, CheckpointId, journal, ModelSnapshot.Registry);
        Assert.Null(restored.Nodes[0].Peer);
        Assert.Null(restored.Nodes[3].Peer);
        Assert.Same(restored.Nodes[1], restored.Nodes[2].Peer);
        Assert.Same(restored.Nodes[2], restored.Nodes[1].Peer);
        Assert.Equal("c#observed", restored.Nodes[2].Id);
        Assert.Same(original.Nodes[1], original.Nodes[0].Peer);
        Assert.Same(original.Nodes[3], original.Nodes[2].Peer);
        Assert.Equal("c", original.Nodes[2].Id);
    }

    private static ReciprocalGraph Checkpoint()
    {
        var a = new Endpoint { Id = "a" };
        var b = new Endpoint { Id = "b" };
        var c = new Endpoint { Id = "c" };
        var d = new Endpoint { Id = "d" };
        a.Peer = b;
        c.Peer = d;
        return ReciprocalGraph.NewBuilder().WithNodes([a, b, c, d]).Build();
    }
}
