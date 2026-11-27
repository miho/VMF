using Eu.Mihosoft.Vmf.Conformance.Immutables;
using Eu.Mihosoft.Vmf.Conformance.Redeclarations;
using Eu.Mihosoft.Vmf.Conformance.Traits;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Eu.Mihosoft.Vmf.Example.Workshops;
using Vmf.Runtime;
using Xunit;
using EqualityContainer = Eu.Mihosoft.Vmf.Conformance.Equality.EqualityContainer;
using TraitModels = Eu.Mihosoft.Vmf.Conformance.Traits.ModelSnapshot;
using ValueModels = Eu.Mihosoft.Vmf.Conformance.Values.ModelSnapshot;

namespace Vmf.CSharp.Conformance;

public sealed class ModelVersionTests
{
    [Fact]
    public void RecordingAndManualVersioningShareACounterWithoutSharingHistoryLifetime()
    {
        var model = new EqualityContainer();
        var changes = model.VmfChanges;
        var initial = changes.ModelVersion;
        Assert.Equal(0, initial.VersionNumber);
        Assert.False(changes.IsModelVersioningEnabled);
        model.Name = "before";
        Assert.Same(initial, changes.ModelVersion);

        changes.Start();
        Assert.True(changes.IsModelVersioningEnabled);
        Assert.Throws<InvalidOperationException>(changes.DisableModelVersioning);
        model.Name = "published";
        var version = changes.ModelVersion;
        Assert.Equal(1, version.VersionNumber);
        Assert.Equal(Assert.Single(changes.History).Timestamp, version.Timestamp);
        Assert.NotNull(changes.PublishTransaction());
        Assert.Same(version, changes.ModelVersion);
        model.Name = "pending";
        changes.Stop();
        Assert.Equal(2, changes.ModelVersion.VersionNumber);
        Assert.False(changes.IsModelVersioningEnabled);
        Assert.Equal(2, changes.Transactions.Count);
        model.Name = "stopped";
        Assert.Equal(2, changes.ModelVersion.VersionNumber);

        changes.EnableModelVersioning();
        changes.EnableModelVersioning();
        model.Name = "version only";
        Assert.Equal(3, changes.ModelVersion.VersionNumber);
        Assert.Equal(2, changes.History.Count);
        changes.DisableModelVersioning();
        changes.Clear();
        Assert.Empty(changes.History);
        Assert.Equal(3, changes.ModelVersion.VersionNumber);
        changes.Start();
        model.Name = "next session";
        Assert.Equal(4, changes.ModelVersion.VersionNumber);
        Assert.Single(changes.History);
        changes.Stop();
    }

    [Fact]
    public void VersionTakesTheTimestampAtItsOwnListenerTurnDuringReentrantDelivery()
    {
        var model = new EqualityContainer { Name = "initial" };
        var changes = model.VmfChanges;
        var observations = new List<string>();
        using var earlier = changes.AddRecursiveListener(change =>
        {
            observations.Add($"before-{change.NewValue}={changes.ModelVersion.VersionNumber}");
            if (Equals("outer", change.NewValue)) model.Name = "nested";
        });
        changes.Start();
        using var later = changes.AddRecursiveListener(change =>
        {
            Assert.Equal(change.Timestamp, changes.ModelVersion.Timestamp);
            observations.Add($"after-{change.NewValue}={changes.ModelVersion.VersionNumber}");
        });
        model.Name = "outer";
        Assert.Equal(["before-outer=0", "before-nested=0", "after-nested=1", "after-outer=2"], observations);
        Assert.Equal(changes.History[0].Timestamp, changes.ModelVersion.Timestamp);
        Assert.True(changes.History[0].Timestamp < changes.History[1].Timestamp);
        changes.Stop();
    }

    [Fact]
    public void VersioningFollowsContainmentAndContinuesWhenAnEarlierObserverThrows()
    {
        var child = new ArchiveNode { Name = "owned" };
        var archive = new ArchiveRoot { Head = child };
        var changes = archive.VmfChanges;
        var failure = new InvalidOperationException("listener failure");
        using var failing = changes.AddPropertyListener("name", _ => throw failure, ModelChangeScope.Recursive);
        changes.EnableModelVersioning();
        Assert.Same(failure, Assert.Throws<InvalidOperationException>(() => child.Name = "owned edit"));
        Assert.Equal(1, changes.ModelVersion.VersionNumber);
        var detached = new ArchiveNode { Name = "outside" };
        child.Anchor = detached;
        Assert.Equal(2, changes.ModelVersion.VersionNumber);
        detached.Name = "external edit";
        Assert.Equal(2, changes.ModelVersion.VersionNumber);
        Assert.Empty(changes.History);
        changes.DisableModelVersioning();
    }

    [Fact]
    public void ReadOnlyViewHasIndependentHistoryWithProjectedSourceAndModelPayloads()
    {
        var child = new ArchiveNode();
        var model = new ArchiveRoot { Head = child };
        var view = model.AsReadOnly();
        var changes = view.VmfChanges;
        Assert.Same(changes, view.VmfChanges);
        Assert.Same(changes, ValueModels.Reflection.ChangesForReadOnlyView(view));
        Assert.Throws<ArgumentException>(() => ValueModels.Reflection.ChangesForReadOnlyView(model));
        var seen = new List<ReadOnlyModelChange>();
        var committed = new List<ReadOnlyModelChange>();
        using var subscription = changes.AddRecursiveListener(seen.Add);
        using var history = changes.History.AddListener(change => committed.AddRange(change.Added));
        changes.Start();
        child.Name = "existing child";
        Assert.Empty(seen);
        Assert.Equal(0, changes.ModelVersion.VersionNumber);

        model.Review.Add(child);
        var addition = Assert.Single(seen);
        Assert.Same(view, addition.Source);
        Assert.Same(child.AsReadOnly(), Assert.Single(addition.Added).Value);
        Assert.Same(addition, Assert.Single(committed));
        Assert.Same(addition, Assert.Single(changes.History));

        var replacement = new ArchiveNode();
        model.Head = replacement;
        replacement.Name = "new child";
        Assert.Equal(["review", "head", "head"], seen.Select(change => change.PropertyName));
        Assert.Same(child.AsReadOnly(), seen[1].OldValue);
        Assert.Null(seen[1].NewValue);
        Assert.Same(replacement.AsReadOnly(), seen[2].NewValue);
        Assert.All(seen, change => Assert.IsNotAssignableFrom<IModelGraphAccess>(change.Source));
        Assert.Equal(3, changes.ModelVersion.VersionNumber);
        Assert.Equal(0, model.VmfChanges.ModelVersion.VersionNumber);
        Assert.Empty(model.VmfChanges.History);

        var transaction = Assert.IsType<ReadOnlyModelTransaction>(changes.PublishTransaction());
        Assert.Same(transaction, Assert.Single(changes.Transactions));
        Assert.Same(seen[2], transaction.Changes[2]);
        Assert.Null(typeof(ReadOnlyModelChange).GetMethod(nameof(ModelChange.Apply), Type.EmptyTypes));
        Assert.Null(typeof(ReadOnlyModelTransaction).GetMethod(nameof(ModelTransaction.Undo)));
        changes.Stop();
        Assert.False(changes.IsModelVersioningEnabled);
    }

    [Fact]
    public void ReadOnlyInverseChangesAdvanceVersionWithoutEnteringHistory()
    {
        var station = new Station();
        var view = station.AsReadOnly();
        var changes = view.VmfChanges;
        var workshop = new Workshop();
        var seen = new List<ReadOnlyModelChange>();
        using var subscription = changes.AddPropertyListener("workshop", seen.Add);
        changes.Start();
        workshop.Stations.Add(station);
        workshop.Stations.Remove(station);
        Assert.Equal(2, changes.ModelVersion.VersionNumber);
        Assert.Empty(changes.History);
        Assert.Equal(2, seen.Count);
        Assert.All(seen, change => Assert.Same(view, change.Source));
        Assert.Same(workshop.AsReadOnly(), seen[0].NewValue);
        Assert.Same(workshop.AsReadOnly(), seen[1].OldValue);
        changes.Stop();
    }

    [Fact]
    public void GetterOnlyTraitAndImmutableModelsUseTheSafeViewWithoutExposingMutableHistory()
    {
        var badge = new MutableBadge();
        IReadOnlyBadgeTraits trait = badge;
        var changes = trait.VmfChanges;
        Assert.Same(badge.AsReadOnly().VmfChanges, changes);
        changes.Start();
        TraitModels.Reflection.Reflect(badge).FindProperty("name")!.Set("observed");
        Assert.Same(badge.AsReadOnly(), Assert.Single(changes.History).Source);
        Assert.Empty(badge.VmfChanges.History);
        changes.Stop();

        var immutable = new ImmutableBadge("fixed");
        var fixedChanges = immutable.VmfChanges;
        fixedChanges.Start();
        Assert.True(fixedChanges.IsModelVersioningEnabled);
        Assert.Equal(0, fixedChanges.ModelVersion.VersionNumber);
        fixedChanges.Stop();
        Assert.False(fixedChanges.IsModelVersioningEnabled);
        Assert.Empty(fixedChanges.History);
    }

    [Fact]
    public void ProjectedChangesApplyOnlySafePayloadsToAnExplicitWritableReceiver()
    {
        var source = new ArchiveNode { Name = "source" };
        var target = new ArchiveNode { Name = "target" };
        var view = source.AsReadOnly();
        var changes = view.VmfChanges;
        var rename = Capture(changes, () => source.Name = "captured");
        Assert.Same(view, rename.Source);
        Assert.Throws<ArgumentException>(() => rename.Apply(target.AsReadOnly()));
        rename.Apply(target);
        Assert.Equal("captured", target.Name);

        var nullSlot = Capture(changes, () => source.Trail.Add(null));
        nullSlot.Apply(target);
        Assert.Null(Assert.Single(target.Trail));

        var model = new ArchiveNode();
        var relation = Capture(changes, () => source.Anchor = model);
        Assert.Throws<ArgumentException>(() => relation.Apply(target));
        Assert.Throws<ArgumentException>(() => relation.Apply(source));
        Assert.Null(target.Anchor);
        Assert.Same(model, source.Anchor);

        var scalarSource = new CollectionProfile();
        var scalarTarget = new CollectionProfile();
        var scalarChanges = scalarSource.AsReadOnly().VmfChanges;
        scalarTarget.Messages.Add("existing");
        Capture(scalarChanges, () => scalarSource.Messages.Add("new")).Apply(scalarTarget);
        Assert.Equal(["new", "existing"], scalarTarget.Messages);
        scalarTarget.Counts.AddRange([1, 2, 3]);
        var bounded = Capture(scalarChanges, () => scalarSource.Counts.Add(9));
        Assert.Throws<ArgumentException>(() => bounded.Apply(scalarTarget));
        Assert.Equal([1, 2, 3], scalarTarget.Counts);

        scalarTarget.Options.Put("occupied", true);
        Capture(scalarChanges, () => scalarSource.Options.Put("occupied", null)).Apply(scalarTarget);
        Assert.True(scalarTarget.Options.ContainsKey("occupied"));
        Assert.Null(scalarTarget.Options["occupied"]);
        scalarTarget.Levels.Add(0.0);
        Capture(scalarChanges, () => scalarSource.Levels.Add(-0.0)).Apply(scalarTarget);
        Assert.Equal(2, scalarTarget.Levels.Count);
        Assert.True(scalarTarget.Levels.Contains(0.0));
        Assert.True(scalarTarget.Levels.Contains(-0.0));

        var opaqueSource = new PayloadRecord();
        var opaqueTarget = new PayloadRecord();
        var opaqueChanges = opaqueSource.AsReadOnly().VmfChanges;
        var external = new System.Text.StringBuilder("foreign host");
        Capture(opaqueChanges, () => opaqueSource.Payload = external).Apply(opaqueTarget);
        Assert.Same(external, opaqueTarget.Payload);
        var badge = new ImmutableBadge("foreign immutable");
        var opaque = Capture(opaqueChanges, () => opaqueSource.Payload = badge);
        Assert.Throws<ArgumentException>(() => opaque.Apply(opaqueTarget));
        Assert.Same(external, opaqueTarget.Payload);
    }

    private static ReadOnlyModelChange Capture(ReadOnlyModelChanges changes, Action edit)
    {
        var observed = new List<ReadOnlyModelChange>();
        using var subscription = changes.AddLocalListener(observed.Add);
        edit();
        return Assert.Single(observed);
    }
}
