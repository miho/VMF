using Eu.Mihosoft.Vmf.Conformance.Values;
using Eu.Mihosoft.Vmf.Example.Workshops;
using Vmf.Runtime;
using Xunit;
using Values = Eu.Mihosoft.Vmf.Conformance.Values.ModelSnapshot;
using Workshops = Eu.Mihosoft.Vmf.Example.Workshops.ModelSnapshot;
using Ordinary = Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Defaults = Eu.Mihosoft.Vmf.Conformance.Collectiondefaults;

namespace Vmf.CSharp.Conformance;

[Collection(Defaults.CollectionDefaultBindings.TestCollection)]
public class ModelPatchTests
{
    [Fact]
    public void OrdinaryContainmentPatchCommitsAndCanUndoWhenAnObserverThrows()
    {
        var source = new Ordinary.LegacyContainer();
        var retained = new Ordinary.LegacyChild { Name = "retained" };
        var departing = new Ordinary.LegacyChild { Name = "departing" };
        source.Children.AddRange([retained, departing]);
        var desired = Ordinary.ModelSnapshot.Reflection.DeepCopy(source);
        var arriving = new Ordinary.LegacyChild { Name = "arriving" };
        desired.Children[1] = arriving;

        var patch = Ordinary.ModelSnapshot.Reflection.Diff(source, desired);
        Assert.Equal("children", Assert.Single(patch.Changes).PropertyName);
        var failure = new InvalidOperationException("observer");
        var trace = new List<int>();
        using (source.VmfChanges.AddLocalListener(change =>
        {
            if (change.PropertyName != "children") return;
            trace.Add(source.Children.Count);
            Assert.Same(retained, source.Children[0]);
            Assert.Same(source, retained.Owner);
            Assert.Same(source, source.Children[1]!.Owner);
            throw failure;
        }))
        {
            Assert.Same(failure, Assert.Throws<InvalidOperationException>(() => patch.Apply(source)));
        }
        var inserted = source.Children[1]!;
        Assert.Equal(new[] { 2 }, trace);
        Assert.NotSame(arriving, inserted);
        Assert.Equal("arriving", inserted.Name);
        Assert.Null(departing.Owner);
        Assert.Same(desired, arriving.Owner);
        patch.Undo(source);
        Assert.Same(retained, source.Children[0]);
        Assert.Same(departing, source.Children[1]);
        Assert.Same(source, departing.Owner);
        Assert.Null(inserted.Owner);
    }

    [Fact]
    public void MergeUsesInstanceDefaultsForReplacementAndStillAppendsLists()
    {
        var template = new DefaultSettings { Name = "template" };
        var overlay = new DefaultSettings();
        Values.Reflection.Reflect(overlay).FindProperty("name")!.SetDefault("overlay default");
        Assert.Equal("overlay default", overlay.Name);
        Assert.Equal("template", Values.Reflection.Merge(template, overlay).Name);
        overlay.Name = "edit";
        Assert.Equal("edit", Values.Reflection.Merge(template, overlay).Name);

        var current = new CollectionProfile();
        current.Options.Add("kept", null);
        current.Levels.Add(1.0);
        current.Counts.Add(1);

        var collectionOverlay = new CollectionProfile();
        Values.Reflection.Reflect(collectionOverlay).FindProperty("options")!.SetDefault(new Dictionary<string, bool?> { ["new"] = true });
        Values.Reflection.Reflect(collectionOverlay).FindProperty("levels")!.SetDefault(new[] { 2.0 });
        Values.Reflection.Reflect(collectionOverlay).FindProperty("counts")!.SetDefault(new[] { 7 });

        var result = Values.Reflection.Merge(current, collectionOverlay);
        Assert.True(result.Options.TryGetValue("kept", out var value));
        Assert.Null(value);
        Assert.Single(result.Options);
        Assert.Equal([1.0], result.Levels);
        Assert.Equal([1, 7], result.Counts);
        Assert.True(collectionOverlay.Options["new"]);

        collectionOverlay.Options["new"] = false;
        collectionOverlay.Levels.Add(3.0);
        result = Values.Reflection.Merge(current, collectionOverlay);
        Assert.False(result.Options["new"]);
        Assert.False(result.Options.ContainsKey("kept"));
        Assert.True(result.Levels.ToHashSet().SetEquals([2.0, 3.0]));
        Assert.Equal([1, 7], result.Counts);
    }

    [Fact]
    public void OwnedMapSetAndSequenceSnapshotsUseLiveManagedCollections()
    {
        var source = new CollectionProfile();
        source.Options.Add("present", null);
        source.Levels.Add(1.0);
        source.Messages.AddRange(["old", null]);
        var desired = Values.Reflection.DeepCopy(source);
        desired.Options.Remove("present");
        desired.Options.Add("new", true);
        desired.Levels.Clear();
        desired.Levels.Add(2.0);
        desired.Messages.Add("new");
        var patch = Values.Reflection.Diff(source, desired);
        var actualMap = source.Options;
        var changes = new List<ModelChange>();
        using var subscription = source.VmfChanges.AddLocalListener(changes.Add);

        desired.Options["new"] = false;
        desired.Messages.Clear();
        source.Options.Add("stale", false);
        changes.Clear();
        Assert.Throws<InvalidOperationException>(() => patch.Apply(source, ModelPatchApplyMode.RequireBeforeValues));
        Assert.Empty(changes);
        Assert.Contains("stale", source.Options.Keys);
        Assert.Equal([1.0], source.Levels);

        patch.Apply(source);
        Assert.Same(actualMap, source.Options);
        Assert.Equal(true, source.Options["new"]);
        Assert.Single(source.Options);
        Assert.Equal([2.0], source.Levels);
        Assert.Equal(new string?[] { "old", null, "new" }, source.Messages);
        Assert.Contains(changes, change => change.Kind == ModelChangeKind.Map && change.PropertyName == "options");
        Assert.Contains(changes, change => change.Kind == ModelChangeKind.Set && change.PropertyName == "levels");
        Assert.Contains(changes, change => change.Kind == ModelChangeKind.List && change.PropertyName == "messages");

        patch.Undo(source);
        Assert.True(source.Options.TryGetValue("present", out var present));
        Assert.Null(present);
        Assert.Single(source.Options);
        Assert.Equal([1.0], source.Levels);
        Assert.Equal(new string?[] { "old", null }, source.Messages);
    }

    [Fact]
    public void MapWritesRestoreCapturedKeyOrderAndKeepLiveStorage()
    {
        var source = new CollectionProfile();
        source.Options.Add("first", null);
        source.Options.Add("second", true);
        source.Markers.Add(+0.0, 'a');
        source.Markers.Add(-0.0, null);
        var desired = new CollectionProfile();
        desired.Options.Add("second", false);
        desired.Options.Add("first", null);
        desired.Markers.Add(-0.0, 'b');
        desired.Markers.Add(+0.0, 'a');
        var options = source.Options;
        var readOnlyOptions = source.AsReadOnly().Options;
        var patch = Values.Reflection.Diff(source, desired);
        desired.Options.Clear();
        desired.Markers.Clear();

        var order = new List<string[]>();
        using var listener = source.VmfChanges.AddPropertyListener("options", _ => order.Add(options.Keys.ToArray()));
        patch.Apply(source);
        Assert.Same(options, source.Options);
        Assert.Equal(["second", "first"], readOnlyOptions.Keys);
        Assert.False(options["second"]);
        Assert.Null(options["first"]);
        Assert.Equal([long.MinValue, 0L], source.Markers.Keys.Select(BitConverter.DoubleToInt64Bits));
        Assert.Equal('b', source.Markers[-0.0]);
        Assert.Collection(order, keys => Assert.Equal(["second"], keys), keys => Assert.Equal(["second", "first"], keys));
        order.Clear();

        patch.Undo(source);
        Assert.Equal(["first", "second"], options.Keys);
        Assert.True(options["second"]);
        Assert.Equal([0L, long.MinValue], source.Markers.Keys.Select(BitConverter.DoubleToInt64Bits));
        Assert.Null(source.Markers[-0.0]);
        Assert.Collection(order, keys => Assert.Equal(["first"], keys), keys => Assert.Equal(["first", "second"], keys));

        var reordered = new CollectionProfile();
        reordered.Options.Add("second", true);
        reordered.Options.Add("first", null);
        reordered.Markers.Add(+0.0, 'a');
        reordered.Markers.Add(-0.0, null);
        Assert.Empty(Values.Reflection.Diff(source, reordered).Changes);
        var template = source.AsReadOnly();
        var overlay = reordered.AsReadOnly();
        var merged = Values.Reflection.Merge(template, overlay);
        Assert.NotSame(template, merged);
        Assert.Equal(["second", "first"], merged.Options.Keys);
        Assert.Null(merged.Options["first"]);
        Assert.Equal(["first", "second"], template.Options.Keys);
        Assert.Equal(["second", "first"], overlay.Options.Keys);

        options["second"] = false;
        patch.Apply(source);
        Assert.Equal(["second", "first"], options.Keys);
    }

    [Fact]
    public void AddedBranchesKeepMapOrderEvenWhenTheMappingsMatchConstructorDefaults()
    {
        var child = new Defaults.ManagedSamples();
        child.Options.Clear();
        child.Options.Add("known", 11);
        child.Options.Add("present", null);
        var source = new Defaults.DefaultCollections();
        var desired = new Defaults.DefaultCollections { Mutable = child };
        var patch = Defaults.ModelSnapshot.Reflection.Diff(source, desired);

        patch.Apply(source);

        var imported = Assert.IsType<Defaults.ManagedSamples>(source.Mutable);
        Assert.NotSame(child, imported);
        Assert.Same(child, desired.Mutable);
        Assert.Equal(["known", "present"], imported.Options.Keys);
        Assert.Null(imported.Options["present"]);
    }

    [Fact]
    public void RejectsIndependentOrReadonlyRootsAndDetachedAffectedObjectsBeforeWriting()
    {
        var child = Node("child");
        var source = Node("source");
        source.Inset = child;
        var desired = Values.Reflection.DeepCopy(source);
        desired.Name = "updated";
        desired.Inset!.Name = "updated child";
        var patch = Values.Reflection.Diff(source, desired.AsReadOnly());

        Assert.Same(source, patch.Source);
        Assert.Contains(patch.Changes, value => ReferenceEquals(value.Object, child) && value.PropertyName == "name");
        var independent = Values.Reflection.DeepCopy(source);
        Assert.Throws<ArgumentException>(() => patch.Apply(independent));
        Assert.Throws<ArgumentException>(() => patch.Apply(source.AsReadOnly()));
        Assert.Throws<ArgumentException>(() => Values.Reflection.Diff(source.AsReadOnly(), desired));
        Assert.Throws<ArgumentException>(() => Values.Reflection.Diff(source, new ArchiveRoot()));
        Assert.Equal("source", independent.Name);

        source.Inset = null;
        var other = Node("other");
        other.Inset = child;
        Assert.Throws<InvalidOperationException>(() => patch.Apply(source));
        Assert.Equal("source", source.Name);
        Assert.Equal("child", child.Name);
        Assert.Same(child, other.Inset);
    }

    [Fact]
    public void OwningAndNonowningReferencesShareCanonicalCounterpartsAndUndo()
    {
        var original = new Station { Name = "A" };
        var source = new Workshop();
        source.Stations.Add(original);
        var desired = Workshops.Reflection.DeepCopy(source);
        var addition = new Station { Name = "B" };
        desired.Stations.Add(addition);
        desired.Favorites.Add(addition);

        var patch = Workshops.Reflection.Diff(source, desired);
        Assert.Equal(["favorites", "stations"], patch.Changes.Select(change => change.PropertyName));
        var planned = Assert.IsAssignableFrom<IReadOnlyList<object?>>(patch.Changes.Single(change => change.PropertyName == "stations").After);
        Assert.Same(original.AsReadOnly(), planned[0]);
        Assert.IsAssignableFrom<IReadOnlyStation>(planned[1]);
        Assert.False(planned[1] is Station);
        Assert.Single(source.Stations);
        addition.Name = "mutated after diff";

        patch.Apply(source);
        var copied = source.Stations[1];
        Assert.Same(original, source.Stations[0]);
        Assert.NotSame(addition, copied);
        Assert.Equal("B", copied.Name);
        Assert.True(source.Favorites.Contains(copied));
        Assert.Same(source, copied.Workshop);
        Assert.Same(desired, addition.Workshop);

        patch.Undo(source);
        Assert.Same(original, Assert.Single(source.Stations));
        Assert.Empty(source.Favorites);
    }

    [Fact]
    public void RedirectsOrdinaryReferencesWithoutRenamingOrDetachingTheOriginalChildren()
    {
        var source = Node("head");
        var left = Node("left");
        var right = Node("right");
        source.Children.Add(left);
        source.Inset = right;
        source.Anchor = left;
        source.Trail.AddRange([left, null]);
        var desired = Values.Reflection.DeepCopy(source);
        desired.Anchor = desired.Inset;
        desired.Trail[0] = desired.Inset;
        desired.Trail.Add(desired.Inset);

        var patch = Values.Reflection.Diff(source, desired);
        Assert.Equal(["anchor", "trail"], patch.Changes.Select(change => change.PropertyName));
        patch.Apply(source);
        Assert.Same(left, Assert.Single(source.Children));
        Assert.Same(right, source.Inset);
        Assert.Same(right, source.Anchor);
        Assert.Equal(new ArchiveNode?[] { right, null, right }, source.Trail);
        Assert.Equal("left", left.Name);

        patch.Undo(source);
        Assert.Same(left, source.Anchor);
        Assert.Equal(new ArchiveNode?[] { left, null }, source.Trail);
    }

    [Fact]
    public void NewBranchIsFullyOwnedBeforeTheRootAttachmentIsObserved()
    {
        var source = new ArchiveRoot();
        var branch = Node("branch");
        var inset = Node("inset");
        var leaf = Node("leaf");
        branch.Inset = inset;
        inset.Children.Add(leaf);
        branch.Anchor = leaf;
        leaf.Anchor = branch;
        var desired = new ArchiveRoot { Head = branch };

        var events = new List<string>();
        using var listener = source.VmfChanges.AddRecursiveListener(change =>
        {
            if (!ReferenceEquals(change.Source, source) || change.PropertyName != "head")
            {
                events.Add(change.PropertyName);
                return;
            }
            if (source.Head is not { } attached)
            {
                events.Add("detach");
                return;
            }
            if (attached.Inset is not { } nested)
            {
                events.Add("attach:incomplete");
                return;
            }
            var nestedLeaf = Assert.Single(nested.Children);
            Assert.Same(attached, nested.InsetOf);
            Assert.Same(nested, nestedLeaf.Parent);
            Assert.Same(nestedLeaf, attached.Anchor);
            Assert.Same(attached, nestedLeaf.Anchor);
            events.Add($"attach:{nestedLeaf.Name}");
        });

        var patch = Values.Reflection.Diff(source, desired);
        Assert.Null(source.Head);
        Assert.Empty(events);
        patch.Apply(source);
        var applied = source.Head!;
        var appliedLeaf = Assert.Single(applied.Inset!.Children);
        Assert.NotSame(branch, applied);
        Assert.NotSame(leaf, appliedLeaf);
        Assert.Same(inset, leaf.Parent);
        Assert.Same(branch, desired.Head);

        patch.Undo(source);
        Assert.Null(source.Head);
        Assert.Same(appliedLeaf, Assert.Single(applied.Inset.Children));
        patch.Apply(source);
        Assert.Same(applied, source.Head);
        Assert.Same(appliedLeaf, Assert.Single(source.Head!.Inset!.Children));
        appliedLeaf.Name = "after attach";
        Assert.Equal(["attach:leaf", "detach", "attach:leaf", "name"], events);
    }

    [Fact]
    public void ATransferFromTheSourceGraphKeepsItsLiveOwnershipNotifications()
    {
        var source = Node("source");
        var child = Node("existing");
        source.Children.Add(child);
        var desired = Values.Reflection.DeepCopy(source);
        desired.Inset = Node("branch");
        desired.Inset.Children.Add(desired.Children[0]);

        var events = new List<string>();
        using var listener = source.VmfChanges.AddRecursiveListener(change =>
        {
            if (ReferenceEquals(change.Source, source) && change.PropertyName == "inset")
            {
                events.Add($"inset:{source.Inset?.Children.Count}");
            }
            if (change.PropertyName == "children" && change.Source is ArchiveNode owner)
            {
                events.Add($"children:{owner.Name}");
            }
        });
        var patch = Values.Reflection.Diff(source, desired);
        Assert.Same(child, Assert.Single(source.Children));
        Assert.Null(source.Inset);
        Assert.Empty(events);

        patch.Apply(source);
        var branch = Assert.IsType<ArchiveNode>(source.Inset);
        Assert.Empty(source.Children);
        Assert.Same(child, Assert.Single(branch.Children));
        Assert.Same(branch, child.Parent);
        Assert.Equal(["children:source", "inset:0", "children:branch"], events);

        patch.Undo(source);
        Assert.Null(source.Inset);
        Assert.Same(child, Assert.Single(source.Children));
        Assert.Same(source, child.Parent);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void UnambiguousChildTransfersKeepTheOriginalObjectAcrossOwningFields(bool useSingularDestination)
    {
        var source = Node("root");
        var child = Node("moving");
        var other = Node("other");
        source.Children.Add(child);
        source.Inset = other;
        source.Anchor = child;
        var desired = Values.Reflection.DeepCopy(source);
        if (useSingularDestination) desired.Inset = desired.Children[0];
        else desired.Inset!.Children.Add(desired.Children[0]);

        var patch = Values.Reflection.Diff(source, desired);
        Assert.Empty(other.Children);
        Assert.Same(source, child.Parent);
        Assert.DoesNotContain(patch.Changes, change => change.PropertyName == "anchor");
        patch.Apply(source, ModelPatchApplyMode.RequireBeforeValues);

        Assert.Empty(source.Children);
        Assert.Same(child, source.Anchor);
        if (useSingularDestination)
        {
            Assert.Same(child, source.Inset);
            Assert.Null(child.Parent);
            Assert.Same(source, child.InsetOf);
            Assert.Null(other.InsetOf);
        }
        else
        {
            Assert.Same(other, source.Inset);
            Assert.Same(child, Assert.Single(other.Children));
            Assert.Same(other, child.Parent);
        }

        patch.Undo(source);
        Assert.Same(child, Assert.Single(source.Children));
        Assert.Same(source, child.Parent);
        Assert.Same(other, source.Inset);
        Assert.Same(source, other.InsetOf);
        Assert.Empty(other.Children);
    }

    [Fact]
    public void RejectsAFaultyFactoryBeforeItCanRewriteAnUnmatchedSourceInstance()
    {
        var sourceChild = new ManualNode { Name = "source" };
        var source = new ManualRoot { Target = sourceChild };
        var desiredChild = new ManualNode { Name = "desired" };
        var desired = new ManualRoot { Target = desiredChild };
        var snapshots = new SnapshotRegistry([
            new SnapshotType("root", typeof(ManualRoot), static () => new ManualRoot(), [
                new("target", SnapshotPropertyKind.Reference, typeof(ManualNode),
                    static model => ((ManualRoot)model).Target, set: static (model, value) => ((ManualRoot)model).Target = (ManualNode?)value),
            ]),
            new SnapshotType("node", typeof(ManualNode), () => sourceChild, [
                new("name", SnapshotPropertyKind.Scalar, typeof(string),
                    static model => ((ManualNode)model).Name, set: static (model, value) => ((ManualNode)model).Name = (string?)value),
            ]),
        ]);
        var reflection = new ModelReflectionRegistry(snapshots, [
            new ModelReflectionType(typeof(ManualRoot), [new("target", static model => ((ManualRoot)model).Target)]),
            new ModelReflectionType(typeof(ManualNode), [new("name", static model => ((ManualNode)model).Name)]),
        ]);

        Assert.Throws<InvalidOperationException>(() => reflection.Diff(source, desired));
        Assert.Same(sourceChild, source.Target);
        Assert.Equal("source", sourceChild.Name);
        Assert.Equal("desired", desiredChild.Name);
    }

    [Fact]
    public void RemovingAnOwningSubtreeKeepsItsOriginalChildrenAvailableForUndo()
    {
        var source = Node("root");
        var holder = Node("holder");
        var child = Node("child");
        source.Inset = holder;
        holder.Children.Add(child);
        var desired = Values.Reflection.DeepCopy(source);
        desired.Children.Add(desired.Inset!.Children[0]);
        desired.Inset = null;

        var patch = Values.Reflection.Diff(source, desired);
        patch.Apply(source);
        Assert.Null(source.Inset);
        Assert.NotSame(child, Assert.Single(source.Children));
        Assert.Same(child, Assert.Single(holder.Children));
        Assert.Same(holder, child.Parent);

        patch.Undo(source);
        Assert.Empty(source.Children);
        Assert.Same(holder, source.Inset);
        Assert.Same(child, Assert.Single(holder.Children));
    }

    [Fact]
    public void MatchesScalarAndReferenceKeyedChildrenBeforeComparingTheirFields()
    {
        var source = new KeyedInventory();
        var product = new KeyedItem { Sku = "A", Quantity = 1 };
        source.Products.Add(product);
        var part = new KeyedPart { Name = "part" };
        source.Parts.Add(part);
        var assignment = new KeyedAssignment { Part = part, Count = 1 };
        source.Assignments.Add(assignment);
        var desired = Values.Reflection.DeepCopy(source);
        desired.FindProductsBySku("A")!.Quantity = 2;
        desired.FindAssignmentsByPart(desired.Parts[0])!.Count = 3;
        var nextPart = new KeyedPart { Name = "new part" };
        desired.Parts.Add(nextPart);
        desired.Assignments.Add(new KeyedAssignment { Part = nextPart, Count = 4 });

        var patch = Values.Reflection.Diff(source, desired);
        patch.Apply(source);
        Assert.Same(product, source.FindProductsBySku("A"));
        Assert.Equal(2, product.Quantity);
        Assert.Same(assignment, source.FindAssignmentsByPart(part));
        Assert.Equal(3, assignment.Count);
        var newPart = source.Parts[1];
        Assert.NotSame(nextPart, newPart);
        Assert.Same(newPart, source.FindAssignmentsByPart(newPart)!.Part);

        patch.Undo(source);
        Assert.Same(part, Assert.Single(source.Parts));
        Assert.Same(assignment, Assert.Single(source.Assignments));
        Assert.Equal(1, assignment.Count);
        Assert.Same(product, source.FindProductsBySku("A"));
        Assert.Equal(1, product.Quantity);
    }

    [Fact]
    public void MergeKeepsDefaultsAndCopiesBothInputsWhileCombiningCollections()
    {
        var template = new DefaultSettings { Name = "template", Limit = 9, Note = "template note" };
        var overlay = new DefaultSettings();
        Assert.Equal("template", Values.Reflection.Merge(template, overlay).Name);
        overlay.Name = null;
        var explicitNull = Values.Reflection.Merge(template, overlay);
        Assert.Null(explicitNull.Name);
        Assert.Equal(9, explicitNull.Limit);
        Assert.Equal("template note", explicitNull.Note);

        var first = new CollectionProfile();
        first.Options.Add("old", null);
        first.Levels.Add(1.0);
        first.Messages.Add("old");
        var second = new CollectionProfile();
        second.Options.Add("new", false);
        second.Levels.Add(2.0);
        second.Messages.Add("new");
        var collections = Values.Reflection.Merge(first, second);
        Assert.Equal(["old", "new"], collections.Messages);
        Assert.Equal([2.0], collections.Levels);
        Assert.True(collections.Options.TryGetValue("new", out var value));
        Assert.Equal(false, value);
        Assert.Single(collections.Options);
        Assert.True(Values.Reflection.Merge(first, new CollectionProfile()).Options.ContainsKey("old"));

        var archive = Node("template");
        var added = Node("overlay");
        var child = Node("child");
        added.Children.Add(child);
        added.Anchor = child;
        child.Anchor = added;
        var combined = Values.Reflection.Merge(archive, added);
        var copied = Assert.Single(combined.Children);
        Assert.NotSame(child, copied);
        Assert.Same(copied, combined.Anchor);
        Assert.Same(combined, copied.Anchor);
        Assert.Same(child, Assert.Single(added.Children));
        Assert.Empty(archive.Children);
    }

    [Fact]
    public void MergeAppliesEditsToAnIndependentReadOnlyTemplateProjection()
    {
        var template = Node("template");
        var originalChild = Node("child");
        template.Children.Add(originalChild);
        template.Anchor = originalChild;
        originalChild.Anchor = template;
        var overlay = Node("edited");
        var view = template.AsReadOnly();

        var merged = Values.Reflection.Merge(view, overlay.AsReadOnly());
        Assert.NotSame(view, merged);
        Assert.Equal("edited", merged.Name);
        var child = Assert.Single(merged.Children);
        Assert.NotSame(originalChild.AsReadOnly(), child);
        Assert.Same(child, merged.Anchor);
        Assert.Same(merged, child!.Anchor);
        Assert.Same(merged, child.Parent);
        var field = Values.Reflection.Reflect(merged).FindProperty("name")!;
        Assert.False(field.CanWrite);
        Assert.Throws<InvalidOperationException>(() => field.Set("not allowed"));
        Assert.Equal("template", template.Name);
        Assert.Same(originalChild, Assert.Single(template.Children));
        Assert.Same(template, originalChild.Anchor);
        Assert.Equal("edited", overlay.Name);
    }

    [Fact]
    public void MergeRebindsOverlayRootReferencesInDirectAndOwnedFields()
    {
        foreach (bool projected in new[] { false, true })
        {
            var template = Node("template");
            var overlay = Node("edited");
            overlay.Anchor = overlay;
            overlay.Trail.AddRange([overlay, null, overlay]);
            overlay.Peers.Add(overlay);
            var merged = MergeArchive(template, overlay, projected);
            Assert.Same(merged, merged.Anchor);
            Assert.Equal(new IReadOnlyArchiveNode?[] { merged, null, merged }, merged.Trail);
            Assert.Same(merged, Assert.Single(merged.Peers));
            Assert.Null(template.Anchor);
            Assert.Empty(template.Peers);
            Assert.Same(overlay, overlay.Anchor);
            Assert.Same(overlay, overlay.Trail[2]);
            Assert.Same(overlay, Assert.Single(overlay.Peers));

            var childTemplate = Node("template");
            var childOverlay = Node("overlay");
            var originalChild = Node("owned");
            childOverlay.Children.Add(originalChild);
            childOverlay.Anchor = originalChild;
            originalChild.Anchor = childOverlay;
            originalChild.Trail.AddRange([originalChild, childOverlay]);
            var combined = MergeArchive(childTemplate, childOverlay, projected);
            var child = Assert.Single(combined.Children);
            Assert.NotSame(originalChild.AsReadOnly(), child);
            Assert.Same(child, combined.Anchor);
            Assert.Same(combined, child!.Parent);
            Assert.Same(combined, child.Anchor);
            Assert.Same(child, child.Trail[0]);
            Assert.Same(combined, child.Trail[1]);
            Assert.Empty(childTemplate.Children);
            Assert.Same(originalChild, Assert.Single(childOverlay.Children));
            Assert.Same(childOverlay, originalChild.Parent);
            Assert.Same(childOverlay, originalChild.Anchor);
        }

        static IReadOnlyArchiveNode MergeArchive(ArchiveNode template, ArchiveNode overlay, bool projected) =>
            projected ? Values.Reflection.Merge(template.AsReadOnly(), overlay.AsReadOnly())
                : Values.Reflection.Merge(template, overlay).AsReadOnly();
    }

    [Fact]
    public void MergeWithOneInputClonesItsGraphAndKeepsImmutableValues()
    {
        var original = Node("root");
        var shared = Node("child");
        original.Children.Add(shared);
        original.Anchor = shared;
        shared.Anchor = original;

        foreach (var copy in new[] { Values.Reflection.Merge(original, null), Values.Reflection.Merge(null, original) })
        {
            var child = Assert.Single(copy.Children);
            Assert.NotSame(original, copy);
            Assert.NotSame(shared, child);
            Assert.Same(child, copy.Anchor);
            Assert.Same(copy, child.Anchor);
        }

        Assert.Null(Values.Reflection.Merge<ArchiveNode>(null, null));
        var immutable = ValueLabel.NewBuilder().WithText("shared").Build();
        Assert.Same(immutable, Values.Reflection.Merge(immutable, null));
        Assert.Same(immutable, Values.Reflection.Merge(null, immutable));
    }

    private static ArchiveNode Node(string name) => new() { Name = name };

    private sealed class ManualRoot
    {
        public ManualNode? Target { get; set; }
    }

    private sealed class ManualNode
    {
        public string? Name { get; set; }
    }
}
