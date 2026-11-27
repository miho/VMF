using Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class BulkListAliasTests
{
    [Theory]
    [InlineData(false, false)]
    [InlineData(false, true)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public void BulkFilterPublishesOriginalSparseSlotsAndMaintainsOwnership(bool owning, bool retain)
    {
        var (graph, first, second, spare) = NewGraph();
        IModelList<ValueMatchedCard?> list = owning ? graph.Children : graph.References;
        IReadOnlyList<IReadOnlyValueMatchedCard?> readOnly = owning ? graph.AsReadOnly().Children : graph.AsReadOnly().References;
        var originals = list.ToArray();
        var held = second.AsReadOnly();
        object?[] candidates = [held, null];
        ValueMatchedCard?[] survivors = retain
            ? owning ? [first, null, second] : [first, null, second, first]
            : [spare];
        int[] positions = retain ? [3] : owning ? [0, 1, 2] : [0, 1, 2, 4];
        var edits = new List<ModelListChange<ValueMatchedCard?>>();
        var viewEdits = new List<ModelListChange<IReadOnlyValueMatchedCard?>>();
        using var watch = list.AddListener(edits.Add);
        using var watchView = readOnly.AddListener(viewEdits.Add);

        graph.VmfChanges.Start();
        Assert.True(retain ? list.RetainAll(candidates) : list.RemoveAll(candidates));
        graph.VmfChanges.Stop();
        var delta = Assert.IsType<ModelCollectionChange>(Assert.Single(graph.VmfChanges.History));
        var edit = Assert.Single(edits);
        var viewEdit = Assert.Single(viewEdits);
        Assert.Same(list, edit.Source);
        Assert.Same(readOnly, viewEdit.Source);
        Assert.Equal(positions, edit.Removed.Select(pair => pair.Index));
        Assert.Equal(positions, viewEdit.Removed.Select(pair => pair.Index));
        Assert.Equal(positions, delta.Removed.Select(pair => pair.Index!.Value));
        for (int i = 0; i < positions.Length; i++)
        {
            var expected = originals[positions[i]];
            Assert.Same(expected, edit.Removed[i].Value);
            Assert.Same(expected?.AsReadOnly(), viewEdit.Removed[i].Value);
            Assert.Same(expected, delta.Removed[i].Value);
        }
        Assert.Empty(edit.Added);
        AssertSlots(survivors);
        Assert.Same(!owning || retain ? graph : null, first.Owner);
        Assert.Same(!owning || retain ? graph : null, second.Owner);
        Assert.Same(owning && retain ? null : graph, spare.Owner);
        Assert.False(retain ? list.RetainAll(candidates) : list.RemoveAll(candidates));
        Assert.Single(edits);

        Assert.True(delta.Undo());
        AssertSlots(originals);
        Assert.Same(graph, first.Owner);
        Assert.Same(graph, second.Owner);
        Assert.Same(graph, spare.Owner);
        Assert.Equal(positions, edits.Skip(1).SelectMany(edit => edit.Added).Select(pair => pair.Index));
        Assert.Equal(!owning && !retain ? 3 : 2, edits.Count);
        Assert.True(delta.Redo());
        AssertSlots(survivors);
        Assert.Equal(positions, edits[^1].Removed.Select(pair => pair.Index));

        void AssertSlots(IReadOnlyList<ValueMatchedCard?> expected)
        {
            Assert.Equal(expected.Count, list.Count);
            for (int i = 0; i < expected.Count; i++)
            {
                Assert.Same(expected[i], list[i]);
                Assert.Same(expected[i]?.AsReadOnly(), readOnly[i]);
            }
        }
    }

    [Theory]
    [InlineData(false, false, false)]
    [InlineData(false, false, true)]
    [InlineData(true, false, false)]
    [InlineData(true, false, true)]
    [InlineData(true, true, false)]
    [InlineData(true, true, true)]
    public void UndoUsesContiguousRunsUnlessAnOwningAliasMustBeRestored(bool owning, bool duplicate, bool transaction)
    {
        var (source, first, second, spare) = NewGraph(spareBetweenMatches: true);
        IModelList<ValueMatchedCard?> list = owning ? source.Children : source.References;
        if (owning && duplicate)
        {
            list.Add(null);
            list[4] = first;
        }
        var original = list.ToArray();
        var inserted = new List<int[]>();
        using var watching = list.AddListener(change =>
        {
            if (change.Added.Count != 0) inserted.Add(change.Added.Select(value => value.Index).ToArray());
        });

        source.VmfChanges.Start();
        Assert.True(list.RemoveAll([second.AsReadOnly(), null]));
        source.VmfChanges.Stop();
        Assert.Same(spare, Assert.Single(list));
        Assert.True(transaction ? Assert.Single(source.VmfChanges.Transactions).Undo()
            : Assert.Single(source.VmfChanges.History).Undo());

        if (owning && duplicate)
        {
            Assert.Equal(new[] { 0, 2, 3, 4 }, Assert.Single(inserted));
        }
        else
        {
            Assert.Equal(2, inserted.Count);
            Assert.Equal(new[] { 0 }, inserted[0]);
            Assert.Equal(owning ? new[] { 2, 3 } : new[] { 2, 3, 4 }, inserted[1]);
        }
        Assert.Equal(original.Length, list.Count);
        for (int i = 0; i < original.Length; i++) Assert.Same(original[i], list[i]);
        Assert.Same(source, first.Owner);
        Assert.Same(source, second.Owner);
    }

    [Fact]
    public void OwningBulkObserverFailuresPreserveOtherInversesAndNestedEdits()
    {
        var (source, first, second, spare) = NewGraph(spareBetweenMatches: true);
        var children = source.Children;
        var destination = new ValueMatchedList();
        var inserted = new ValueMatchedCard { Id = "inserted", Caption = "inserted" };
        var trace = new List<string>();
        var firstFailure = new InvalidOperationException("first inverse");
        var secondFailure = new InvalidOperationException("second inverse");
        var listFailure = new InvalidOperationException("list observer");
        using var firstObserver = first.VmfChanges.AddPropertyListener("owner", _ =>
        {
            if (first.Owner is not null) return;
            Assert.Same(spare, Assert.Single(children));
            Assert.Same(source, second.Owner);
            trace.Add("first inverse");
            Assert.Throws<ArgumentException>(() => children.RemoveAll([spare]));
            trace.Add("guarded");
            destination.Children.Add(first);
            trace.Add("adopted");
            throw firstFailure;
        });
        using var secondObserver = second.VmfChanges.AddPropertyListener("owner", _ =>
        {
            Assert.Null(second.Owner);
            trace.Add("second inverse");
            throw secondFailure;
        });
        using var listObserver = children.AddListener(change =>
        {
            if (change.Removed.Count != 0)
            {
                trace.Add("first removal");
                children.Add(inserted);
                throw listFailure;
            }
            trace.Add("first insertion");
        });
        using var laterObserver = children.AddListener(change =>
        {
            if (change.Removed.Count != 0)
            {
                Assert.Equal(new[] { 0, 2, 3 }, change.Removed.Select(slot => slot.Index));
                Assert.Same(first, change.Removed[0].Value);
                Assert.Same(second, change.Removed[1].Value);
                Assert.Null(change.Removed[2].Value);
                Assert.Equal(2, children.Count);
                Assert.Same(inserted, children[1]);
                trace.Add("later removal");
            }
            else
            {
                trace.Add("later insertion");
            }
        });
        var error = Assert.Throws<InvalidOperationException>(() => children.RemoveAll([second.AsReadOnly(), null]));
        Assert.Same(firstFailure, error);
        Assert.Equal(new Exception[] { secondFailure, listFailure }, ModelChanges.GetSuppressedObserverExceptions(error));
        Assert.Equal(new[]
        {
            "first inverse", "guarded", "adopted", "second inverse",
            "first removal", "first insertion", "later insertion", "later removal"
        }, trace);
        Assert.Same(destination, first.Owner);
        Assert.Same(first, Assert.Single(destination.Children));
        Assert.Null(second.Owner);
        Assert.Same(spare, children[0]);
        Assert.Same(source, inserted.Owner);
    }

    [Fact]
    public void ReciprocalIdentityListDoesNotNormalizeTheHeldViewForBulkMatching()
    {
        var member = new Eu.Mihosoft.Vmftest.Referenceends.Member();
        var team = new Eu.Mihosoft.Vmftest.Referenceends.Team();
        member.Teams.AddRange([team, null, team]);
        var held = team.AsReadOnly();
        Assert.True(held.Equals(held));
        Assert.False(held.Equals(team));
        Assert.False(team.Equals(held));
        Assert.Contains(held, member.AsReadOnly().Teams);
        var edits = new List<ModelListChange<Eu.Mihosoft.Vmftest.Referenceends.Team?>>();
        using var watching = member.Teams.AddListener(edits.Add);

        Assert.True(member.Teams.Contains(held));
        Assert.False(member.Teams.RemoveAll([held]));
        Assert.Empty(edits);
        Assert.True(member.Teams.RemoveAll([team]));
        Assert.Null(Assert.Single(member.Teams));
        Assert.Empty(team.Members);
        Assert.Equal(new[] { 0, 2 }, Assert.Single(edits).Removed.Select(pair => pair.Index));
    }

    [Fact]
    public void InstanceProjectionIdentityIsIndependentOfModelListLookup()
    {
        var archive = new Eu.Mihosoft.Vmf.Conformance.Values.ArchiveNode();
        var node = new Eu.Mihosoft.Vmf.Conformance.Values.ArchiveNode();
        var alias = node.AsReadOnly();
        Assert.Same(alias, node.AsReadOnly());
        Assert.True(alias.Equals(alias));
        Assert.False(alias.Equals(node));
        Assert.False(node.Equals(alias));
        Assert.Equal(System.Runtime.CompilerServices.RuntimeHelpers.GetHashCode(alias), alias.GetHashCode());

        var trail = archive.Trail;
        trail.AddRange([node, null, node]);
        var projected = archive.AsReadOnly().Trail;
        Assert.Same(alias, projected[0]);
        Assert.Contains(alias, projected);
        Assert.Same(alias, projected[2]);
        Assert.True(trail.Contains(alias));
        Assert.Equal(0, trail.IndexOf(alias));
        Assert.False(trail.RemoveAll([alias]));
        Assert.True(trail.Remove(alias));
        Assert.True(trail.Remove(alias));
        Assert.Null(Assert.Single(trail));

        var parent = new LegacyContainer();
        var child = new LegacyChild();
        var childAlias = child.AsReadOnly();
        parent.Children.Add(child);
        Assert.True(parent.Children.Contains(childAlias));
        Assert.Contains(childAlias, parent.AsReadOnly().Children);
        Assert.True(parent.Children.Remove(childAlias));
        Assert.Null(child.Owner);

        var values = new Eu.Mihosoft.Vmf.Conformance.Values.CollectionProfile().Messages;
        values.AddRange(["value", null]);
        Assert.Contains(new string("value"), values);
        Assert.True(values.Remove(new string("value")));
        Assert.Null(Assert.Single(values));
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void NullOnlyOrdinaryListEditsApplyToReceiverSlots(bool owning)
    {
        var first = new ValueMatchedCard();
        var kept = new ValueMatchedCard();
        var last = new ValueMatchedCard();
        var sourceMiddle = new ValueMatchedCard();
        var source = new ValueMatchedList();
        var target = new ValueMatchedList();
        IModelList<ValueMatchedCard?> sourceList = owning ? source.Children : source.References;
        IModelList<ValueMatchedCard?> targetList = owning ? target.Children : target.References;
        sourceList.AddRange([null, sourceMiddle, null]);
        targetList.AddRange([first, kept, last]);

        var removal = Record(() => Assert.True(sourceList.RemoveAll([null])));
        Assert.Throws<ArgumentException>(() => removal.Apply(target.AsReadOnly()));
        var receiving = new List<ModelListChange<ValueMatchedCard?>>();
        using (targetList.AddListener(receiving.Add))
        {
            removal.Apply(target);
        }
        var received = Assert.Single(receiving);
        Assert.Same(targetList, received.Source);
        Assert.Equal(new[] { 0, 2 }, received.Removed.Select(slot => slot.Index));
        Assert.Same(first, received.Removed[0].Value);
        Assert.Same(last, received.Removed[1].Value);
        Assert.Same(sourceMiddle, Assert.Single(sourceList));
        Assert.Same(kept, Assert.Single(targetList));
        Assert.Null(first.Owner);
        Assert.Null(last.Owner);
        Assert.Same(owning ? target : null, kept.Owner);

        Record(() => sourceList.Insert(0, null)).Apply(target);
        Assert.Equal(2, targetList.Count);
        Assert.Null(targetList[0]);

        var replace = Record(() => sourceList[0] = null);
        targetList[0] = first;
        replace.Apply(target);
        Assert.Null(targetList[0]);
        Assert.Null(first.Owner);

        var withModel = Record(() => sourceList[0] = first);
        Assert.Throws<ArgumentException>(() => withModel.Apply(target));
        Assert.Null(targetList[0]);

        ModelCollectionChange Record(Action edit)
        {
            source.VmfChanges.Start();
            edit();
            source.VmfChanges.Stop();
            return Assert.IsType<ModelCollectionChange>(Assert.Single(source.VmfChanges.History));
        }
    }

    [Fact]
    public void StructuralOwningReplayReportsAndDetachesTheReceiversOwnChild()
    {
        var source = new ValueMatchedList();
        source.Children.Add(null);
        var target = new LegacyContainer();
        var child = new LegacyChild();
        target.Children.Add(child);
        source.VmfChanges.Start();
        source.Children.RemoveAt(0);
        source.VmfChanges.Stop();
        var removal = Assert.Single(source.VmfChanges.History);

        var events = new List<ModelListChange<LegacyChild?>>();
        using (target.Children.AddListener(events.Add))
        {
            removal.Apply(target);
        }
        Assert.Empty(target.Children);
        Assert.Null(child.Owner);
        Assert.Same(child, Assert.Single(Assert.Single(events).Removed).Value);
    }

    [Fact]
    public void OrdinaryOppositeCanUndoBulkRemovalWhenTheRemovedIdentityIsAlreadyPresent()
    {
        var member = new Eu.Mihosoft.Vmftest.Referenceends.Member();
        var restored = new Eu.Mihosoft.Vmftest.Referenceends.Team();
        var replaced = new Eu.Mihosoft.Vmftest.Referenceends.Team();
        var teams = member.Teams;
        teams.AddRange([restored, replaced]);

        var changes = member.VmfChanges;
        changes.Start();
        Assert.True(teams.RemoveAll([restored]));
        changes.Stop();
        var removal = Assert.Single(changes.History);
        teams[0] = restored;

        Assert.True(removal.CanUndo);
        Assert.True(removal.Undo());
        Assert.Equal(2, teams.Count);
        Assert.Same(restored, teams[0]);
        Assert.Same(restored, teams[1]);
        Assert.Same(member, Assert.Single(restored.Members));
        Assert.Empty(replaced.Members);
    }

    [Fact]
    public void SharedShallowSequencePublishesTheSparseRemovalToBothOwners()
    {
        var (owner, _, second, _) = NewGraph();
        owner.Children.Clear();
        var copy = ModelSnapshot.Reflection.ShallowCopy(owner);
        Assert.Same(owner.References, copy.References);
        var firstWatch = new List<ModelChange>();
        var secondWatch = new List<ModelChange>();
        using var watchingFirst = owner.VmfChanges.AddPropertyListener("references", firstWatch.Add);
        using var watchingSecond = copy.VmfChanges.AddPropertyListener("references", secondWatch.Add);
        Assert.True(copy.References.RemoveAll([second.AsReadOnly(), null]));
        Assert.Equal(new[] { 0, 1, 2, 4 }, Assert.IsType<ModelCollectionChange>(Assert.Single(firstWatch)).Removed.Select(pair => pair.Index!.Value));
        Assert.Equal(new[] { 0, 1, 2, 4 }, Assert.IsType<ModelCollectionChange>(Assert.Single(secondWatch)).Removed.Select(pair => pair.Index!.Value));
    }

    [Fact]
    public void SparseReferenceAndOwnershipFiltersRoundTripTheSameV1Positions()
    {
        const string checkpoint = "bulk-filter";
        var binding = new JournalBinding("ordinary-list-bulk", 1, value => value is ValueMatchedCard card ? card.Id! : "list");
        var (graph, _, second, spare) = NewGraph();
        string saved = ModelSnapshot.Write(graph);
        using var recorder = JournalRecorder<ValueMatchedList>.Start(graph, ModelSnapshot.Registry, binding, checkpoint, 20);
        Assert.True(graph.References.RemoveAll([second.AsReadOnly(), null]));
        Assert.NotNull(recorder.PublishFrame());
        Assert.True(graph.Children.RemoveAll([second.AsReadOnly(), null]));
        var journal = JournalJson.Read(JournalJson.Write(recorder.Finish()));
        Assert.Equal(1, journal.WireVersion);
        Assert.Equal(new[] { 0, 1, 2, 4 }, Assert.IsType<JournalListEdit>(Assert.Single(journal.Transactions[0].Operations)).Indices);
        Assert.Equal(new[] { 0, 1, 2 }, Assert.IsType<JournalListEdit>(Assert.Single(journal.Transactions[1].Operations)).Indices);

        var baseline = ModelSnapshot.Read<ValueMatchedList>(saved);
        var replayed = JournalReplayer.Replay(baseline, binding, checkpoint, journal, ModelSnapshot.Registry);
        var replayedSpare = Assert.IsType<ValueMatchedCard>(Assert.Single(replayed.Children));
        Assert.Same(replayedSpare, Assert.Single(replayed.References));
        Assert.Same(replayed, replayedSpare.Owner);
        Assert.Equal(spare.Id, replayedSpare.Id);
        Assert.Equal(4, baseline.Children.Count);
        Assert.Same(baseline.Children[0], baseline.References[4]);
    }

    private static (ValueMatchedList Graph, ValueMatchedCard First, ValueMatchedCard Second, ValueMatchedCard Spare) NewGraph(
        bool spareBetweenMatches = false)
    {
        var first = new ValueMatchedCard { Id = "first", Caption = "same" };
        var second = new ValueMatchedCard { Id = "second", Caption = "same" };
        var spare = new ValueMatchedCard { Id = "spare", Caption = "spare" };
        ValueMatchedCard?[] children = spareBetweenMatches ? [first, spare, second, null] : [first, null, second, spare];
        ValueMatchedCard?[] references = [.. children, first];
        var owner = ValueMatchedList.NewBuilder().WithChildren(children).WithReferences(references).Build();
        return (owner, first, second, spare);
    }
}
