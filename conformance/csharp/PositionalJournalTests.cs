using System.Text.Json;
using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class PositionalJournalTests
{
    private const string CheckpointId = "archive-checkpoint-20";
    private static readonly string Fixture = Path.Combine(AppContext.BaseDirectory, "fixtures", "archive-references.json");
    private static readonly JournalBinding Binding = new("conformance.positional-journal", 1, model => model switch
    {
        ArchiveRoot => "archive",
        ArchiveNode { Name: { } name } => name,
        _ => throw new ArgumentException("Checkpoint object needs a stable ID.")
    });
    private static readonly (string Extension, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[] Formats =
    [
        ("json", JournalJson.Write, JournalJson.Read),
        ("yaml", JournalYaml.Write, JournalYaml.Read),
        ("xml", JournalXml.Write, JournalXml.Read),
    ];

    [Fact]
    public void JournalsReplayExistingReferencesAndOrderedEditsAcrossRuntimes()
    {
        var working = ModelSnapshot.Read<ArchiveRoot>(File.ReadAllText(Fixture));
        string baseline = ModelSnapshot.Write(working);
        var journal = Record(working);
        string document = JournalJson.Write(journal);
        Verify(baseline, document);

        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_POSITIONAL_JOURNAL");
        if (interchange is not null)
        {
            string javaCheckpoint = File.ReadAllText(Path.Combine(interchange, "java-checkpoint.json"));
            string javaJournal = File.ReadAllText(Path.Combine(interchange, "java-journal.json"));
            Verify(javaCheckpoint, javaJournal);
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(javaJournal), JsonNode.Parse(document)));
            File.WriteAllText(Path.Combine(interchange, "csharp-checkpoint.json"), baseline);
            File.WriteAllText(Path.Combine(interchange, "csharp-journal.json"), document);
        }
    }

    [Fact]
    public void NestedReferenceListEditsReplayWithAliasesAcrossRuntimes()
    {
        var working = ModelSnapshot.Read<ArchiveRoot>(File.ReadAllText(Fixture));
        string baseline = ModelSnapshot.Write(working);
        var head = Assert.IsType<ArchiveNode>(working.Head);
        var left = Assert.Single(head.Children);
        int start = head.Trail.Count;
        using var local = head.VmfChanges.AddPropertyListener("trail", change =>
        {
            var edit = Assert.IsType<ModelCollectionChange>(change);
            if (edit.Added[0].Index == start) head.Trail.AddRange([null, left]);
        });
        using var recorder = JournalRecorder<ArchiveRoot>.Start(working, ModelSnapshot.Registry, Binding, CheckpointId, 20);
        head.Trail.Add(left);
        Assert.Equal([start + 1, start], working.VmfChanges.History.Cast<ModelCollectionChange>()
            .Select(change => change.Added[0].Index!.Value));
        var journal = recorder.Finish();
        var original = ModelSnapshot.Read<ArchiveRoot>(baseline);
        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_POSITIONAL_JOURNAL");
        foreach (var format in Formats)
        {
            string payload = format.Write(journal);
            VerifyNestedTrail(original, format.Read(payload), start);
            if (interchange is null) continue;
            var java = format.Read(File.ReadAllText(Path.Combine(interchange, $"java-nested-trail-journal.{format.Extension}")));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(journal)), JsonNode.Parse(JournalJson.Write(java))));
            var javaCheckpoint = ModelSnapshot.Read<ArchiveRoot>(File.ReadAllText(Path.Combine(interchange, "java-checkpoint.json")));
            VerifyNestedTrail(javaCheckpoint, java, start);
            File.WriteAllText(Path.Combine(interchange, $"csharp-nested-trail-journal.{format.Extension}"), payload);
        }
    }

    private static void VerifyNestedTrail(ArchiveRoot checkpoint, ChangeJournal journal, int start)
    {
        var replayed = JournalReplayer.Replay(checkpoint, Binding, CheckpointId, journal, ModelSnapshot.Registry);
        var head = Assert.IsType<ArchiveNode>(replayed.Head);
        var suffix = head.Trail.Skip(start).ToArray();
        var left = Assert.Single(head.Children);
        Assert.Collection(suffix, first => Assert.Same(left, first), Assert.Null, last => Assert.Same(left, last));
        Assert.Equal(start, Assert.IsType<ArchiveNode>(checkpoint.Head).Trail.Count);
    }

    [Fact]
    public void NestedOwningSlotReplacementReplaysWithItsInverseAcrossRuntimes()
    {
        var working = new ArchiveNode { Name = "slot", Inset = new ArchiveNode { Name = "initial" } };
        var outer = new ArchiveNode { Name = "outer" };
        var nested = new ArchiveNode { Name = "nested" };
        var binding = Binding.WithObjectTypes(_ => "node", (_, id) => new ArchiveNode { Name = id });
        string baseline = ModelSnapshot.Write(working);
        var callbacks = new List<string>();
        using var listener = working.VmfChanges.AddPropertyListener("inset", change =>
        {
            callbacks.Add((change.NewValue as ArchiveNode)?.Name ?? "-");
            if (ReferenceEquals(change.NewValue, outer)) working.Inset = nested;
        });
        using var recorder = JournalRecorder<ArchiveNode>.Start(working, ModelSnapshot.Registry, binding, CheckpointId, 20);
        working.Inset = outer;
        Assert.Same(nested, working.Inset);
        Assert.Null(outer.InsetOf);
        Assert.Same(working, nested.InsetOf);
        Assert.Equal(["-", "outer", "-", "nested"], callbacks);
        Assert.Equal(["-", "-", "nested", "outer"], working.VmfChanges.History
            .Where(change => change.PropertyName == "inset").Select(change => (change.NewValue as ArchiveNode)?.Name ?? "-"));

        var journal = recorder.Finish();
        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_POSITIONAL_JOURNAL");
        if (interchange is not null)
            File.WriteAllText(Path.Combine(interchange, "csharp-nested-slot-checkpoint.json"), baseline);
        foreach (var format in Formats)
        {
            string payload = format.Write(journal);
            Verify(baseline, format.Read(payload));
            if (interchange is null) continue;
            var java = format.Read(File.ReadAllText(Path.Combine(interchange, $"java-nested-slot-journal.{format.Extension}")));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(journal)), JsonNode.Parse(JournalJson.Write(java))));
            Verify(File.ReadAllText(Path.Combine(interchange, "java-nested-slot-checkpoint.json")), java);
            File.WriteAllText(Path.Combine(interchange, $"csharp-nested-slot-journal.{format.Extension}"), payload);
        }

        void Verify(string checkpoint, ChangeJournal wire)
        {
            var original = ModelSnapshot.Read<ArchiveNode>(checkpoint);
            var replayed = JournalReplayer.Replay(original, binding, CheckpointId, wire, ModelSnapshot.Registry);
            var child = Assert.IsType<ArchiveNode>(replayed.Inset);
            Assert.Equal("nested", child.Name);
            Assert.Same(replayed, child.InsetOf);
            var seed = Assert.IsType<ArchiveNode>(original.Inset);
            Assert.Equal("initial", seed.Name);
            Assert.Same(original, seed.InsetOf);
        }
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void NestedReferenceMembershipReplaysWithFreshIdentitiesAcrossRuntimes(bool ordered)
    {
        var working = new ArchiveNode { Name = "references" };
        var first = new ArchiveNode { Name = "first" };
        var second = new ArchiveNode { Name = "second" };
        working.Children.AddRange([first, second]);
        string baseline = ModelSnapshot.Write(working);
        string property = ordered ? "featured" : "peers";
        Func<ArchiveNode, bool> add = ordered ? working.Featured.Add : working.Peers.Add;
        Func<ArchiveNode, bool> remove = ordered ? working.Featured.Remove : working.Peers.Remove;
        var callbacks = new List<string>();
        using var listener = working.VmfChanges.AddPropertyListener(property, change =>
        {
            var edit = Assert.IsType<ModelCollectionChange>(change);
            callbacks.Add(Label(edit));
            if (edit.Added.Any(entry => ReferenceEquals(entry.Value, first)))
            {
                remove(first);
                add(second);
            }
        });
        using var recorder = JournalRecorder<ArchiveNode>.Start(working, ModelSnapshot.Registry, Binding, CheckpointId, 20);
        add(first);
        Assert.Same(second, Assert.Single(Membership(working)));
        Assert.Equal(["+first", "-first", "+second"], callbacks);
        Assert.Equal(["-first", "+second", "+first"], working.VmfChanges.History.Cast<ModelCollectionChange>().Select(Label));
        var journal = recorder.Finish();

        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_POSITIONAL_JOURNAL");
        if (interchange is not null)
            File.WriteAllText(Path.Combine(interchange, $"csharp-nested-{property}-checkpoint.json"), baseline);
        foreach (var format in Formats)
        {
            string payload = format.Write(journal);
            Verify(baseline, format.Read(payload));
            if (interchange is null) continue;
            var java = format.Read(File.ReadAllText(Path.Combine(interchange, $"java-nested-{property}-journal.{format.Extension}")));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(journal)), JsonNode.Parse(JournalJson.Write(java))));
            Verify(File.ReadAllText(Path.Combine(interchange, $"java-nested-{property}-checkpoint.json")), java);
            File.WriteAllText(Path.Combine(interchange, $"csharp-nested-{property}-journal.{format.Extension}"), payload);
        }

        void Verify(string checkpoint, ChangeJournal wire)
        {
            var original = ModelSnapshot.Read<ArchiveNode>(checkpoint);
            var replayed = JournalReplayer.Replay(original, Binding, CheckpointId, wire, ModelSnapshot.Registry);
            Assert.Same(replayed.Children[1], Assert.Single(Membership(replayed)));
            Assert.Empty(Membership(original));
        }

        IEnumerable<ArchiveNode> Membership(ArchiveNode node) => ordered ? node.Featured : node.Peers;
        static string Label(ModelCollectionChange edit) => edit.Added.Count == 0
            ? "-" + ((ArchiveNode)edit.Removed[0].Value!).Name : "+" + ((ArchiveNode)edit.Added[0].Value!).Name;
    }

    [Fact]
    public void ReplaysExistingContainedObjectsAcrossListAndSlotEdits()
    {
        var working = ModelSnapshot.Read<ArchiveRoot>(File.ReadAllText(Fixture));
        string baseline = ModelSnapshot.Write(working);
        var head = Assert.IsType<ArchiveNode>(working.Head);
        var left = Assert.Single(head.Children);
        var right = Assert.IsType<ArchiveNode>(head.Inset);

        using var recorder = JournalRecorder<ArchiveRoot>.Start(
            working, ModelSnapshot.Registry, Binding, CheckpointId, 20);
        right.Children.Add(left);
        var moved = Assert.IsType<JournalFrame>(recorder.PublishFrame());
        Assert.Equal(["children", "children"], moved.Operations.Select(edit => edit.Property));
        right.Inset = left;
        var journal = recorder.Finish();
        Assert.Equal(["children", "inset"], journal.Transactions[1].Operations.Select(edit => edit.Property));

        var original = ModelSnapshot.Read<ArchiveRoot>(baseline);
        var replayed = JournalReplayer.Replay(original, Binding, CheckpointId, journal, ModelSnapshot.Registry);
        var restoredHead = Assert.IsType<ArchiveNode>(replayed.Head);
        var restoredRight = Assert.IsType<ArchiveNode>(restoredHead.Inset);
        var restoredLeft = Assert.IsType<ArchiveNode>(restoredRight.Inset);
        Assert.Same(restoredRight, restoredLeft.InsetOf);
        Assert.Empty(restoredHead.Children);
        Assert.Empty(restoredRight.Children);
        Assert.Same(restoredLeft, replayed.Review[0]);
        Assert.Same(restoredLeft, restoredHead.Trail[2]);
        Assert.Single(Assert.IsType<ArchiveNode>(original.Head).Children);
    }

    [Fact]
    public void ReplaysOrderedScalarSetMovesAndNullableScalarSlots()
    {
        var working = new CollectionProfile();
        working.Phases.AddRange(["one", "two"]);
        var baseline = ModelSnapshot.WritePlain(working);
        var binding = new JournalBinding("conformance.positional-scalars", 1, _ => "root");

        using var recorder = JournalRecorder<CollectionProfile>.Start(
            working, ModelSnapshot.Registry, binding, "start", 0);
        working.Phases.Move(1, 0);
        working.Messages.AddRange(["note", null, "note"]);
        var journal = JournalJson.Read(JournalJson.Write(recorder.Finish()));
        var replayed = JournalReplayer.Replay(ModelSnapshot.ReadPlain<CollectionProfile>(baseline),
            binding, "start", journal, ModelSnapshot.Registry);
        Assert.Equal(["two", "one"], replayed.Phases);
        Assert.Equal(new string?[] { "note", null, "note" }, replayed.Messages);
    }

    [Fact]
    public void JournalRejectsInvalidPositionsAndUnknownReferences()
    {
        string baseline = File.ReadAllText(Fixture);
        var working = ModelSnapshot.Read<ArchiveRoot>(baseline);
        var original = ModelSnapshot.Read<ArchiveRoot>(baseline);
        string wire = JournalJson.Write(Record(working));

        var unknownTarget = JsonNode.Parse(wire)!;
        unknownTarget["transactions"]![1]!["operations"]![0]!["after"]![0]!["text"] = "absent";
        var unknown = JournalJson.Read(unknownTarget.ToJsonString());
        Assert.Throws<InvalidOperationException>(() =>
            JournalReplayer.Replay(original, Binding, CheckpointId, unknown, ModelSnapshot.Registry));
        Assert.Null(original.Head?.Anchor);
        Assert.Equal(5, original.Head?.Trail.Count);

        var nonIncreasing = JsonNode.Parse(wire)!;
        nonIncreasing["transactions"]![0]!["operations"]![2]!["indices"] = JsonNode.Parse("[2,1]");
        Assert.Throws<JsonException>(() => JournalJson.Read(nonIncreasing.ToJsonString()));

        var stale = JsonNode.Parse(wire)!;
        stale["transactions"]![0]!["operations"]![1]!["before"]![0]!["kind"] = "REFERENCE";
        stale["transactions"]![0]!["operations"]![1]!["before"]![0]!["text"] = "main";
        var wrongOldValue = JournalJson.Read(stale.ToJsonString());
        Assert.Throws<InvalidOperationException>(() =>
            JournalReplayer.Replay(original, Binding, CheckpointId, wrongOldValue, ModelSnapshot.Registry));
        Assert.Null(original.Head?.Anchor);
    }

    private static ChangeJournal Record(ArchiveRoot working)
    {
        var head = Assert.IsType<ArchiveNode>(working.Head);
        var left = Assert.Single(head.Children);
        var right = Assert.IsType<ArchiveNode>(head.Inset);
        using var recorder = JournalRecorder<ArchiveRoot>.Start(
            working, ModelSnapshot.Registry, Binding, CheckpointId, 20);
        head.Anchor = left;
        head.Trail.RemoveAt(1);
        head.Trail.InsertRange(1, [right, null]);
        head.Featured.Move(1, 0);
        Assert.Equal(21, Assert.IsType<JournalFrame>(recorder.PublishFrame()).Sequence);

        head.Anchor = right;
        head.Trail[0] = left;
        head.Featured.RemoveAt(1);
        var journal = recorder.Finish();
        Assert.Equal([21L, 22L], journal.Transactions.Select(frame => frame.Sequence));
        return journal;
    }

    private static void Verify(string baseline, string wire)
    {
        var original = ModelSnapshot.Read<ArchiveRoot>(baseline);
        var journal = JournalJson.Read(wire);
        var first = new ChangeJournal(journal.Model, journal.ModelVersion, journal.Root, journal.Checkpoint,
            journal.BaseSequence, [journal.Transactions[0]]);
        var intermediate = JournalReplayer.Replay(original, Binding, CheckpointId, first, ModelSnapshot.Registry);
        VerifyState(intermediate, intermediateFrame: true);
        var restored = JournalReplayer.Replay(original, Binding, CheckpointId, journal, ModelSnapshot.Registry);
        VerifyState(restored, intermediateFrame: false);
        Assert.NotSame(intermediate.Head, restored.Head);
        Assert.NotSame(original.Head, restored.Head);
        Assert.Null(original.Head?.Anchor);
        Assert.Equal(5, original.Head?.Trail.Count);
    }

    private static void VerifyState(ArchiveRoot root, bool intermediateFrame)
    {
        var head = Assert.IsType<ArchiveNode>(root.Head);
        var left = Assert.Single(head.Children);
        var right = Assert.IsType<ArchiveNode>(head.Inset);
        Assert.Same(intermediateFrame ? left : right, head.Anchor);
        Assert.Equal(6, head.Trail.Count);
        Assert.Same(intermediateFrame ? right : left, head.Trail[0]);
        Assert.Same(right, head.Trail[1]);
        Assert.Null(head.Trail[2]);
        Assert.Same(left, head.Trail[3]);
        Assert.Same(right, head.Trail[4]);
        Assert.Same(head, head.Trail[5]);
        Assert.Same(head, left.Parent);
        Assert.Same(head, right.InsetOf);
        Assert.Equal(intermediateFrame ? 2 : 1, head.Featured.Count);
        Assert.Same(right, head.Featured[0]);
        if (intermediateFrame)
        {
            Assert.Same(left, head.Featured[1]);
        }
    }
}
