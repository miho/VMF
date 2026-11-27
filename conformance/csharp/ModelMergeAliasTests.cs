using Eu.Mihosoft.Vmf.Conformance.Values;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ModelMergeAliasTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void MergeCopiesDetachedReferencesOnceAcrossFieldsAndRebindsTheirCycle(bool projected)
    {
        var template = new ArchiveNode { Name = "template" };
        var overlay = new ArchiveNode { Name = "overlay" };
        var shared = new ArchiveNode { Name = "shared" };
        overlay.Anchor = shared;
        overlay.Trail.AddRange([shared, null, shared]);
        overlay.Peers.Add(shared);
        shared.Anchor = overlay;

        var reflection = ModelSnapshot.Reflection;
        var merged = projected
            ? reflection.Merge(template.AsReadOnly(), overlay.AsReadOnly())
            : reflection.Merge(template, overlay).AsReadOnly();
        var copied = Assert.IsAssignableFrom<IReadOnlyArchiveNode>(merged.Anchor);
        Assert.NotSame(shared.AsReadOnly(), copied);
        Assert.Equal("shared", copied.Name);
        Assert.Same(merged, copied.Anchor);
        Assert.Equal(new IReadOnlyArchiveNode?[] { copied, null, copied }, merged.Trail);
        Assert.Same(copied, Assert.Single(merged.Peers));

        shared.Name = "changed after merge";
        Assert.Equal("shared", copied.Name);
        Assert.Same(overlay, shared.Anchor);
        Assert.Same(shared, overlay.Anchor);
        Assert.Same(shared, Assert.Single(overlay.Peers));
        Assert.Null(template.Anchor);
        Assert.Empty(template.Trail);
        Assert.Empty(template.Peers);
    }
}
