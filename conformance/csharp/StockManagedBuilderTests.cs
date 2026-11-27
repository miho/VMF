using Eu.Mihosoft.Vmf.Conformance.Generickeys;
using Groups = Eu.Mihosoft.Vmftest.Multiplicity;
using Maps = Eu.Mihosoft.Vmftest.Managedmap;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class StockManagedBuilderTests
{
    [Fact]
    public void ImportsThroughTheManagedMapBaseAndOverridesWithoutSharingStorage()
    {
        var input = new Dictionary<string, double?> { ["first"] = 1.0 };
        var builder = Maps.ManagedSettings.NewBuilder().WithScores(input);
        input.Clear();
        var model = builder.Build();
        var sibling = builder.Build();
        var scores = model.Scores;
        var view = model.AsReadOnly().Scores;
        scores.Put("second", 2.0);
        model.Ports.Put("other", 443);

        var merged = Maps.ManagedSettings.NewBuilder().WithScores([new("third", 3.0), new("first", 7.0)])
            .ApplyFrom((Maps.ScoresModel)model).Build();
        Assert.Equal(["third", "first", "second"], merged.Scores.Keys);
        Assert.Equal(1.0, merged.Scores["first"]);
        Assert.Single(merged.Ports);
        Assert.Equal(80, merged.Ports["port"]);

        builder.AppendCollections(false).ApplyTo(model);
        Assert.Same(scores, model.Scores);
        Assert.Same(view, model.AsReadOnly().Scores);
        Assert.Equal(1.0, Assert.Single(view).Value);
        Assert.Equal(1.0, Assert.Single(sibling.Scores).Value);
        Assert.Equal(80, Assert.Single(model.Ports).Value);
    }

    [Fact]
    public void ReplacementKeepsOrderPresenceAndTheJavaMapEditSequence()
    {
        var model = new Maps.ManagedSettings();
        var scores = model.Scores;
        scores.PutAll([new("drop-a", 1.0), new("drop-b", 2.0), new("keep", 3.0)]);
        var view = model.AsReadOnly().Scores;
        var changes = new List<ModelMapChange<string, double?>>();
        using var listener = view.AddListener(changes.Add);
        var builder = Maps.ManagedSettings.NewBuilder()
            .WithScores([new("keep", 3.0), new("nil", null), new("tail", 4.0)]);

        builder.AppendCollections(false).ApplyTo(model);
        Assert.Same(scores, model.Scores);
        Assert.Equal(["keep", "nil", "tail"], scores.Keys);
        Assert.True(scores.TryGetValue("nil", out var value));
        Assert.Null(value);
        Assert.Collection(changes,
            change =>
            {
                Assert.Equal("drop-a", Assert.Single(change.Removed).Key);
                Assert.Empty(change.Added);
            },
            change =>
            {
                Assert.Equal("drop-b", Assert.Single(change.Removed).Key);
                Assert.Empty(change.Added);
            },
            change =>
            {
                Assert.Empty(change.Removed);
                Assert.Equal(["nil", "tail"], change.Added.Keys);
            });
        Assert.All(changes, change => Assert.Same(view, change.Source));

        changes.Clear();
        builder.AppendCollections(true).ApplyTo(model);
        Assert.Empty(changes);
    }

    [Fact]
    public void CheckedAppendFiltersDuplicatesAndRejectsOverflowBeforeEditing()
    {
        var target = Groups.ValueGroups.NewBuilder().WithStages(["a", "b"]).Build();
        var stages = target.Stages;
        Groups.ValueGroups.NewBuilder().WithStages(["b", "c"]).ApplyTo(target);
        Assert.Equal(["a", "b", "c"], stages);
        using var listener = stages.AddListener(_ => throw new InvalidOperationException("Unexpected edit"));
        Assert.Throws<ArgumentException>(() => Groups.ValueGroups.NewBuilder().WithStages(["d"]).ApplyTo(target));
        Assert.Equal(["a", "b", "c"], stages);
        Assert.Throws<ArgumentException>(() => Groups.ValueGroups.NewBuilder().WithStages(["a", "a"]));

        var bounded = new CollectionProfile();
        bounded.Limits.PutAll([new("a", 1L), new("b", null), new("c", 3L)]);
        using var mapListener = bounded.Limits.AddListener(_ => throw new InvalidOperationException("Unexpected edit"));
        Assert.Throws<ArgumentException>(() => CollectionProfile.NewBuilder()
            .WithLimits([new("a", 7L), new("d", 4L)]).ApplyTo(bounded));
        Assert.Equal(["a", "b", "c"], bounded.Limits.Keys);
        Assert.Equal(1L, bounded.Limits["a"]);
        Assert.Null(bounded.Limits["b"]);

        var optionalKeyStorage = new IntegerIndex();
        IntegerIndex.NewBuilder()
            .WithEntries([new(3, null)]).AppendCollections(false).ApplyTo(optionalKeyStorage);
        Assert.True(optionalKeyStorage.Entries.TryGetValue(3, out var presentNull));
        Assert.Null(presentNull);
    }

    [Fact]
    public void ImportCapturesReflectedDefaultsAndKeepsBuilderStagesIndependent()
    {
        var original = new Groups.DefaultGroups();
        var reflection = Groups.ModelSnapshot.Reflection.Reflect(original);
        var tags = reflection.FindProperty("tags")!;
        var stages = reflection.FindProperty("stages")!;
        var replacement = new List<string> { "custom" };
        tags.SetDefault(replacement);
        stages.SetDefault(replacement);
        replacement.Clear();
        Assert.False(tags.IsSet);
        Assert.False(stages.IsSet);

        var builder = Groups.DefaultGroups.NewBuilder().AppendCollections(false).ApplyFrom(original);
        var first = builder.Build();
        var second = builder.WithTags(["y"]).WithStages(["z"]).Build();
        Assert.Equal("custom", Assert.Single(first.Tags));
        Assert.Equal(["custom"], first.Stages);
        Assert.Equal("y", Assert.Single(second.Tags));
        Assert.Equal(["z"], second.Stages);

        builder.AppendCollections(true).ApplyTo(original);
        Assert.Equal(["custom", "y"], original.Tags.Order());
        Assert.Equal(["custom", "z"], original.Stages);
        Assert.True(tags.IsSet);
        Assert.True(stages.IsSet);
        Assert.Equal("custom", Assert.Single(first.Tags));
    }
}
