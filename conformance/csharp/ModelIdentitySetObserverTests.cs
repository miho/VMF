using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;
using OwningSnapshot = Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment.ModelSnapshot;
using ValuesSnapshot = Eu.Mihosoft.Vmf.Conformance.Values.ModelSnapshot;

namespace Vmf.CSharp.Conformance;

public sealed class ModelIdentitySetObserverTests
{
    private static readonly JournalBinding Journal = new("conformance.set-anchors", 1, node => node switch
    {
        Shelf shelf => shelf.Name!,
        Volume volume => volume.Name!,
        ArchiveNode archive => archive.Name!,
        ReadingRoom or ArchiveRoot => "root",
        _ => throw new ArgumentException("Unexpected journal node.", nameof(node))
    });

    private static readonly Dictionary<string, string[]> Expected =
        JsonSerializer.Deserialize<Dictionary<string, string[]>>(
            File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "fixtures", "model-identity-set-observers.json")))!;

    [Theory]
    [InlineData(false, false)]
    [InlineData(false, true)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public void PublicGetterAndFirstModeledObserverReserveDifferentRelayPositions(bool owning, bool observeEarly)
    {
        var fixture = Create(owning);
        var trace = new List<string>();
        using var mutable = fixture.Watch(false, ModelChangeScope.Local, () => trace.Add("M0"));
        fixture.ReadOnly();
        fixture.CollectionView();
        IDisposable? projected = null;
        if (observeEarly) projected = fixture.Watch(true, ModelChangeScope.Local, () => trace.Add("R0"));
        using var mutableRecursive = fixture.Watch(false, ModelChangeScope.Recursive, () => trace.Add("M1"));
        if (!observeEarly) projected = fixture.Watch(true, ModelChangeScope.Local, () => trace.Add("R0"));
        using var cleanup = projected;
        using var projectedRecursive = fixture.Watch(true, ModelChangeScope.Recursive, () => trace.Add("R1"));

        fixture.Edit();

        ListenerTraces.EqualByKind(Expected[observeEarly ? "early-projected-modeled" : "public-getter-alone"], trace);
    }

    [Theory]
    [InlineData(false, false)]
    [InlineData(false, true)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public void ModeledSetRelayKeepsItsPositionWhileDormant(bool owning, bool reentrant)
    {
        var fixture = Create(owning);
        var trace = new List<string>();
        IDisposable? activated = null;
        using var mutable = fixture.Watch(false, ModelChangeScope.Local, () =>
        {
            trace.Add("M0");
            if (reentrant) activated = fixture.Watch(true, ModelChangeScope.Local, () => trace.Add("Rnew"));
        });
        fixture.Watch(true, ModelChangeScope.Local, () => throw new InvalidOperationException("Disposed subscriber")).Dispose();
        using var mutableRecursive = fixture.Watch(false, ModelChangeScope.Recursive, () => trace.Add("M1"));
        if (!reentrant) activated = fixture.Watch(true, ModelChangeScope.Local, () => trace.Add("Rnew"));

        fixture.Edit();

        using var cleanup = activated;
        ListenerTraces.EqualByKind(Expected["reactivated-modeled"], trace);
    }

    [Fact]
    public void OwnedSetInverseCanActivateAnExistingProjectedRelay()
    {
        var shelf = new Shelf();
        var child = new Volume();
        var fixture = Owning(shelf, child);
        var trace = new List<string>();
        fixture.Watch(true, ModelChangeScope.Local, () => throw new InvalidOperationException("Disposed subscriber")).Dispose();
        IDisposable? projected = null;
        using var inverse = child.VmfChanges.AddPropertyListener("shelf", _ =>
        {
            trace.Add("inverse");
            projected = fixture.Watch(true, ModelChangeScope.Local, () => trace.Add("projected"));
        });

        fixture.Edit();

        using var cleanup = projected;
        ListenerTraces.EqualByKind(Expected["inverse-activation"], trace);
    }

    [Fact]
    public void PlainKeyedRelayIsReservedWhenItsGetterIsRead()
    {
        var trace = new List<string>();
        var root = new KeyedInventory();
        var item = new KeyedItem { Sku = "A" };
        var raw = root.Products;
        using var first = raw.AddListener(_ => trace.Add("M0"));
        var ownerView = root.AsReadOnly();
        using var second = raw.AddListener(_ => trace.Add("M1"));
        var view = ownerView.Products;
        using var last = raw.AddListener(_ => trace.Add("M2"));
        using var projected = view.AddListener(edit =>
        {
            Assert.Same(view, edit.Source);
            var added = Assert.Single(edit.Added);
            Assert.Same(item.AsReadOnly(), added.Value);
            Assert.Equal(0, added.Index);
            Assert.Same(item.AsReadOnly(), ownerView.FindProductsBySku("A"));
            trace.Add("P0");
        });
        using var projectedLocal = ownerView.VmfChanges.AddLocalListener(_ => trace.Add("R0"));
        using var mutableLocal = root.VmfChanges.AddLocalListener(_ => trace.Add("C0"));
        using var projectedRecursive = ownerView.VmfChanges.AddRecursiveListener(_ => trace.Add("R1"));

        raw.Add(item);

        ListenerTraces.EqualByKind(Expected["plain-keyed-list-getter"], trace);
    }

    [Fact]
    public void ActiveRecorderPreservesAnOwningTransferWithAProjectedRelay()
    {
        var source = new Shelf { Name = "source" };
        var destination = new Shelf { Name = "dest" };
        var child = new Volume { Name = "volume" };
        source.Volumes.Add(child);
        var root = ReadingRoom.NewBuilder().WithShelves([source, destination]).WithFeatured(child).Build();
        var checkpoint = OwningSnapshot.Reflection.DeepCopy(root);
        var trace = new List<string>();
        using var projected = destination.AsReadOnly().VmfChanges.AddLocalListener(_ => trace.Add("projected"));
        using var recursive = root.VmfChanges.AddRecursiveListener(change =>
        {
            if (change.PropertyName == "volumes") trace.Add(ReferenceEquals(change.Source, source) ? "source" : "dest");
        });
        using var recorder = JournalRecorder<ReadingRoom>.Start(root, OwningSnapshot.Registry, Journal, "base", 0);

        destination.Volumes.Add(child);

        ListenerTraces.EqualByKind(Expected["owning-journal"], trace);
        foreach (var journal in RoundTrip(recorder.Finish(), "source.volumes", "dest.volumes"))
        {
            var replayed = JournalReplayer.Replay(checkpoint, Journal, "base", journal, OwningSnapshot.Registry);
            Assert.NotSame(checkpoint, replayed);
            var owner = replayed.FindShelvesByName("dest")!;
            Assert.Same(replayed.Featured, Assert.Single(owner.Volumes));
            Assert.Same(owner, replayed.Featured!.Shelf);
            Assert.Empty(checkpoint.FindShelvesByName("dest")!.Volumes);
            Assert.Same(checkpoint.FindShelvesByName("source"), checkpoint.Featured!.Shelf);
        }
    }

    [Fact]
    public void ActiveRecorderPreservesAnUnopposedReferenceAliasWithAProjectedRelay()
    {
        var peer = new ArchiveNode { Name = "peer" };
        var head = ArchiveNode.NewBuilder().WithName("head").WithChildren([peer]).Build();
        var root = ArchiveRoot.NewBuilder().WithHead(head).Build();
        var checkpoint = ValuesSnapshot.Reflection.DeepCopy(root);
        var trace = new List<string>();
        using var projected = head.AsReadOnly().VmfChanges.AddLocalListener(_ => trace.Add("projected"));
        using var recursive = root.VmfChanges.AddRecursiveListener(change =>
        {
            if (change.PropertyName == "peers") trace.Add("root");
        });
        using var recorder = JournalRecorder<ArchiveRoot>.Start(root, ValuesSnapshot.Registry, Journal, "base", 0);

        head.Peers.Add(peer);

        ListenerTraces.EqualByKind(Expected["unopposed-journal"], trace);
        foreach (var journal in RoundTrip(recorder.Finish(), "head.peers"))
        {
            var replayed = JournalReplayer.Replay(checkpoint, Journal, "base", journal, ValuesSnapshot.Registry);
            Assert.NotSame(checkpoint, replayed);
            Assert.Same(Assert.Single(replayed.Head!.Children), Assert.Single(replayed.Head.Peers));
            Assert.Empty(checkpoint.Head!.Peers);
        }
    }

    private static IEnumerable<ChangeJournal> RoundTrip(ChangeJournal journal, params string[] targets)
    {
        ChangeJournal[] formats =
        [
            JournalJson.Read(JournalJson.Write(journal)),
            JournalXml.Read(JournalXml.Write(journal)),
            JournalYaml.Read(JournalYaml.Write(journal))
        ];
        foreach (var restored in formats)
        {
            Assert.Equal(1, restored.WireVersion);
            var operations = Assert.Single(restored.Transactions).Operations;
            Assert.All(operations, edit => Assert.True(Assert.IsType<JournalMembershipEdit>(edit).References));
            Assert.Equal(targets, operations.Select(edit => edit.Target + "." + edit.Property));
            yield return restored;
        }
    }

    private static Fixture Create(bool owning)
    {
        if (owning) return Owning(new Shelf(), new Volume());
        var root = new ArchiveNode();
        var peer = new ArchiveNode();
        return new(root, root.VmfChanges, root.AsReadOnly, "peers", () => root.AsReadOnly().Peers,
            peer, peer.AsReadOnly, () => root.Peers.Add(peer));
    }

    private static Fixture Owning(Shelf shelf, Volume child) =>
        new(shelf, shelf.VmfChanges, shelf.AsReadOnly, "volumes", () => shelf.AsReadOnly().Volumes,
            child, child.AsReadOnly, () =>
            {
                shelf.Volumes.Add(child);
                Assert.Same(shelf, child.Shelf);
            });

    private sealed record Fixture(object Owner, ModelChanges Mutable, Func<IReadOnlyModelView> ReadOnly,
        string Property, Func<object> CollectionView, object Target, Func<object> TargetView, Action Edit)
    {
        public IDisposable Watch(bool projected, ModelChangeScope scope, Action callback)
        {
            if (projected)
            {
                var projection = ReadOnly();
                return projection.VmfChanges.AddListener(edit =>
                {
                    if (edit.PropertyName != Property) return;
                    Assert.Same(projection, edit.Source);
                    Assert.Equal(ModelChangeKind.ReferenceSet, edit.Kind);
                    Assert.Empty(edit.Removed);
                    var member = Assert.Single(edit.Added);
                    Assert.Same(TargetView(), member.Value);
                    Assert.Null(member.Index);
                    callback();
                }, scope);
            }
            return Mutable.AddListener(edit =>
            {
                if (edit.PropertyName != Property) return;
                Assert.Same(Owner, edit.Source);
                Assert.Equal(ModelChangeKind.ReferenceSet, edit.Kind);
                var delta = Assert.IsType<ModelCollectionChange>(edit);
                Assert.Empty(delta.Removed);
                var member = Assert.Single(delta.Added);
                Assert.Same(Target, member.Value);
                Assert.Null(member.Index);
                callback();
            }, scope);
        }
    }
}
