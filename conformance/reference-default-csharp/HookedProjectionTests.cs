using Eu.Mihosoft.Vmf.Conformance.Referencedefaults;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed partial class ProjectedDefaultsTests
{
    private static readonly string[] HookedShellOrder =
        ["hook-leaf", "accent", "ahead", "before", "numbers", "path", "summary", "zulu"];

    [Fact]
    public void ComposedConstructionHookPrecedesEveryDefaultProviderOnTheConcreteOwner()
    {
        ProjectedDefaultBindings.Events.Clear();
        var composed = new HookedProjection();
        Calls("hook", "accent", "before", "favorites", "numbers", "path", "queue", "summary", "zulu");
        Initialized(composed);
        Assert.Equal("direct:2:2:1:2:before", composed.Describe("direct"));
        var leaf = new HookedProjectionLeaf();
        Calls("hook-leaf", "accent", "ahead", "before", "favorites", "numbers", "path", "queue", "summary", "zulu");
        Initialized(leaf);
        Assert.Equal("ahead", leaf.Ahead);
        HookedProjection baseView = leaf;
        Assert.Equal("leaf:2:2:1:2:before", leaf.Describe("leaf"));
        Assert.Equal("base:3:2:1:2:before", baseView.Describe("base"));

        var staged = HookedProjectionLeaf.NewBuilder();
        Calls("favorites", "queue", "accent", "ahead", "before", "numbers", "path", "summary", "zulu");
        var first = staged.Build();
        Calls(HookedShellOrder);
        Initialized(first);
        Assert.Equal("build:2:2:1:2:before", first.Describe("build"));
        var empty = staged.WithQueue([]).WithFavorites([]).Build();
        Calls(HookedShellOrder);
        Assert.Empty(empty.Queue);
        Assert.Empty(empty.Favorites);
        Assert.Same(Captured(first, "queue"), Captured(empty, "queue"));
        Assert.Same(Captured(first, "favorites"), Captured(empty, "favorites"));
        Assert.Equal("empty:2:0:0:2:before", empty.Describe("empty"));

        var deep = ModelSnapshot.Reflection.DeepCopy(leaf);
        Calls(HookedShellOrder);
        var shallow = ModelSnapshot.Reflection.ShallowCopy(leaf);
        Calls(HookedShellOrder);
        foreach (var copy in new[] { deep, shallow })
        {
            Assert.Equal("copy:2:3:2:2:before", copy.Describe("copy"));
            Assert.Equal(["hook-queue", "first", "second"], copy.Queue.Select(member => member.Code));
            Assert.Equal(["favorite", "hook-favorite"], copy.Favorites.Select(member => member.Code).Order());
            Assert.Same(copy.Queue[1], Captured(copy, "queue")[0]);
            Assert.True(copy.Favorites.Contains(Assert.IsType<SelectionTarget>(Assert.Single(Captured(copy, "favorites")))));
        }
        Assert.NotSame(leaf.Queue[0], deep.Queue[1]);
        Assert.NotSame(Assert.Single(Captured(leaf, "favorites")), Assert.Single(Captured(deep, "favorites")));
        Assert.Same(leaf.Queue[0], shallow.Queue[1]);
        Assert.Same(Captured(leaf, "favorites"), Captured(shallow, "favorites"));
        Assert.Same(Captured(leaf, "queue"), Captured(shallow, "queue"));
        Assert.Equal("source:4:2:1:2:before", leaf.Describe("source"));
    }

    private static void Initialized(HookedProjection owner)
    {
        Assert.Equal(["first", "second"], owner.Queue.Select(member => member.Code));
        var favorite = Assert.Single(owner.Favorites);
        Assert.Equal("favorite", favorite.Code);
        Assert.Equal([2, 3], owner.Numbers);
        Assert.Equal([4, 5], owner.Summary);
        Assert.Equal("before", owner.Before);
        Assert.Equal("zulu", owner.Zulu);
        Assert.Equal("accent", owner.Accent);
        Assert.Same(owner.Queue[0], Captured(owner, "queue")[0]);
        Assert.Same(favorite, Assert.Single(Captured(owner, "favorites")));
        Assert.Same(owner.Queue[0].AsReadOnly(), Captured(owner.AsReadOnly(), "queue")[0]);
        Assert.Same(favorite.AsReadOnly(), Assert.Single(Captured(owner.AsReadOnly(), "favorites")));
    }
}
