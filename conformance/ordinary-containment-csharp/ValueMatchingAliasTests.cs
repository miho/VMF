using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ValueMatchingAliasTests
{
    private const string Model = "ordinary-list-alias";
    private const string Checkpoint = "before-matching";
    private static readonly JournalBinding Binding = new(Model, 1, value => value is ValueMatchedCard card ? card.Id! : "list");

    [Theory]
    [InlineData("json")]
    [InlineData("xml")]
    [InlineData("yaml")]
    public void HeldViewRemovalsUseTheFirstValueEqualSlotAcrossV1Replay(string format)
    {
        (Func<ValueMatchedList, string> Write, Func<string, ValueMatchedList> Read) snapshots = format switch
        {
            "json" => (ModelSnapshot.Write, ModelSnapshot.Read<ValueMatchedList>),
            "xml" => (ModelSnapshot.WriteXml, ModelSnapshot.ReadXml<ValueMatchedList>),
            "yaml" => (ModelSnapshot.WriteYaml, ModelSnapshot.ReadYaml<ValueMatchedList>),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
        (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) journals = format switch
        {
            "json" => (JournalJson.Write, JournalJson.Read),
            "xml" => (JournalXml.Write, JournalXml.Read),
            "yaml" => (JournalYaml.Write, JournalYaml.Read),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
        string snapshot = snapshots.Write(CreateCheckpoint());
        string journal = journals.Write(Record(snapshots.Read(snapshot)));
        Verify(snapshot, journal);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ORDINARY_CONTAINMENT") is { } directory)
        {
            string PathFor(string author, string kind) => Path.Combine(directory, $"{author}-value-match-{kind}.{format}");
            File.WriteAllText(PathFor("csharp", "checkpoint"), snapshot);
            File.WriteAllText(PathFor("csharp", "journal"), journal);
            Verify(File.ReadAllText(PathFor("java", "checkpoint")), File.ReadAllText(PathFor("java", "journal")));
        }

        void Verify(string saved, string edits)
        {
            var fresh = snapshots.Read(saved);
            var recorded = journals.Read(edits);
            string normalized = JournalJson.Write(recorded);
            Assert.Equal(1, recorded.WireVersion);
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(Expected())), JsonNode.Parse(normalized)), normalized);
            var replayed = JournalReplayer.Replay(fresh, Binding, Checkpoint, recorded, ModelSnapshot.Registry);
            var second = Assert.IsType<ValueMatchedCard>(Assert.Single(replayed.Children));
            CheckFinal(replayed, second);
            Assert.NotSame(fresh.Children[1], second);
            Assert.Equal(2, fresh.Children.Count);
            Assert.Same(fresh.Children[0], fresh.References[0]);
        }
    }

    [Fact]
    public void SharedShallowReferenceListRetainsItsReadOnlyAliasResolver()
    {
        var first = new ValueMatchedCard { Id = "first", Caption = "same" };
        var second = new ValueMatchedCard { Id = "second", Caption = "same" };
        var source = new ValueMatchedList();
        source.References.AddRange([first, second]);
        var copy = ModelSnapshot.Reflection.ShallowCopy(source);
        Assert.Same(source.References, copy.References);
        Assert.True(copy.References.Remove(second.AsReadOnly()));
        Assert.Same(second, Assert.Single(source.References));
    }

    private static ValueMatchedList CreateCheckpoint()
    {
        var first = new ValueMatchedCard { Id = "first", Caption = "same" };
        var second = new ValueMatchedCard { Id = "second", Caption = "same" };
        return ValueMatchedList.NewBuilder().WithChildren([first, second]).WithReferences([first, null, second]).Build();
    }

    private static ChangeJournal Record(ValueMatchedList graph)
    {
        var first = graph.Children[0]!;
        var second = graph.Children[1]!;
        var held = second.AsReadOnly();
        Assert.NotSame(first, second);
        Assert.Equal(first, second);
        var removedReferences = new List<ModelListEntry<ValueMatchedCard?>>();
        var removedChildren = new List<ModelListEntry<ValueMatchedCard?>>();
        using var watchingReferences = graph.References.AddListener(change => removedReferences.AddRange(change.Removed));
        using var watchingChildren = graph.Children.AddListener(change => removedChildren.AddRange(change.Removed));
        using var recorder = JournalRecorder<ValueMatchedList>.Start(graph, ModelSnapshot.Registry, Binding, Checkpoint, 20);
        Assert.True(graph.References.Contains(held));
        Assert.Equal(0, graph.References.IndexOf(held));
        Assert.True(graph.References.Remove(held));
        Assert.Same(graph, first.Owner);
        Assert.Equal(21L, Assert.IsType<JournalFrame>(recorder.PublishFrame()).Sequence);
        Assert.True(graph.Children.Contains(held));
        Assert.Equal(0, graph.Children.IndexOf(held));
        Assert.True(graph.Children.Remove(held));
        Assert.Null(first.Owner);
        CheckFinal(graph, second);
        Assert.Equal(0, Assert.Single(removedReferences).Index);
        Assert.Same(first, removedReferences[0].Value);
        Assert.Equal(0, Assert.Single(removedChildren).Index);
        Assert.Same(first, removedChildren[0].Value);
        return recorder.Finish();
    }

    private static ChangeJournal Expected() => new(Model, 1, "list", Checkpoint, 20,
    [
        new(21, [RemoveFirst("references")]),
        new(22, [RemoveFirst("children")]),
    ]);

    private static JournalListEdit RemoveFirst(string property) =>
        new(JournalListEditKind.Remove, "list", property, [0], [JournalValue.Reference("first")], []);

    private static void CheckFinal(ValueMatchedList graph, ValueMatchedCard second)
    {
        Assert.Equal("second", second.Id);
        Assert.Same(second, Assert.Single(graph.Children));
        Assert.Same(graph, second.Owner);
        Assert.Equal(2, graph.References.Count);
        Assert.Null(graph.References[0]);
        Assert.Same(second, graph.References[1]);
    }
}
