using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class CreationJournalTests
{
    private const string MutableCheckpoint = "archive-creation-10";
    private const string ImmutableCheckpoint = "selection-creation-30";
    private static readonly JournalBinding Mutable = new("conformance.creation-journal.mutable", 1, model => model switch
    {
        ArchiveRoot => "archive",
        ArchiveNode { Name: { } name } => name,
        _ => throw new ArgumentException("Checkpoint object needs a stable ID.")
    });
    private static readonly JournalBinding Immutable = new("conformance.creation-journal.immutable", 1, model => model switch
    {
        ValueSelection => "selection",
        ValueLabel { Text: { } text } => text,
        ValueNode { Next: null } => "fresh-tail",
        ValueNode => "fresh-chain",
        _ => throw new ArgumentException("Checkpoint value needs a stable ID.")
    });

    [Theory]
    [InlineData("inset", ModelChangeScope.Local)]
    [InlineData("inset", ModelChangeScope.Recursive)]
    [InlineData("anchor", ModelChangeScope.Local)]
    [InlineData("anchor", ModelChangeScope.Recursive)]
    [InlineData("trail", ModelChangeScope.Local)]
    [InlineData("trail", ModelChangeScope.Recursive)]
    [InlineData("peers", ModelChangeScope.Local)]
    [InlineData("peers", ModelChangeScope.Recursive)]
    public void NewReferencesRetainInitializationBeforeRecursiveObservation(string edge, ModelChangeScope scope)
    {
        var root = new ArchiveNode { Name = "root" };
        var fresh = new ArchiveNode { Name = "fresh" };
        bool ownedByEdge = edge == "inset";
        var binding = new JournalBinding("conformance.creation-journal.observer-init", 1,
            model => ((ArchiveNode)model).Name!.StartsWith("root", StringComparison.Ordinal) ? "root" : "fresh");
        using var watch = root.VmfChanges.AddPropertyListener(edge, _ =>
        {
            root.Name = "root-edited";
            fresh.Name = "fresh-edited";
            if (!ownedByEdge) root.Inset = fresh;
        }, scope);
        using var recorder = JournalRecorder<ArchiveNode>.Start(root, ModelSnapshot.Registry, binding, "observer-init", 0);
        switch (edge)
        {
            case "inset": root.Inset = fresh; break;
            case "anchor": root.Anchor = fresh; break;
            case "trail": root.Trail.Add(fresh); break;
            case "peers": root.Peers.Add(fresh); break;
        }
        // The triggering edit is recorded before the edits its listeners made (portable contract H4).
        Assert.Equal(ownedByEdge ? [edge, "name"] : new[] { edge, "name", "inset" },
            root.VmfChanges.History.Select(change => change.PropertyName));
        string wire = JournalJson.Write(recorder.Finish());
        Verify(wire);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_CREATION_JOURNAL");
        if (exchange is not null)
        {
            string name = $"{edge}-{scope.ToString().ToLowerInvariant()}-observer-init.json";
            Verify(File.ReadAllText(Path.Combine(exchange, "java-" + name)));
            File.WriteAllText(Path.Combine(exchange, "csharp-" + name), wire);
        }

        void Verify(string text)
        {
            var journal = JournalJson.Read(text);
            var frame = Assert.Single(journal.Transactions);
            Assert.Equal(1, journal.WireVersion);
            Assert.Equal(ownedByEdge ? [edge, "name"] : new[] { edge, "name", "inset" },
                frame.Operations.Select(operation => operation.Property));
            Assert.Equal("fresh", Assert.Single(frame.Operations[0].Created).Id);
            var checkpoint = new ArchiveNode { Name = "root" };
            var replay = JournalReplayer.Replay(checkpoint, binding, "observer-init", journal, ModelSnapshot.Registry);
            var child = edge switch
            {
                "inset" => replay.Inset,
                "anchor" => replay.Anchor,
                "trail" => Assert.Single(replay.Trail),
                "peers" => Assert.Single(replay.Peers),
                _ => throw new ArgumentException(edge),
            };
            Assert.Equal("fresh-edited", child?.Name);
            Assert.Same(replay.Inset, child);
            Assert.Equal("root-edited", replay.Name);
            Assert.Equal("root", checkpoint.Name);
        }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void NestedCreationMaterializesOnlyItsRequiredDefinitions(bool dependent)
    {
        var root = Checkpoint();
        var left = root.Children[0];
        var right = root.Children[1];
        var outer = new ArchiveNode { Name = "outer" };
        var inner = new ArchiveNode { Name = "inner" };
        if (dependent) outer.Inset = inner;
        var binding = new JournalBinding("conformance.creation-journal.observer-dependencies", 1,
            model => ((ArchiveNode)model).Name is "outer-edited" ? "outer" : ((ArchiveNode)model).Name!);
        using var listener = left.VmfChanges.AddPropertyListener("inset", _ =>
        {
            if (dependent)
            {
                right.Anchor = inner;
            }
            else
            {
                right.Inset = inner;
                outer.Name = "outer-edited";
            }
        });
        using var recorder = JournalRecorder<ArchiveNode>.Start(root, ModelSnapshot.Registry, binding, "observer-dependencies", 0);
        left.Inset = outer;
        // The triggering edit is recorded before the edits its listeners made (portable contract H4).
        Assert.Equal(new[] { left, right }, root.VmfChanges.History.Select(change => Assert.IsType<ArchiveNode>(change.Source)));
        string wire = JournalJson.Write(recorder.Finish());
        Verify(wire);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_CREATION_JOURNAL");
        if (exchange is not null)
        {
            string name = (dependent ? "dependent" : "independent") + "-observer-init.json";
            Verify(File.ReadAllText(Path.Combine(exchange, "java-" + name)));
            File.WriteAllText(Path.Combine(exchange, "csharp-" + name), wire);
        }

        void Verify(string text)
        {
            var journal = JournalJson.Read(text);
            Assert.Equal(1, journal.WireVersion);
            var operations = Assert.Single(journal.Transactions).Operations;
            Assert.Equal(["left", "right"], operations.Select(operation => operation.Target));
            Assert.Equal(dependent ? 2 : 1, operations[0].Created.Count);
            var replay = JournalReplayer.Replay(Checkpoint(), binding, "observer-dependencies", journal, ModelSnapshot.Registry);
            var first = Assert.IsType<ArchiveNode>(replay.Children[0].Inset);
            if (dependent)
            {
                Assert.Same(first.Inset, replay.Children[1].Anchor);
            }
            else
            {
                Assert.Equal("outer-edited", first.Name);
                Assert.Equal("inner", replay.Children[1].Inset?.Name);
            }
        }

        static ArchiveNode Checkpoint() => ArchiveNode.NewBuilder().WithName("root")
            .WithChildren([new ArchiveNode { Name = "left" }, new ArchiveNode { Name = "right" }]).Build();
    }

    [Theory]
    [InlineData("one-way")]
    [InlineData("late-one-way")]
    [InlineData("mutual")]
    [InlineData("late-tracked")]
    [InlineData("tracked")]
    public void NestedDeferredDefinitionsReserveIdsAndPreserveTheirObservedInitialState(string mode)
    {
        bool mutual = mode == "mutual";
        bool late = mutual || mode.StartsWith("late-", StringComparison.Ordinal);
        bool tracked = mode.EndsWith("tracked", StringComparison.Ordinal);
        var root = Checkpoint();
        var left = root.Children[0];
        var right = root.Children[1];
        var a = new ArchiveNode { Name = "a" };
        var b = new ArchiveNode { Name = "b" };
        var binding = new JournalBinding("conformance.creation-journal.cross-definitions", 1, value => ((ArchiveNode)value).Name!);
        using var watchLeft = left.VmfChanges.AddPropertyListener("inset", _ =>
        {
            right.Inset = b;
            if (tracked) b.Anchor = root;
            if (late) a.Anchor = mutual ? b : root;
        });
        using var watchRight = right.VmfChanges.AddPropertyListener("inset", _ => b.Anchor = a);
        using var recorder = JournalRecorder<ArchiveNode>.Start(root, ModelSnapshot.Registry, binding, "cross-definitions", 0);
        left.Inset = a;
        // The triggering edit is recorded before the edits its listeners made (portable contract H4).
        Assert.Equal(tracked ? ["left", "right", "b"] : new[] { "left", "right" },
            root.VmfChanges.History.Select(change => ((ArchiveNode)change.Source).Name));
        var recorded = recorder.Finish();
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_CREATION_JOURNAL");
        var formats = new (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read), ("xml", JournalXml.Write, JournalXml.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read),
        };
        foreach (var (format, write, read) in formats)
        {
            string wire = write(recorded);
            Verify(read(wire));
            if (exchange is null) continue;
            string filename = $"cross-{mode}.{format}";
            Verify(read(File.ReadAllText(Path.Combine(exchange, "java-" + filename))));
            File.WriteAllText(Path.Combine(exchange, "csharp-" + filename), wire);
        }

        void Verify(ChangeJournal journal)
        {
            Assert.Equal(1, journal.WireVersion);
            var operations = Assert.Single(journal.Transactions).Operations;
            Assert.Equal(tracked ? 3 : 2, operations.Count);
            Assert.Equal("left", operations[0].Target);
            Assert.Equal("right", operations[1].Target);
            Assert.Equal(mutual ? ["b", "a"] : new[] { "a" }, operations[0].Created.Select(definition => definition.Id));
            Assert.Equal(mutual ? 0 : 1, operations[1].Created.Count);
            if (tracked)
            {
                var edit = Assert.IsType<JournalSet>(operations[2]);
                Assert.Equal("b", edit.Target);
                Assert.Equal("anchor", edit.Property);
                Assert.Equal(("REFERENCE", "a"), (edit.Before.Kind, edit.Before.Text));
                Assert.Equal(("REFERENCE", "root"), (edit.After.Kind, edit.After.Text));
            }
            var replay = JournalReplayer.Replay(Checkpoint(), binding, "cross-definitions", journal, ModelSnapshot.Registry);
            var first = Assert.IsType<ArchiveNode>(replay.Children[0].Inset);
            var second = Assert.IsType<ArchiveNode>(replay.Children[1].Inset);
            Assert.Same(mutual ? second : late ? replay : null, first.Anchor);
            Assert.Same(tracked ? replay : first, second.Anchor);
            Assert.Same(replay.Children[0], first.InsetOf);
            Assert.Same(replay.Children[1], second.InsetOf);
        }

        static ArchiveNode Checkpoint() => ArchiveNode.NewBuilder().WithName("root")
            .WithChildren([new ArchiveNode { Name = "left" }, new ArchiveNode { Name = "right" }]).Build();
    }

    [Fact]
    public void NewMutableObjectsRestoreTheirInitialStateAndLaterEdits()
    {
        var archive = MutableBaseline();
        string checkpoint = ModelSnapshot.Write(archive);
        var journal = RecordMutable(archive);
        Assert.Equal(1, journal.WireVersion);
        Assert.Equal(["new-right", "new-left"], journal.Transactions[0].Operations[0].Created.Select(record => record.Id));
        Exchange("mutable", checkpoint, journal, VerifyMutable);
    }

    [Fact]
    public void ImmutableCreationsPreserveAliasesAndAllowForwardDependencies()
    {
        var selection = ImmutableBaseline();
        string checkpoint = ModelSnapshot.Write(selection);
        var journal = RecordImmutable(selection);
        Assert.Equal(1, journal.WireVersion);
        Assert.Equal(["fresh", "fresh-tail", "fresh-chain"],
            journal.Transactions[0].Operations[0].Created.Select(record => record.Id));
        Exchange("immutable", checkpoint, journal, VerifyImmutable);

        var reversed = JsonNode.Parse(JournalJson.Write(journal))!;
        var operation = reversed["transactions"]![0]!["operations"]![0]!;
        var created = operation["created"]!.AsArray();
        operation["created"] = new JsonArray(created.Reverse().Select(item => item?.DeepClone()).ToArray());
        VerifyImmutable(checkpoint, JournalJson.Read(reversed.ToJsonString()));
    }

    [Fact]
    public void CyclicImmutableDefinitionsAndReusedCreationIdsLeaveCheckpointUnchanged()
    {
        var original = ImmutableBaseline();
        string baseline = ModelSnapshot.Write(original);
        var journal = RecordImmutable(ImmutableBaseline());
        var cycle = JsonNode.Parse(JournalJson.Write(journal))!;
        var creations = cycle["transactions"]![0]!["operations"]![0]!["created"]!.AsArray();
        var tail = creations.Single(item => (string?)item!["id"] == "fresh-tail")!;
        tail["properties"]!["next"]![0]!["kind"] = "IMMUTABLE_REFERENCE";
        tail["properties"]!["next"]![0]!["text"] = "fresh-chain";
        var invalid = JournalJson.Read(cycle.ToJsonString());
        Assert.Throws<InvalidOperationException>(() =>
            JournalReplayer.Replay(original, Immutable, ImmutableCheckpoint, invalid, ModelSnapshot.Registry));
        Assert.Equal(baseline, ModelSnapshot.Write(original));

        var reused = JsonNode.Parse(JournalJson.Write(journal))!;
        reused["transactions"]![0]!["operations"]![0]!["created"]![0]!["id"] = "baseline";
        var duplicate = JournalJson.Read(reused.ToJsonString());
        Assert.Throws<InvalidOperationException>(() =>
            JournalReplayer.Replay(original, Immutable, ImmutableCheckpoint, duplicate, ModelSnapshot.Registry));
        Assert.Same(original.Current, original.Second);
        Assert.Equal("baseline", original.Current?.Text);
        Assert.Null(original.First);
    }

    private static ArchiveRoot MutableBaseline()
    {
        var main = ArchiveNode.NewBuilder().WithName("main").Build();
        return ArchiveRoot.NewBuilder().WithHead(main).WithReview([main, null]).Build();
    }

    private static ChangeJournal RecordMutable(ArchiveRoot archive)
    {
        var main = Assert.IsType<ArchiveNode>(archive.Head);
        using var recorder = JournalRecorder<ArchiveRoot>.Start(
            archive, ModelSnapshot.Registry, Mutable, MutableCheckpoint, 10);
        var left = ArchiveNode.NewBuilder().WithName("new-left").Build();
        var right = ArchiveNode.NewBuilder().WithName("new-right").Build();
        left.Anchor = right;
        main.Children.AddRange([left, right]);
        right.Anchor = left;
        main.Trail.AddRange([left, null, left]);
        Assert.Equal(11, Assert.IsType<JournalFrame>(recorder.PublishFrame()).Sequence);
        archive.Review.Add(right);
        main.Anchor = right;
        return recorder.Finish();
    }

    private static ValueSelection ImmutableBaseline()
    {
        var shared = new ValueLabel("baseline");
        return ValueSelection.NewBuilder().WithCurrent(shared).WithSecond(shared).Build();
    }

    private static ChangeJournal RecordImmutable(ValueSelection selection)
    {
        using var recorder = JournalRecorder<ValueSelection>.Start(
            selection, ModelSnapshot.Registry, Immutable, ImmutableCheckpoint, 30);
        var fresh = new ValueLabel("fresh");
        var tail = new ValueNode(fresh);
        var head = new ValueNode(fresh, tail);
        selection.First = head;
        selection.Current = fresh;
        selection.Second = fresh;
        return recorder.Finish();
    }

    private static void VerifyMutable(string checkpoint, ChangeJournal journal)
    {
        var original = ModelSnapshot.Read<ArchiveRoot>(checkpoint);
        var intermediate = JournalReplayer.Replay(original, Mutable, MutableCheckpoint,
            new ChangeJournal(journal.Model, journal.ModelVersion, journal.Root, journal.Checkpoint, journal.BaseSequence,
                [journal.Transactions[0]]), ModelSnapshot.Registry);
        var prefix = Assert.IsType<ArchiveNode>(intermediate.Head);
        Assert.Null(prefix.Anchor);
        Assert.Equal(2, intermediate.Review.Count);
        CheckChildren(prefix);

        var restored = JournalReplayer.Replay(original, Mutable, MutableCheckpoint, journal, ModelSnapshot.Registry);
        var main = Assert.IsType<ArchiveNode>(restored.Head);
        CheckChildren(main);
        Assert.Same(main.Children[1], main.Anchor);
        Assert.Same(main, restored.Review[0]);
        Assert.Null(restored.Review[1]);
        Assert.Same(main.Children[1], restored.Review[2]);
        Assert.Empty(Assert.IsType<ArchiveNode>(original.Head).Children);
        Assert.Equal(2, original.Review.Count);
    }

    private static void CheckChildren(ArchiveNode main)
    {
        Assert.Equal(["new-left", "new-right"], main.Children.Select(node => node.Name));
        var left = main.Children[0];
        var right = main.Children[1];
        Assert.Same(main, left.Parent);
        Assert.Same(main, right.Parent);
        Assert.Same(right, left.Anchor);
        Assert.Same(left, right.Anchor);
        Assert.Equal(3, main.Trail.Count);
        Assert.Same(left, main.Trail[0]);
        Assert.Null(main.Trail[1]);
        Assert.Same(left, main.Trail[2]);
    }

    private static void VerifyImmutable(string checkpoint, ChangeJournal journal)
    {
        var original = ModelSnapshot.Read<ValueSelection>(checkpoint);
        var restored = JournalReplayer.Replay(original, Immutable, ImmutableCheckpoint, journal, ModelSnapshot.Registry);
        var fresh = Assert.IsType<ValueLabel>(restored.Current);
        var head = Assert.IsType<ValueNode>(restored.First);
        var tail = Assert.IsType<ValueNode>(head.Next);
        Assert.Equal("fresh", fresh.Text);
        Assert.Same(fresh, restored.Second);
        Assert.Same(fresh, head.Label);
        Assert.Same(fresh, tail.Label);
        Assert.Null(tail.Next);
        Assert.Null(original.First);
        Assert.Equal("baseline", original.Current?.Text);
        Assert.Same(original.Current, original.Second);
    }

    private static void Exchange(string name, string checkpoint, ChangeJournal journal, Action<string, ChangeJournal> verify)
    {
        string wire = JournalJson.Write(journal);
        verify(checkpoint, JournalJson.Read(wire));
        string xml = JournalXml.Write(journal);
        verify(checkpoint, JournalXml.Read(xml));
        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_CREATION_JOURNAL");
        if (interchange is not null)
        {
            string javaWire = File.ReadAllText(Path.Combine(interchange, $"java-{name}-journal.json"));
            string javaBaseline = File.ReadAllText(Path.Combine(interchange, $"java-{name}-checkpoint.json"));
            var fromXml = JournalXml.Read(File.ReadAllText(Path.Combine(interchange, $"java-{name}-journal.xml")));
            verify(javaBaseline, JournalJson.Read(javaWire));
            verify(javaBaseline, fromXml);
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(javaWire), JsonNode.Parse(wire)));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(javaWire), JsonNode.Parse(JournalJson.Write(fromXml))));
            File.WriteAllText(Path.Combine(interchange, $"csharp-{name}-checkpoint.json"), checkpoint);
            File.WriteAllText(Path.Combine(interchange, $"csharp-{name}-journal.json"), wire);
            File.WriteAllText(Path.Combine(interchange, $"csharp-{name}-journal.xml"), xml);
        }
    }
}
