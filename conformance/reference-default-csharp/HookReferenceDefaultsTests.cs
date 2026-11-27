using Eu.Mihosoft.Vmf.Conformance.Referencedefaults;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed partial class HookReferenceDefaultsTests
{
    [Fact]
    public void InheritedHookRunsBeforeDefaultsAndCopyShellsKeepItsAddedMembership()
    {
        HookReferenceDefaultBindings.Events.Clear();
        Assert.Single(new HookSelectionBase().Queue);
        Calls("queue", "zulu");
        Initialized(new HookSelection());
        Calls("hook", "earlier", "queue", "zulu");
        var leaf = new HookSelectionLeaf();
        Initialized(leaf);
        Calls("hook", "earlier", "queue", "zulu");

        var staged = HookSelectionLeaf.NewBuilder();
        Calls("queue", "earlier", "zulu");
        var first = staged.Build();
        Initialized(first);
        Calls("hook", "earlier", "zulu");
        var empty = staged.WithQueue([]).Build();
        Calls("hook", "earlier", "zulu");
        Assert.Empty(empty.Queue);
        Assert.True(Field(empty).IsSet);
        Assert.Same(Field(first).DefaultValue, Field(empty).DefaultValue);
        Field(empty).Reset();
        Assert.Same(first.Queue[0], Assert.Single(empty.Queue));
        Calls();

        var deep = ModelSnapshot.Reflection.DeepCopy(leaf);
        Calls("hook", "earlier", "zulu");
        var shallow = ModelSnapshot.Reflection.ShallowCopy(leaf);
        Calls("hook", "earlier", "zulu");
        foreach (var copy in new[] { deep, shallow })
        {
            Assert.Equal(["hook-member", "seed"], copy.Queue.Select(member => member.Code));
            Assert.Same(copy.Queue[1], Fallback(copy));
            Assert.True(Field(copy).IsSet);
        }
        Assert.NotSame(leaf.Queue[0], deep.Queue[1]);
        Assert.Same(leaf.Queue[0], shallow.Queue[1]);
        Assert.Same(Field(leaf).DefaultValue, Field(shallow).DefaultValue);
        Calls();
    }

    private static void Initialized(HookSelection owner)
    {
        var seed = Assert.Single(owner.Queue);
        Assert.Equal("seed", seed.Code);
        Assert.Same(seed, Fallback(owner));
        Assert.Equal(4, Assert.Single(owner.Earlier));
        Assert.Equal("last", owner.Zulu);
        Assert.False(Field(owner).IsSet);
        Assert.Same(seed.AsReadOnly(), Fallback(owner.AsReadOnly()));
    }

    private static ModelProperty Field(object owner) =>
        ModelSnapshot.Reflection.Reflect(owner).FindProperty("queue")!;

    private static object Fallback(object owner) =>
        Assert.Single(Assert.IsAssignableFrom<IReadOnlyList<object>>(Field(owner).DefaultValue));

    private static void Calls(params string[] expected)
    {
        Assert.Equal(expected, HookReferenceDefaultBindings.Events);
        HookReferenceDefaultBindings.Events.Clear();
    }
}
