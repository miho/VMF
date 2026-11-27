using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;
using OtherModel = Eu.Mihosoft.Vmf.Conformance.Multiplebases;

namespace Vmf.CSharp.Conformance;

public class ScalarCollectionApplyTests
{
    private const string Checkpoint = "collection-apply-0";
    private static readonly double NegativeZero = BitConverter.Int64BitsToDouble(long.MinValue);
    private static readonly JournalBinding Binding = new("conformance.collection-apply", 1, _ => "peer");

    [Fact]
    public void WritablePeerRecordsItsOwnValuesAndReplaysBothProducers()
    {
        var (map, set) = SourceEdits();
        var peer = Peer();
        var events = new List<ModelChange>();
        using var limits = peer.VmfChanges.AddPropertyListener("limits", events.Add);
        using var levels = peer.VmfChanges.AddPropertyListener("levels", events.Add);
        Assert.Contains("\"peer_limits\"", ModelSnapshot.WritePlain(peer));
        using var recorder = JournalRecorder<CollectionApplyPeer>.Start(peer, ModelSnapshot.Registry, Binding, Checkpoint, 0);

        map.Apply(peer);
        set.Apply(peer);
        CheckFinal(peer);
        Assert.Collection(events,
            change =>
            {
                Assert.Same(peer, change.Source);
                var entry = Assert.Single(Assert.IsType<ModelCollectionChange>(change).Removed);
                Assert.Equal("keep", entry.Key);
                Assert.Equal(99L, entry.Value);
            },
            change =>
            {
                Assert.Same(peer, change.Source);
                Assert.Equal(ModelChangeKind.Set, change.Kind);
            });
        var journal = recorder.Finish();
        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_COLLECTIONS");
        (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[] formats =
        [
            ("json", JournalJson.Write, JournalJson.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read)
        ];
        foreach (var (name, write, read) in formats)
        {
            string wire = write(journal);
            Replay(read(wire));
            if (directory is null) continue;
            File.WriteAllText(Path.Combine(directory, $"csharp-collection-apply.{name}"), wire);
            var java = read(File.ReadAllText(Path.Combine(directory, $"java-collection-apply.{name}")));
            Assert.Equal(JournalJson.Write(journal), JournalJson.Write(java));
            Replay(java);
        }
    }

    [Fact]
    public void WritablePeerCanBelongToAnotherGeneratedModelRegistry()
    {
        var (map, set) = SourceEdits();
        var peer = new OtherModel.JoinedCollections();
        peer.Limits["keep"] = 99;
        peer.Levels.Add(0.0);
        map.Apply(peer);
        set.Apply(peer);
        Assert.Equal(20L, peer.Limits["keep"]);
        Assert.True(peer.Limits.TryGetValue("optional", out var optional));
        Assert.Null(optional);
        Assert.True(peer.Levels.Contains(0.0) && peer.Levels.Contains(NegativeZero) && peer.Levels.Contains(double.NaN));
    }

    [Fact]
    public void IncompatibleWritableTypesBoundsAndReadOnlyTargetsAreRejectedBeforeChanges()
    {
        var (map, set) = SourceEdits();
        var wrong = new CollectionApplyIncompatible();
        wrong.Limits["keep"] = "original";
        wrong.Levels.Add(true);
        Assert.Throws<ArgumentException>(() => map.Apply(wrong));
        Assert.Throws<ArgumentException>(() => set.Apply(wrong));
        Assert.Equal("original", Assert.Single(wrong.Limits).Value);
        Assert.True(Assert.Single(wrong.Levels));

        var full = Peer();
        full.Limits["extra"] = 8;
        full.Levels.AddRange([1, 2]);
        using var limits = full.VmfChanges.AddPropertyListener("limits", _ => throw new InvalidOperationException("Uncommitted map edit."));
        using var levels = full.VmfChanges.AddPropertyListener("levels", _ => throw new InvalidOperationException("Uncommitted set edit."));
        Assert.Throws<ArgumentException>(() => map.Apply(full));
        Assert.Throws<ArgumentException>(() => set.Apply(full));
        Assert.Equal(99L, full.Limits["keep"]);
        Assert.Equal(8L, full.Limits["extra"]);
        Assert.Equal(2, full.Limits.Count);
        Assert.Equal(3, full.Levels.Count);
        Assert.False(full.Levels.Contains(NegativeZero));
        Assert.False(full.Levels.Contains(double.NaN));

        var peer = Peer();
        Assert.Throws<ArgumentException>(() => map.Apply(peer.AsReadOnly()));
        Assert.Throws<ArgumentException>(() => set.Apply(peer.AsReadOnly()));
        Assert.Equal(99L, Assert.Single(peer.Limits).Value);
        Assert.Equal(0.0, Assert.Single(peer.Levels));
    }

    private static (ModelChange Map, ModelChange Set) SourceEdits()
    {
        var source = new CollectionProfile();
        source.Limits["keep"] = 10;
        source.Levels.Add(0.0);
        source.VmfChanges.Start();
        source.Limits.PutAll(new Dictionary<string, long?> { ["keep"] = 20, ["optional"] = null });
        source.Levels.AddRange([NegativeZero, double.NaN]);
        source.VmfChanges.Stop();
        return (source.VmfChanges.History[0], source.VmfChanges.History[1]);
    }

    private static void Replay(ChangeJournal journal)
    {
        Assert.Equal(1, journal.WireVersion);
        Assert.Collection(Assert.Single(journal.Transactions).Operations, operation =>
        {
            var map = Assert.IsType<JournalMapEdit>(operation);
            Assert.Equal("limits", map.Property);
            Assert.Equal("99", Assert.Single(map.Before).Value.Text);
        }, operation => Assert.Equal("levels", Assert.IsType<JournalMembershipEdit>(operation).Property));
        var original = Peer();
        CheckFinal(JournalReplayer.Replay(original, Binding, Checkpoint, journal, ModelSnapshot.Registry));
        Assert.Equal(99L, Assert.Single(original.Limits).Value);
        Assert.Equal(0.0, Assert.Single(original.Levels));
    }

    private static CollectionApplyPeer Peer()
    {
        var peer = new CollectionApplyPeer();
        peer.Limits["keep"] = 99;
        peer.Levels.Add(0.0);
        return peer;
    }

    private static void CheckFinal(CollectionApplyPeer peer)
    {
        Assert.Equal(20L, peer.Limits["keep"]);
        Assert.True(peer.Limits.TryGetValue("optional", out var optional));
        Assert.Null(optional);
        Assert.Equal(2, peer.Limits.Count);
        Assert.Equal(3, peer.Levels.Count);
        Assert.True(peer.Levels.Contains(0.0) && peer.Levels.Contains(NegativeZero) && peer.Levels.Contains(double.NaN));
    }
}
