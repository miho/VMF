using Eu.Mihosoft.Vmftest.Containment;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class StockSingularOwningBuilderTests
{
    [Fact]
    public void ImportStagesTheOriginalSingularChildForConstructionOnly()
    {
        var source = new ContainerOne();
        var target = new ContainerOne();
        var incoming = new Element();
        var retained = new Element();
        source.Element1 = incoming;
        target.Element1 = retained;
        var sourceSlot = ModelSnapshot.Reflection.Reflect(source).FindProperty("element1")!;
        var targetSlot = ModelSnapshot.Reflection.Reflect(target).FindProperty("element1")!;
        var events = new List<string>();
        using var sourceListener = source.VmfChanges.AddPropertyListener("element1", _ => events.Add("source"));
        using var targetListener = target.VmfChanges.AddPropertyListener("element1", _ => events.Add("target"));

        var staged = ContainerOne.NewBuilder().AppendCollections(false).ApplyFrom(source);
        staged.ApplyTo(source).ApplyTo(target);
        Assert.Empty(events);
        Assert.Same(incoming, source.Element1);
        Assert.Same(retained, target.Element1);
        Assert.Same(source, incoming.ParentOne);
        Assert.Same(target, retained.ParentOne);
        Assert.Null(sourceSlot.DefaultValue);
        Assert.True(sourceSlot.IsSet);
        Assert.True(targetSlot.IsSet);

        var built = staged.Build();
        Assert.Same(incoming, built.Element1);
        Assert.Same(built, incoming.ParentOne);
        Assert.Null(source.Element1);
        Assert.False(sourceSlot.IsSet);
        Assert.True(targetSlot.IsSet);
        Assert.Equal("source", Assert.Single(events));
    }

    [Fact]
    public void ApplyingASingularSlotStageCannotIntroduceACycle()
    {
        var root = new CycleSlotNode();
        var branch = new CycleSlotNode();
        var leaf = new CycleSlotNode();
        root.Child = branch;
        branch.Child = leaf;
        var events = new List<ModelChange>();
        using var listener = branch.VmfChanges.AddLocalListener(events.Add);

        CycleSlotNode.NewBuilder().WithChild(root).ApplyTo(branch);
        Assert.Throws<ArgumentException>(() => branch.Child = root);

        Assert.Empty(events);
        Assert.Same(branch, root.Child);
        Assert.Same(leaf, branch.Child);
    }
}
