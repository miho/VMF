using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class CollectionObservationTests
{
    [Fact]
    public void ScalarSequenceEmitsIndexedEditAndOnlyAcceptedChangesAreRecorded()
    {
        var model = new CollectionProfile();
        var changes = model.VmfChanges;
        var events = new List<ModelChange>();
        using var watch = changes.AddLocalListener(events.Add);
        changes.Start();
        model.Phases.AddRange(["first", "second"]);
        Assert.False(model.Phases.Add("first"));
        Assert.ThrowsAny<ArgumentException>(() => model.Phases.AddRange(["bad1", "bad2"]));
        model.Phases.Move(1, 0);
        changes.Stop();

        Assert.Equal(2, events.Count);
        var insert = Assert.IsType<ModelCollectionChange>(events[0]);
        Assert.Equal(ModelChangeKind.List, insert.Kind);
        Assert.Equal("phases", insert.PropertyName);
        Assert.Same(model, insert.Source);
        Assert.Empty(insert.Removed);
        Assert.Equal([new ModelCollectionEntry("first", 0), new ModelCollectionEntry("second", 1)], insert.Added);
        var move = Assert.IsType<ModelCollectionChange>(events[1]);
        Assert.Equal([new ModelCollectionEntry("first", 0), new ModelCollectionEntry("second", 1)], move.Removed);
        Assert.Equal([new ModelCollectionEntry("second", 0), new ModelCollectionEntry("first", 1)], move.Added);

        var transaction = Assert.Single(changes.Transactions);
        Assert.True(transaction.Undo());
        Assert.Empty(model.Phases);
        Assert.True(transaction.Redo());
        Assert.Equal(["second", "first"], model.Phases);

        var copy = new CollectionProfile();
        insert.Apply(copy);
        move.Apply(copy);
        Assert.Equal(["second", "first"], copy.Phases);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void OrdinaryScalarFiltersUseSparsePositionsForHistoryAndCompatibleApply(bool retain)
    {
        double?[] candidates = [-0.0, null, double.NaN];
        double?[] before = ScalarFilterReadings();
        int[] positions = retain ? [0, 3, 7] : [1, 2, 4, 5, 6];
        double?[] survivors = retain ? [before[1], null, before[4], before[5], before[6]] : [before[0], 4.0, 7.0];
        var profile = new CollectionProfile();
        IModelList<double?> readings = profile.Readings;
        readings.AddRange(before);
        var events = new List<ModelListChange<double?>>();
        var viewEvents = new List<ModelListChange<double?>>();
        using var observer = readings.AddListener(events.Add);
        using var viewObserver = profile.AsReadOnly().Readings.AddListener(viewEvents.Add);

        var changes = profile.VmfChanges;
        changes.Start();
        Assert.True(retain ? readings.RetainAll(candidates) : readings.RemoveAll(candidates));
        changes.Stop();
        var removal = Assert.IsType<ModelCollectionChange>(Assert.Single(changes.History));
        var direct = Assert.Single(events);
        var projected = Assert.Single(viewEvents);
        Assert.Same(profile.AsReadOnly().Readings, projected.Source);
        Assert.Equal(positions, direct.Removed.Select(pair => pair.Index));
        Assert.Equal(positions, projected.Removed.Select(pair => pair.Index));
        Assert.Equal(positions, removal.Removed.Select(pair => pair.Index!.Value));
        AssertBits(positions.Select(index => before[index]), direct.Removed.Select(pair => pair.Value));
        AssertBits(survivors, readings);

        Assert.True(removal.Undo());
        AssertBits(before, readings);
        Assert.Equal(retain ? 4 : 3, events.Count);
        if (retain)
        {
            Assert.Equal(0, Assert.Single(events[1].Added).Index);
            Assert.Equal(3, Assert.Single(events[2].Added).Index);
            Assert.Equal(7, Assert.Single(events[3].Added).Index);
        }
        else
        {
            Assert.Equal(new[] { 1, 2 }, events[1].Added.Select(pair => pair.Index));
            Assert.Equal(new[] { 4, 5, 6 }, events[2].Added.Select(pair => pair.Index));
            readings[1] = +0.0;
            Assert.False(removal.CanRedo);
            readings[1] = -0.0;
        }
        Assert.True(removal.Redo());
        Assert.Equal(positions, events[^1].Removed.Select(pair => pair.Index));
        AssertBits(survivors, readings);
        int eventCount = events.Count;
        Assert.False(retain ? readings.RetainAll(candidates) : readings.RemoveAll(candidates));
        Assert.Equal(eventCount, events.Count);

        var other = new CollectionProfile();
        double?[] otherBefore = [91, 92, 93, 94, 95, 96, 97, 98];
        other.Readings.AddRange(otherBefore);
        var receiverEvents = new List<ModelListChange<double?>>();
        using var watchingReceiver = other.Readings.AddListener(receiverEvents.Add);
        removal.Apply(other);
        var receiverRemoval = Assert.Single(receiverEvents);
        Assert.Equal(positions, receiverRemoval.Removed.Select(pair => pair.Index));
        AssertBits(positions.Select(index => otherBefore[index]), receiverRemoval.Removed.Select(pair => pair.Value));
        double?[] receiverSurvivors = retain ? [92, 93, 95, 96, 97] : [91, 94, 98];
        AssertBits(receiverSurvivors, other.Readings);
    }

    [Fact]
    public void BoundedOrdinaryScalarFilterProducesASparseV1Removal()
    {
        const string checkpointId = "bulk-filter";
        var source = new CollectionProfile();
        source.Counts.AddRange([1, 2, 1]);
        var baseline = ModelSnapshot.Read<CollectionProfile>(ModelSnapshot.Write(source));
        var binding = new JournalBinding("scalar-list-filter", 1, _ => "profile");
        using var recorder = JournalRecorder<CollectionProfile>.Start(source, ModelSnapshot.Registry, binding, checkpointId, 0);
        Assert.True(source.Counts.RemoveAll(new[] { 1 }));
        var journal = JournalJson.Read(JournalJson.Write(recorder.Finish()));
        Assert.Equal(1, journal.WireVersion);
        var operation = Assert.IsType<JournalListEdit>(Assert.Single(Assert.Single(journal.Transactions).Operations));
        Assert.Equal(JournalListEditKind.Remove, operation.Kind);
        Assert.Equal(new[] { 0, 2 }, operation.Indices);
        var replay = JournalReplayer.Replay(baseline, binding, checkpointId, journal, ModelSnapshot.Registry);
        Assert.Equal(new[] { 2 }, replay.Counts);
        Assert.Equal(new[] { 1, 2, 1 }, baseline.Counts);
    }

    private static double?[] ScalarFilterReadings() =>
        [+0.0, -0.0, null, 4.0, -0.0, BitConverter.Int64BitsToDouble(0x7ff8000000000042L),
            BitConverter.Int64BitsToDouble(unchecked((long)0xfff8000000000017UL)), 7.0];

    private static void AssertBits(IEnumerable<double?> expected, IEnumerable<double?> actual) =>
        Assert.Equal(expected.Select(ToBits), actual.Select(ToBits));

    private static long? ToBits(double? value) => value is double number ? BitConverter.DoubleToInt64Bits(number) : null;

    [Fact]
    public void MapBulkEditKeepsExplicitNullAndCanBeUndoneAndReapplied()
    {
        var model = new CollectionProfile();
        model.Limits["existing"] = null;
        var changes = model.VmfChanges;
        var events = new List<ModelChange>();
        using var watch = changes.AddLocalListener(events.Add);
        changes.Start();
        model.Limits.PutAll([new("existing", 5L), new("added", null)]);
        changes.Stop();

        var edit = Assert.IsType<ModelCollectionChange>(Assert.Single(events));
        Assert.Equal(ModelChangeKind.Map, edit.Kind);
        Assert.Equal([new ModelCollectionEntry(null, Key: "existing")], edit.Removed);
        Assert.Equal([new ModelCollectionEntry(5L, Key: "existing"), new ModelCollectionEntry(null, Key: "added")], edit.Added);
        Assert.Same(edit, Assert.Single(changes.History));
        Assert.True(edit.Undo());
        Assert.True(model.Limits.TryGetValue("existing", out var restored));
        Assert.Null(restored);
        Assert.False(model.Limits.ContainsKey("added"));
        Assert.True(edit.Redo());
        Assert.Equal(5L, model.Limits["existing"]);
        Assert.True(model.Limits.ContainsKey("added"));
        Assert.Null(model.Limits["added"]);

        var copy = new CollectionProfile();
        copy.Limits["existing"] = null;
        edit.Apply(copy);
        Assert.Equal(5L, copy.Limits["existing"]);
        Assert.True(copy.Limits.ContainsKey("added"));
        Assert.Null(copy.Limits["added"]);
    }

    [Fact]
    public void ModelReferenceCollectionsPreserveSlotAndMembershipIdentityInRecordedEvents()
    {
        var head = new ArchiveNode();
        var child = new ArchiveNode();
        var hosted = new ArchiveRoot { Head = head };
        var changes = hosted.VmfChanges;
        var events = new List<ModelChange>();
        using var watch = changes.AddRecursiveListener(events.Add);
        changes.Start();
        head.Trail.AddRange([child, null, child]);
        head.Featured.Add(child);
        Assert.False(head.Featured.Add(child));
        changes.Stop();

        var sequence = Assert.IsType<ModelCollectionChange>(events[0]);
        Assert.Equal(ModelChangeKind.List, sequence.Kind);
        Assert.True(sequence.IsModelReference);
        Assert.Equal([new ModelCollectionEntry(child, 0), new ModelCollectionEntry(null, 1),
            new ModelCollectionEntry(child, 2)], sequence.Added);
        var membership = Assert.IsType<ModelCollectionChange>(events[1]);
        Assert.Equal(ModelChangeKind.ReferenceSet, membership.Kind);
        Assert.Same(child, Assert.Single(membership.Added).Value);
        Assert.Equal(0, Assert.Single(membership.Added).Index);
        Assert.True(Assert.Single(changes.Transactions).Undo());
        Assert.Empty(head.Trail);
        Assert.Empty(head.Featured);
        Assert.True(Assert.Single(changes.Transactions).Redo());
        Assert.Same(child, head.Trail[0]);
        Assert.Null(head.Trail[1]);
        Assert.Same(child, head.Trail[2]);
        Assert.Same(child, head.Featured[0]);
        Assert.ThrowsAny<ArgumentException>(() => sequence.Apply(new ArchiveNode()));

        var selection = new ValueSelection();
        var first = new ValueLabel("equal");
        var second = new ValueLabel("equal");
        Assert.Equal(first, second);
        var unique = new List<ModelChange>();
        using var watchUnique = selection.VmfChanges.AddLocalListener(unique.Add);
        selection.Selected.Add(first);
        selection.Selected.Add(second);
        Assert.False(selection.Selected.Add(second));
        var distinct = Assert.IsType<ModelCollectionChange>(unique[1]);
        Assert.Equal(ModelChangeKind.ReferenceSet, distinct.Kind);
        Assert.Null(Assert.Single(distinct.Added).Index);
        Assert.Same(second, Assert.Single(distinct.Added).Value);
    }

    [Fact]
    public void ReentrantSetEditRecordsBeforeNestedScalarAndObserversSeeAppliedMembers()
    {
        var model = new CollectionProfile();
        var changes = model.VmfChanges;
        var notes = new List<string>();
        using var membership = changes.AddLocalListener(change =>
        {
            notes.Add("first " + change.PropertyName);
            if (change.PropertyName == "levels")
            {
                Assert.True(model.Levels.Contains(2.0));
                model.Messages.Add("nested");
            }
        });
        using var other = changes.AddLocalListener(change => notes.Add("second " + change.PropertyName));

        changes.Start();
        model.Levels.Add(2.0);
        changes.Stop();
        Assert.Equal(["first levels", "first messages", "second messages", "second levels"], notes);
        Assert.Equal(["levels", "messages"], changes.History.Select(change => change.PropertyName));
        Assert.Equal(ModelChangeKind.Set, changes.History[0].Kind);
    }
}
