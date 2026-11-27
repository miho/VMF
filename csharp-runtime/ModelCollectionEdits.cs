using System.Collections.Frozen;

namespace Vmf.Runtime;

internal sealed partial class ModelSequence<T>(ModelChangeSource source, IEqualityComparer<T> comparer,
    ModelChangeKind kind, bool isModelReference, int upperBound = -1, bool unique = false)
{
    private SharedModelSequenceOwners? sharedOwners;
    private ModelListListeners<T>? listeners;

    internal List<T> Items { get; } = new();
    internal long CursorVersion { get; private set; }
    internal long CursorStructureVersion { get; private set; }

    internal void MarkCursorVersion(bool structural = true)
    {
        CursorVersion++;
        if (structural) CursorStructureVersion++;
    }
    internal ReciprocalEndpoint? Reciprocal { get; set; }
    internal object? ResolveReadOnlyAlias(object? value) => source.ResolveReadOnlyAlias(value);

    internal IDisposable AddListener(IReadOnlyList<T> list, Action<ModelListChange<T>> listener) =>
        (listeners ??= new()).Add(list, listener);

    internal bool AddProjection(Action<ModelListListeners<T>.ListEdit> relay, Func<bool> isActive,
        Func<bool>? isAlive = null) => (listeners ??= new()).AddProjection(relay, isActive, isAlive);

    internal void AttachShallowOwner(ModelChangeSource owner)
    {
        if (Reciprocal is not null || unique || upperBound >= 0 || !owner.IsTracked)
        {
            throw new InvalidOperationException("Only a tracked ordinary list without an opposite can be shared.");
        }
        if (sharedOwners is null)
        {
            sharedOwners = new SharedModelSequenceOwners();
            sharedOwners.Add(source);
            source = default;
        }
        sharedOwners.Add(owner);
    }

    private SequenceDelivery CaptureDelivery()
    {
        var listObservers = listeners?.Capture() ?? default;
        bool reciprocalList = Reciprocal is not null && !unique;
        bool projectedRelayMayActivate = listObservers.HasProjections && (reciprocalList || listObservers.HasObservers);
        if (sharedOwners is { } owners)
        {
            return new(null, ModelChanges.SharedSequenceDelivery.Capture(owners, projectedRelayMayActivate), listObservers);
        }
        if (source.IsTracked && (reciprocalList || listObservers.Count != 0)
            && ModelChanges.CollectionRelayDelivery.Capture(source.Owner,
                projectedRelayMayActivate: projectedRelayMayActivate) is { } ordered)
        {
            return new(null, null, listObservers, ordered);
        }
        return new(source.CaptureDelivery(), null, listObservers);
    }

    private readonly record struct SequenceDelivery(Action<ModelChange>? Single,
        ModelChanges.SharedSequenceDelivery? Shared, ModelListListeners<T>.Delivery ListObservers,
        ModelChanges.CollectionRelayDelivery? Ordered = null)
    {
        internal bool HasObservers => Single is not null || Shared is { HasModelGroups: true }
            || Ordered is { HasModelGroups: true } || ListObservers.HasObservers;
    }

    internal void Insert(int index, T value)
    {
        if (Reciprocal is not null) { ApplyRange(index, 0, [value]); return; }
        var delivery = CaptureDelivery();
        int countBefore = Items.Count;
        Items.Insert(index, value);
        MarkCursorVersion();
        if (delivery.HasObservers) Publish(delivery, index, [], [value], countBefore);
    }

    internal void InsertRange(int index, List<T> values)
    {
        if (values.Count == 0) return;
        if (Reciprocal is not null) { ApplyRange(index, 0, values.ToArray()); return; }
        var delivery = CaptureDelivery();
        int countBefore = Items.Count;
        Items.InsertRange(index, values);
        MarkCursorVersion();
        if (delivery.HasObservers) Publish(delivery, index, [], values.ToArray(), countBefore);
    }

    internal void Set(int index, T value)
    {
        if (Reciprocal is not null) { ApplyRange(index, 1, [value]); return; }
        T previous = Items[index];
        var delivery = CaptureDelivery();
        Items[index] = value;
        MarkCursorVersion(structural: false);
        if (delivery.HasObservers) Publish(delivery, index, [previous], [value], Items.Count);
    }

    internal void RemoveAt(int index)
    {
        if (Reciprocal is not null) { ApplyRange(index, 1, []); return; }
        T previous = Items[index];
        var delivery = CaptureDelivery();
        int countBefore = Items.Count;
        Items.RemoveAt(index);
        MarkCursorVersion();
        if (delivery.HasObservers) Publish(delivery, index, [previous], [], countBefore);
    }

    internal void Clear()
    {
        if (Items.Count == 0) return;
        if (Reciprocal is not null) { ApplyRange(0, Items.Count, []); return; }
        var delivery = CaptureDelivery();
        T[]? removed = delivery.HasObservers ? Items.ToArray() : null;
        Items.Clear();
        MarkCursorVersion();
        if (delivery.HasObservers) Publish(delivery, 0, removed!, [], removed!.Length);
    }

    internal void Move(int oldIndex, int newIndex)
    {
        T item = Items[oldIndex];
        ArgumentOutOfRangeException.ThrowIfNegative(newIndex);
        ArgumentOutOfRangeException.ThrowIfGreaterThanOrEqual(newIndex, Items.Count);
        if (oldIndex == newIndex) return;

        int first = Math.Min(oldIndex, newIndex);
        int length = Math.Abs(newIndex - oldIndex) + 1;
        if (Reciprocal is not null)
        {
            var moved = Range(first, length).ToList();
            moved.RemoveAt(oldIndex - first);
            moved.Insert(newIndex - first, item);
            ApplyRange(first, length, moved.ToArray());
            return;
        }
        var delivery = CaptureDelivery();
        T[]? previous = delivery.HasObservers ? Range(first, length) : null;
        Items.RemoveAt(oldIndex);
        Items.Insert(newIndex, item);
        MarkCursorVersion();
        if (delivery.HasObservers)
        {
            T[] moved = Range(first, length);
            Publish(delivery, first, previous!, moved, Items.Count);
        }
    }

    private T[] Range(int index, int length)
    {
        var result = new T[length];
        Items.CopyTo(index, result, 0, length);
        return result;
    }

    private void Publish(SequenceDelivery delivery, int index, T[] removed, T[] added, int countBefore,
        ReciprocalEdit reciprocal = default, Exception? failure = null, ModelChange? prepared = null)
    {
        var edit = delivery.ListObservers.Count == 0 ? default : ModelListListeners<T>.Delivery.CreateEdit(index, removed, added);
        Dispatch(delivery, edit, owner => prepared ?? CreateRangeChange(owner, index, removed, added, countBefore, reciprocal),
            failure, prepared is not null);
    }

    private ModelChange CreateRangeChange(ModelChangeSource owner, int index, T[] removed, T[] added, int countBefore,
        ReciprocalEdit reciprocal)
    {
        int countAfter = countBefore - removed.Length + added.Length;
        return owner.Create(kind, isModelReference,
            removed.Select((value, offset) => new ModelCollectionEntry(value, Index: index + offset)),
            added.Select((value, offset) => new ModelCollectionEntry(value, Index: index + offset)),
            matches: undo => undo ? Matches(index, added, removed, countAfter) : Matches(index, removed, added, countBefore),
            replay: undo =>
            {
                if (undo) ApplyRange(index, added.Length, removed);
                else ApplyRange(index, removed.Length, added);
            },
            apply: target =>
            {
                if (owner.IsOwner(target)) ApplyRange(index, removed.Length, added);
                else if (!isModelReference || !owner.TryApplyNullListRange(target, index, removed.Length, added.Length))
                    owner.ResolveTarget<IOrdinaryModelListMembership<T>>(target).ApplyRange(index, removed.Length, added);
            }, reciprocal: reciprocal, isOrdinaryCollection: Reciprocal is null || !unique,
            isTransferRemoval: reciprocal.IsTransferRemoval,
            allowCompatibleNulls: !unique && !reciprocal.IsInverse && !reciprocal.IsTransferRemoval);
    }

    private void Dispatch(SequenceDelivery delivery, ModelListListeners<T>.ListEdit edit,
        Func<ModelChangeSource, ModelChange> create, Exception? failure, bool prepared = false)
    {
        if (delivery.Shared is { } shared)
        {
            ModelObserverFailures.Rethrow(shared.Dispatch(create, delivery.ListObservers, edit, failure));
        }
        else if (delivery.Ordered is { } ordered)
        {
            ModelObserverFailures.Rethrow(ordered.Dispatch(create(source), delivery.ListObservers, edit, failure, prepared));
        }
        else
        {
            if (delivery.Single is { } single)
            {
                ReciprocalEndpoint.Attempt(() => single(create(source)), ref failure);
            }
            if (delivery.ListObservers.HasObservers) failure = delivery.ListObservers.Notify(edit, failure);
            ModelObserverFailures.Rethrow(failure);
        }
    }

    private bool Matches(int index, T[] expected, T[] replacement, int expectedCount)
    {
        int end = index + expected.Length;
        long size = (long)Items.Count - expected.Length + replacement.Length;
        if (end > Items.Count || (expected.Length == 0 && Items.Count != expectedCount)
            || (upperBound >= 0 && size > upperBound)) return false;

        for (int offset = 0; offset < expected.Length; offset++)
        {
            bool matches = isModelReference ? ReferenceEquals(Items[index + offset], expected[offset])
                : comparer.Equals(Items[index + offset], expected[offset]);
            if (!matches) return false;
        }
        return HasUniqueResult(index, end, replacement);
    }

    private bool HasUniqueResult(int index, int end, T[] replacement)
    {
        if (unique && replacement.Length != 0)
        {
            var restored = new HashSet<T>(replacement, comparer);
            if (restored.Count != replacement.Length) return false;
            for (int i = 0; i < Items.Count; i++)
            {
                if ((i < index || i >= end) && restored.Contains(Items[i])) return false;
            }
        }
        return true;
    }

    internal void ApplyRange(int index, int removalCount, T[] added, ReciprocalEdit edit = default)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(index);
        ArgumentOutOfRangeException.ThrowIfNegative(removalCount);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(index, Items.Count);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(removalCount, Items.Count - index);
        ScalarCollectionBounds.CheckCapacity(upperBound, (long)Items.Count - removalCount + added.Length);
        if (!HasUniqueResult(index, index + removalCount, added))
        {
            throw new ArgumentException("The edit would duplicate an element in the ordered set.");
        }
        var delivery = CaptureDelivery();
        int countBefore = Items.Count;
        var removed = !delivery.HasObservers && Reciprocal is null ? null : Range(index, removalCount);
        if (Reciprocal is not null)
        {
            if (removalCount == 0 && added.Length == 0) return;
            Reciprocal.PrepareCollectionEdit(removed!, added, edit);
        }
        if (removalCount != 0) Items.RemoveRange(index, removalCount);
        if (added.Length != 0) Items.InsertRange(index, added);
        MarkCursorVersion();
        if (Reciprocal is null)
        {
            if (delivery.HasObservers) Publish(delivery, index, removed!, added, countBefore);
            return;
        }
        Exception? failure = null;
        ModelChange? prepared = null;
        if (!edit.IsInverse && delivery.Ordered is { HasPortableRecorder: true } captured
            && Reciprocal.HasCollectionOpposites(removed!) && Reciprocal.HasCollectionOpposites(added))
        {
            prepared = CreateRangeChange(source, index, removed!, added, countBefore, edit);
            captured.Prepare(prepared);
        }
        if (!edit.IsInverse) Reciprocal.UpdateCollectionOpposites(removed!, added, ref failure);
        if (delivery.HasObservers)
        {
            var inverseFailure = failure;
            ReciprocalEndpoint.Attempt(() => Publish(delivery, index, removed!, added, countBefore, edit, inverseFailure, prepared),
                ref failure);
        }
        ModelObserverFailures.Rethrow(failure);
    }

    internal void RemoveOppositeOccurrences(object target)
    {
        int[] indices = Enumerable.Range(0, Items.Count).Where(index => ReferenceEquals(Items[index], target)).ToArray();
        if (indices.Length == 0) return;
        if (indices.Length == 1)
        {
            ApplyRange(indices[0], 1, [], ReciprocalEdit.Inverse);
            return;
        }

        int count = Items.Count;
        var value = Items[indices[0]];
        var delivery = CaptureDelivery();
        for (int i = indices.Length - 1; i >= 0; i--) Items.RemoveAt(indices[i]);
        MarkCursorVersion();
        if (delivery.HasObservers)
        {
            var edit = delivery.ListObservers.Count == 0 ? default : ModelListListeners<T>.Delivery.CreateRemoval(indices, value);
            Dispatch(delivery, edit, Create, null);
        }

        ModelChange Create(ModelChangeSource owner) => owner.Create(kind, isModelReference,
            indices.Select(index => new ModelCollectionEntry(value, Index: index)), [],
            matches: undo => undo ? Items.Count == count - indices.Length
                : Items.Count == count && indices.All(index => ReferenceEquals(Items[index], target)),
            replay: undo =>
            {
                var replacement = Items.ToList();
                if (undo)
                {
                    foreach (int index in indices) replacement.Insert(index, value);
                }
                else
                {
                    for (int i = indices.Length - 1; i >= 0; i--) replacement.RemoveAt(indices[i]);
                }
                ApplyRange(0, Items.Count, replacement.ToArray());
            }, reciprocal: ReciprocalEdit.Inverse);
    }
}

internal sealed class ModelSetMembers<T>(ModelChangeSource source, IEqualityComparer<T> comparer,
    ModelChangeKind kind, bool isModelReference, int upperBound = -1, ISetMembers<T>? storage = null)
{
    private ModelListeners<SetEdit>? setListeners;
    private ModelListeners<SetEdit>? viewListeners;
    private ReferenceSetProjectionRelay? referenceProjection;

    internal ISetMembers<T> Items { get; } = storage ?? new HashedSetMembers<T>(comparer);
    internal ReciprocalEndpoint? Reciprocal { get; set; }
    internal object? ResolveReadOnlyAlias(object? value) => source.ResolveReadOnlyAlias(value);

    internal IDisposable AddListener(IReadOnlyScalarSet<T> set, Action<ModelSetChange<T>> listener, bool readOnly)
    {
        var listeners = readOnly ? (viewListeners ??= new()) : (setListeners ??= new());
        return listeners.Add(edit => listener(new(set, edit.Removed, edit.Added)), order: ModelChanges.NextObservationOrder());
    }

    internal IDisposable AddProjectedModelRelay(ModelChanges.ObserverGroup group) =>
        (viewListeners ??= new()).Add(edit => edit.Context?.Notify(group));

    internal IDisposable? AddReferenceSetModelRelay(ModelChanges.ObserverGroup group)
    {
        if (!source.IsTracked) return null;
        if (referenceProjection is null)
        {
            var relay = new ReferenceSetProjectionRelay();
            (setListeners ??= new()).Add(edit => relay.Notify(edit.Context), order: ModelChanges.NextObservationOrder(),
                relayActive: relay.IsActive);
            referenceProjection = relay;
        }
        return referenceProjection.Add(group);
    }

    private SetDelivery CaptureDelivery()
    {
        var direct = new CollectionCallbackSnapshot<SetEdit>(setListeners?.Snapshot);
        var projected = new CollectionCallbackSnapshot<SetEdit>(viewListeners?.Snapshot);
        return source.IsTracked && ((!isModelReference && kind is ModelChangeKind.Set) || referenceProjection is not null)
            ? new(null, ModelChanges.CollectionRelayDelivery.Capture(source.Owner,
                projectedRelayMayActivate: direct.HasProjections), direct, projected)
            : new(source.CaptureDelivery(), null, direct, projected);
    }

    private readonly record struct SetEdit(IReadOnlySet<T> Removed, IReadOnlySet<T> Added,
        ModelChanges.CollectionRelayContext? Context = null);

    private readonly record struct SetDelivery(Action<ModelChange>? Model, ModelChanges.CollectionRelayDelivery? Ordered,
        CollectionCallbackSnapshot<SetEdit> Set, CollectionCallbackSnapshot<SetEdit> View)
    {
        internal bool HasDirectObservers => Set.HasObservers || View.HasObservers;
        internal bool HasObservers => Model is not null || Ordered?.HasModelGroups == true || HasDirectObservers;
    }

    internal bool Add(T item)
    {
        ArgumentNullException.ThrowIfNull(item);
        if (Items.Contains(item)) return false;
        if (Reciprocal is not null) { ApplyMembership([], [item]); return true; }
        ScalarCollectionBounds.CheckCapacity(upperBound, (long)Items.Count + 1);
        var delivery = CaptureDelivery();
        Items.Add(item);
        if (delivery.HasObservers) Publish(delivery, [], [item]);
        return true;
    }

    internal bool AddRange(IEnumerable<T> values)
    {
        ArgumentNullException.ThrowIfNull(values);
        var additions = new HashSet<T>(comparer);
        foreach (var value in values)
        {
            ArgumentNullException.ThrowIfNull(value);
            if (!Items.Contains(value)) additions.Add(value);
        }
        if (additions.Count == 0) return false;
        if (Reciprocal is not null) { ApplyMembership([], additions.ToArray()); return true; }
        ScalarCollectionBounds.CheckCapacity(upperBound, (long)Items.Count + additions.Count);
        var delivery = CaptureDelivery();
        Items.AddRange(additions);
        if (delivery.HasObservers) Publish(delivery, [], additions.ToArray());
        return true;
    }

    internal bool Remove(T item)
    {
        if (!Items.TryGetValue(item, out var stored)) return false;
        if (Reciprocal is not null) { ApplyMembership([stored], []); return true; }
        var delivery = CaptureDelivery();
        Items.Remove(stored);
        if (delivery.HasObservers) Publish(delivery, [stored], []);
        return true;
    }

    internal void Clear()
    {
        if (Items.Count == 0) return;
        if (Reciprocal is not null) { ApplyMembership(Items.ToArray(), []); return; }
        var delivery = CaptureDelivery();
        var removed = delivery.HasObservers ? Items.ToArray() : null;
        Items.Clear();
        if (delivery.HasObservers) Publish(delivery, removed!, []);
    }

    private void Publish(SetDelivery delivery, T[] removed, T[] added, ReciprocalEdit reciprocal = default)
    {
        if (delivery.Ordered is { } route)
        {
            var edit = !isModelReference && delivery.HasDirectObservers ? CreateEdit() : default;
            ModelObserverFailures.Rethrow(route.Dispatch(CreateChange(), delivery.Set, edit,
                static (value, context) => value with { Context = context }, projectedLane: delivery.View));
            return;
        }

        if (!delivery.HasDirectObservers)
        {
            NotifyModel();
            return;
        }

        var snapshot = CreateEdit();
        Exception? failure = null;
        ReciprocalEndpoint.Attempt(NotifyModel, ref failure);
        failure = delivery.Set.Notify(snapshot, failure);
        ModelObserverFailures.Rethrow(delivery.View.Notify(snapshot, failure));

        SetEdit CreateEdit() => new(removed.ToFrozenSet(comparer), added.ToFrozenSet(comparer));

        void NotifyModel() => delivery.Model?.Invoke(CreateChange());

        ModelCollectionChange CreateChange() => source.Create(kind, isModelReference,
            removed.Select(value => new ModelCollectionEntry(value)),
            added.Select(value => new ModelCollectionEntry(value)),
            matches: undo => undo ? Matches(added, removed) : Matches(removed, added),
            replay: undo =>
            {
                if (undo) ApplyMembership(added, removed);
                else ApplyMembership(removed, added);
            },
            apply: isModelReference ? null : target =>
            {
                if (source.IsOwner(target)) ApplyMembership(removed, added);
                else source.ResolveTarget<ScalarSet<T>>(target).ApplyEdit(removed, added);
            }, reciprocal: reciprocal, isOrdinaryCollection: Reciprocal is null);
    }

    private bool Matches(T[] expected, T[] replacement)
    {
        long size = (long)Items.Count - expected.Length + replacement.Length;
        if (upperBound >= 0 && size > upperBound) return false;
        return expected.All(Items.Contains) && !replacement.Any(Items.Contains);
    }

    internal void ApplyMembership(T[] removed, T[] added, ReciprocalEdit edit = default, Action? editStorage = null)
    {
        var prior = new List<T>();
        foreach (var item in removed)
        {
            if (Items.TryGetValue(item, out var existing)) prior.Add(existing);
        }
        var additions = added.Where(item => !Items.Contains(item)).ToArray();
        if (prior.Count == 0 && additions.Length == 0) return;
        ScalarCollectionBounds.CheckCapacity(upperBound, (long)Items.Count - prior.Count + additions.Length);
        var delivery = CaptureDelivery();
        Reciprocal?.PrepareCollectionEdit(prior, additions, edit);
        if (editStorage is null)
        {
            foreach (var value in prior) Items.Remove(value);
            Items.AddRange(additions);
        }
        else
        {
            editStorage();
        }
        if (Reciprocal is null)
        {
            if (delivery.HasObservers) Publish(delivery, prior.ToArray(), additions);
            return;
        }
        Exception? failure = null;
        if (!edit.IsInverse) Reciprocal.UpdateCollectionOpposites(prior, additions, ref failure);
        if (delivery.HasObservers)
        {
            ReciprocalEndpoint.Attempt(() => Publish(delivery, prior.ToArray(), additions, edit), ref failure);
        }
        ModelObserverFailures.Rethrow(failure);
    }
}
