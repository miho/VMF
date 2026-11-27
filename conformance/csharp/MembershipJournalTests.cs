using System.Text.Json;
using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class MembershipJournalTests
{
    private static readonly string Fixtures = Path.Combine(AppContext.BaseDirectory, "fixtures");
    private static readonly double NegativeZero = BitConverter.Int64BitsToDouble(long.MinValue);
    private static readonly JournalBinding Binding = new("conformance.membership-journal", 1, model => model switch
    {
        CollectionProfile => "values",
        ArchiveRoot => "archive",
        ArchiveNode { Name: { } name } => name,
        _ => throw new ArgumentException("Checkpoint object needs a stable ID.")
    });

    [Fact]
    public void StringMapJournalsPreservePresentNullReplacementsAndInsertionOrder()
    {
        const string checkpointId = "membership-v3-30";
        var working = ReadValues();
        string baseline = ModelSnapshot.WritePlain(working);
        using var recorder = JournalRecorder<CollectionProfile>.Start(
            working, ModelSnapshot.Registry, Binding, checkpointId, 30);
        working.Limits.PutAll([new("optional", long.MinValue), new("added", null)]);
        working.Limits.Remove("limit");
        var journal = recorder.Finish();
        Assert.Equal(1, journal.WireVersion);

        Exchange("string-map", baseline, journal, (checkpoint, loaded) =>
        {
            var original = ModelSnapshot.ReadPlain<CollectionProfile>(checkpoint);
            var restored = JournalReplayer.Replay(original, Binding, checkpointId, loaded, ModelSnapshot.Registry);
            Assert.Equal(["optional", "added"], restored.Limits.Keys);
            Assert.Equal(long.MinValue, restored.Limits["optional"]);
            Assert.True(restored.Limits.ContainsKey("added"));
            Assert.Null(restored.Limits["added"]);
            Assert.False(restored.Limits.ContainsKey("limit"));
            Assert.Equal(long.MaxValue, original.Limits["limit"]);
            Assert.True(original.Limits.ContainsKey("optional"));
            Assert.Null(original.Limits["optional"]);
        });
    }

    [Fact]
    public void TypedMapAndScalarSetJournalPreserveJavaEquality()
    {
        const string checkpointId = "membership-v4-40";
        var working = ReadValues();
        string baseline = ModelSnapshot.WritePlain(working);
        using var recorder = JournalRecorder<CollectionProfile>.Start(
            working, ModelSnapshot.Registry, Binding, checkpointId, 40);
        working.Levels.Remove(+0.0);
        working.Levels.Add(7.0);
        working.Markers.PutAll([new(+0.0, 'p'), new(double.NaN, null), new(double.PositiveInfinity, null)]);
        working.Markers.Remove(NegativeZero);
        var journal = recorder.Finish();
        Assert.Equal(1, journal.WireVersion);

        Exchange("typed-membership", baseline, journal, (checkpoint, loaded) =>
        {
            var original = ModelSnapshot.ReadPlain<CollectionProfile>(checkpoint);
            var restored = JournalReplayer.Replay(original, Binding, checkpointId, loaded, ModelSnapshot.Registry);
            Assert.True(restored.Levels.Contains(NegativeZero));
            Assert.False(restored.Levels.Contains(+0.0));
            Assert.True(restored.Levels.Contains(double.NaN));
            Assert.True(restored.Levels.Contains(7.0));
            Assert.Equal(3, restored.Levels.Count);
            Assert.Equal([+0.0, double.NaN, double.PositiveInfinity], restored.Markers.Keys);
            Assert.Equal('p', restored.Markers[+0.0]);
            Assert.False(restored.Markers.ContainsKey(NegativeZero));
            Assert.Null(restored.Markers[double.NaN]);
            Assert.Null(restored.Markers[double.PositiveInfinity]);
            Assert.Equal('-', original.Markers[NegativeZero]);
            Assert.True(original.Levels.Contains(+0.0));
        });

        var unsupportedVersion = JsonNode.Parse(JournalJson.Write(journal))!;
        unsupportedVersion["version"] = 3;
        Assert.Throws<JsonException>(() => JournalJson.Read(unsupportedVersion.ToJsonString()));

        var stale = ReadValues();
        stale.Markers.Remove(double.NaN);
        Assert.Throws<InvalidOperationException>(() =>
            JournalReplayer.Replay(stale, Binding, checkpointId, journal, ModelSnapshot.Registry));
        Assert.True(stale.Levels.Contains(+0.0));
        Assert.Equal('+', stale.Markers[+0.0]);
    }

    [Fact]
    public void ReferenceSetJournalResolvesMembersInTheNewGraph()
    {
        const string checkpointId = "membership-v5-50";
        var working = ModelSnapshot.Read<ArchiveRoot>(File.ReadAllText(Path.Combine(Fixtures, "archive-references.json")));
        var head = Assert.IsType<ArchiveNode>(working.Head);
        var left = Assert.Single(head.Children);
        var right = Assert.IsType<ArchiveNode>(head.Inset);
        head.Peers.Add(left);
        string baseline = ModelSnapshot.Write(working);

        using var recorder = JournalRecorder<ArchiveRoot>.Start(
            working, ModelSnapshot.Registry, Binding, checkpointId, 50);
        head.Peers.Remove(left);
        head.Peers.Add(right);
        var journal = recorder.Finish();
        Assert.Equal(1, journal.WireVersion);

        Exchange("reference-set", baseline, journal, (checkpoint, loaded) =>
        {
            var original = ModelSnapshot.Read<ArchiveRoot>(checkpoint);
            var restored = JournalReplayer.Replay(original, Binding, checkpointId, loaded, ModelSnapshot.Registry);
            var resultingHead = Assert.IsType<ArchiveNode>(restored.Head);
            Assert.Same(resultingHead.Inset, Assert.Single(resultingHead.Peers));
            var originalHead = Assert.IsType<ArchiveNode>(original.Head);
            Assert.Same(Assert.Single(originalHead.Children), Assert.Single(originalHead.Peers));
            Assert.NotSame(right, Assert.Single(resultingHead.Peers));
        });
    }

    [Fact]
    public void ScalarListJournalRecordsInitiatingEditBeforeObserverAppend()
    {
        const string checkpointId = "scalar-list-60";
        var working = ReadValues();
        working.Counts.Clear();
        working.Messages.Clear();
        string baseline = ModelSnapshot.WritePlain(working);
        using var subscription = working.VmfChanges.AddRecursiveListener(change =>
        {
            if (change is not ModelCollectionChange listEdit) return;
            if (change.PropertyName == "counts" && listEdit.Added[0].Value is 1) working.Counts.Add(2);
            if (change.PropertyName == "messages" && listEdit.Added[0].Value is "outer") working.Messages.Add("inner");
        });
        using var recorder = JournalRecorder<CollectionProfile>.Start(working, ModelSnapshot.Registry, Binding, checkpointId, 60);
        working.Counts.Add(1);
        working.Messages.Add("outer");

        Assert.Equal([1, 0, 1, 0], working.VmfChanges.History.Cast<ModelCollectionChange>()
            .Select(change => change.Added[0].Index!.Value));
        var journal = recorder.Finish();
        Assert.Equal(1, journal.WireVersion);
        var operations = Assert.Single(journal.Transactions).Operations.Cast<JournalListEdit>().ToArray();
        Assert.Equal(["counts", "counts", "messages", "messages"], operations.Select(edit => edit.Property));
        Assert.Equal([0, 1, 0, 1], operations.Select(edit => Assert.Single(edit.Indices)));

        Exchange("scalar-list", baseline, journal, (checkpoint, loaded) =>
        {
            var original = ModelSnapshot.ReadPlain<CollectionProfile>(checkpoint);
            var restored = JournalReplayer.Replay(original, Binding, checkpointId, loaded, ModelSnapshot.Registry);
            Assert.Equal([1, 2], restored.Counts);
            Assert.Equal(["outer", "inner"], restored.Messages);
            Assert.Empty(original.Counts);
            Assert.Empty(original.Messages);
        });
    }

    [Fact]
    public void DirectScalarMembershipJournalRetainsMutationOrder()
    {
        const string checkpointId = "direct-membership-70";
        var working = ReadValues();
        string baseline = ModelSnapshot.WritePlain(working);
        using var directMap = working.Limits.AddListener(change =>
        {
            if (change.Added.TryGetValue("optional", out long? value) && value == 1L) working.Limits["optional"] = 2L;
        });
        using var projectedMap = working.AsReadOnly().Markers.AddListener(change =>
        {
            if (change.Added.TryGetValue(NegativeZero, out char? value) && value == 'b') working.Markers[NegativeZero] = 'c';
        });
        using var directSet = working.Levels.AddListener(change =>
        {
            if (change.Added.Contains(7.0)) working.Levels.Remove(7.0);
        });
        using var recorder = JournalRecorder<CollectionProfile>.Start(
            working, ModelSnapshot.Registry, Binding, checkpointId, 70);
        working.Limits["optional"] = 1L;
        working.Markers[NegativeZero] = 'b';
        working.Levels.Add(7.0);

        var history = working.VmfChanges.History.Cast<ModelCollectionChange>().ToArray();
        Assert.Equal(["limits", "limits", "markers", "markers", "levels", "levels"], history.Select(edit => edit.PropertyName));
        // Each nested edit reaches native recording before its outer direct callback returns.
        Assert.Equal<object?>([2L, 1L, 'c', 'b'], history.Take(4).Select(edit => Assert.Single(edit.Added).Value));
        Assert.Empty(history[4].Added);
        Assert.Equal(7.0, Assert.Single(history[4].Removed).Value);
        Assert.Empty(history[5].Removed);
        Assert.Equal(7.0, Assert.Single(history[5].Added).Value);
        var journal = recorder.Finish();
        Assert.Equal(1, journal.WireVersion);
        Assert.Equal(6, Assert.Single(journal.Transactions).Operations.Count);

        Exchange("direct-membership", baseline, journal, (checkpoint, loaded) =>
        {
            var original = ModelSnapshot.ReadPlain<CollectionProfile>(checkpoint);
            var restored = JournalReplayer.Replay(original, Binding, checkpointId, loaded, ModelSnapshot.Registry);
            Assert.NotSame(original, restored);
            Assert.Equal(2L, restored.Limits["optional"]);
            Assert.Equal('c', restored.Markers[NegativeZero]);
            Assert.Equal('+', restored.Markers[+0.0]);
            Assert.Equal(original.Levels.Count, restored.Levels.Count);
            Assert.All(original.Levels, value => Assert.True(restored.Levels.Contains(value)));
            Assert.False(restored.Levels.Contains(7.0));
            Assert.True(original.Limits.ContainsKey("optional"));
            Assert.Null(original.Limits["optional"]);
            Assert.Equal('-', original.Markers[NegativeZero]);
        }, hasJavaWriter: false);
    }

    private static void Exchange(string scenario, string baseline, ChangeJournal journal, Action<string, ChangeJournal> verify,
        bool hasJavaWriter = true)
    {
        string wire = JournalJson.Write(journal);
        verify(baseline, JournalJson.Read(wire));
        string xml = JournalXml.Write(journal);
        verify(baseline, JournalXml.Read(xml));

        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_MEMBERSHIP_JOURNAL");
        if (interchange is not null)
        {
            if (hasJavaWriter)
            {
                string javaWire = File.ReadAllText(Path.Combine(interchange, $"java-{scenario}-journal.json"));
                string javaBaseline = File.ReadAllText(Path.Combine(interchange, $"java-{scenario}-checkpoint.json"));
                var fromXml = JournalXml.Read(File.ReadAllText(Path.Combine(interchange, $"java-{scenario}-journal.xml")));
                verify(javaBaseline, JournalJson.Read(javaWire));
                verify(javaBaseline, fromXml);
                Assert.True(JsonNode.DeepEquals(JsonNode.Parse(javaWire), JsonNode.Parse(wire)));
                Assert.True(JsonNode.DeepEquals(JsonNode.Parse(javaWire), JsonNode.Parse(JournalJson.Write(fromXml))));
            }
            File.WriteAllText(Path.Combine(interchange, $"csharp-{scenario}-checkpoint.json"), baseline);
            File.WriteAllText(Path.Combine(interchange, $"csharp-{scenario}-journal.json"), wire);
            File.WriteAllText(Path.Combine(interchange, $"csharp-{scenario}-journal.xml"), xml);
        }
    }

    private static CollectionProfile ReadValues() =>
        ModelSnapshot.ReadPlain<CollectionProfile>(File.ReadAllText(Path.Combine(Fixtures, "scalar-collections.json")));
}
