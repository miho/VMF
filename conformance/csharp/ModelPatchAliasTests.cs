using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ModelPatchAliasTests
{
    [Fact]
    public void SwappingOwningFieldsPreservesBothIdentitiesAndAllowsUndo()
    {
        var source = new ArchiveNode { Name = "root" };
        var left = new ArchiveNode { Name = "left" };
        var right = new ArchiveNode { Name = "right" };
        source.Children.Add(left);
        source.Inset = right;
        source.Anchor = left;
        var desired = ModelSnapshot.Reflection.DeepCopy(source);
        desired.Children.Add(desired.Inset!);
        desired.Inset = desired.Children[0];

        var patch = ModelSnapshot.Reflection.Diff(source, desired);
        Assert.DoesNotContain(patch.Changes, change => change.PropertyName == "anchor");
        Assert.Same(source, left.Parent);
        Assert.Same(source, right.InsetOf);
        patch.Apply(source, ModelPatchApplyMode.RequireBeforeValues);
        Assert.Same(left, source.Inset);
        Assert.Same(right, Assert.Single(source.Children));
        Assert.Same(left, source.Anchor);
        Assert.Same(source, left.InsetOf);
        Assert.Same(source, right.Parent);

        patch.Undo(source);
        Assert.Same(left, Assert.Single(source.Children));
        Assert.Same(right, source.Inset);
        Assert.Same(left, source.Anchor);
        Assert.Same(source, left.Parent);
        Assert.Same(source, right.InsetOf);
    }
}
