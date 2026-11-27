using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ScalarMapObservationTests
{
    [Fact]
    public void MutationsExposeTypedEntriesAndStableProjectedSource()
    {
        var profile = new CollectionProfile();
        var limits = profile.Limits;
        var view = profile.AsReadOnly().Limits;
        var mutable = new List<ModelMapChange<string, long?>>();
        var readOnly = new List<ModelMapChange<string, long?>>();
        using var watchMap = limits.AddListener(mutable.Add);
        using var watchView = view.AddListener(readOnly.Add);

        limits.Put("existing", null);
        limits.PutAll([new("existing", 7L), new("new", null)]);
        Assert.Equal(2, mutable.Count);
        Assert.Equal(2, readOnly.Count);
        Assert.All(mutable, edit => Assert.Same(limits, edit.Source));
        Assert.All(readOnly, edit => Assert.Same(view, edit.Source));
        Assert.Empty(readOnly[0].Removed);
        Assert.True(readOnly[0].Added.TryGetValue("existing", out var first));
        Assert.Null(first);
        var batch = readOnly[1];
        Assert.True(batch.Removed.TryGetValue("existing", out var old));
        Assert.Null(old);
        Assert.False(batch.Removed.ContainsKey("new"));
        Assert.Equal(7L, batch.Added["existing"]);
        Assert.True(batch.Added.TryGetValue("new", out var presentNull));
        Assert.Null(presentNull);
        Assert.False(batch.Added.ContainsKey("absent"));

        watchView.Dispose();
        Assert.True(limits.Remove("new"));
        watchMap.Dispose();
        limits.Clear();
        Assert.Equal(3, mutable.Count);
        Assert.Equal(2, readOnly.Count);
        Assert.Null(mutable[2].Removed["new"]);
        Assert.Empty(mutable[2].Added);
        Assert.Equal(7L, batch.Added["existing"]);
    }

    [Fact]
    public void TypedEntrySnapshotsUseTheManagedKeyComparer()
    {
        var profile = new CollectionProfile();
        var events = new List<ModelMapChange<double, char?>>();
        using var listener = profile.Markers.AddListener(events.Add);
        profile.Markers.PutAll([new(0.0, 'p'), new(-0.0, 'n'), new(double.NaN, null)]);
        var added = Assert.Single(events).Added;
        Assert.Equal(3, added.Count);
        Assert.Equal('p', added[0.0]);
        Assert.Equal('n', added[-0.0]);
        Assert.True(added.TryGetValue(BitConverter.Int64BitsToDouble(0x7ff8000000000001L), out var nan));
        Assert.Null(nan);
    }

    [Fact]
    public void FailingMapListenerKeepsHistoryAndV1Replay()
    {
        var profile = new CollectionProfile();
        var checkpoint = new CollectionProfile();
        checkpoint.Limits.Put("existing", null);
        profile.Limits.Put("existing", null);
        var binding = new JournalBinding("scalar-map-observation", 1, _ => "profile");
        var expected = new InvalidOperationException("map view listener");
        var order = new List<string>();
        using var owner = profile.VmfChanges.AddPropertyListener("limits", _ => order.Add("owner"));
        using var first = profile.AsReadOnly().Limits.AddListener(_ =>
        {
            order.Add("failing view");
            throw expected;
        });
        using var second = profile.Limits.AddListener(_ => order.Add("mutable"));
        using var last = profile.AsReadOnly().Limits.AddListener(_ => order.Add("view"));
        using var recorder = JournalRecorder<CollectionProfile>.Start(profile, ModelSnapshot.Registry, binding, "checkpoint", 0);
        Assert.Same(expected, Assert.Throws<InvalidOperationException>(() =>
            profile.Limits.PutAll([new("existing", 7L), new("new", null)])));
        Assert.Equal(["owner", "failing view", "view", "mutable"], order);
        var recorded = Assert.Single(profile.VmfChanges.History);
        Assert.Equal("limits", recorded.PropertyName);
        var journal = recorder.Finish();
        Assert.Equal(1, journal.WireVersion);
        Assert.IsType<JournalMapEdit>(Assert.Single(Assert.Single(journal.Transactions).Operations));
        var replayed = JournalReplayer.Replay(checkpoint, binding, "checkpoint",
            JournalJson.Read(JournalJson.Write(journal)), ModelSnapshot.Registry);
        Assert.Equal(7L, replayed.Limits["existing"]);
        Assert.True(replayed.Limits.TryGetValue("new", out var value));
        Assert.Null(value);
        Assert.False(checkpoint.Limits.ContainsKey("new"));

        first.Dispose();
        var undone = new List<ModelMapChange<string, long?>>();
        using var watchUndo = profile.AsReadOnly().Limits.AddListener(undone.Add);
        Assert.True(recorded.Undo());
        var undo = Assert.Single(undone);
        Assert.True(undo.Added.TryGetValue("existing", out var restored));
        Assert.Null(restored);
        Assert.Equal(7L, undo.Removed["existing"]);
        Assert.Null(undo.Removed["new"]);
        Assert.False(profile.Limits.ContainsKey("new"));
    }
}
