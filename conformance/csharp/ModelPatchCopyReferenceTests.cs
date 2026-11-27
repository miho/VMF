using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;
using Equality = Eu.Mihosoft.Vmf.Conformance.Equality;

namespace Vmf.CSharp.Conformance;

public sealed class ModelPatchCopyReferenceTests
{
    [Fact]
    public void NewSubtreeOutboundReferencesUseTheRetainedSibling()
    {
        var source = new ArchiveNode { Name = "root" };
        var retained = new ArchiveNode { Name = "retained" };
        source.Children.Add(retained);
        var desired = ModelSnapshot.Reflection.DeepCopy(source);
        var reference = Assert.Single(desired.Children);
        var leaf = new ArchiveNode { Name = "new leaf" };
        leaf.Trail.AddRange([reference, null, reference]);
        var branch = new ArchiveNode { Name = "new branch", Anchor = reference, Inset = leaf };
        desired.Inset = branch;
        desired.Anchor = branch;

        var attachments = new List<bool>();
        using var subscription = source.VmfChanges.AddPropertyListener("inset", _ =>
        {
            if (source.Inset is { } attached)
                attachments.Add(ReferenceEquals(attached.Anchor, retained));
        });
        var patch = ModelSnapshot.Reflection.Diff(source, desired);
        Assert.Null(source.Inset);
        Assert.Same(retained, Assert.Single(source.Children));
        Assert.Empty(attachments);

        patch.Apply(source);
        var copy = Assert.IsType<ArchiveNode>(source.Inset);
        Assert.NotSame(branch, copy);
        Assert.Same(copy, source.Anchor);
        Assert.Same(retained, copy.Anchor);
        Assert.Equal(new ArchiveNode?[] { retained, null, retained }, copy.Inset!.Trail);
        Assert.Equal(new[] { true }, attachments);
        Assert.Same(reference, branch.Anchor);
        Assert.Same(reference, leaf.Trail[0]);

        patch.Undo(source);
        Assert.Null(source.Inset);
        Assert.Null(source.Anchor);
        Assert.Same(retained, Assert.Single(source.Children));
        patch.Apply(source);
        Assert.Same(copy, source.Inset);
        Assert.Same(retained, copy.Anchor);
        Assert.Same(retained, copy.Inset.Trail[0]);
    }

    [Fact]
    public void IgnoredEqualityFieldStillProducesAnEditInAnOwningOrderedSet()
    {
        var source = new Equality.EqualityContainer();
        var child = new Equality.EqualityNode { Name = "child", Note = "before" };
        source.Children.Add(child);
        var desired = Equality.ModelSnapshot.Reflection.DeepCopy(source);
        desired.Children[0].Note = "after";

        Assert.True(Equality.ModelSnapshot.Reflection.ContentEquals(source, desired));
        var patch = Equality.ModelSnapshot.Reflection.Diff(source, desired);
        var edit = Assert.Single(patch.Changes);
        Assert.Equal("note", edit.PropertyName);
        Assert.Same(child, edit.Object);

        patch.Apply(source);
        Assert.Same(child, Assert.Single(source.Children));
        Assert.Equal("after", child.Note);

        patch.Undo(source);
        Assert.Same(child, Assert.Single(source.Children));
        Assert.Equal("before", child.Note);
    }
}
