using Eu.Mihosoft.Vmf.Conformance.Values;
using Eu.Mihosoft.Vmf.Example.Workshops;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ObservationTests
{
    [Theory]
    [InlineData("start", "reference")]
    [InlineData("clear", "reference")]
    [InlineData("stop", "reference")]
    [InlineData("start", "attach")]
    [InlineData("clear", "attach")]
    [InlineData("stop", "attach")]
    [InlineData("start", "detach")]
    [InlineData("clear", "detach")]
    [InlineData("stop", "detach")]
    public void ModelPropertyRecordingFollowsTheNativeAncestorRelay(string lifecycle, string edit)
    {
        var root = new ArchiveNode();
        var child = new ArchiveNode();
        var target = new ArchiveNode();
        root.Children.Add(child);
        bool owning = edit != "reference";
        var property = owning ? "inset" : "anchor";
        ArchiveNode? before = edit == "detach" ? target : null;
        ArchiveNode? after = before is null ? target : null;
        if (before is not null) child.Inset = before;
        var changes = root.VmfChanges;
        var trace = new List<string>();
        using var local = child.VmfChanges.AddPropertyListener(property, _ =>
        {
            trace.Add($"child:{changes.History.Count}");
            switch (lifecycle)
            {
                case "start": changes.Start(); break;
                case "clear": changes.Clear(); break;
                case "stop": changes.Stop(); break;
            }
        });
        using var ancestor = changes.AddPropertyListener(property, change =>
        {
            Assert.Same(child, change.Source);
            Assert.Same(before, change.OldValue);
            Assert.Same(after, change.NewValue);
            trace.Add($"root:{changes.History.Count}");
        }, ModelChangeScope.Recursive);
        if (lifecycle != "start")
        {
            changes.Start();
            root.Name = "recorded before the reference";
        }

        if (owning) child.Inset = after;
        else child.Anchor = after;
        changes.Stop();
        Assert.Equal(new[] { $"child:{(lifecycle == "start" ? 0 : 1)}", $"root:{(lifecycle == "stop" ? 1 : 0)}" }, trace);
        var recorded = Assert.Single(changes.History);
        var transaction = Assert.Single(changes.Transactions);
        Assert.Same(recorded, Assert.Single(transaction.Changes));
        Assert.Same(after, owning ? child.Inset : child.Anchor);
        Assert.Same(owning && after is not null ? child : null, target.InsetOf);
        local.Dispose();
        ancestor.Dispose();
        if (lifecycle == "stop")
        {
            Assert.Equal("name", recorded.PropertyName);
        }
        else
        {
            Assert.Equal(property, recorded.PropertyName);
            Assert.Same(child, recorded.Source);
            Assert.Equal(owning, recorded.IsContainment);
            Assert.False(recorded.IsInverse);
            Assert.True(transaction.Undo());
            Assert.Same(before, owning ? child.Inset : child.Anchor);
            Assert.Same(owning && before is not null ? child : null, target.InsetOf);
            Assert.Same(child, Assert.Single(root.Children));
        }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void HistoryAndTransactionListsReportCommittedPositionsDuringReentrancy(bool readOnly)
    {
        var sample = new ScalarSample();
        if (readOnly)
        {
            var changes = sample.AsReadOnly().VmfChanges;
            VerifyHistoryPositions(sample, changes.History, changes.Transactions, changes.Start, changes.Stop,
                changes.Clear, () => changes.PublishTransaction());
        }
        else
        {
            var changes = sample.VmfChanges;
            VerifyHistoryPositions(sample, changes.History, changes.Transactions, changes.Start, changes.Stop,
                changes.Clear, () => changes.PublishTransaction());
        }
    }

    private static void VerifyHistoryPositions<TEdit, TGroup>(ScalarSample sample, IReadOnlyList<TEdit> history,
        ModelHistory<TGroup> transactions, Action start, Action stop, Action clear, Action publish)
        where TGroup : class
    {
        start();
        IReadOnlyList<TGroup> groups = transactions;
        var editEvents = new List<ModelListChange<TEdit>>();
        var groupEvents = new List<ModelListChange<TGroup>>();
        var groupCounts = new List<int>();
        using var historyObserver = history.AddListener(editEvents.Add);
        bool publishedNested = false;
        using var first = transactions.AddListener(change =>
        {
            if (change.Added.Count == 0 || publishedNested) return;
            publishedNested = true;
            sample.IntValue = 7;
            publish();
        });
        using var later = groups.AddListener(change =>
        {
            groupEvents.Add(change);
            groupCounts.Add(groups.Count);
        });

        sample.Description = "outer";
        publish();
        TEdit[] originalEdits = history.ToArray();
        TGroup[] originalGroups = groups.ToArray();
        Assert.All(editEvents, change => Assert.Same(history, change.Source));
        Assert.All(groupEvents, change => Assert.Same(groups, change.Source));
        Assert.Equal([0, 1], editEvents.Select(change => Assert.Single(change.Added).Index));
        Assert.Equal([1, 0], groupEvents.Select(change => Assert.Single(change.Added).Index));
        Assert.Equal([2, 2], groupCounts);
        Assert.Same(originalGroups[1], Assert.Single(groupEvents[0].Added).Value);
        Assert.Same(originalGroups[0], Assert.Single(groupEvents[1].Added).Value);

        clear();
        Assert.Equal([0, 1], editEvents[2].Removed.Select(entry => entry.Index));
        Assert.Equal([0, 1], groupEvents[2].Removed.Select(entry => entry.Index));
        Assert.Equal(originalEdits, editEvents[2].Removed.Select(entry => entry.Value));
        Assert.Equal(originalGroups, groupEvents[2].Removed.Select(entry => entry.Value));
        Assert.Empty(history);
        Assert.Empty(groups);
        stop();
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void ClearingHistoryRemovesTransactionsAfterHistoryObserversRun(bool readOnly)
    {
        var sample = new ScalarSample();
        if (readOnly)
        {
            var changes = sample.AsReadOnly().VmfChanges;
            VerifyReentrantHistoryClear(sample, changes.History, changes.Transactions, changes.Start, changes.Stop,
                changes.Clear, () => changes.PublishTransaction());
        }
        else
        {
            var changes = sample.VmfChanges;
            VerifyReentrantHistoryClear(sample, changes.History, changes.Transactions, changes.Start, changes.Stop,
                changes.Clear, () => changes.PublishTransaction());
        }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void ThrowingHistoryObserverLeavesTransactionsForTheNextClear(bool readOnly)
    {
        var sample = new ScalarSample();
        if (readOnly)
        {
            var changes = sample.AsReadOnly().VmfChanges;
            VerifyFailedHistoryClear(sample, changes.History, changes.Transactions, changes.Start, changes.Stop,
                changes.Clear, () => changes.PublishTransaction());
        }
        else
        {
            var changes = sample.VmfChanges;
            VerifyFailedHistoryClear(sample, changes.History, changes.Transactions, changes.Start, changes.Stop,
                changes.Clear, () => changes.PublishTransaction());
        }
    }

    private static void VerifyReentrantHistoryClear<TEdit, TGroup>(ScalarSample sample, ModelHistory<TEdit> history,
        ModelHistory<TGroup> transactions, Action start, Action stop, Action clear, Action publish)
    {
        start();
        sample.Description = "old group";
        publish();

        var observed = new List<string>();
        using var historyListener = history.AddListener(change =>
        {
            if (change.Removed.Count == 0) return;
            observed.Add($"clear edits:{history.Count}/{transactions.Count}");
            sample.OptionalInt = 88;
            publish();
        });
        using var transactionListener = transactions.AddListener(change =>
        {
            observed.Add($"groups:+{change.Added.Count}/-{change.Removed.Count}; current={transactions.Count}");
        });

        clear();
        Assert.Equal(["clear edits:0/1", "groups:+1/-0; current=2", "groups:+0/-2; current=0"], observed);
        Assert.Single(history);
        Assert.Empty(transactions);
        Assert.Equal(88, sample.OptionalInt);
        stop();
        Assert.Empty(transactions);
    }

    private static void VerifyFailedHistoryClear<TEdit, TGroup>(ScalarSample sample, ModelHistory<TEdit> history,
        ModelHistory<TGroup> transactions, Action start, Action stop, Action clear, Action publish)
    {
        start();
        sample.Description = "old group";
        publish();

        int removedGroups = 0;
        using var transactionListener = transactions.AddListener(change => removedGroups += change.Removed.Count);
        var failure = new InvalidOperationException("history listener");
        using (history.AddListener(change => { if (change.Removed.Count > 0) throw failure; }))
        {
            Assert.Same(failure, Assert.Throws<InvalidOperationException>(clear));
        }
        Assert.Empty(history);
        Assert.Single(transactions);
        Assert.Equal(0, removedGroups);
        Assert.Equal("old group", sample.Description);

        clear();
        Assert.Empty(transactions);
        Assert.Equal(1, removedGroups);
        stop();
    }

    [Fact]
    public void ScalarListenersFollowCurrentContainmentAndTheirScope()
    {
        var root = new ArchiveNode();
        var child = new ArchiveNode();
        var external = new ArchiveNode();
        root.Children.Add(child);
        root.Trail.Add(external);

        var local = new List<string?>();
        var names = new List<string?>();
        var filtered = new List<string?>();
        using var watchLocal = root.VmfChanges.AddLocalListener(change =>
        {
            if (change.PropertyName == "name") local.Add(change.NewValue as string);
        });
        using var watchNames = root.VmfChanges.AddPropertyListener("name", change => names.Add(change.NewValue as string),
            ModelChangeScope.Recursive);
        using var watchFiltered = root.VmfChanges.AddFilteredListener(
            change => ReferenceEquals(change.Source, child) && change.NewValue is "reattached",
            change => filtered.Add(change.NewValue as string), ModelChangeScope.Recursive);

        root.Name = "root";
        child.Name = "attached";
        child.Name = "attached";
        external.Name = "external";
        root.Children.Remove(child);
        child.Name = "detached";
        root.Inset = child;
        child.Name = "reattached";
        watchNames.Dispose();
        child.Name = "unsubscribed";

        Assert.Equal(["root"], local);
        Assert.Equal(["root", "attached", "reattached"], names);
        Assert.Equal(["reattached"], filtered);
    }

    [Fact]
    public void ModelReferencesEmitWhenAnEqualValuedInstanceIsReplaced()
    {
        var first = new ValueLabel("same");
        var second = new ValueLabel("same");
        Assert.Equal(first, second);
        var selection = new ValueSelection { Current = first };
        var observed = new List<ModelChange>();
        using var watch = selection.VmfChanges.AddLocalListener(observed.Add);

        selection.Current = second;
        selection.Current = second;
        var change = Assert.Single(observed);
        Assert.Same(selection, change.Source);
        Assert.Equal("current", change.PropertyName);
        Assert.Same(first, change.OldValue);
        Assert.Same(second, change.NewValue);
        Assert.True(change.CanUndo);
        Assert.True(change.Undo());
        Assert.Same(first, selection.Current);
        Assert.True(change.Redo());
        Assert.Same(second, selection.Current);
        Assert.ThrowsAny<ArgumentException>(() => change.Apply(new ValueSelection()));
    }

    [Fact]
    public void InheritedGenericPropertyUsesTheConcreteObjectsStream()
    {
        var catalog = new Catalog();
        Shelf<Instrument> shelf = catalog;
        var thermometer = new Thermometer { Sku = "T-1", Unit = "C" };
        shelf.Products.Add(thermometer);
        Assert.Same(shelf.VmfChanges, catalog.VmfChanges);
        var local = new List<ModelChange>();
        var recursive = new List<ModelChange>();
        using var watchLocal = shelf.VmfChanges.AddLocalListener(local.Add);
        using var watchRecursive = catalog.VmfChanges.AddRecursiveListener(recursive.Add);

        shelf.Featured = thermometer;
        thermometer.Unit = "K";
        var reference = Assert.Single(local);
        Assert.Equal("featured", reference.PropertyName);
        Assert.Same(catalog, reference.Source);
        Assert.Same(thermometer, reference.NewValue);
        Assert.Equal(2, recursive.Count);
        Assert.Same(reference, recursive[0]);
        Assert.Equal("unit", recursive[1].PropertyName);
        Assert.Same(thermometer, recursive[1].Source);
    }

    [Fact]
    public void PrimitiveAndBoxedFloatSettersRetainJavaNoOpRules()
    {
        var sample = new ScalarSample { OptionalDouble = +0.0 };
        var changes = new List<ModelChange>();
        using var watch = sample.VmfChanges.AddLocalListener(changes.Add);
        double negativeZero = BitConverter.Int64BitsToDouble(long.MinValue);

        sample.DoubleValue = negativeZero;
        Assert.Equal(0, BitConverter.DoubleToInt64Bits(sample.DoubleValue));
        sample.OptionalDouble = negativeZero;
        sample.OptionalDouble = negativeZero;
        var change = Assert.Single(changes);
        Assert.Equal("optionalDouble", change.PropertyName);
        Assert.Equal(0, BitConverter.DoubleToInt64Bits(Assert.IsType<double>(change.OldValue)));
        Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(Assert.IsType<double>(change.NewValue)));

        changes.Clear();
        const long firstNan = 0x7ff8000000000001L;
        const long secondNan = 0x7ff8000000000002L;
        sample.DoubleValue = BitConverter.Int64BitsToDouble(firstNan);
        sample.DoubleValue = BitConverter.Int64BitsToDouble(secondNan);
        sample.OptionalDouble = BitConverter.Int64BitsToDouble(firstNan);
        sample.OptionalDouble = BitConverter.Int64BitsToDouble(secondNan);
        Assert.Equal(["doubleValue", "optionalDouble"], changes.Select(change => change.PropertyName));
        Assert.Equal(secondNan, BitConverter.DoubleToInt64Bits(sample.DoubleValue));
        Assert.Equal(firstNan, BitConverter.DoubleToInt64Bits(Assert.IsType<double>(sample.OptionalDouble)));
    }

    [Fact]
    public void ReentrantScalarEditsDeliverDepthFirstAndRetainUndoOrder()
    {
        var root = new ArchiveNode();
        var changes = root.VmfChanges;
        var history = changes.History;
        var delivered = new List<string>();
        var committed = new List<ModelChange>();
        using var watchHistory = history.AddListener(update =>
        {
            Assert.Same(history, update.Source);
            committed.AddRange(update.Added);
        });
        using var first = changes.AddLocalListener(change =>
        {
            delivered.Add("first " + change.NewValue);
            if (change.NewValue is "outer")
            {
                Assert.Empty(history);
                root.Name = "nested";
            }
        });
        using var second = changes.AddLocalListener(change => delivered.Add("second " + change.NewValue));

        changes.Start();
        root.Name = "outer";
        var group = Assert.IsType<ModelTransaction>(changes.PublishTransaction());
        changes.Stop();

        Assert.Equal(["first outer", "first nested", "second nested", "second outer"], delivered);
        Assert.Equal(["outer", "nested"], history.Select(change => change.NewValue as string));
        Assert.Same(history, changes.History);
        Assert.Equal(history, committed);
        Assert.True(history[0].Timestamp < history[1].Timestamp);
        Assert.Same(group, Assert.Single(changes.Transactions));
        Assert.Same(history[0], group.Changes[0]);

        first.Dispose();
        second.Dispose();
        Assert.True(group.CanUndo);
        Assert.True(group.Undo());
        Assert.Null(root.Name);
        Assert.True(group.CanRedo);
        Assert.True(group.Redo());
        Assert.Equal("nested", root.Name);
        var newNode = new ArchiveNode();
        group.Changes[1].Apply(newNode);
        Assert.Equal("nested", newNode.Name);

        changes.Clear();
        Assert.Empty(history);
        Assert.Empty(changes.Transactions);
        Assert.Equal(2, group.Changes.Count);
    }

    [Fact]
    public void DirectReferenceHistoryRecordsNestedEditBeforeInitiatingEdit()
    {
        var selection = new ValueSelection();
        var first = new ValueLabel("first");
        var second = new ValueLabel("second");
        var changes = selection.VmfChanges;
        var delivered = new List<string>();
        using var initiating = changes.AddLocalListener(change =>
        {
            delivered.Add("first " + change.PropertyName);
            if (change.PropertyName == "current")
            {
                selection.Second = second;
                Assert.Equal("second", Assert.Single(changes.History).PropertyName);
            }
        });
        using var following = changes.AddLocalListener(change => delivered.Add("second " + change.PropertyName));

        changes.Start();
        selection.Current = first;
        changes.Stop();
        Assert.Equal(["first current", "first second", "second second", "second current"], delivered);
        Assert.Equal(["second", "current"], changes.History.Select(change => change.PropertyName));
        Assert.True(changes.History[0].Timestamp > changes.History[1].Timestamp);
    }

    [Fact]
    public void ThrowingListenerDoesNotSkipDeliveryOrUndoAppliedEdit()
    {
        var root = new ArchiveNode();
        var changes = root.VmfChanges;
        var expected = new InvalidOperationException("listener failure");
        var later = new ArgumentException("second listener failure");
        var observed = new List<ModelChange>();
        using var throwing = changes.AddLocalListener(_ => throw expected);
        using var secondThrowing = changes.AddLocalListener(_ => throw later);
        using var watch = changes.AddLocalListener(observed.Add);

        changes.Start();
        var actual = Assert.Throws<InvalidOperationException>(() => root.Name = "applied");
        Assert.Same(expected, actual);
        Assert.Equal("applied", root.Name);
        Assert.Same(Assert.Single(changes.History), Assert.Single(observed));
        Assert.Same(later, Assert.Single(ModelChanges.GetSuppressedObserverExceptions(actual)));
        throwing.Dispose();
        secondThrowing.Dispose();
        root.Name = "next";
        changes.Stop();
        Assert.Equal(["applied", "next"], Assert.Single(changes.Transactions).Changes.Select(c => c.NewValue as string));
    }

    [Fact]
    public void NestedPublicationCommitsEachRangeExactlyOnce()
    {
        var model = new ArchiveNode();
        var changes = model.VmfChanges;
        var delivered = new List<string>();
        changes.Start();
        using var first = changes.Transactions.AddListener(update =>
        {
            Assert.Same(changes.Transactions, update.Source);
            string? name = Assert.Single(Assert.Single(update.Added).Changes).NewValue as string;
            delivered.Add("first " + name);
            if (name == "outer")
            {
                Assert.Null(changes.PublishTransaction());
                model.Name = "nested";
                Assert.NotNull(changes.PublishTransaction());
                model.Name = "next";
            }
        });
        using var second = changes.Transactions.AddListener(update =>
            delivered.Add("second " + Assert.Single(Assert.Single(update.Added).Changes).NewValue));

        model.Name = "outer";
        changes.PublishTransaction();
        Assert.Equal(["first outer", "first nested", "second nested", "second outer"], delivered);
        changes.PublishTransaction();
        changes.Stop();
        Assert.Equal(["first outer", "first nested", "second nested", "second outer", "first next", "second next"], delivered);
        Assert.Equal(["outer", "nested", "next"], changes.Transactions.Select(group => group.Changes[0].NewValue as string));
        Assert.False(changes.IsRecording);
    }

    [Fact]
    public void ADispatchUsesTheListenersRegisteredWhenItStarted()
    {
        var model = new ArchiveNode();
        var changes = model.VmfChanges;
        var notifications = new List<string>();
        IDisposable? second = null;
        IDisposable? late = null;
        using var first = changes.AddLocalListener(change =>
        {
            notifications.Add("first " + change.NewValue);
            if (change.NewValue is "initial")
            {
                second?.Dispose();
                late = changes.AddLocalListener(next => notifications.Add("late " + next.NewValue));
            }
        });
        try
        {
            second = changes.AddLocalListener(change => notifications.Add("second " + change.NewValue));
            model.Name = "initial";
            model.Name = "next";
            Assert.Equal(["first initial", "second initial", "first next", "late next"], notifications);
        }
        finally
        {
            second?.Dispose();
            late?.Dispose();
        }
    }

    [Fact]
    public void FailedStopStillClosesRecordingAndRetainsPublishedTransaction()
    {
        var model = new ArchiveNode();
        var changes = model.VmfChanges;
        var expected = new InvalidOperationException("publication failed");
        int deliveryAfterFailure = 0;
        using var throwing = changes.Transactions.AddListener(_ => throw expected);
        using var later = changes.Transactions.AddListener(_ => deliveryAfterFailure++);

        changes.Start();
        model.Name = "recorded";
        Assert.Same(expected, Assert.Throws<InvalidOperationException>(() => changes.Stop()));
        Assert.Equal(0, deliveryAfterFailure);
        Assert.False(changes.IsRecording);
        Assert.Same(Assert.Single(changes.History), Assert.Single(Assert.Single(changes.Transactions).Changes));
        model.Name = "unrecorded";
        Assert.Single(changes.History);

        throwing.Dispose();
        changes.Start();
        model.Name = "restarted";
        changes.Stop();
        Assert.Equal("restarted", Assert.Single(Assert.Single(changes.Transactions).Changes).NewValue);
    }
}
