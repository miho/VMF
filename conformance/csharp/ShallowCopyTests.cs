using Eu.Mihosoft.Vmf.Conformance.Opposites;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Eu.Mihosoft.Vmf.Example.Workshops;
using Vmf.Runtime;
using Xunit;
using Opposites = Eu.Mihosoft.Vmf.Conformance.Opposites.ModelSnapshot;
using Values = Eu.Mihosoft.Vmf.Conformance.Values.ModelSnapshot;
using Workshops = Eu.Mihosoft.Vmf.Example.Workshops.ModelSnapshot;

namespace Vmf.CSharp.Conformance;

public class ShallowCopyTests
{
    [Fact]
    public void SharedPhysicalListInterleavesDistinctModeledOwnersAndItsProjection()
    {
        var source = new CollectionProfile();
        var raw = source.Messages;
        var copy = Values.Reflection.ShallowCopy(source);
        Assert.Same(raw, copy.Messages);
        var sourceChanges = source.VmfChanges;
        var copyChanges = copy.VmfChanges;
        var trace = new List<string>();
        using var first = raw.AddListener(_ => trace.Add($"raw0:{sourceChanges.History.Count}"));
        using var sourceLocal = sourceChanges.AddLocalListener(change =>
        {
            Assert.Same(source, change.Source);
            trace.Add($"source:{sourceChanges.History.Count}");
        });
        var projected = source.AsReadOnly().Messages;
        var copyView = copy.AsReadOnly();
        using var projectedFirst = projected.AddListener(_ => trace.Add($"projected0:{sourceChanges.History.Count}"));
        using var later = raw.AddListener(_ => trace.Add($"raw1:{copyChanges.History.Count}"));
        using var copyReadOnly = copyView.VmfChanges.AddLocalListener(change =>
        {
            Assert.Same(copyView, change.Source);
            trace.Add("readonly-copy");
        });
        using var projectedLast = projected.AddListener(_ => trace.Add("projected1"));
        using var copyLocal = copyChanges.AddLocalListener(change =>
        {
            Assert.Same(copy, change.Source);
            trace.Add($"copy:{copyChanges.History.Count}");
        });
        sourceChanges.Start();
        copyChanges.Start();
        raw.Add("outer");
        sourceChanges.Stop();
        copyChanges.Stop();

        Assert.Equal(["raw0:0", "source:0", "projected0:1", "readonly-copy", "projected1", "raw1:0", "copy:0"], trace);
        Assert.Same(source, Assert.Single(sourceChanges.History).Source);
        Assert.Same(copy, Assert.Single(copyChanges.History).Source);
    }

    [Fact]
    public void RawCallbackCanActivateTheShallowOwnersModeledRelayOnAnExistingProjection()
    {
        var source = new CollectionProfile();
        var raw = source.Messages;
        var copy = Values.Reflection.ShallowCopy(source);
        var view = copy.AsReadOnly();
        var trace = new List<string>();
        IDisposable? modeled = null;
        using var rawListener = raw.AddListener(_ =>
        {
            trace.Add("raw");
            modeled = view.VmfChanges.AddLocalListener(change =>
            {
                Assert.Same(view, change.Source);
                trace.Add("readonly-copy");
            });
        });
        using var projected = source.AsReadOnly().Messages.AddListener(_ => trace.Add("projected"));
        try
        {
            raw.Add("first");
            Assert.Equal(["raw", "projected", "readonly-copy"], trace);
        }
        finally
        {
            modeled?.Dispose();
        }
    }

    [Fact]
    public void SharesMaterializedOrdinaryListsAndCopiesManagedStorage()
    {
        var source = new CollectionProfile();
        source.Messages.Add("before");
        source.Readings.Add(1.0);
        source.Phases.Add("phase");
        source.Counts.Add(1);
        source.Levels.Add(1.0);
        source.Options.Add("present", null);
        var copy = Values.Reflection.ShallowCopy(source);
        Assert.NotSame(source, copy);
        Assert.Same(source.Messages, copy.Messages);
        Assert.Same(source.Readings, copy.Readings);
        Assert.NotSame(source.Phases, copy.Phases);
        Assert.NotSame(source.Counts, copy.Counts);
        Assert.NotSame(source.Levels, copy.Levels);
        Assert.NotSame(source.Options, copy.Options);

        copy.Messages.Add(null);
        source.Readings.Add(2.0);
        copy.Phases.Clear();
        copy.Counts.Add(2);
        copy.Levels.Clear();
        copy.Options.Add("other", true);
        Assert.Equal(new string?[] { "before", null }, source.Messages);
        Assert.Equal(new double?[] { 1.0, 2.0 }, copy.Readings);
        Assert.Equal(["phase"], source.Phases);
        Assert.Equal([1], source.Counts);
        Assert.Equal([1.0], source.Levels);
        Assert.Equal("present", Assert.Single(source.Options).Key);
        Assert.Null(copy.Options["present"]);
    }

    [Fact]
    public void OrdinaryListsStayIndependentUntilMaterializedByAccessOrObservation()
    {
        var source = new CollectionProfile();
        var copy = Values.Reflection.ShallowCopy(source);
        Assert.NotSame(source.Messages, copy.Messages);
        var again = Values.Reflection.ShallowCopy(source);
        Assert.Same(source.Messages, again.Messages);

        var observed = new CollectionProfile();
        using (observed.VmfChanges.AddLocalListener(_ => { })) { }
        var observedCopy = Values.Reflection.ShallowCopy(observed);
        Assert.Same(observed.Messages, observedCopy.Messages);

        var raw = new ArchiveNode();
        var view = raw.AsReadOnly();
        var viewCopy = Values.Reflection.ShallowCopy(view);
        raw.Trail.Add(new ArchiveNode());
        Assert.Empty(viewCopy.Trail);
        var sharedView = Values.Reflection.ShallowCopy(view);
        Assert.Same(view.Trail[0], sharedView.Trail[0]);
        raw.Trail.Clear();
        Assert.Empty(sharedView.Trail);
    }

    [Fact]
    public void SharedCollectionDispatchUsesEachOwnerAndActualRegistrationOrder()
    {
        var source = new CollectionProfile();
        source.Messages.Add("before");
        var copy = Values.Reflection.ShallowCopy(source);
        var trace = new List<string>();
        using var sourceLocal = source.VmfChanges.AddLocalListener(change =>
        {
            Assert.Same(source, change.Source);
            trace.Add("source local");
        });
        using var copyRecursive = copy.VmfChanges.AddRecursiveListener(change =>
        {
            Assert.Same(copy, change.Source);
            trace.Add("copy recursive");
        });
        using var sourceRecursive = source.VmfChanges.AddRecursiveListener(change =>
        {
            Assert.Same(source, change.Source);
            trace.Add("source recursive");
        });

        copy.Messages.Add("edit");
        // The source's listeners run together, in the order they were added (portable contract E7); the copy shares
        // the list but is not an ancestor, so its place relative to the source is not specified.
        Assert.Equal(["source local", "source recursive", "copy recursive"], trace);
    }

    [Fact]
    public void RecursiveHistoryRecordsBothLogicalOwnersWithoutCopyingPriorHistory()
    {
        var original = new ArchiveNode { Name = "original" };
        original.VmfChanges.Start();
        original.Trail.Add(null);
        var copy = Values.Reflection.ShallowCopy(original);
        Assert.Single(original.VmfChanges.History);
        Assert.Empty(copy.VmfChanges.History);
        Assert.False(copy.VmfChanges.IsRecording);
        original.VmfChanges.Stop();

        var root = new ArchiveNode();
        root.Children.AddRange([copy, original]);
        root.VmfChanges.Start();
        original.Trail.Add(null);
        Assert.Collection(root.VmfChanges.History, change => Assert.Same(copy, change.Source),
            change => Assert.Same(original, change.Source));
        root.VmfChanges.Stop();
    }

    [Fact]
    public void SharedListHistoryReplaysIntoBothListsOfAFreshCheckpoint()
    {
        var original = new ArchiveNode { Name = "original" };
        original.Trail.Add(null);
        var copy = Values.Reflection.ShallowCopy(original);
        copy.Name = "copy";
        var root = new ArchiveNode { Name = "root" };
        root.Children.AddRange([original, copy]);
        string checkpoint = Values.Write(root);
        var binding = new JournalBinding("conformance.shallow-copy", 1, model => ((ArchiveNode)model).Name!);

        var journal = JournalRecorder<ArchiveNode>.RecordFrame(root, Values.Registry, binding, "initial", 0,
            _ => copy.Trail.Add(null));
        Assert.Equal(["original", "copy"], Assert.Single(journal.Transactions).Operations.Select(operation => operation.Target));
        var fresh = Values.Read<ArchiveNode>(checkpoint);
        var replayed = JournalReplayer.Replay(fresh, binding, "initial", JournalJson.Read(JournalJson.Write(journal)), Values.Registry);
        Assert.All(fresh.Children, child => Assert.Single(child.Trail));
        Assert.All(replayed.Children, child => Assert.Equal(new ArchiveNode?[] { null, null }, child.Trail));
    }

    [Fact]
    public void CopiesInheritedValuesAndReferenceAliasesWhileDetachingTheNewRoot()
    {
        var settings = new DefaultSettings { Name = "name", Limit = 9, Note = "detail" };
        DefaultBase copy = Values.Reflection.ShallowCopy<DefaultBase>(settings);
        Assert.Equal("name", copy.Name);
        Assert.Equal(9, copy.Limit);
        Assert.Equal("detail", Assert.IsType<DefaultSettings>(copy).Note);

        var original = new ArchiveNode { Name = "original" };
        var member = new ArchiveNode { Name = "member" };
        original.Anchor = original;
        original.Trail.AddRange([member, null, member]);
        original.Peers.Add(member);
        original.Featured.Add(member);
        var shallow = Values.Reflection.ShallowCopy(original);
        Assert.Same(original, shallow.Anchor);
        Assert.Same(original.Trail, shallow.Trail);
        Assert.NotSame(original.Peers, shallow.Peers);
        Assert.NotSame(original.Featured, shallow.Featured);
        Assert.Same(member, Assert.Single(shallow.Peers));
        Assert.Same(member, Assert.Single(shallow.Featured));
        var view = Values.Reflection.ShallowCopy(original.AsReadOnly());
        Assert.Same(original.AsReadOnly(), view.Anchor);
        Assert.Same(member.AsReadOnly(), view.Trail[0]);

        var label = new ValueLabel("same");
        var withValue = new ValueSelection { Current = label };
        Assert.Same(label, Values.Reflection.ShallowCopy(withValue).Current);
        Assert.Same(label, Values.Reflection.ShallowCopy(label));

        var workshop = new Workshop();
        var station = new Station { Name = "station" };
        workshop.Stations.Add(station);
        var detached = Workshops.Reflection.ShallowCopy(station);
        Assert.Equal("station", detached.Name);
        Assert.Same(workshop, station.Workshop);
        Assert.Null(detached.Workshop);
    }

    [Fact]
    public void ProtectsExistingContainmentAndAllowsEmptyOwningCollections()
    {
        var workshop = new Workshop();
        var empty = Workshops.Reflection.ShallowCopy(workshop);
        Assert.NotSame(workshop.Stations, empty.Stations);
        var station = new Station { Name = "station" };
        workshop.Stations.Add(station);
        Assert.Throws<InvalidOperationException>(() => Workshops.Reflection.ShallowCopy(workshop));
        Assert.Same(workshop, station.Workshop);

        var root = new ArchiveNode { Inset = new ArchiveNode() };
        var child = root.Inset;
        Assert.Throws<InvalidOperationException>(() => Values.Reflection.ShallowCopy(root));
        Assert.Same(child, root.Inset);
        Assert.Same(root, child!.InsetOf);

        var inventory = new KeyedInventory();
        var independent = Values.Reflection.ShallowCopy(inventory);
        Assert.NotSame(inventory.Products, independent.Products);
        inventory.Products.Add(new KeyedItem { Sku = "one" });
        Assert.Empty(independent.Products);
        Assert.Throws<InvalidOperationException>(() => Values.Reflection.ShallowCopy(inventory));
    }

    [Fact]
    public void ReusesTargetsOnlyWhenTheOppositeCanAccommodateBothOwners()
    {
        var first = new Endpoint { Id = "first" };
        var second = new Endpoint { Id = "second" };
        first.Links.Add(second);
        first.Groups.AddRange([second, null, second]);
        first.Supervisor = second;
        var oppositeChanges = new List<string>();
        using var watch = second.VmfChanges.AddLocalListener(change => oppositeChanges.Add(change.PropertyName));
        var copy = Opposites.Reflection.ShallowCopy(first);
        Assert.Equal("first", copy.Id);
        Assert.NotSame(first.Groups, copy.Groups);
        Assert.Equal(new Endpoint?[] { second, null, second }, copy.Groups);
        Assert.Same(second, Assert.Single(copy.Links));
        Assert.Same(second, copy.Supervisor);
        Assert.True(second.Panels.Contains(first));
        Assert.True(second.Panels.Contains(copy));
        Assert.True(second.Members.Contains(first));
        Assert.True(second.Members.Contains(copy));
        Assert.Contains(first, second.Reports);
        Assert.Contains(copy, second.Reports);
        Assert.Contains("panels", oppositeChanges);

        first.Peer = second;
        Assert.Throws<InvalidOperationException>(() => Opposites.Reflection.ShallowCopy(first));
        Assert.Throws<InvalidOperationException>(() => Opposites.Reflection.ShallowCopy(second)); // reports has an inverse scalar
        Assert.Same(second, first.Peer);
        Assert.Same(first, second.Peer);
        Assert.Equal(2, second.Panels.Count);
    }
}
