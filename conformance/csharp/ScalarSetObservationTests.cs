using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ScalarSetObservationTests
{
    [Fact]
    public void GeneratedScalarSetObserversPreserveSourceMembershipAndSnapshot()
    {
        var profile = new CollectionProfile();
        var levels = profile.Levels;
        var view = profile.AsReadOnly().Levels;
        var mutable = new List<ModelSetChange<double>>();
        var projected = new List<ModelSetChange<double>>();
        using var watchView = view.AddListener(projected.Add);
        using var watchLevels = levels.AddListener(mutable.Add);

        Assert.True(levels.AddRange([0.0, -0.0, double.NaN, BitConverter.Int64BitsToDouble(0x7ff8000000000001L)]));
        Assert.False(levels.Add(double.NaN));
        Assert.Same(levels, Assert.Single(mutable).Source);
        var change = Assert.Single(projected);
        Assert.Same(view, change.Source);
        Assert.Empty(change.Removed);
        Assert.Equal(3, change.Added.Count);
        Assert.True(change.Added.Contains(0.0));
        Assert.True(change.Added.Contains(-0.0));
        Assert.True(change.Added.Contains(double.NaN));

        watchView.Dispose();
        Assert.True(levels.Remove(0.0));
        watchLevels.Dispose();
        levels.Clear();
        Assert.Equal(2, mutable.Count);
        Assert.Single(projected);
        Assert.Equal(3, change.Added.Count);
        Assert.Empty(mutable[1].Added);
        Assert.Single(mutable[1].Removed);
        Assert.True(mutable[1].Removed.Contains(0.0));
        Assert.False(mutable[1].Removed.Contains(-0.0));
    }

    [Fact]
    public void SetViewObserverFailureIsReportedAfterTheModelAndLaterListeners()
    {
        var profile = new CollectionProfile();
        var levels = profile.Levels;
        var view = profile.AsReadOnly().Levels;
        var expected = new InvalidOperationException("read-only set listener");
        var order = new List<string>();
        using var first = view.AddListener(_ =>
        {
            order.Add("failing view");
            throw expected;
        });
        using var next = view.AddListener(_ => order.Add("view"));
        using var source = levels.AddListener(_ => order.Add("mutable"));
        var changes = profile.VmfChanges;
        using var owner = changes.AddPropertyListener("levels", _ => order.Add("owner"));
        changes.Start();
        Assert.Same(expected, Assert.Throws<InvalidOperationException>(() => levels.Add(1.0)));
        changes.Stop();
        Assert.Equal(["mutable", "owner", "failing view", "view"], order);
        Assert.Contains(1.0, levels);
        Assert.Equal("levels", Assert.Single(changes.History).PropertyName);
    }
}
