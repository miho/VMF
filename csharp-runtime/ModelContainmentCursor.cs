using System.Collections;
using System.Diagnostics.CodeAnalysis;

namespace Vmf.Runtime;

public sealed partial class ModelReflectionRegistry
{
    /// <summary>Opens a depth-first cursor over the owning edges of a writable model.</summary>
    public ModelContainmentCursor OpenContainmentCursor(object root)
    {
        var model = Bind(root);
        if (model.IsReadOnly) throw new ArgumentException("A containment cursor requires a writable root.", nameof(root));
        return new(model, Bind);
    }

    /// <summary>Opens a depth-first cursor over a writable model, visiting each mutable identity once by default.</summary>
    /// <remarks>Choose <see cref="ModelTraversalStrategy.UniqueProperty"/> to select each aliased occurrence.</remarks>
    public ModelGraphCursor OpenGraphCursor(object root, ModelTraversalStrategy strategy = ModelTraversalStrategy.UniqueNode)
    {
        if (!Enum.IsDefined(strategy)) throw new ArgumentOutOfRangeException(nameof(strategy));
        var model = Bind(root);
        if (model.IsReadOnly) throw new ArgumentException("A graph cursor requires a writable root.", nameof(root));
        return new(model, Bind, strategy, editUnorderedContainment: true);
    }
}

/// <summary>Traverses live containment and edits the last returned owning slot.</summary>
/// <remarks>Null slots are skipped. Repeated slots are returned, but each identity is expanded once.
/// Peek leaves the current slot selected. Replacement skips the replacement subtree and any
/// detached subtree. If another ordinary owning slot retains the old child, its traversal continues.
/// Insertion skips the inserted child and keeps the current slot selected. An unrelated change
/// to an active owning slot or collection invalidates the cursor.</remarks>
public sealed class ModelContainmentCursor : ModelGraphCursor
{
    internal ModelContainmentCursor(ModelReflectionRegistry.ModelBinding root,
        Func<object, ModelReflectionRegistry.ModelBinding> bind)
        : base(root, bind, ModelTraversalStrategy.ContainmentTree, editUnorderedContainment: false) { }
}

/// <summary>Traverses live model edges and edits the last returned singular or positional list slot.</summary>
/// <remarks>For graph traversal, replacing an edge keeps the original object's pending descendants.
/// Non-owning list and ordered-set insertions skip the inserted objects and keep their insertion order.
/// A position in one of those lists or sets cannot be replaced again until the cursor advances.
/// Indexed assignment in a non-owning sequence does not prevent read traversal of its remaining slots.
/// In graph traversal an entered frame remains available even when its incoming edge is detached.</remarks>
public class ModelGraphCursor : IDisposable
{
    private readonly Func<object, ModelReflectionRegistry.ModelBinding> bind;
    private readonly ModelTraversalStrategy strategy;
    private readonly bool editUnorderedContainment;
    private readonly HashSet<object> expanded = new(ReferenceEqualityComparer.Instance);
    private readonly Entry root;
    private Frame? remaining;
    private Entry? current;
    private Step? pending;
    private bool expandingCurrent;
    private bool ended;
    private bool disposed;
    private bool editing;

    internal ModelGraphCursor(ModelReflectionRegistry.ModelBinding root,
        Func<object, ModelReflectionRegistry.ModelBinding> bind, ModelTraversalStrategy strategy,
        bool editUnorderedContainment)
    {
        this.root = new(root, null, null, null, 0);
        this.bind = bind;
        this.strategy = strategy;
        this.editUnorderedContainment = editUnorderedContainment;
    }

    /// <summary>The last returned object, even if its slot has just been edited.</summary>
    public object Current
    {
        get
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            return current?.Model.Input ?? throw new InvalidOperationException("The cursor is not positioned on an object.");
        }
    }

    public bool CanReplace => current is { CanReplace: true } && SelectionIsLive();
    public bool CanInsertAfter => current is { CanInsertAfter: true } && SelectionIsLive();

    private bool IsContainmentTraversal => strategy == ModelTraversalStrategy.ContainmentTree;

    private bool SelectionIsLive() => !disposed && !editing && current!.IsLive(IsContainmentTraversal)
        && (!IsContainmentTraversal || pending is null || pending.Entry.IsLive(includeAncestors: true));

    /// <summary>Finds the next object without changing the selected slot.</summary>
    public bool TryPeek([NotNullWhen(true)] out object? next)
    {
        var step = Peek();
        next = step?.Entry.Model.Input;
        return step is not null;
    }

    public bool MoveNext()
    {
        var step = Peek();
        if (step is null)
        {
            current = null;
            expandingCurrent = false;
            ended = true;
            return false;
        }

        current = step.Entry;
        expandingCurrent = current.Model.Schema.Snapshot.IsImmutable || expanded.Add(current.Model.Identity);
        remaining = expandingCurrent ? new(current, 0, null, 0, step.Rest) : step.Rest;
        InvalidatePeek();
        return true;
    }

    /// <summary>Replaces the current singular or indexed slot with a compatible model object.</summary>
    /// <exception cref="InvalidOperationException">The cursor is unpositioned, stale, or at an unsupported edge.</exception>
    public void ReplaceCurrent(object? replacement)
    {
        var edge = EditableEntry(insert: false);
        CheckChild(edge, replacement, nameof(replacement));
        if (edge.Property!.Kind != SnapshotPropertyKind.ReferenceList
            && ReferenceEquals(edge.Expected, replacement)) return;

        var list = edge.Sequence?.Native;
        int count = list?.Count ?? 0;
        long version = list?.Version ?? 0;
        long? revision = edge.Slot?.Version;
        int slotChanges = (edge.Expected is null ? 0 : 1) + (replacement is null ? 0 : 1);
        Edit(() =>
        {
            if (list is not null) list.ReplaceAt(edge.Index, replacement);
            else edge.Property.Set!(edge.Owner!.Model.Identity, replacement);
        }, () =>
        {
            if (list is not null && (list.Count != count || list.Version != version + 1)
                || revision is not null && edge.Slot!.Version != revision + slotChanges
                || !ReferenceEquals(edge.Read(), replacement)) return false;

            edge.Expected = replacement;
            edge.Refresh();
            if (IsContainmentTraversal) ContinueExpansionAfterReplacement(edge);
            return true;
        }, recoverCommittedFailure: !edge.Property.IsContainment);
    }

    private void ContinueExpansionAfterReplacement(Entry selected)
    {
        if (!expandingCurrent || !ReferenceEquals(remaining!.Owner, selected)) return;
        var frame = remaining;
        if (selected.Property?.Kind == SnapshotPropertyKind.ContainmentSequence && selected.Sequence?.Native is { } list)
        {
            for (int index = 0; index < list.Count; index++)
            {
                if (!ReferenceEquals(list.Get(index), selected.Model.Identity)) continue;
                // Keep validating descendants through a live slot while edits still address the selected slot.
                remaining = frame with { Owner = selected.AtIndex(index) };
                return;
            }
        }

        remaining = frame.Parent;
        expandingCurrent = false;
    }

    /// <summary>Inserts into the current positional list and continues after the inserted object.</summary>
    /// <exception cref="ArgumentException">The child is incompatible or is already owned by the same list.</exception>
    /// <exception cref="InvalidOperationException">The cursor is unpositioned, stale, or at a non-list edge.</exception>
    public void InsertAfterCurrent(object? child)
    {
        var edge = EditableEntry(insert: true);
        CheckChild(edge, child, nameof(child));
        var list = edge.Sequence!.Native!;
        if (edge.Property!.IsContainment && child is not null && list.ContainsIdentity(child))
            throw new ArgumentException("Inserting here would reposition a child already owned by this list.", nameof(child));

        int index = edge.InsertionIndex;
        int count = list.Count;
        long version = list.Version;
        Edit(() => list.InsertAt(index, child), () =>
        {
            if (list.Count != count + 1 || list.Version != version + 1
                || !ReferenceEquals(list.Get(index), child) || !ReferenceEquals(edge.Read(), edge.Expected)) return false;

            ContinueAfterInsertion(edge, index);
            return true;
        }, recoverCommittedFailure: !edge.Property.IsContainment);
    }

    private void ContinueAfterInsertion(Entry edge, int index)
    {
        edge.Sequence!.Refresh();
        edge.CompleteInsertion();
        var frame = remaining!;
        if (expandingCurrent)
        {
            var owner = frame.Owner;
            if (!ReferenceEquals(owner, edge) && owner.Index >= index) owner = owner.AtIndex(owner.Index + 1);
            remaining = frame with { Owner = owner, Parent = SkipInsertion(frame.Parent!) };
        }
        else
        {
            remaining = SkipInsertion(frame);
        }

        static Frame SkipInsertion(Frame frame) => frame with { Index = frame.Index + 1 };
    }

    private Entry EditableEntry(bool insert)
    {
        RequireIdle();
        if (current is not { } edge)
            throw new InvalidOperationException("The cursor is not positioned on a model slot.");
        edge.RequireLive(IsContainmentTraversal);
        if (IsContainmentTraversal) pending?.Entry.RequireLive(includeAncestors: true);
        if (!(insert ? edge.CanInsertAfter : edge.CanReplace))
            throw new InvalidOperationException("This traversal edge does not support the requested edit.");
        return edge;
    }

    private void CheckChild(Entry edge, object? child, string parameter)
    {
        if (child is null)
        {
            if (edge.Sequence is not null && !edge.Property!.HasNullableModelSlots)
                throw new ArgumentNullException(parameter);
            return;
        }

        if (!edge.Property!.ValueType.IsInstanceOfType(child))
            throw new ArgumentException($"Expected a model object assignable to '{edge.Property.ValueType}'.", parameter);
        var model = bind(child);
        if (model.IsReadOnly && (IsContainmentTraversal || !model.Schema.Snapshot.IsImmutable))
            throw new ArgumentException("Expected a writable model object.", parameter);
    }

    private void Edit(Action operation, Func<bool> update, bool recoverCommittedFailure)
    {
        editing = true;
        bool completed = false;
        try
        {
            operation();
            completed = true;
        }
        finally
        {
            editing = false;
            InvalidatePeek();
            if (completed)
            {
                if (!update()) throw Stale();
            }
            else if (recoverCommittedFailure)
            {
                // A native listener may throw after the reference edit has committed.
                update();
            }
        }
    }

    private void InvalidatePeek() => pending = null;

    private Step? Peek()
    {
        RequireIdle();
        if (ended) return null;
        if (!IsContainmentTraversal)
        {
            // The previous selection and lookahead need not name the same live edge.
            return current is null ? new(root, null) : FindNext();
        }
        current?.RequireLive(includeAncestors: true);
        pending ??= current is null ? new(root, null) : FindNext();
        pending?.Entry.RequireLive(includeAncestors: true);
        return pending;
    }

    private Step? FindNext()
    {
        var frame = remaining;
        while (frame is not null)
        {
            var owner = frame.Owner;
            var fields = owner.Model.Schema.Fields;
            if (frame.Field == fields.Count)
            {
                frame = frame.Parent;
                continue;
            }

            var field = fields[frame.Field];
            var property = field.Snapshot;
            if (!ModelReflectionRegistry.FollowsEdge(property, strategy))
            {
                frame = frame with { Field = frame.Field + 1 };
                continue;
            }

            var nextField = frame with { Field = frame.Field + 1, Sequence = null, Index = 0 };
            if (!property!.IsCollection)
            {
                if (field.Read(owner.Model) is { } child && NextModel(child) is { } model)
                    return new(new(model, owner, field, null, 0), nextField);
            }
            else
            {
                var collection = field.Read(owner.Model);
                if (collection is not null)
                {
                    var sequence = frame.Sequence ?? new Sequence(collection,
                        allowIndexedChanges: !IsContainmentTraversal && !property.IsContainment,
                        allowUnorderedContainmentEdits: editUnorderedContainment
                            && property.Kind == SnapshotPropertyKind.ContainmentSet);
                    sequence.RequireLive();
                    if (!ReferenceEquals(sequence.Collection, collection)) throw Stale();
                    for (int index = frame.Index; index < sequence.Count; index++)
                    {
                        if (sequence.Get(index) is not { } member || NextModel(member) is not { } model) continue;
                        return new(new(model, owner, field, sequence, index),
                            frame with { Sequence = sequence, Index = index + 1 });
                    }
                }
            }
            frame = nextField;
        }
        return null;
    }

    private ModelReflectionRegistry.ModelBinding? NextModel(object value)
    {
        var model = bind(value);
        return strategy == ModelTraversalStrategy.UniqueNode && expanded.Contains(model.Identity) ? null : model;
    }

    private void RequireIdle()
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        if (editing) throw new InvalidOperationException("The cursor is already editing its current slot.");
    }

    public void Dispose()
    {
        if (disposed) return;
        RequireIdle();
        disposed = true;
        current = null;
        remaining = null;
        InvalidatePeek();
        expanded.Clear();
    }

    private static InvalidOperationException Stale() => new("A model edge used by this cursor has changed.");

    private sealed record Step(Entry Entry, Frame? Rest);

    private sealed record Frame(Entry Owner, int Field, Sequence? Sequence, int Index, Frame? Parent);

    private sealed class Entry
    {
        internal Entry(ModelReflectionRegistry.ModelBinding model, Entry? owner, ModelReflectionRegistry.PropertySchema? field,
            Sequence? sequence, int index)
        {
            Model = model;
            Owner = owner;
            Field = field;
            Sequence = sequence;
            Index = index;
            InsertionIndex = index + 1;
            Expected = model.Input;
            if (sequence is null && owner is not null && Property is { IsContainment: true })
            {
                Slot = ModelChanges.FindContainmentSlot(owner.Model.Identity, model.Identity);
                slotVersion = Slot?.Version ?? 0;
            }
        }

        private long slotVersion;
        internal ModelReflectionRegistry.ModelBinding Model { get; }
        internal Entry? Owner { get; }
        private ModelReflectionRegistry.PropertySchema? Field { get; }
        internal SnapshotProperty? Property => Field?.Snapshot;
        internal Sequence? Sequence { get; }
        internal IModelCursorRevision? Slot { get; }
        internal int Index { get; }
        internal int InsertionIndex { get; private set; }
        internal object? Expected { get; set; }
        private bool HasWritableOwner => Owner is { Model.IsReadOnly: false }
            && (!Model.IsReadOnly || Property is { IsContainment: false } && Model.Schema.Snapshot.IsImmutable);
        private bool InsertedInSequence => InsertionIndex != Index + 1;
        internal bool CanInsertAfter => HasWritableOwner
            && Sequence?.CanEditPosition == true;
        internal bool CanReplace => HasWritableOwner
            && (Sequence is null ? Property?.Set is not null : CanInsertAfter && !InsertedInSequence);

        internal void CompleteInsertion()
        {
            if (Property is { IsContainment: false } or { Kind: SnapshotPropertyKind.ContainmentSet }) InsertionIndex++;
        }

        internal Entry AtIndex(int index) => new(Model, Owner, Field, Sequence, index);

        internal void Refresh()
        {
            Sequence?.Refresh();
            if (Slot is not null) slotVersion = Slot.Version;
        }

        internal object? Read() => Sequence is not null ? Sequence.Get(Index) : Field!.Read(Owner!.Model);

        internal bool IsLive(bool includeAncestors)
        {
            for (Entry? edge = this; edge?.Owner is { } owner; edge = owner)
            {
                if (edge.Sequence is { } entries)
                {
                    if (!entries.IsLive || edge.Index >= entries.Count) return false;
                    if (!ReferenceEquals(entries.Collection, edge.Field!.Read(owner.Model))) return false;
                }
                if (edge.Slot is { } slot && slot.Version != edge.slotVersion
                    || !ReferenceEquals(edge.Read(), edge.Expected)) return false;
                if (!includeAncestors) return true;
            }
            return true;
        }

        internal void RequireLive(bool includeAncestors)
        {
            if (!IsLive(includeAncestors)) throw Stale();
        }
    }

    private sealed class Sequence
    {
        private readonly object?[]? fallback;
        private readonly bool allowIndexedChanges;
        private long version;

        internal Sequence(object collection, bool allowIndexedChanges, bool allowUnorderedContainmentEdits)
        {
            Collection = collection;
            this.allowIndexedChanges = allowIndexedChanges;
            Native = collection as IModelCursorList ?? (collection as IModelCursorListProvider)?.CursorList;
            CanEditPosition = Native is not null && (Native.CanEditPosition || allowUnorderedContainmentEdits);
            if (Native is null) fallback = ((IEnumerable)collection).Cast<object?>().ToArray();
            else Refresh();
        }

        internal object Collection { get; }
        internal IModelCursorList? Native { get; }
        internal bool CanEditPosition { get; }
        internal int Count => Native?.Count ?? fallback!.Length;
        internal bool IsLive => Native is null || CurrentVersion == version;
        private long CurrentVersion => allowIndexedChanges ? Native!.StructureVersion : Native!.Version;
        internal object? Get(int index) => Native is null ? fallback![index] : Native.Get(index);
        internal void Refresh() => version = CurrentVersion;
        internal void RequireLive()
        {
            if (!IsLive) throw Stale();
        }
    }
}
