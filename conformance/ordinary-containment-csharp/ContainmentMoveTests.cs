using Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

/// <summary>
/// Edits of one containment list that change no owner: a move, its undo and redo, and a range that holds one child
/// in several slots.
/// </summary>
public class ContainmentMoveTests
{
    [Fact]
    public void AMoveInAnOwningListReportsTheShiftedRangeAndCanBeUndoneAndRedone()
    {
        var root = Container("a", "b", "c", "d", "e");
        var (a, b, c, d, e) = (root.Children[0]!, root.Children[1]!, root.Children[2]!, root.Children[3]!, root.Children[4]!);
        var edits = new List<ModelCollectionChange>();
        var others = new List<ModelChange>();
        using var watch = root.VmfChanges.AddListener(change =>
        {
            if (change is ModelCollectionChange edit) edits.Add(edit);
            else others.Add(change);
        }, ModelChangeScope.Recursive);

        root.Children.Move(1, 3);

        Assert.Equal(new[] { a, c, d, b, e }, root.Children);
        var move = Assert.Single(edits);
        Assert.Equal(ModelChangeKind.List, move.Kind);
        Assert.Equal("children", move.PropertyName);
        Assert.Equal(new (int?, object?)[] { (1, b), (2, c), (3, d) }, move.Removed.Select(entry => (entry.Index, entry.Value)));
        Assert.Equal(new (int?, object?)[] { (1, c), (2, d), (3, b) }, move.Added.Select(entry => (entry.Index, entry.Value)));
        Assert.True(move.CanUndo);
        Assert.False(move.CanRedo);

        Assert.True(move.Undo());
        Assert.Equal(new[] { a, b, c, d, e }, root.Children);
        Assert.Equal(2, edits.Count);
        Assert.Equal(new (int?, object?)[] { (1, c), (2, d), (3, b) }, edits[1].Removed.Select(entry => (entry.Index, entry.Value)));
        Assert.Equal(new (int?, object?)[] { (1, b), (2, c), (3, d) }, edits[1].Added.Select(entry => (entry.Index, entry.Value)));
        Assert.False(move.CanUndo);
        Assert.True(move.CanRedo);
        Assert.False(move.Undo());

        Assert.True(move.Redo());
        Assert.Equal(new[] { a, c, d, b, e }, root.Children);
        Assert.Equal(3, edits.Count);

        // The replayed edit is an edit of its own.
        Assert.True(edits[2].Undo());
        Assert.Equal(new[] { a, b, c, d, e }, root.Children);
        Assert.True(edits[1].CanUndo);

        // No child changed its owner at any time.
        Assert.Empty(others);
        Assert.All(new[] { a, b, c, d, e }, child => Assert.Same(root, child.Owner));
    }

    [Fact]
    public void AMoveToALowerIndexAndAMoveInAnOrderedSetReportTheirRange()
    {
        var layout = new LegacyLayout();
        var containers = new[] { Container(), Container(), Container(), Container() };
        layout.Containers.AddRange(containers);
        var edits = new List<ModelCollectionChange>();
        using var watch = layout.VmfChanges.AddListener(
            change => edits.Add(Assert.IsType<ModelCollectionChange>(change)), ModelChangeScope.Local);

        layout.Containers.Move(3, 1);

        Assert.Equal(new[] { containers[0], containers[3], containers[1], containers[2] }, layout.Containers);
        var move = Assert.Single(edits);
        Assert.Equal(ModelChangeKind.ReferenceSet, move.Kind);
        Assert.Equal(new int?[] { 1, 2, 3 }, move.Removed.Select(entry => entry.Index));
        Assert.Equal(new object?[] { containers[1], containers[2], containers[3] }, move.Removed.Select(entry => entry.Value));
        Assert.Equal(new object?[] { containers[3], containers[1], containers[2] }, move.Added.Select(entry => entry.Value));

        Assert.True(move.Undo());
        Assert.Equal(containers, layout.Containers);
        move.Apply();
        Assert.Equal(new[] { containers[0], containers[3], containers[1], containers[2] }, layout.Containers);
        Assert.Equal(3, edits.Count);

        layout.Containers.Move(2, 2);
        Assert.Equal(3, edits.Count);
        Assert.Throws<ArgumentOutOfRangeException>(() => layout.Containers.Move(0, 4));
        Assert.Throws<ArgumentOutOfRangeException>(() => layout.Containers.Move(-1, 0));
    }

    [Fact]
    public void AMoveKeepsAChildThatOccupiesSeveralSlots()
    {
        var root = Container("a", "b", "placeholder", "c");
        var (a, b, c) = (root.Children[0]!, root.Children[1]!, root.Children[3]!);
        root.Children[2] = a;
        Assert.Equal(new[] { a, b, a, c }, root.Children);
        int ownerChanges = 0;
        using var watch = a.VmfChanges.AddPropertyListener("owner", _ => ownerChanges++);
        ModelCollectionChange? move = null;
        using var edits = root.VmfChanges.AddListener(change => move = (ModelCollectionChange)change, ModelChangeScope.Local);

        root.Children.Move(0, 3);

        Assert.Equal(new[] { b, a, c, a }, root.Children);
        Assert.Equal(new object?[] { a, b, a, c }, move!.Removed.Select(entry => entry.Value));
        Assert.Equal(new object?[] { b, a, c, a }, move.Added.Select(entry => entry.Value));
        Assert.True(move.Undo());
        Assert.Equal(new[] { a, b, a, c }, root.Children);
        Assert.Same(root, a.Owner);
        Assert.Equal(0, ownerChanges);
    }

    [Fact]
    public void ARecordedMoveIsUndoneAndRedoneWithItsTransaction()
    {
        var root = Container("a", "b", "c", "d");
        var initial = root.Children.ToArray();
        var changes = root.VmfChanges;
        changes.Start();
        changes.StartTransaction();
        root.Name = "moved";
        root.Children.Move(0, 3);
        root.Children.Move(2, 0);
        changes.PublishTransaction();
        changes.Stop();
        var moved = root.Children.ToArray();
        Assert.Equal(new[] { initial[3], initial[1], initial[2], initial[0] }, moved);
        var transaction = Assert.Single(changes.Transactions);
        Assert.Equal(3, transaction.Changes.Count);

        Assert.True(transaction.CanUndo);
        Assert.True(transaction.Undo());
        Assert.Equal(initial, root.Children);
        Assert.Equal("root", root.Name);
        Assert.False(transaction.CanUndo);

        Assert.True(transaction.CanRedo);
        Assert.True(transaction.Redo());
        Assert.Equal(moved, root.Children);
        Assert.Equal("moved", root.Name);

        Assert.True(transaction.Undo());
        foreach (var change in transaction.Changes) change.Apply();
        Assert.Equal(moved, root.Children);
        Assert.All(initial, child => Assert.Same(root, child!.Owner));
    }

    [Fact]
    public void ARecordedMoveDoesNotUndoAChangedRangeAndAppliesAsAReplacement()
    {
        var root = Container("a", "b", "c", "d");
        var (a, b, c, d) = (root.Children[0]!, root.Children[1]!, root.Children[2]!, root.Children[3]!);
        ModelCollectionChange? move = null;
        var watch = root.VmfChanges.AddListener(change => move = (ModelCollectionChange)change, ModelChangeScope.Local);
        root.Children.Move(0, 2);
        watch.Dispose();
        Assert.Equal(new[] { b, c, a, d }, root.Children);

        // The range no longer holds what the move left there.
        var other = new LegacyContainer { Name = "other" };
        other.Children.Add(c);
        Assert.Equal(new[] { b, a, d }, root.Children);
        Assert.False(move!.CanUndo);
        Assert.False(move.Undo());
        Assert.False(move.CanRedo);
        Assert.Equal(new[] { b, a, d }, root.Children);

        // Applying puts the recorded arrangement over the recorded range, whatever it holds now.
        move.Apply();
        Assert.Equal(new[] { b, c, a }, root.Children);
        Assert.Same(root, c.Owner);
        Assert.Empty(other.Children);
        Assert.Null(d.Owner);
    }

    [Fact]
    public void AnUnobservedMoveMakesAnOpenCursorStale()
    {
        var root = Container("a", "b", "c");
        using var cursor = ModelSnapshot.Reflection.OpenContainmentCursor(root);
        Assert.True(cursor.MoveNext());
        Assert.True(cursor.MoveNext());
        Assert.Same(root.Children[0], cursor.Current);

        // The slot of the cursor keeps its child; the other two slots change.
        root.Children.Move(1, 2);

        Assert.Throws<InvalidOperationException>(() => cursor.MoveNext());
    }

    [Fact]
    public void ClearingARangeDetachesAChildThatOccupiesSeveralSlotsOnce()
    {
        var root = Container("a", "b", "placeholder");
        var (a, b) = (root.Children[0]!, root.Children[1]!);
        root.Children[2] = a;
        var owners = new List<(object? Old, object? New)>();
        using var watch = a.VmfChanges.AddPropertyListener("owner", change => owners.Add((change.OldValue, change.NewValue)));
        ModelCollectionChange? clear = null;
        using var edits = root.VmfChanges.AddListener(change => clear = (ModelCollectionChange)change, ModelChangeScope.Local);

        root.Children.Clear();

        Assert.Empty(root.Children);
        Assert.Null(a.Owner);
        Assert.Null(b.Owner);
        Assert.Equal(new (object?, object?)[] { (root, null) }, owners);
        Assert.Equal(new object?[] { a, b, a }, clear!.Removed.Select(entry => entry.Value));

        Assert.True(clear.Undo());
        Assert.Equal(new[] { a, b, a }, root.Children);
        Assert.Same(root, a.Owner);
        Assert.Equal(new (object?, object?)[] { (root, null), (null, root) }, owners);
    }

    private static LegacyContainer Container(params string[] children)
    {
        var root = new LegacyContainer { Name = "root" };
        foreach (string name in children) root.Children.Add(new LegacyChild { Name = name });
        return root;
    }
}
