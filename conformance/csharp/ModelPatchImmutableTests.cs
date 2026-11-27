using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ModelPatchImmutableTests
{
    [Fact]
    public void ImmutableDiffReplacesReferencesAndPreservesAliases()
    {
        var previous = new ValueLabel("previous");
        var selected = new ValueLabel("selected");
        var source = new ValueSelection { Current = previous, Second = previous };
        source.Selected.Add(previous);
        var desired = new ValueSelection { Current = selected, Second = selected };
        desired.Selected.Add(selected);

        var patch = ModelSnapshot.Reflection.Diff(source, desired);
        Assert.Equal(3, patch.Changes.Count);
        Assert.All(patch.Changes, change => Assert.Same(source, change.Object));
        patch.Apply(source);
        Assert.Same(selected, source.Current);
        Assert.Same(selected, source.Second);
        Assert.Same(selected, Assert.Single(source.Selected));

        patch.Undo(source);
        Assert.Same(previous, source.Current);
        Assert.Same(previous, source.Second);
        Assert.Same(previous, Assert.Single(source.Selected));

        var sameContents = new ValueLabel("previous");
        Assert.Equal(previous, sameContents);
        Assert.NotSame(previous, sameContents);
        desired.Current = sameContents;
        desired.Second = previous;
        desired.Selected.Clear();
        desired.Selected.Add(previous);

        var converging = ModelSnapshot.Reflection.Diff(source, desired);
        Assert.Equal("current", Assert.Single(converging.Changes).PropertyName);
        converging.Apply(source);
        Assert.Same(sameContents, source.Current);
        Assert.Same(previous, source.Second);

        source.Current = new ValueLabel("previous");
        Assert.Throws<InvalidOperationException>(() => converging.Undo(source));
    }
}
