using Eu.Mihosoft.Vmf.Conformance.Referencedefaults;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed partial class HookReferenceDefaultsTests
{
    [Fact]
    public void RootHookRunsOnTheConcreteLeafBeforeItsOwnProviders()
    {
        HookReferenceDefaultBindings.Events.Clear();
        var root = new RootHookSelection();
        Calls("hook-root", "earlier", "queue", "zulu");
        Initialized(root, root.AsReadOnly(), root.Queue, root.Earlier, root.Zulu);

        var leaf = new RootHookSelectionLeaf();
        Calls("hook-leaf", "ahead", "earlier", "queue", "zulu");
        Initialized(leaf, leaf.AsReadOnly(), leaf.Queue, leaf.Earlier, leaf.Zulu);
        Assert.Equal("early", leaf.Ahead);
        var builder = RootHookSelectionLeaf.NewBuilder();
        Calls("queue", "ahead", "earlier", "zulu");
        var built = builder.Build();
        Calls("hook-leaf", "ahead", "earlier", "zulu");
        Initialized(built, built.AsReadOnly(), built.Queue, built.Earlier, built.Zulu);
        var empty = builder.WithQueue([]).Build();
        Calls("hook-leaf", "ahead", "earlier", "zulu");
        Assert.Empty(empty.Queue);
        Assert.Same(Field(built).DefaultValue, Field(empty).DefaultValue);

        var deep = ModelSnapshot.Reflection.DeepCopy(leaf);
        Calls("hook-leaf", "ahead", "earlier", "zulu");
        var shallow = ModelSnapshot.Reflection.ShallowCopy(leaf);
        Calls("hook-leaf", "ahead", "earlier", "zulu");
        Assert.Equal(["hook-member", "seed"], deep.Queue.Select(member => member.Code));
        Assert.Equal(["hook-member", "seed"], shallow.Queue.Select(member => member.Code));
        Assert.NotSame(leaf.Queue[0], deep.Queue[1]);
        Assert.Same(deep.Queue[1], Fallback(deep));
        Assert.Same(leaf.Queue[0], shallow.Queue[1]);
        Assert.Same(Field(leaf).DefaultValue, Field(shallow).DefaultValue);
    }

    [Fact]
    public void ClosestDeclaredHookReplacesTheRootHook()
    {
        HookReferenceDefaultBindings.Events.Clear();
        var owner = new RootHookOverride();
        Calls("hook-child-local", "earlier", "queue", "zulu");
        Initialized(owner, owner.AsReadOnly(), owner.Queue, owner.Earlier, owner.Zulu);
        var builder = RootHookOverride.NewBuilder();
        Calls("queue", "earlier", "zulu");
        builder.Build();
        Calls("hook-child-local", "earlier", "zulu");
        var copy = ModelSnapshot.Reflection.DeepCopy(owner);
        Calls("hook-child-local", "earlier", "zulu");
        Assert.Equal(["hook-member", "seed"], copy.Queue.Select(member => member.Code));
        Assert.Same(copy.Queue[1], Fallback(copy));
    }

    [Fact]
    public void StandaloneHookCopiesItsUnorderedMembersAndFallback()
    {
        HookReferenceDefaultBindings.Events.Clear();
        var owner = new SingleHookSelection();
        Calls("hook-single", "earlier", "queue", "zulu");
        Initialized(owner, owner.AsReadOnly(), owner.Queue, owner.Earlier, owner.Zulu);
        var builder = SingleHookSelection.NewBuilder();
        Calls("queue", "earlier", "zulu");
        var built = builder.Build();
        Calls("hook-single", "earlier", "zulu");
        Initialized(built, built.AsReadOnly(), built.Queue, built.Earlier, built.Zulu);
        var empty = builder.WithQueue([]).Build();
        Calls("hook-single", "earlier", "zulu");
        Assert.Empty(empty.Queue);
        Assert.Same(Field(built).DefaultValue, Field(empty).DefaultValue);

        var deep = ModelSnapshot.Reflection.DeepCopy(owner);
        Calls("hook-single", "earlier", "zulu");
        var shallow = ModelSnapshot.Reflection.ShallowCopy(owner);
        Calls("hook-single", "earlier", "zulu");
        Assert.Equal(["hook-member", "seed"], deep.Queue.Select(member => member.Code).Order());
        Assert.Equal(["hook-member", "seed"], shallow.Queue.Select(member => member.Code).Order());
        Assert.False(deep.Queue.Contains(Assert.Single(owner.Queue)));
        Assert.True(deep.Queue.Contains(Assert.IsType<SelectionTarget>(Fallback(deep))));
        Assert.True(shallow.Queue.Contains(Assert.Single(owner.Queue)));
        Assert.Same(Field(owner).DefaultValue, Field(shallow).DefaultValue);
    }

    [Fact]
    public void HookedBuilderTransfersAncestorFieldsWithoutRunningTheTargetHook()
    {
        HookReferenceDefaultBindings.Events.Clear();
        var source = new RootHookSelection();
        Calls("hook-root", "earlier", "queue", "zulu");
        var imported = new SelectionTarget { Code = "imported" };
        source.Queue.Clear();
        source.Queue.Add(imported);
        source.Earlier.Clear();
        source.Earlier.Add(8);
        source.Zulu = null;

        var builder = RootHookSelectionLeaf.NewBuilder();
        Calls("queue", "ahead", "earlier", "zulu");
        builder.WithAhead("leaf-only").ApplyFrom(source);
        Calls();
        var built = builder.Build();
        Calls("hook-leaf", "ahead", "earlier", "zulu");
        Assert.Equal([4, 8], built.Earlier);
        Assert.Equal("leaf-only", built.Ahead);
        Assert.Null(built.Zulu);
        Assert.Collection(built.Queue,
            seed => Assert.Same(Fallback(built), seed),
            member => Assert.Same(imported, member));

        var target = new RootHookSelectionLeaf();
        Calls("hook-leaf", "ahead", "earlier", "queue", "zulu");
        var targetSeed = Assert.Single(target.Queue);
        builder.ApplyTo(target);
        Calls();
        Assert.Equal([4, 4, 8], target.Earlier);
        Assert.Collection(target.Queue,
            seed => Assert.Same(targetSeed, seed),
            seed => Assert.Same(built.Queue[0], seed),
            member => Assert.Same(imported, member));
        Assert.Same(targetSeed, Fallback(target));
        Assert.Equal("leaf-only", target.Ahead);
        Assert.Null(target.Zulu);

        builder.AppendCollections(false).ApplyFrom(source).ApplyTo(target);
        Calls();
        var replaced = builder.Build();
        Calls("hook-leaf", "ahead", "earlier", "zulu");
        Assert.Equal([8], replaced.Earlier);
        Assert.Equal([8], target.Earlier);
        Assert.Same(imported, Assert.Single(replaced.Queue));
        Assert.Same(imported, Assert.Single(target.Queue));
        Assert.Same(Fallback(built), Fallback(replaced));
        Assert.Same(targetSeed, Fallback(target));
        Assert.Equal("leaf-only", replaced.Ahead);
    }

    private static void Initialized(object owner, object view, IEnumerable<SelectionTarget> queue,
        IEnumerable<int> earlier, string? zulu)
    {
        var seed = Assert.Single(queue);
        Assert.Equal("seed", seed.Code);
        Assert.Same(seed, Fallback(owner));
        Assert.Same(seed.AsReadOnly(), Fallback(view));
        Assert.Equal(4, Assert.Single(earlier));
        Assert.Equal("last", zulu);
    }
}
