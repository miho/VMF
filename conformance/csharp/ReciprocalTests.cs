using System.Text.Json;
using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Opposites;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ReciprocalTests
{
    private const string CheckpointId = "opposite-checkpoint-10";
    private static readonly JournalBinding Binding = new("conformance.reciprocal", 1,
        model => model is Endpoint { Id: { } id } ? id : "graph");

    [Fact]
    public void SelfReciprocalReferenceCanShareItsModelClassName()
    {
        var one = new Partner { Name = "one" };
        var two = new Partner { Name = "two" };
        var three = new Partner { Name = "three" };
        var graph = new PartnerGraph();
        graph.Partners.AddRange([one, two, three]);
        one.PartnerReference = two;
        Assert.Same(one, two.PartnerReference);
        three.PartnerReference = two;
        Assert.Same(three, two.PartnerReference);
        Assert.Null(one.PartnerReference);

        var field = ModelSnapshot.Reflection.Reflect(one).FindProperty("partner")!;
        field.Set(one);
        Assert.Same(one, one.PartnerReference);
        Assert.Same(one.AsReadOnly(), one.AsReadOnly().PartnerReference);
        Assert.Equal("partner", field.WireName);

        string snapshot = ModelSnapshot.Write(graph);
        Verify(snapshot);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_RECIPROCAL") is { } exchange)
        {
            Verify(File.ReadAllText(Path.Combine(exchange, "java-partners.json")));
            File.WriteAllText(Path.Combine(exchange, "csharp-partners.json"), snapshot);
        }

        static void Verify(string document)
        {
            using var json = JsonDocument.Parse(document);
            var first = json.RootElement.GetProperty("partners")[0];
            Assert.True(first.TryGetProperty("partner", out _));
            Assert.False(first.TryGetProperty("partnerReference", out _));
            var members = ModelSnapshot.Read<PartnerGraph>(document).Partners;
            Assert.Same(members[0], members[0]!.PartnerReference);
            Assert.Same(members[2], members[1]!.PartnerReference);
            Assert.Same(members[1], members[2]!.PartnerReference);
        }
    }

    [Fact]
    public void OccupiedScalarTransferDispatchesPrimaryAndInversePhasesAndCompletesAfterObserverErrors()
    {
        var graph = Checkpoint();
        var a = graph.Nodes[0]; var b = graph.Nodes[1]; var c = graph.Nodes[2]; var d = graph.Nodes[3];
        var other = new Endpoint { Id = "other" };
        var trace = new List<string>();
        var first = new InvalidOperationException("displaced");
        var second = new InvalidOperationException("initiating");
        using var displaced = a.VmfChanges.AddPropertyListener("peer", change =>
        {
            Assert.False(change.IsInverse);
            Assert.Null(a.Peer);
            Assert.Same(a, b.Peer);
            Assert.Same(d, c.Peer);
            trace.Add("displaced");
            throw first;
        });
        using var cleared = d.VmfChanges.AddPropertyListener("peer", change =>
        {
            Assert.True(change.IsInverse);
            Assert.Null(d.Peer);
            Assert.Same(b, c.Peer);
            Assert.Same(a, b.Peer);
            trace.Add("old inverse");
        });
        using var initiating = c.VmfChanges.AddPropertyListener("peer", change =>
        {
            Assert.False(change.IsInverse);
            Assert.Same(d, change.OldValue);
            Assert.Same(b, c.Peer);
            Assert.Same(a, b.Peer);
            Assert.Throws<ArgumentException>(() => other.Peer = b);
            Assert.Throws<ArgumentException>(() => b.Peer = other);
            Assert.Throws<ArgumentException>(() => c.Peer = other);
            trace.Add("source");
            throw second;
        });
        using var reciprocal = b.VmfChanges.AddPropertyListener("peer", change =>
        {
            Assert.True(change.IsInverse);
            Assert.Same(c, b.Peer);
            trace.Add("new inverse");
        });

        Assert.Same(first, Assert.Throws<InvalidOperationException>(() => c.Peer = b));
        Assert.Equal([second], ModelChanges.GetSuppressedObserverExceptions(first));
        Assert.Equal(["displaced", "old inverse", "source", "new inverse"], trace);
        Assert.Null(a.Peer);
        Assert.Null(d.Peer);
        Assert.Null(other.Peer);
        Assert.Same(c, b.Peer);
        Assert.Same(b, c.Peer);
        other.Peer = other;
        Assert.Same(other, other.Peer);
        other.Peer = null;
        Assert.Null(other.Peer);
    }

    [Fact]
    public void ScalarAndListMovesPreserveLogicalOldValuesAndNormalizeEachRecordingScope()
    {
        var graph = Checkpoint();
        var a = graph.Nodes[0]; var b = graph.Nodes[1]; var c = graph.Nodes[2]; var d = graph.Nodes[3];
        var other = new Endpoint();
        var trace = new List<string>();
        var clear = b.VmfChanges.AddPropertyListener("supervisor", change =>
        {
            Assert.Same(a, change.OldValue);
            if (change.IsInverse)
            {
                Assert.Null(b.Supervisor);
                trace.Add("inverse clear");
            }
            else
            {
                Assert.Same(c, b.Supervisor);
                Assert.Empty(c.Reports);
                Assert.Throws<ArgumentException>(() => other.Reports.Add(b));
                trace.Add("assign");
            }
        });
        var removal = a.VmfChanges.AddPropertyListener("reports", change =>
        {
            Assert.Null(b.Supervisor);
            Assert.Equal(0, Assert.Single(Assert.IsType<ModelCollectionChange>(change).Removed).Index);
            trace.Add("prior remove");
        });
        var attachment = c.VmfChanges.AddPropertyListener("reports", change =>
        {
            Assert.Same(c, b.Supervisor);
            Assert.True(change.IsInverse);
            trace.Add("reciprocal add");
        });
        graph.VmfChanges.Start();
        b.VmfChanges.Start();
        b.Supervisor = c;
        graph.VmfChanges.Stop();
        b.VmfChanges.Stop();
        clear.Dispose();
        removal.Dispose();
        attachment.Dispose();

        Assert.Equal(["inverse clear", "prior remove", "assign", "reciprocal add"], trace);
        Assert.Equal(["reports", "supervisor"], graph.VmfChanges.History.Select(edit => edit.PropertyName));
        Assert.Null(graph.VmfChanges.History[1].OldValue);
        Assert.Same(a, Assert.Single(b.VmfChanges.History).OldValue);
        var transaction = Assert.Single(graph.VmfChanges.Transactions);
        Assert.True(transaction.Undo());
        Assert.Equal([b, d], a.Reports);
        Assert.True(transaction.CanRedo);
        Assert.True(transaction.Redo());
        Assert.Same(c, b.Supervisor);
        Assert.Equal([d], a.Reports);
        Assert.Equal([b], c.Reports);

        c.Reports.AddRange([d, d]);
        Assert.Equal([b, d], c.Reports);
        Assert.Same(c, d.Supervisor);
        d.Supervisor = null;
        Assert.Equal([b], c.Reports);
    }

    [Fact]
    public void NonUniqueAndUniqueCollectionsMirrorTheCorrectOccurrenceAndPreserveNullSlots()
    {
        var one = new Endpoint(); var two = new Endpoint(); var three = new Endpoint();
        int inverseEdits = 0;
        using var inverse = two.VmfChanges.AddPropertyListener("members", _ => inverseEdits++);
        one.Groups.AddRange([two, null, two, three]);
        Assert.Same(one, Assert.Single(two.Members));
        one.Groups.Move(2, 0);
        Assert.Equal([two, two, null, three], one.Groups);
        Assert.Equal(1, inverseEdits);
        one.Groups.RemoveAt(0);
        Assert.True(two.Members.Contains(one));
        one.Groups.Insert(2, two);
        var removed = new List<int?[]>();
        using (one.VmfChanges.AddPropertyListener("groups", edit =>
        {
            Assert.True(edit.IsInverse);
            Assert.Equal([null, three], one.Groups);
            removed.Add(Assert.IsType<ModelCollectionChange>(edit).Removed.Select(entry => entry.Index).ToArray());
        }))
        {
            Assert.True(two.Members.Remove(one));
        }
        Assert.Equal([0, 2], Assert.Single(removed));
        Assert.Equal([null, three], one.Groups);
        Assert.Equal(2, inverseEdits);
        Assert.True(two.Members.Add(one));
        Assert.False(two.Members.Add(one));
        Assert.Equal([null, three, two], one.Groups);

        var trace = new List<string>();
        using var published = one.VmfChanges.AddPropertyListener("groups", _ => trace.Add("source"));
        one.Groups[2] = two;
        Assert.Equal(["source"], trace);
        Assert.Equal(3, inverseEdits);
        Assert.True(one.Links.Add(two));
        Assert.False(two.Panels.Add(one));
        Assert.True(two.Panels.Remove(one));
        Assert.Empty(one.Links);
    }

    [Theory]
    [InlineData("direct")]
    [InlineData("inverse")]
    public void PrimaryListCommitPrecedesAReentrantDependentSlotOnThePortableTape(string scope)
    {
        var graph = PrimaryListCheckpoint();
        var a = graph.Nodes[0];
        var b = graph.Nodes[1];
        using var observer = scope == "inverse"
            ? b.VmfChanges.AddPropertyListener("members", _ => a.Groups.Add(null))
            : a.Groups.AddListener(edit =>
            {
                if (edit.Added.Any(entry => ReferenceEquals(entry.Value, b))) a.Groups.Add(null);
            });
        using var recorder = JournalRecorder<ReciprocalGraph>.Start(graph, ModelSnapshot.Registry, Binding, CheckpointId, 10);
        a.Groups.Add(b);

        var history = graph.VmfChanges.History.Select(Assert.IsType<ModelCollectionChange>).ToArray();
        Assert.Equal(2, history.Length);
        Assert.Null(Assert.Single(history[0].Added).Value);
        Assert.Same(b, Assert.Single(history[1].Added).Value);
        var journal = recorder.Finish();
        var formats = new (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
        };
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_RECIPROCAL");
        foreach (var (format, write, read) in formats)
        {
            string wire = write(journal);
            Verify(read(wire));
            if (exchange is null) continue;
            string filename = $"reciprocal-primary-list-{scope}-journal.{format}";
            Verify(read(File.ReadAllText(Path.Combine(exchange, "java-" + filename))));
            File.WriteAllText(Path.Combine(exchange, "csharp-" + filename), wire);
        }

        static void Verify(ChangeJournal recorded)
        {
            Assert.Equal(1, recorded.WireVersion);
            var frame = Assert.Single(recorded.Transactions);
            Assert.Equal(11, frame.Sequence);
            var operations = frame.Operations.Select(Assert.IsType<JournalListEdit>).ToArray();
            Assert.Equal(["a", "a"], operations.Select(edit => edit.Target));
            Assert.All(operations, edit =>
            {
                Assert.Equal("groups", edit.Property);
                Assert.Equal(JournalListEditKind.Add, edit.Kind);
                Assert.Empty(edit.Before);
                Assert.Empty(edit.Created);
            });
            Assert.Equal([0, 1], operations.Select(edit => Assert.Single(edit.Indices)));
            Assert.Equal(("REFERENCE", "b"), (Assert.Single(operations[0].After).Kind, Assert.Single(operations[0].After).Text));
            Assert.Equal("NULL", Assert.Single(operations[1].After).Kind);
            var checkpoint = PrimaryListCheckpoint();
            var replay = JournalReplayer.Replay(checkpoint, Binding, CheckpointId, recorded, ModelSnapshot.Registry);
            var owner = replay.Nodes[0];
            var opposite = replay.Nodes[1];
            Assert.Equal([opposite, null], owner.Groups);
            Assert.True(opposite.Members.Contains(owner));
            Assert.Empty(checkpoint.Nodes[0].Groups);
        }
    }

    private static ReciprocalGraph PrimaryListCheckpoint()
    {
        var graph = new ReciprocalGraph();
        graph.Nodes.AddRange([new Endpoint { Id = "a" }, new Endpoint { Id = "b" }]);
        return graph;
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void CheckpointCopyKeepsRepeatedNullableReciprocalSlots(bool reverse)
    {
        var graph = ReciprocalCheckpoint(reverse);
        var a = Node(graph, "a");
        var b = Node(graph, "b");
        a.Groups.AddRange([b, null, b]);

        var copy = ModelSnapshot.Reflection.DeepCopy(graph);

        Assert.NotSame(a, Node(copy, "a"));
        Assert.NotSame(b, Node(copy, "b"));
        VerifyReciprocalSlots(copy, 3);
        VerifyReciprocalSlots(graph, 3);
    }

    [Theory]
    [InlineData("direct", false)]
    [InlineData("inverse", false)]
    [InlineData("direct", true)]
    [InlineData("inverse", true)]
    public void RecorderOpenedFromCommittedReciprocalCallbackStartsAfterTheAdoption(string scope, bool reverse)
    {
        var graph = ReciprocalCheckpoint(reverse);
        var a = Node(graph, "a");
        var b = Node(graph, "b");
        JournalRecorder<ReciprocalGraph>? recorder = null;
        using var recursive = graph.VmfChanges.AddRecursiveListener(_ => { });
        using var observer = scope == "inverse"
            ? b.VmfChanges.AddPropertyListener("members", _ => Open())
            : a.Groups.AddListener(change =>
            {
                if (change.Added.Any(entry => ReferenceEquals(entry.Value, b))) Open();
            });
        ChangeJournal journal;
        try
        {
            a.Groups.Add(b);
            Assert.NotNull(recorder);
            VerifyReciprocalSlots(graph, 2);
            journal = recorder.Finish();
        }
        finally
        {
            recorder?.Dispose();
        }

        var formats = new (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
        };
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_RECIPROCAL");
        foreach (var (format, write, read) in formats)
        {
            string wire = write(journal);
            Verify(read(wire));
            if (exchange is null) continue;
            string suffix = reverse ? "-reverse" : "";
            string filename = $"reciprocal-checkpoint-{scope}{suffix}-journal.{format}";
            Verify(read(File.ReadAllText(Path.Combine(exchange, "java-" + filename))));
            File.WriteAllText(Path.Combine(exchange, "csharp-" + filename), wire);
        }

        void Open()
        {
            VerifyReciprocalSlots(graph, 1);
            recorder = JournalRecorder<ReciprocalGraph>.Start(graph, ModelSnapshot.Registry, Binding, CheckpointId, 10);
            a.Groups.Add(null);
        }

        void Verify(ChangeJournal recorded)
        {
            Assert.Equal(1, recorded.WireVersion);
            var frame = Assert.Single(recorded.Transactions);
            Assert.Equal(11, frame.Sequence);
            var edit = Assert.IsType<JournalListEdit>(Assert.Single(frame.Operations));
            Assert.Equal(("a", "groups", JournalListEditKind.Add), (edit.Target, edit.Property, edit.Kind));
            Assert.Empty(edit.Created);
            Assert.Empty(edit.Before);
            Assert.Equal(1, Assert.Single(edit.Indices));
            Assert.Equal("NULL", Assert.Single(edit.After).Kind);
            var checkpoint = ReciprocalCheckpoint(reverse);
            Node(checkpoint, "a").Groups.Add(Node(checkpoint, "b"));
            var replay = JournalReplayer.Replay(checkpoint, Binding, CheckpointId, recorded, ModelSnapshot.Registry);
            VerifyReciprocalSlots(replay, 2);
            VerifyReciprocalSlots(checkpoint, 1);
        }
    }

    private static ReciprocalGraph ReciprocalCheckpoint(bool reverse)
    {
        var graph = new ReciprocalGraph();
        var a = new Endpoint { Id = "a" };
        var b = new Endpoint { Id = "b" };
        graph.Nodes.AddRange(reverse ? [b, a] : [a, b]);
        return graph;
    }

    private static Endpoint Node(ReciprocalGraph graph, string id) => graph.Nodes.Single(node => node.Id == id);

    private static void VerifyReciprocalSlots(ReciprocalGraph graph, int count)
    {
        var a = Node(graph, "a");
        var b = Node(graph, "b");
        Assert.Equal(count, a.Groups.Count);
        Assert.Same(b, a.Groups[0]);
        if (count > 1) Assert.Null(a.Groups[1]);
        if (count > 2) Assert.Same(b, a.Groups[2]);
        Assert.Same(a, Assert.Single(b.Members));
    }

    [Theory]
    [InlineData("scalar", "local")]
    [InlineData("scalar", "recursive")]
    [InlineData("scalar", "direct")]
    [InlineData("list", "local")]
    [InlineData("list", "recursive")]
    [InlineData("list", "direct")]
    public void ShiftedSourceCallbacksPreserveLiveOrderAndReplayAgainstCommittedPositions(string trigger, string scope)
    {
        bool byScalar = trigger == "scalar";
        var graph = TransferCheckpoint();
        var a = graph.Nodes[0]; var b = graph.Nodes[1]; var c = graph.Nodes[2];
        var d = graph.Nodes[3]; var e = graph.Nodes[4];
        var trace = new List<string>();
        bool entered = false;
        using var source = scope switch
        {
            "local" => a.VmfChanges.AddPropertyListener("reports", _ => EditShiftedSibling()),
            "recursive" => graph.VmfChanges.AddPropertyListener("reports", change =>
            {
                if (ReferenceEquals(a, change.Source)) EditShiftedSibling();
            }, ModelChangeScope.Recursive),
            "direct" => a.Reports.AddListener(_ => EditShiftedSibling()),
            _ => throw new ArgumentOutOfRangeException(nameof(scope)),
        };
        using var supervisor = b.VmfChanges.AddPropertyListener("supervisor", change =>
        {
            if (change.NewValue is null)
            {
                Assert.True(byScalar);
                Assert.True(change.IsInverse);
                Assert.Null(b.Supervisor);
                trace.Add("b clear");
            }
            else
            {
                Assert.Equal(!byScalar, change.IsInverse);
                Assert.Same(c, b.Supervisor);
                trace.Add("b assign");
            }
        });
        using var destination = c.VmfChanges.AddPropertyListener("reports", change =>
        {
            Assert.Equal(byScalar, change.IsInverse);
            Assert.Same(b, Assert.Single(c.Reports));
            trace.Add("destination");
        });

        using var recorder = JournalRecorder<ReciprocalGraph>.Start(graph, ModelSnapshot.Registry, Binding, CheckpointId, 10);
        if (byScalar) b.Supervisor = c;
        else c.Reports.Add(b);

        Assert.True(entered);
        Assert.Equal(byScalar ? ["b clear", "source callback", "nested returned", "b assign", "destination"]
            : new[] { "source callback", "nested returned", "b assign", "destination" }, trace);
        string finish = byScalar ? "b:supervisor" : "c:add";
        Assert.Equal(["a:set", "a:remove", finish], graph.VmfChanges.History.Select(HistoryName));
        CheckMoved(graph);

        var journal = recorder.Finish();
        var codecs = new (string Format, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
        };
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_RECIPROCAL");
        foreach (var (format, write, read) in codecs)
        {
            string wire = write(journal);
            Verify(read(wire), byScalar);
            if (exchange is null) continue;
            string filename = $"reciprocal-source-{trigger}-{scope}-journal.{format}";
            Verify(read(File.ReadAllText(Path.Combine(exchange, "java-" + filename))), byScalar);
            File.WriteAllText(Path.Combine(exchange, "csharp-" + filename), wire);
        }

        void EditShiftedSibling()
        {
            if (entered) return;
            entered = true;
            trace.Add("source callback");
            Assert.Same(d, Assert.Single(a.Reports));
            Assert.Same(byScalar ? null : c, b.Supervisor);
            a.Reports[0] = e;
            Assert.Same(e, Assert.Single(a.Reports));
            Assert.Null(d.Supervisor);
            Assert.Same(a, e.Supervisor);
            trace.Add("nested returned");
        }

        static string HistoryName(ModelChange change)
        {
            var node = (Endpoint)change.Source;
            return node.Id + ":" + (change is ModelCollectionChange edit
                ? edit.Added.Count == 0 ? "remove" : edit.Removed.Count == 0 ? "add" : "set"
                : change.PropertyName);
        }

        static void CheckMoved(ReciprocalGraph model)
        {
            var first = model.Nodes[0]; var moving = model.Nodes[1]; var next = model.Nodes[2];
            var displaced = model.Nodes[3]; var replacement = model.Nodes[4];
            Assert.Same(replacement, Assert.Single(first.Reports));
            Assert.Same(first, replacement.Supervisor);
            Assert.Null(displaced.Supervisor);
            Assert.Same(moving, Assert.Single(next.Reports));
            Assert.Same(next, moving.Supervisor);
        }

        static void Verify(ChangeJournal recorded, bool byScalar)
        {
            Assert.Equal(1, recorded.WireVersion);
            var frame = Assert.Single(recorded.Transactions);
            Assert.Equal(11, frame.Sequence);
            Assert.Collection(frame.Operations,
                operation => AssertList(operation, JournalListEditKind.Remove, "a", ["b"], []),
                operation => AssertList(operation, JournalListEditKind.Set, "a", ["d"], ["e"]),
                operation =>
                {
                    if (byScalar)
                    {
                        var edit = Assert.IsType<JournalSet>(operation);
                        Assert.Equal("b", edit.Target);
                        Assert.Equal("supervisor", edit.Property);
                        Assert.Equal("NULL", edit.Before.Kind);
                        Assert.Equal("c", edit.After.Text);
                        Assert.Empty(edit.Created);
                    }
                    else AssertList(operation, JournalListEditKind.Add, "c", [], ["b"]);
                });
            var checkpoint = TransferCheckpoint();
            var result = JournalReplayer.Replay(checkpoint, Binding, CheckpointId, recorded, ModelSnapshot.Registry);
            CheckMoved(result);
            Assert.NotSame(checkpoint.Nodes[1], result.Nodes[1]);
            Assert.Equal([checkpoint.Nodes[1], checkpoint.Nodes[3]], checkpoint.Nodes[0].Reports);
            Assert.Empty(checkpoint.Nodes[2].Reports);
        }

        static void AssertList(JournalOperation operation, JournalListEditKind kind, string owner,
            string[] before, string[] after)
        {
            var edit = Assert.IsType<JournalListEdit>(operation);
            Assert.Equal(kind, edit.Kind);
            Assert.Equal(owner, edit.Target);
            Assert.Equal("reports", edit.Property);
            Assert.Equal(0, Assert.Single(edit.Indices));
            Assert.Equal(before, edit.Before.Select(value => value.Text));
            Assert.Equal(after, edit.After.Select(value => value.Text));
            Assert.Empty(edit.Created);
        }
    }

    private static ReciprocalGraph TransferCheckpoint()
    {
        var nodes = new[] { "a", "b", "c", "d", "e" }.Select(id => new Endpoint { Id = id }).ToArray();
        var graph = ReciprocalGraph.NewBuilder().WithNodes(nodes).Build();
        nodes[0].Reports.AddRange([nodes[1], nodes[3]]);
        return graph;
    }

    [Fact]
    public void SnapshotsAndReciprocalMembershipJournalInteroperateWithJava()
    {
        var working = Checkpoint();
        string checkpoint = ModelSnapshot.Write(working);
        string journalWire = JournalJson.Write(Record(working));
        var mixed = Checkpoint();
        mixed.Nodes[0].Groups.AddRange([mixed.Nodes[2], null, mixed.Nodes[2], mixed.Nodes[3]]);
        var snapshot = ModelSnapshot.Write(mixed);
        VerifySnapshot(snapshot);
        VerifyReplay(checkpoint, journalWire);

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_RECIPROCAL");
        if (exchange is null) return;
        string javaSnapshot = File.ReadAllText(Path.Combine(exchange, "java-snapshot.json"));
        string javaCheckpoint = File.ReadAllText(Path.Combine(exchange, "java-checkpoint.json"));
        string javaJournal = File.ReadAllText(Path.Combine(exchange, "java-journal.json"));
        VerifySnapshot(javaSnapshot);
        VerifyReplay(javaCheckpoint, javaJournal);
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(javaJournal), JsonNode.Parse(journalWire)));
        File.WriteAllText(Path.Combine(exchange, "csharp-snapshot.json"), snapshot);
        File.WriteAllText(Path.Combine(exchange, "csharp-checkpoint.json"), checkpoint);
        File.WriteAllText(Path.Combine(exchange, "csharp-journal.json"), journalWire);
    }

    [Fact]
    public void DeepCopyPreservesBothEndpointsAndRepeatedNullSlotsAsAnIndependentGraph()
    {
        var original = Checkpoint();
        original.Nodes[0].Groups.AddRange([original.Nodes[2], null, original.Nodes[2], original.Nodes[3]]);
        var copied = ModelSnapshot.Reflection.DeepCopy(original);
        CheckSnapshot(copied);
        Assert.NotSame(original, copied);
        for (int index = 0; index < original.Nodes.Count; index++)
        {
            Assert.NotSame(original.Nodes[index], copied.Nodes[index]);
        }
        Assert.True(copied.Nodes[2].Members.Remove(copied.Nodes[0]));
        Assert.Equal([null, copied.Nodes[3]], copied.Nodes[0].Groups);
        Assert.Equal([original.Nodes[2], null, original.Nodes[2], original.Nodes[3]], original.Nodes[0].Groups);
    }

    private static ReciprocalGraph Checkpoint()
    {
        var a = new Endpoint { Id = "a" }; var b = new Endpoint { Id = "b" };
        var c = new Endpoint { Id = "c" }; var d = new Endpoint { Id = "d" };
        var root = new ReciprocalGraph();
        root.Nodes.AddRange([a, b, c, d]);
        a.Peer = b;
        c.Peer = d;
        a.Reports.AddRange([b, d]);
        a.Links.Add(d);
        return root;
    }

    private static ChangeJournal Record(ReciprocalGraph graph)
    {
        var a = graph.Nodes[0]; var b = graph.Nodes[1]; var c = graph.Nodes[2]; var d = graph.Nodes[3];
        using var recorder = JournalRecorder<ReciprocalGraph>.Start(graph, ModelSnapshot.Registry, Binding, CheckpointId, 10);
        c.Peer = b;
        b.Supervisor = c;
        a.Links.Add(c);
        Assert.Equal(11, Assert.IsType<JournalFrame>(recorder.PublishFrame()).Sequence);
        Assert.True(d.Panels.Remove(a));
        c.Reports.Add(d);
        return recorder.Finish();
    }

    private static void VerifySnapshot(string wire)
    {
        CheckSnapshot(ModelSnapshot.Read<ReciprocalGraph>(wire));
        var pruned = JsonNode.Parse(wire)!;
        pruned["nodes"]![1]!.AsObject().Remove("peer");
        pruned["nodes"]![2]!.AsObject().Remove("members");
        CheckSnapshot(ModelSnapshot.Read<ReciprocalGraph>(pruned.ToJsonString()));

        var inconsistentSet = JsonNode.Parse(wire)!;
        inconsistentSet["nodes"]![2]!["members"] = new JsonArray();
        var membershipError = Assert.Throws<JsonException>(() => ModelSnapshot.Read<ReciprocalGraph>(inconsistentSet.ToJsonString()));
        Assert.Contains("Conflicting reciprocal membership", Assert.IsType<InvalidOperationException>(membershipError.InnerException).Message);

        var conflictingScalar = JsonNode.Parse(wire)!;
        var conflictingNodes = conflictingScalar["nodes"]!.AsArray();
        conflictingNodes[1]!["peer"] = conflictingNodes[2]!["@vmf-id"]!.GetValue<string>();
        var scalarError = Assert.Throws<JsonException>(() => ModelSnapshot.Read<ReciprocalGraph>(conflictingScalar.ToJsonString()));
        Assert.Contains("Conflicting reciprocal scalar values", Assert.IsType<InvalidOperationException>(scalarError.InnerException).Message);
    }

    private static void CheckSnapshot(ReciprocalGraph graph)
    {
        var a = graph.Nodes[0]; var b = graph.Nodes[1]; var c = graph.Nodes[2]; var d = graph.Nodes[3];
        Assert.Equal(["a", "b", "c", "d"], graph.Nodes.Select(node => node.Id));
        Assert.Same(b, a.Peer);
        Assert.Same(a, b.Peer);
        Assert.Same(d, c.Peer);
        Assert.Same(c, d.Peer);
        Assert.Equal([b, d], a.Reports);
        Assert.Same(a, b.Supervisor);
        Assert.Same(a, d.Supervisor);
        Assert.Equal([c, null, c, d], a.Groups);
        Assert.Same(a, Assert.Single(c.Members));
        Assert.Same(a, Assert.Single(d.Members));
        Assert.Same(d, Assert.Single(a.Links));
        Assert.Same(a, Assert.Single(d.Panels));
    }

    private static void VerifyReplay(string checkpoint, string journalWire)
    {
        var original = ModelSnapshot.Read<ReciprocalGraph>(checkpoint);
        var journal = JournalJson.Read(journalWire);
        Assert.Equal(1, journal.WireVersion);
        var replayed = JournalReplayer.Replay(original, Binding, CheckpointId, journal, ModelSnapshot.Registry);
        var a = replayed.Nodes[0]; var b = replayed.Nodes[1]; var c = replayed.Nodes[2]; var d = replayed.Nodes[3];
        Assert.NotSame(a, original.Nodes[0]);
        Assert.Null(a.Peer);
        Assert.Null(d.Peer);
        Assert.Same(c, b.Peer);
        Assert.Same(b, c.Peer);
        Assert.Empty(a.Reports);
        Assert.Equal([b, d], c.Reports);
        Assert.Same(c, b.Supervisor);
        Assert.Same(c, d.Supervisor);
        Assert.Same(c, Assert.Single(a.Links));
        Assert.Same(a, Assert.Single(c.Panels));
        Assert.Empty(d.Panels);

        var stale = JsonNode.Parse(journalWire)!;
        stale["transactions"]![0]!["operations"]![0]!["before"]![0]!["text"] = "d";
        Assert.Throws<InvalidOperationException>(() => JournalReplayer.Replay(original, Binding, CheckpointId,
            JournalJson.Read(stale.ToJsonString()), ModelSnapshot.Registry));
        Assert.Same(original.Nodes[1], original.Nodes[0].Peer);
    }
}
