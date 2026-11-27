using Eu.Mihosoft.Vmftest.Keyed;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class StockOwnershipBuilderTests
{
    [Fact]
    public void KeyedReplacementStagesAndMovesTheOriginalChildren()
    {
        Item moving = ItemWithSku("a"), replaced = ItemWithSku("b");
        var assignment = Assignment.NewBuilder().WithKey(moving).Build();
        var source = Catalog.NewBuilder().WithItems([moving]).WithAssignments([assignment]).Build();
        var target = Catalog.NewBuilder().WithItems([replaced]).Build();
        source.Prices.Put(1, 11L);
        target.Prices.Put(2, 22L);
        var items = target.Items;
        var prices = target.Prices;
        var events = new List<string>();
        using var sourceListener = source.VmfChanges.AddPropertyListener("items",
            change => events.Add("source:" + Operation(change)));
        using var targetListener = target.VmfChanges.AddPropertyListener("items",
            change => events.Add("target:" + Operation(change)));

        var staged = Catalog.NewBuilder().AppendCollections(false).ApplyFrom(source);
        Assert.Same(source, moving.__vmfContainerLink.Owner);
        Assert.Empty(events);
        staged.ApplyTo(source);
        Assert.Equal(["source:remove", "source:add"], events);
        Assert.Same(source, moving.__vmfContainerLink.Owner);
        events.Clear();

        staged.ApplyTo(target);
        Assert.Equal(["target:remove", "source:remove", "target:add"], events);
        Assert.Same(items, target.Items);
        Assert.Same(prices, target.Prices);
        Assert.Equal(11L, prices[1]);
        Assert.Single(prices);
        Assert.Empty(source.Items);
        Assert.Empty(source.Assignments);
        Assert.Same(moving, target.FindItemsBySku("a"));
        Assert.Same(assignment, target.FindAssignmentsByKey(moving));
        Assert.Same(target, moving.__vmfContainerLink.Owner);
        Assert.Null(replaced.__vmfContainerLink.Owner);
        Assert.Same(moving, assignment.Key);
    }

    [Fact]
    public void DuplicateOrLostKeyDoesNotChangeTheDestinationOrSource()
    {
        Item existing = ItemWithSku("same"), conflicting = ItemWithSku("same"), detached = ItemWithSku("valid");
        var target = Catalog.NewBuilder().WithItems([existing]).Build();
        var source = Catalog.NewBuilder().WithItems([conflicting]).Build();
        var events = new List<ModelChange>();
        using var listener = target.VmfChanges.AddPropertyListener("items", events.Add);
        Assert.Throws<ArgumentException>(() => Catalog.NewBuilder().ApplyFrom(target).ApplyTo(target));
        Assert.Throws<ArgumentException>(() => Catalog.NewBuilder().ApplyFrom(source).ApplyTo(target));
        var staged = Catalog.NewBuilder().WithItems([detached]).AppendCollections(false);
        detached.Sku = null;
        Assert.Throws<ArgumentException>(() => staged.ApplyTo(target));

        Assert.Empty(events);
        Assert.Same(existing, target.FindItemsBySku("same"));
        Assert.Same(conflicting, source.FindItemsBySku("same"));
        Assert.Same(source, conflicting.__vmfContainerLink.Owner);
        Assert.Same(target, existing.__vmfContainerLink.Owner);
        Assert.Null(detached.__vmfContainerLink.Owner);
    }

    [Fact]
    public void ChecksKeyedContainmentCyclesBeforeClearingTheDestination()
    {
        var root = RecursiveKeyedNode.NewBuilder().WithKey("root").Build();
        var branch = RecursiveKeyedNode.NewBuilder().WithKey("branch").Build();
        var leaf = RecursiveKeyedNode.NewBuilder().WithKey("leaf").Build();
        root.Children.Add(branch);
        branch.Children.Add(leaf);
        var events = new List<ModelChange>();
        using var listener = branch.VmfChanges.AddPropertyListener("children", events.Add);

        var staged = RecursiveKeyedNode.NewBuilder().WithChildren([root]).AppendCollections(false);
        Assert.Throws<ArgumentException>(() => staged.ApplyTo(branch));
        Assert.Empty(events);
        Assert.Same(leaf, branch.FindChildrenByKey("leaf"));
        Assert.Same(branch, leaf.__vmfContainerLink.Owner);
        Assert.Same(root, branch.__vmfContainerLink.Owner);
    }

    private static Item ItemWithSku(string sku) => Item.NewBuilder().WithSku(sku).Build();

    private static string Operation(ModelChange change)
    {
        var edit = Assert.IsType<ModelCollectionChange>(change);
        Assert.Equal(ModelChangeKind.List, edit.Kind);
        Assert.True(edit.IsContainment);
        return edit.Removed.Count > 0 ? "remove" : "add";
    }
}
