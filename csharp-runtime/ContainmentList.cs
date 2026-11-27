using System.Collections;

namespace Vmf.Runtime;

/// <summary>Tracks the single containing relationship for a generated child.</summary>
public class ContainmentLink
{
    internal IContainment? CurrentCollection { get; set; }
    private IContainment? pendingDestination;
    private bool pending;

    public object? Owner => CurrentCollection?.OwnerObject;

    public object? Collection => CurrentCollection;

    /// <summary>Checks the declared reciprocal property of the current link, including during transfer notifications.</summary>
    public bool HasOpposite(string propertyName) =>
        CurrentCollection?.OppositeName is { } name && name == propertyName;

    public bool Detach(object child)
    {
        RequireAvailable();
        var previous = CurrentCollection;
        if (previous is null || previous.FindChild(child) < 0) return false;
        previous.RequireWritable();

        Exception? failure = null;
        Reserve(destination: null);
        try
        {
            Unregister(child, releaseBeforeInverse: true, ref failure);
        }
        finally
        {
            Release();
        }
        ModelObserverFailures.Rethrow(failure);
        return true;
    }

    internal void RequireAvailable()
    {
        if (pending)
        {
            throw new ArgumentException("A containment edit for this child is already in progress.");
        }
    }

    internal void Reserve(IContainment? destination)
    {
        pendingDestination = destination;
        pending = true;
    }

    internal void Release()
    {
        pendingDestination = null;
        pending = false;
    }

    internal void RequireKeyChange<TValue>(object child, string propertyName, TValue value)
    {
        if (CurrentCollection is IKeyedContainment { HasKey: true } current)
        {
            current.RequireKeyChange(child, propertyName, value);
        }
        if (!ReferenceEquals(CurrentCollection, pendingDestination)
            && pendingDestination is IKeyedContainment { HasKey: true } destination)
        {
            destination.RequireKeyChange(child, propertyName, value);
        }
    }

    internal void RequireAcyclicAttachmentTo(ContainmentLink? ownerLink)
    {
        bool hasPendingParent = false;
        for (var ancestor = ownerLink; ancestor is not null; ancestor = ancestor.CurrentCollection?.OwnerLink)
        {
            if (ReferenceEquals(this, ancestor)) throw ContainmentCycle();
            hasPendingParent |= ancestor.pendingDestination?.OwnerLink is not null;
        }
        if (!hasPendingParent) return;

        var visited = new HashSet<ContainmentLink>(ReferenceEqualityComparer.Instance);
        var candidates = new Stack<ContainmentLink>();
        candidates.Push(ownerLink!);
        while (candidates.TryPop(out var ancestor))
        {
            if (ReferenceEquals(this, ancestor)) throw ContainmentCycle();
            if (!visited.Add(ancestor)) continue;
            if (ancestor.CurrentCollection?.OwnerLink is { } current) candidates.Push(current);
            if (ancestor.pendingDestination?.OwnerLink is { } proposed) candidates.Push(proposed);
        }
    }

    internal void Unregister(object child, bool releaseBeforeInverse, ref Exception? failure)
    {
        var previous = CurrentCollection;
        if (previous is null) return;
        previous.RemoveForTransfer(child, ref failure);
        ClearOwner(child, previous, releaseBeforeInverse, ref failure);
    }

    internal void ClearOwner(object child, IContainment previous, bool releaseBeforeInverse, ref Exception? failure)
    {
        CurrentCollection = null;
        if (releaseBeforeInverse) Release();
        NotifyInverse(child, previous, null, ref failure);
    }

    internal void AssignOwner(object child, IContainment next, ref Exception? failure)
    {
        CurrentCollection = next;
        NotifyInverse(child, null, next, ref failure);
    }

    private void NotifyInverse(object child, IContainment? previous, IContainment? next, ref Exception? failure)
    {
        string? oppositeName = (previous ?? next)!.OppositeName;
        var propertyName = oppositeName ?? "__vmf_prop_unnamed_container";
        var deliver = ModelChanges.CapturePropertyDelivery(child, propertyName);
        if (deliver is null) return;

        var change = new ModelContainmentPropertyChange(child, propertyName,
            previous?.OwnerObject, next?.OwnerObject,
            () => CurrentCollection?.OppositeName == oppositeName ? Owner : null,
            value =>
            {
                if (value is null) Detach(child);
                else (next ?? previous)!.AttachChild(child);
            }, isInverse: true);
        ContainmentEdits.Notify(deliver, change, ref failure);
    }

    private static ArgumentException ContainmentCycle() => new("The containment edit would create a cycle.");
}

/// <summary>Provides typed access to a containment link with a fixed owner and child type.</summary>
public sealed class ContainmentLink<TParent, TChild> : ContainmentLink
    where TParent : class
    where TChild : class?
{
    public ContainmentList<TParent, TChild>? Current => CurrentCollection as ContainmentList<TParent, TChild>;
}

internal interface IContainment
{
    object OwnerObject { get; }
    ContainmentLink? OwnerLink { get; }
    string? OppositeName { get; }
    IEnumerable<object> Children { get; }
    ContainmentLink GetChildLink(object child);
    int FindChild(object child);
    void RequireWritable();
    void RemoveForTransfer(object child, ref Exception? failure);
    void AttachChild(object child);
}

/// <summary>An ordered owning collection of child objects, compared by identity.</summary>
/// <remarks>
/// The generated model supplies each child's containment link. Multiplicity is
/// checked when the model is validated, allowing intermediate edits.
/// </remarks>
public sealed partial class ContainmentList<TParent, TChild> : IContainmentCollection<TChild>, IContainment, IJournalSequence, IKeyedContainment
    where TParent : class
    where TChild : class?
{
    private readonly List<TChild> items = new();
    private readonly Func<TChild, ContainmentLink> getLink;
    private readonly ContainmentLink? ownerLink;
    private readonly ModelChangeSource changeSource;
    private readonly string? oppositeName;
    private readonly ContainmentKeyRule<TChild>? key;
    private readonly bool orderedSet = true;
    private readonly bool unordered;
    private bool updating;

    public ContainmentList(TParent owner, Func<TChild, ContainmentLink<TParent, TChild>> getLink)
        : this(owner, (Func<TChild, ContainmentLink>)getLink)
    {
    }

    public ContainmentList(TParent owner, Func<TChild, ContainmentLink> getLink)
    {
        ArgumentNullException.ThrowIfNull(owner);
        ArgumentNullException.ThrowIfNull(getLink);
        Owner = owner;
        this.getLink = getLink;
        ModelChanges.RegisterContainment(owner, this);
    }

    public ContainmentList(TParent owner, ContainmentLink ownerLink, Func<TChild, ContainmentLink> getLink)
        : this(owner, ownerLink, getLink, default)
    {
    }

    public ContainmentList(TParent owner, ContainmentLink ownerLink, Func<TChild, ContainmentLink> getLink,
        ModelChangeSource changeSource, string? oppositeName = null)
        : this(owner, ownerLink, getLink, changeSource, oppositeName, null, null, orderedSet: true)
    {
    }

    public ContainmentList(TParent owner, ContainmentLink ownerLink, Func<TChild, ContainmentLink> getLink,
        ModelChangeSource changeSource, string? oppositeName, string? keyPropertyName,
        Func<TChild, object?>? keySelector, bool keyUsesIdentity = false, bool orderedSet = false)
        : this(owner, getLink)
    {
        ArgumentNullException.ThrowIfNull(ownerLink);
        this.ownerLink = ownerLink;
        this.changeSource = changeSource;
        this.oppositeName = oppositeName;
        this.orderedSet = orderedSet;
        if (keyPropertyName is not null || keySelector is not null)
        {
            ArgumentException.ThrowIfNullOrWhiteSpace(keyPropertyName);
            ArgumentNullException.ThrowIfNull(keySelector);
            key = new(keyPropertyName, keySelector, keyUsesIdentity);
        }
    }

    internal ContainmentList(TParent owner, ContainmentLink ownerLink, Func<TChild, ContainmentLink> getLink,
        ModelChangeSource changeSource, string? oppositeName, bool unordered, string? keyPropertyName,
        Func<TChild, object?>? keySelector, bool keyUsesIdentity)
        : this(owner, ownerLink, getLink, changeSource, oppositeName, keyPropertyName, keySelector,
            keyUsesIdentity, orderedSet: true)
    {
        this.unordered = unordered;
    }

    public TParent Owner { get; }

    public int Count => items.Count;

    public TChild this[int index]
    {
        get => items[index];
        set => ReplaceRange(index, 1, [value]);
    }

    public bool Contains(TChild? item) => IndexOf(item) >= 0;
    public bool Contains(object? item) => IndexOf(item) >= 0;

    public int IndexOf(object? item)
    {
        if (!orderedSet) item = changeSource.ResolveReadOnlyAlias(item) ?? item;
        int index = item is TChild stored ? IndexOfValue(stored) : item is null ? IndexOfValue(default) : -1;
        return index < 0 && orderedSet && changeSource.ResolveReadOnlyAlias(item) is TChild model ? IndexOfIdentity(model) : index;
    }

    /// <summary>Finds the entry with the supplied scalar key or model identity.</summary>
    public TChild? FindByKey<TKey>(TKey lookupKey) =>
        (key ?? throw new InvalidOperationException("The containment list has no declared entry key."))
            .Find(items, lookupKey);

    public bool Add(TChild item)
    {
        RequireWritable();
        if (!IsOrdinaryList) ArgumentNullException.ThrowIfNull(item);
        if (orderedSet && IndexOfIdentity(item) >= 0)
        {
            return false;
        }

        if (IsOrdinaryList) InsertOrdinaryRange(items.Count, [item]);
        else ReplaceRange(items.Count, 0, [item]);
        return true;
    }

    public void Insert(int index, TChild item)
    {
        if (IsOrdinaryList) InsertRange(index, [item]);
        else ReplaceRange(index, 0, [item]);
    }

    public bool AddRange(IEnumerable<TChild> children) => InsertRange(Count, children);

    public bool InsertRange(int index, IEnumerable<TChild> children)
    {
        RequireWritable();
        ArgumentNullException.ThrowIfNull(children);
        CheckRange(index, 0);
        var seen = orderedSet ? new HashSet<TChild>(items, ReferenceEqualityComparer.Instance) : null;
        var incoming = new List<TChild>();
        foreach (var child in children)
        {
            if (!IsOrdinaryList) ArgumentNullException.ThrowIfNull(child);
            if (seen is null || seen.Add(child)) incoming.Add(child);
        }
        if (incoming.Count == 0) return false;
        if (IsOrdinaryList) InsertOrdinaryRange(index, incoming);
        else ReplaceRange(index, 0, incoming.ToArray());
        return true;
    }

    public bool Remove(TChild? item) => Remove((object?)item);
    public bool Remove(object? item)
    {
        RequireWritable();
        int index = IndexOf(item);
        if (index < 0) return false;
        ReplaceRange(index, 1, []);
        return true;
    }

    public void RemoveAt(int index) => ReplaceRange(index, 1, []);

    public void Clear()
    {
        RequireWritable();
        if (items.Count != 0) ReplaceRange(0, items.Count, []);
    }

    public void Move(int oldIndex, int newIndex)
    {
        RequireWritable();
        ArgumentOutOfRangeException.ThrowIfNegative(oldIndex);
        ArgumentOutOfRangeException.ThrowIfGreaterThanOrEqual(oldIndex, items.Count);
        ArgumentOutOfRangeException.ThrowIfNegative(newIndex);
        ArgumentOutOfRangeException.ThrowIfGreaterThanOrEqual(newIndex, items.Count);
        if (oldIndex == newIndex)
        {
            return;
        }

        int first = Math.Min(oldIndex, newIndex);
        int length = Math.Abs(oldIndex - newIndex) + 1;
        var reordered = items.GetRange(first, length);
        TChild item = reordered[oldIndex - first];
        reordered.RemoveAt(oldIndex - first);
        reordered.Insert(newIndex - first, item);
        ReplaceRange(first, length, reordered.ToArray());
    }

    public IEnumerator<TChild> GetEnumerator() => items.GetEnumerator();

    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    void IJournalSequence.Replace(IReadOnlyList<object?> values) => ReplaceContents(values);

    void IKeyedContainment.HydrateMembers(IReadOnlyList<object?> values) => ReplaceContents(values);

    private void ReplaceContents(IReadOnlyList<object?> values)
    {
        var children = values.Select(value => value is null && IsOrdinaryList ? default!
            : value as TChild ?? throw new ArgumentException($"Expected a containment child of type '{typeof(TChild)}'."))
            .ToArray();
        ReplaceRange(0, Count, children);
    }

    object IContainment.OwnerObject => Owner;

    ContainmentLink? IContainment.OwnerLink => ownerLink;
    string? IContainment.OppositeName => oppositeName;
    IEnumerable<object> IContainment.Children => items.OfType<object>();
    ContainmentLink IContainment.GetChildLink(object child) => getLink((TChild)child);
    bool IKeyedContainment.HasKey => key is not null;

    void IKeyedContainment.RequireKeyChange<TValue>(object child, string propertyName, TValue value) =>
        key?.RequireChange(items, child, propertyName, value);

    int IContainment.FindChild(object child) => child is TChild item ? IndexOfIdentity(item) : -1;

    void IContainment.RequireWritable() => RequireWritable();

    void IContainment.AttachChild(object child) => Add((TChild)child);

    void IContainment.RemoveForTransfer(object child, ref Exception? failure)
    {
        if (IsOrdinaryList && RemoveRepeatedForTransfer(child, ref failure)) return;
        int index = IndexOfIdentity((TChild)child);
        if (index < 0) throw Inconsistent();
        var delivery = CaptureListDelivery();
        int countBefore = Count;
        items.RemoveAt(index);
        cursorVersion++;
        TChild[] removed = [(TChild)child];
        var edit = delivery.NeedsEdit && !unordered ? ModelListListeners<TChild>.Delivery.CreateEdit(index, removed, []) : default;
        Publish(delivery.Prepare(edit, failure), index, removed, [], countBefore, ref failure, transferRemoval: true);
        failure = delivery.Complete(edit, failure);
    }

    // Ordinary VMF lists select the first equal item. The resulting slot still owns its exact instance.
    private int IndexOfValue(TChild? item) => IsOrdinaryList ? items.IndexOf(item!) : IndexOfIdentity(item);

    private int IndexOfIdentity(TChild? item) => items.FindIndex(other => ReferenceEquals(other, item));

    private bool IsOrdinaryList => !orderedSet && key is null;

    private void RequireWritable()
    {
        if (updating) throw new ArgumentException("A containment edit for this list is already in progress.");
    }

    private void CheckRange(int index, int count)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(index);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(index, items.Count);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(count, items.Count - index);
    }

    private void ReplaceRange(int index, int count, TChild[] added, int[]? positions = null)
    {
        RequireWritable();
        if (positions is null) CheckRange(index, count);
        else ModelListSlots.CheckBounds(items, positions, adding: added.Length != 0);
        if (added.Length != 0) key?.ValidateRange(items, index, count, added);
        TChild[] removed = count == 0 ? [] : positions is null
            ? items.GetRange(index, count).ToArray()
            : positions.Select(position => items[position]).ToArray();
        var identity = ReferenceEqualityComparer.Instance;
        var replacing = new HashSet<TChild>(removed, identity);
        var finalMembers = new HashSet<TChild>(identity);
        var incoming = new List<Ownership>();
        var outgoing = new List<Ownership>();
        foreach (TChild child in added)
        {
            if (child is null)
            {
                if (!IsOrdinaryList) throw new ArgumentNullException(nameof(added));
                continue;
            }
            var link = getLink(child);
            link.RequireAvailable();
            bool first = finalMembers.Add(child);
            bool attachedHere = ReferenceEquals(link.CurrentCollection, this);
            if (IsOrdinaryList)
            {
                if (!first || attachedHere) continue;
            }
            else if (!first || (attachedHere && !replacing.Contains(child)))
            {
                throw new ArgumentException("The child is already in this containment.");
            }
            if (replacing.Contains(child)) continue;

            link.RequireAcyclicAttachmentTo(ownerLink);
            var previous = link.CurrentCollection;
            if (previous is not null)
            {
                previous.RequireWritable();
                if (previous.FindChild(child) < 0) throw Inconsistent();
            }
            incoming.Add(new Ownership(child, link));
        }
        if (IsOrdinaryList && replacing.Count > 0)
        {
            if (positions is null)
            {
                for (int i = 0; i < index; i++) finalMembers.Add(items[i]);
                for (int i = index + count; i < Count; i++) finalMembers.Add(items[i]);
            }
            else
            {
                int next = 0;
                for (int i = 0; i < Count; i++)
                {
                    if (next < positions.Length && positions[next] == i) next++;
                    else finalMembers.Add(items[i]);
                }
            }
        }
        foreach (TChild child in removed)
        {
            if (child is null || !replacing.Remove(child)) continue;
            var link = getLink(child);
            link.RequireAvailable();
            if (!ReferenceEquals(link.CurrentCollection, this)) throw Inconsistent();
            if (!finalMembers.Contains(child)) outgoing.Add(new Ownership(child, link));
        }

        // The collection edit begins delivery before repairing the inverse links.
        // A group registered by an inverse callback only observes later list edits.
        var delivery = CaptureListDelivery();
        int countBefore = items.Count;
        items.EnsureCapacity(countBefore - count + added.Length);
        Exception? failure = null;
        updating = true;
        foreach (var item in incoming) item.Link.Reserve(this);
        foreach (var item in outgoing) item.Link.Reserve(destination: null);
        try
        {
            if (positions is null)
            {
                if (count != 0) items.RemoveRange(index, count);
                if (added.Length != 0) items.InsertRange(index, added);
            }
            else
            {
                ModelListSlots.Edit(items, positions, added);
            }
            if (count != 0 || added.Length != 0) cursorVersion++;
            foreach (var item in incoming)
            {
                item.Link.Unregister(item.Child, releaseBeforeInverse: false, ref failure);
                item.Link.AssignOwner(item.Child, this, ref failure);
            }
            foreach (var item in outgoing)
            {
                item.Link.ClearOwner(item.Child, this, releaseBeforeInverse: true, ref failure);
            }
        }
        finally
        {
            foreach (var item in incoming) item.Link.Release();
            foreach (var item in outgoing) item.Link.Release();
            updating = false;
        }
        var edit = !delivery.NeedsEdit || unordered ? default : positions is null
            ? ModelListListeners<TChild>.Delivery.CreateEdit(index, removed, added)
            : ModelListListeners<TChild>.Delivery.CreateEdit(positions, removed, added);
        Publish(delivery.Prepare(edit, failure), index, removed, added, countBefore, ref failure, positions: positions);
        ModelObserverFailures.Rethrow(delivery.Complete(edit, failure));
    }

    private void Publish(Action<ModelChange>? deliver, int index, TChild[] removed, TChild[] added, int countBefore,
        ref Exception? failure, bool transferRemoval = false, int[]? positions = null)
    {
        if (deliver is null)
        {
            if (!changeSource.IsTracked)
            {
                ModelChanges.ReconcileUntrackedContainment(this, removed.OfType<object>(), added.OfType<object>(),
                    removed.Length + added.Length > 1);
            }
            return;
        }

        if (unordered)
        {
            PublishMembership(deliver, removed, added, ref failure);
            return;
        }

        int countAfter = countBefore - removed.Length + added.Length;
        var kind = orderedSet ? ModelChangeKind.ReferenceSet : ModelChangeKind.List;
        if (positions is not null)
        {
            bool restoreKeyedRuns = key is not null && !orderedSet && added.Length == 0;
            var sparse = changeSource.Create(kind, isModelReference: true,
                ModelListSlots.Entries(positions, removed), ModelListSlots.Entries(positions, added),
                matches: undo => undo ? ModelListSlots.Match(items, positions, added, countAfter)
                    && (!restoreKeyedRuns || CanRestoreKeyedEntries(removed))
                    : ModelListSlots.Match(items, positions, removed, countBefore),
                replay: undo =>
                {
                    int removeCount = undo ? added.Length : removed.Length;
                    TChild[] replacement = undo ? removed : added;
                    if (IsOrdinaryList) ReplayOrdinarySlots(positions, removeCount, replacement);
                    else if (undo && restoreKeyedRuns)
                        ModelListSlots.RestoreRuns(positions, replacement, (start, run) => ReplaceRange(start, 0, run));
                    else ReplaceRange(0, removeCount, replacement, positions);
                },
                apply: target =>
                {
                    if (changeSource.IsOwner(target)) ReplaceRange(0, removed.Length, added, positions);
                    else if (!changeSource.TryApplyNullListRemoval(target, positions))
                        changeSource.ResolveTarget<IOrdinaryModelListMembership<TChild>>(target).RemoveSlots(positions);
                },
                isContainment: true, isOrdinaryCollection: IsOrdinaryList, isKeyedContainment: key is not null,
                allowCompatibleNulls: IsOrdinaryList && removed.Length != 0);
            ContainmentEdits.Notify(deliver, sparse, ref failure);
            return;
        }
        var delta = changeSource.Create(kind, isModelReference: true,
            removed.Select((child, offset) => new ModelCollectionEntry(child, Index: index + offset)),
            added.Select((child, offset) => new ModelCollectionEntry(child, Index: index + offset)),
            matches: undo => undo ? Matches(index, added, removed, countAfter, undo: true)
                : Matches(index, removed, added, countBefore, undo: false),
            replay: undo =>
            {
                if (undo) ReplaceRange(index, added.Length, removed);
                else ReplaceRange(index, removed.Length, added);
            },
            apply: target =>
            {
                if (changeSource.IsOwner(target)) ReplaceRange(index, removed.Length, added);
                else if (!changeSource.TryApplyNullListRange(target, index, removed.Length, added.Length))
                    changeSource.ResolveTarget<IOrdinaryModelListMembership<TChild>>(target).ApplyRange(index, removed.Length, added);
            },
            isContainment: true, isOrdinaryCollection: IsOrdinaryList, isKeyedContainment: key is not null,
            isTransferRemoval: transferRemoval, allowCompatibleNulls: IsOrdinaryList && !transferRemoval);
        ContainmentEdits.Notify(deliver, delta, ref failure);
    }

    private bool CanRestoreKeyedEntries(TChild[] restoring)
    {
        try
        {
            key!.ValidateRange(items, 0, 0, restoring);
            return true;
        }
        catch (ArgumentException)
        {
            return false;
        }
    }

    internal void ApplySetMembership(TChild[] removed, TChild[] added)
    {
        var departing = new HashSet<TChild>(removed, ReferenceEqualityComparer.Instance);
        ReplaceRange(0, Count, items.Where(child => !departing.Contains(child)).Concat(added).ToArray());
    }

    private void PublishMembership(Action<ModelChange> deliver, TChild[] previous, TChild[] next, ref Exception? failure)
    {
        var oldMembers = new HashSet<TChild>(previous, ReferenceEqualityComparer.Instance);
        var newMembers = new HashSet<TChild>(next, ReferenceEqualityComparer.Instance);
        TChild[] removed = previous.Where(child => !newMembers.Contains(child)).ToArray();
        TChild[] added = next.Where(child => !oldMembers.Contains(child)).ToArray();
        if (removed.Length == 0 && added.Length == 0) return;

        var delta = changeSource.Create(ModelChangeKind.ReferenceSet, isModelReference: true,
            removed.Select(child => new ModelCollectionEntry(child)), added.Select(child => new ModelCollectionEntry(child)),
            matches: undo => undo ? MatchesMembership(added, removed) : MatchesMembership(removed, added),
            replay: undo =>
            {
                if (undo) ApplySetMembership(added, removed);
                else ApplySetMembership(removed, added);
            },
            apply: _ => ApplySetMembership(removed, added), isContainment: true);
        ContainmentEdits.Notify(deliver, delta, ref failure);
    }

    private bool MatchesMembership(TChild[] present, TChild[] absent) =>
        present.All(child => IndexOfIdentity(child) >= 0) && absent.All(child => IndexOfIdentity(child) < 0);

    private bool Matches(int index, TChild[] expected, TChild[] restored, int expectedCount, bool undo)
    {
        if (index > Count - expected.Length || (expected.Length == 0 && Count != expectedCount)) return false;
        for (int offset = 0; offset < expected.Length; offset++)
        {
            if (!ReferenceEquals(items[index + offset], expected[offset])) return false;
        }
        // Undo of an insertion or replacement only depends on the recorded positions.
        if (IsOrdinaryList) return undo || Count == expectedCount;
        foreach (var child in restored)
        {
            int currentIndex = IndexOfIdentity(child);
            if (currentIndex >= 0 && (currentIndex < index || currentIndex >= index + expected.Length)) return false;
        }
        return true;
    }

    private static InvalidOperationException Inconsistent() => new("The child and its containment are inconsistent.");

    private readonly record struct Ownership(object Child, ContainmentLink Link);
}
