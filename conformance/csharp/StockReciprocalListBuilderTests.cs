using Eu.Mihosoft.Vmftest.Cross_ref;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class StockReciprocalListBuilderTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void ReciprocalListsKeepTheBulkPositionsAndPublishInverseEditsInOccurrenceOrder(bool append)
    {
        var source = new EntityOneC();
        var target = new EntityOneC();
        var repeated = new EntityTwoC();
        var fresh = new EntityTwoC();
        var former = new EntityTwoC();
        EntityTwoC?[] original = [repeated, null, fresh, repeated, null];
        source.Refs.AddRange(original);
        target.Refs.AddRange([former, repeated, null]);
        var targetRefs = target.Refs;
        var events = new List<string>();
        using var observeSource = Observe(source.VmfChanges, "source", events);
        using var observeTarget = Observe(target.VmfChanges, "target", events);
        using var observeRepeated = Observe(repeated.VmfChanges, "repeated", events);
        using var observeFresh = Observe(fresh.VmfChanges, "fresh", events);
        using var observeFormer = Observe(former.VmfChanges, "former", events);

        var staged = EntityOneC.NewBuilder().AppendCollections(append).ApplyFrom(source);
        Assert.Empty(events);
        Assert.Equal([source, source, target], repeated.Refs);
        staged.ApplyTo(target);
        Assert.Same(targetRefs, target.Refs);
        Assert.Equal(original, source.Refs);
        EntityTwoC?[] expected = append ? [former, repeated, null, repeated, null, fresh, repeated, null] : original;
        Assert.Equal(expected, targetRefs);
        Assert.Equal(append ? [source, source, target, target, target] : [source, source, target, target], repeated.Refs);
        Assert.Equal([source, target], fresh.Refs);
        Assert.Equal(append ? [target] : [], former.Refs);
        Assert.Equal(append ? ["repeated:+", "fresh:+", "repeated:+", "target:+"]
            : ["former:-", "repeated:-", "target:-", "repeated:+", "fresh:+", "repeated:+", "target:+"], events);

        events.Clear();
        EntityOneC.NewBuilder().AppendCollections(append).ApplyFrom(target).ApplyTo(target);
        EntityTwoC?[] sameOwner = append ? [.. expected, .. expected] : expected;
        Assert.Equal(sameOwner, targetRefs);
        Assert.Equal(append ? ["former:+", "repeated:+", "repeated:+", "fresh:+", "repeated:+", "target:+"]
            : ["repeated:-", "fresh:-", "repeated:-", "target:-", "repeated:+", "fresh:+", "repeated:+", "target:+"], events);
        Assert.Equal(append ? 8 : 4, repeated.Refs.Count);
        Assert.Equal(append ? 3 : 2, fresh.Refs.Count);
        Assert.Equal(append ? 2 : 0, former.Refs.Count);

        events.Clear();
        var built = staged.Build();
        Assert.Equal(original, built.Refs);
        Assert.Equal(original, source.Refs);
        Assert.Equal(sameOwner, targetRefs);
        Assert.Same(built, repeated.Refs[^1]);
        Assert.Same(repeated.AsReadOnly(), built.AsReadOnly().Refs[0]);
        Assert.Same(built.AsReadOnly().Refs[0], built.AsReadOnly().Refs[3]);
        Assert.Equal(["repeated:+", "fresh:+", "repeated:+"], events);
    }

    [Fact]
    public void EitherEndCanImportTheSameModelAliasTwice()
    {
        var source = new EntityTwoC();
        var target = new EntityTwoC();
        var left = new EntityOneC();
        EntityOneC?[] original = [left, null, left];
        source.Refs.AddRange(original);
        var staged = EntityTwoC.NewBuilder().ApplyFrom(source).ApplyTo(target);
        var built = staged.Build();
        Assert.Equal(original, source.Refs);
        Assert.Equal(original, target.Refs);
        Assert.Equal(original, built.Refs);
        Assert.Equal([source, source, target, target, built, built], left.Refs);
    }

    [Fact]
    public void InverseObserverCanClearTheBulkInsertWithoutLeavingStaleInverses()
    {
        var source = new EntityOneC();
        var repeated = new EntityTwoC();
        var fresh = new EntityTwoC();
        bool cleared = false;
        using var reenter = repeated.VmfChanges.AddPropertyListener("refs", change =>
        {
            if (cleared || Assert.IsType<ModelCollectionChange>(change).Added.Count == 0) return;
            cleared = true;
            source.Refs.Clear();
        });
        source.Refs.AddRange([repeated, fresh, repeated]);
        Assert.True(cleared);
        Assert.Empty(source.Refs);
        Assert.Empty(repeated.Refs);
        Assert.Empty(fresh.Refs);
    }

    private static IDisposable Observe(ModelChanges owner, string label, List<string> events) =>
        owner.AddPropertyListener("refs", change => events.Add(label +
            (Assert.IsType<ModelCollectionChange>(change).Added.Count != 0 ? ":+" : ":-")));
}
