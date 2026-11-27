using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class DeepCopyTests
{
    private static ModelReflectionRegistry Reflection => ModelSnapshot.Reflection;

    [Fact]
    public void DeepCopyRebuildsOwnershipAndCopiesDetachedAliasesAndCycles()
    {
        var root = Named("root");
        var child = Named("child");
        var inset = Named("inset");
        var detached = Named("detached");
        root.Children.Add(child);
        root.Inset = inset;
        root.Anchor = detached;
        detached.Anchor = root;
        root.Featured.AddRange([child, inset]);
        root.Peers.Add(detached);
        root.Trail.AddRange([child, null, detached, child, root]);

        var copy = Reflection.DeepCopy(root);
        var newChild = Assert.Single(copy.Children);
        var newInset = Assert.IsType<ArchiveNode>(copy.Inset);
        var newDetached = Assert.IsType<ArchiveNode>(copy.Anchor);
        Assert.NotSame(root, copy);
        Assert.NotSame(child, newChild);
        Assert.NotSame(inset, newInset);
        Assert.NotSame(detached, newDetached);
        Assert.Same(copy, newChild.Parent);
        Assert.Same(copy, newInset.InsetOf);
        Assert.Null(newDetached.Parent);
        Assert.Same(copy, newDetached.Anchor);
        Assert.Same(newChild, copy.Featured[0]);
        Assert.Same(newInset, copy.Featured[1]);
        Assert.Same(newDetached, Assert.Single(copy.Peers));
        Assert.Equal(new ArchiveNode?[] { newChild, null, newDetached, newChild, copy }, copy.Trail);
        newDetached.Name = "local";
        Assert.Equal("detached", detached.Name);
        Assert.Same(root, child.Parent);
        Assert.Null(Reflection.DeepCopy(child).Parent);
    }

    [Fact]
    public void AReadOnlyInputProducesAReadOnlyIndependentCopy()
    {
        var root = Named("root");
        var child = Named("child");
        root.Children.Add(child);
        root.Anchor = child;
        child.Anchor = root;
        var view = root.AsReadOnly();
        IReadOnlyArchiveNode copy = Reflection.DeepCopy(view);
        Assert.NotSame(view, copy);
        Assert.IsNotType<ArchiveNode>(copy);
        Assert.Same(copy.Children[0], copy.Anchor);
        Assert.Same(copy, copy.Anchor!.Anchor);
        Assert.Same(copy, copy.Children[0].Parent);
        Assert.Equal("root", copy.Name);
        root.Name = "edited";
        child.Name = "child edited";
        Assert.Equal("root", copy.Name);
        Assert.Equal("child", copy.Children[0].Name);
    }

    [Fact]
    public void ReferenceAndScalarKeysAreInstalledBeforeContainment()
    {
        var inventory = new KeyedInventory();
        var part = new KeyedPart { Name = "bolt" };
        var assignment = new KeyedAssignment { Part = part, Count = 5 };
        var product = new KeyedItem { Sku = "sku-a", Quantity = 2 };
        inventory.Parts.Add(part);
        inventory.Assignments.Add(assignment);
        inventory.Products.Add(product);

        var copy = Reflection.DeepCopy(inventory);
        var newPart = Assert.Single(copy.Parts);
        var newAssignment = Assert.Single(copy.Assignments);
        var newProduct = Assert.IsType<KeyedItem>(copy.FindProductsBySku("sku-a"));
        Assert.NotSame(part, newPart);
        Assert.NotSame(assignment, newAssignment);
        Assert.NotSame(product, newProduct);
        Assert.Same(newPart, newAssignment.Part);
        Assert.Same(newAssignment, copy.FindAssignmentsByPart(newPart));
        Assert.Null(copy.FindAssignmentsByPart(part));
        Assert.Equal(2, newProduct.Quantity);
    }

    [Fact]
    public void ScalarCollectionsAreIndependentAndImmutableAliasesAreShared()
    {
        var profile = new CollectionProfile();
        profile.Messages.AddRange(new string?[] { "ready", null });
        profile.Levels.AddRange([0.0, -0.0, double.NaN]);
        profile.Limits.Add("pending", null);
        profile.Markers.Add(0.0, '+');
        profile.Markers.Add(-0.0, '-');
        var copy = Reflection.DeepCopy(profile);
        Assert.Equal(new string?[] { "ready", null }, copy.Messages);
        Assert.Equal(3, copy.Levels.Count);
        Assert.All(new[] { 0.0, -0.0, double.NaN }, value => Assert.True(copy.Levels.Contains(value)));
        Assert.True(copy.Limits.ContainsKey("pending"));
        Assert.Null(copy.Limits["pending"]);
        Assert.Equal('+', copy.Markers[0.0]);
        Assert.Equal('-', copy.Markers[-0.0]);
        copy.Messages.Clear();
        copy.Limits.Clear();
        Assert.Equal(2, profile.Messages.Count);
        Assert.True(profile.Limits.ContainsKey("pending"));

        var defaults = Reflection.DeepCopy(new DefaultSettings { Name = null, Limit = null });
        Assert.Null(defaults.Name);
        Assert.Null(defaults.Limit);
        Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(defaults.Baseline));

        var value = new ValueLabel("shared");
        var selection = new ValueSelection { Current = value };
        selection.Selected.Add(value);
        var copiedSelection = Reflection.DeepCopy(selection);
        Assert.NotSame(selection, copiedSelection);
        Assert.Same(value, copiedSelection.Current);
        Assert.Same(value, Assert.Single(copiedSelection.Selected));
        Assert.Same(value, Reflection.DeepCopy(value));
    }

    private static ArchiveNode Named(string name) => ArchiveNode.NewBuilder().WithName(name).Build();
}
