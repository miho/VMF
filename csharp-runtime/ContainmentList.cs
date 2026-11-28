using System.Collections;
using System.Runtime.InteropServices;

namespace Vmf.Runtime;

/// <summary>Tracks the single containing relationship for a generated child.</summary>
public class ContainmentLink
{
    internal IContainment? CurrentCollection { get; set; }
    private IContainment? pendingDestination;
    private bool pending;
    // The object that named this link as its own (IModelLinkAccess), and what the change streams know about it.
    private object? holder;
    private ModelChanges.ObservationNode? observation;

    public object? Owner => CurrentCollection?.OwnerObject;

    /// <summary>
    /// The observation state of the object this link was passed with, or null if it has none. The state of the
    /// link's own object is kept here; any other object is looked up.
    /// </summary>
    internal ModelChanges.ObservationNode? ObservationOf(object owner) =>
        ReferenceEquals(owner, holder) ? observation : ModelChanges.FindObservationNode(owner);

    /// <summary>Whether the object is the one that names this link as its own. The first to do so holds it.</summary>
    internal bool IsHeldBy(object owner) => ReferenceEquals(holder ??= owner, owner);

    internal ModelChanges.ObservationNode? Observation => observation;

    internal ModelChanges.ObservationNode RequireObservation() =>
        observation ?? Interlocked.CompareExchange(ref observation, new(), null) ?? observation;

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
        var deliver = ModelChanges.CapturePropertyDelivery(child, this, propertyName);
        if (deliver is null) return;
        ContainmentEdits.Notify(deliver, InverseChange(child, propertyName, oppositeName, previous, next), ref failure);
    }

    private ModelContainmentPropertyChange InverseChange(object child, string propertyName, string? oppositeName,
        IContainment? previous, IContainment? next) =>
        new(child, propertyName, previous?.OwnerObject, next?.OwnerObject,
            () => CurrentCollection?.OppositeName == oppositeName ? Owner : null,
            value =>
            {
                if (value is null) Detach(child);
                else (next ?? previous)!.AttachChild(child);
            }, isInverse: true);

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
    /// <summary>The number of slots, and the child of one slot (null for an empty slot).</summary>
    int SlotCount { get; }
    object? ChildAt(int slot);
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

    // An edit of up to this many added and removed children answers its membership questions by scanning them;
    // a larger one builds identity sets.
    private const int ScannedEditSize = 8;

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
        if (!IsOrdinaryList)
        {
            ReplaceRange(index, 0, [item]);
            return;
        }
        RequireWritable();
        CheckRange(index, 0);
        InsertOrdinaryRange(index, [item]);
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
        if (IsOrdinaryList) InsertOrdinaryRange(index, incoming.ToArray());
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
        if (key is not null)
        {
            // A keyed list checks the keys of its final contents with every edit.
            var reordered = items.GetRange(first, length);
            TChild moved = reordered[oldIndex - first];
            reordered.RemoveAt(oldIndex - first);
            reordered.Insert(newIndex - first, moved);
            ReplaceRange(first, length, reordered.ToArray());
            return;
        }

        RequireSettledSlots(first, length);
        var delivery = CaptureListDelivery();
        TChild[]? before = delivery.NeedsValues || !changeSource.IsTracked ? CopyRange(first, length) : null;
        TChild item = items[oldIndex];
        items.RemoveAt(oldIndex);
        items.Insert(newIndex, item);
        cursorVersion++;
        if (before is null) return;
        Exception? failure = null;
        Report(delivery, first, before, CopyRange(first, length), items.Count, ref failure, reorder: true);
        ModelObserverFailures.Rethrow(failure);
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
    int IContainment.SlotCount => items.Count;
    object? IContainment.ChildAt(int slot) => items[slot];
    ContainmentLink IContainment.GetChildLink(object child) => getLink((TChild)child);
    bool IKeyedContainment.HasKey => key is not null;

    void IKeyedContainment.RequireKeyChange<TValue>(object child, string propertyName, TValue value) =>
        key?.RequireChange(items, child, propertyName, value);

    int IContainment.FindChild(object child) => child is TChild item ? IndexOfIdentity(item) : -1;

    void IContainment.RequireWritable() => RequireWritable();

    void IContainment.AttachChild(object child) => Add((TChild)child);

    void IContainment.RemoveForTransfer(object child, ref Exception? failure)
    {
        var item = (TChild)child;
        int index = IndexOfIdentity(item);
        if (index < 0) throw Inconsistent();
        // An ordinary list can hold the child in several slots. They leave in one edit.
        if (IsOrdinaryList && IndexOfIdentity(item, index + 1) >= 0 && RemoveRepeatedForTransfer(child, ref failure)) return;
        var delivery = CaptureListDelivery();
        int countBefore = Count;
        items.RemoveAt(index);
        cursorVersion++;
        if (!delivery.NeedsValues && changeSource.IsTracked) return;
        Report(delivery, index, [item], [], countBefore, ref failure, transferRemoval: true);
    }

    // Ordinary VMF lists select the first equal item. The resulting slot still owns its exact instance.
    private int IndexOfValue(TChild? item) => IsOrdinaryList ? items.IndexOf(item!) : IndexOfIdentity(item);

    private int IndexOfIdentity(TChild? item, int start = 0)
    {
        var slots = CollectionsMarshal.AsSpan(items);
        for (int slot = start; slot < slots.Length; slot++)
        {
            if (ReferenceEquals(slots[slot], item)) return slot;
        }
        return -1;
    }

    /// <summary>The first position before <paramref name="end"/> that holds the child, or -1.</summary>
    private static int IndexOfIdentity(TChild[] values, TChild child, int end)
    {
        for (int position = 0; position < end; position++)
        {
            if (ReferenceEquals(values[position], child)) return position;
        }
        return -1;
    }

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
        TChild[] removed = count == 0 ? [] : positions is null ? CopyRange(index, count) : CopySlots(positions);
        bool scanned = added.Length <= ScannedEditSize && removed.Length <= ScannedEditSize;
        var replacing = scanned ? null : new HashSet<TChild>(removed, ReferenceEqualityComparer.Instance);
        var finalMembers = scanned ? null : new HashSet<TChild>(ReferenceEqualityComparer.Instance);
        // The link of each added child that enters the list, and of each removed child that leaves it.
        ContainmentLink?[]? incoming = null;
        ContainmentLink?[]? outgoing = null;
        for (int position = 0; position < added.Length; position++)
        {
            TChild child = added[position];
            if (child is null)
            {
                if (!IsOrdinaryList) throw new ArgumentNullException(nameof(added));
                continue;
            }
            var link = getLink(child);
            link.RequireAvailable();
            bool first = finalMembers?.Add(child) ?? IndexOfIdentity(added, child, position) < 0;
            bool attachedHere = ReferenceEquals(link.CurrentCollection, this);
            if (IsOrdinaryList)
            {
                if (!first || attachedHere) continue;
            }
            else if (!first || (attachedHere && !Holds(replacing, removed, child)))
            {
                throw new ArgumentException("The child is already in this containment.");
            }
            if (Holds(replacing, removed, child)) continue;

            link.RequireAcyclicAttachmentTo(ownerLink);
            var previous = link.CurrentCollection;
            if (previous is not null)
            {
                previous.RequireWritable();
                if (previous.FindChild(child) < 0) throw Inconsistent();
            }
            (incoming ??= new ContainmentLink?[added.Length])[position] = link;
        }
        if (finalMembers is not null && IsOrdinaryList && replacing!.Count > 0)
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
        for (int position = 0; position < removed.Length; position++)
        {
            TChild child = removed[position];
            // A child that occupies several of the removed slots leaves once.
            if (child is null || !(replacing?.Remove(child) ?? IndexOfIdentity(removed, child, position) < 0)) continue;
            var link = getLink(child);
            link.RequireAvailable();
            if (!ReferenceEquals(link.CurrentCollection, this)) throw Inconsistent();
            bool remains = finalMembers?.Contains(child) ?? (IndexOfIdentity(added, child, added.Length) >= 0
                || IsOrdinaryList && OccupiesOtherSlot(child, index, count, positions));
            if (!remains) (outgoing ??= new ContainmentLink?[removed.Length])[position] = link;
        }

        // The collection edit begins delivery before repairing the inverse links.
        // A group registered by an inverse callback only observes later list edits.
        var delivery = CaptureListDelivery();
        int countBefore = items.Count;
        items.EnsureCapacity(countBefore - count + added.Length);
        Exception? failure = null;
        updating = true;
        Reserve(incoming, this);
        Reserve(outgoing, destination: null);
        try
        {
            if (positions is null)
            {
                if (count != 0) items.RemoveRange(index, count);
                if (added.Length == 1) items.Insert(index, added[0]);
                else if (added.Length != 0) items.InsertRange(index, added);
            }
            else
            {
                ModelListSlots.Edit(items, positions, added);
            }
            if (count != 0 || added.Length != 0) cursorVersion++;
            for (int position = 0; incoming is not null && position < incoming.Length; position++)
            {
                if (incoming[position] is not { } link) continue;
                link.Unregister(added[position]!, releaseBeforeInverse: false, ref failure);
                link.AssignOwner(added[position]!, this, ref failure);
            }
            for (int position = 0; outgoing is not null && position < outgoing.Length; position++)
            {
                outgoing[position]?.ClearOwner(removed[position]!, this, releaseBeforeInverse: true, ref failure);
            }
        }
        finally
        {
            Release(incoming);
            Release(outgoing);
            updating = false;
        }
        Report(delivery, index, removed, added, countBefore, ref failure, positions);
        ModelObserverFailures.Rethrow(failure);
    }

    private static bool Holds(HashSet<TChild>? members, TChild[] values, TChild child) =>
        members?.Contains(child) ?? IndexOfIdentity(values, child, values.Length) >= 0;

    /// <summary>Whether a child also occupies a slot that an edit of the given slots does not remove.</summary>
    private bool OccupiesOtherSlot(TChild child, int index, int count, int[]? positions)
    {
        if (positions is null)
        {
            int before = IndexOfIdentity(child);
            return before >= 0 && (before < index || IndexOfIdentity(child, index + count) >= 0);
        }
        int next = 0;
        for (int slot = 0; slot < items.Count; slot++)
        {
            if (next < positions.Length && positions[next] == slot) next++;
            else if (ReferenceEquals(items[slot], child)) return true;
        }
        return false;
    }

    private static void Reserve(ContainmentLink?[]? links, IContainment? destination)
    {
        if (links is null) return;
        foreach (var link in links) link?.Reserve(destination);
    }

    private static void Release(ContainmentLink?[]? links)
    {
        if (links is null) return;
        foreach (var link in links) link?.Release();
    }

    private TChild[] CopyRange(int index, int count)
    {
        var slots = new TChild[count];
        items.CopyTo(index, slots, 0, count);
        return slots;
    }

    private TChild[] CopySlots(int[] positions)
    {
        var slots = new TChild[positions.Length];
        for (int i = 0; i < positions.Length; i++) slots[i] = items[positions[i]];
        return slots;
    }

    /// <summary>
    /// Reorders the slots of one range. No child enters or leaves the list, so no ownership changes; the children
    /// only must not be part of another containment edit.
    /// </summary>
    private void RequireSettledSlots(int index, int count)
    {
        for (int slot = index; slot < index + count; slot++)
        {
            if (items[slot] is { } child) getLink(child).RequireAvailable();
        }
        for (int slot = index; slot < index + count; slot++)
        {
            if (items[slot] is { } child && !ReferenceEquals(getLink(child).CurrentCollection, this)) throw Inconsistent();
        }
    }

    /// <summary>
    /// Replays a recorded edit of one range. A recorded reordering of slots that still hold what it expects
    /// changes no ownership and skips the analysis of an ordinary replacement.
    /// </summary>
    private void ReplayRange(int index, TChild[] expected, TChild[] replacement, bool reorder)
    {
        if (!reorder || key is not null || !HoldsSlots(index, expected))
        {
            ReplaceRange(index, expected.Length, replacement);
            return;
        }
        RequireWritable();
        RequireSettledSlots(index, expected.Length);
        var delivery = CaptureListDelivery();
        for (int offset = 0; offset < replacement.Length; offset++) items[index + offset] = replacement[offset];
        cursorVersion++;
        Exception? failure = null;
        Report(delivery, index, expected, replacement, items.Count, ref failure, reorder: true);
        ModelObserverFailures.Rethrow(failure);
    }

    private bool HoldsSlots(int index, TChild[] expected)
    {
        if (index > Count - expected.Length) return false;
        for (int offset = 0; offset < expected.Length; offset++)
        {
            if (!ReferenceEquals(items[index + offset], expected[offset])) return false;
        }
        return true;
    }

    /// <summary>Reports a committed edit to the observers of the list and of its owner.</summary>
    private void Report(ListDelivery delivery, int index, TChild[] removed, TChild[] added, int countBefore,
        ref Exception? failure, int[]? positions = null, bool transferRemoval = false, bool reorder = false)
    {
        if (!delivery.NeedsValues && changeSource.IsTracked) return;
        var edit = !delivery.NeedsEdit || unordered ? default : positions is null
            ? ModelListListeners<TChild>.Delivery.CreateEdit(index, removed, added)
            : ModelListListeners<TChild>.Delivery.CreateEdit(positions, removed, added);
        Publish(delivery.Prepare(edit, failure), index, removed, added, countBefore, ref failure, transferRemoval, positions,
            reorder);
        failure = delivery.Complete(edit, failure);
    }

    private void Publish(Action<ModelChange>? deliver, int index, TChild[] removed, TChild[] added, int countBefore,
        ref Exception? failure, bool transferRemoval = false, int[]? positions = null, bool reorder = false)
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

        var change = positions is not null ? SlotsChange(positions, removed, added, countBefore)
            : RangeChange(index, removed, added, countBefore, transferRemoval, reorder);
        ContainmentEdits.Notify(deliver, change, ref failure);
    }

    private ModelCollectionChange SlotsChange(int[] positions, TChild[] removed, TChild[] added, int countBefore)
    {
        int countAfter = countBefore - removed.Length + added.Length;
        bool restoreKeyedRuns = key is not null && !orderedSet && added.Length == 0;
        return changeSource.Create(orderedSet ? ModelChangeKind.ReferenceSet : ModelChangeKind.List, isModelReference: true,
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
    }

    private ModelCollectionChange RangeChange(int index, TChild[] removed, TChild[] added, int countBefore,
        bool transferRemoval, bool reorder)
    {
        int countAfter = countBefore - removed.Length + added.Length;
        return changeSource.Create(orderedSet ? ModelChangeKind.ReferenceSet : ModelChangeKind.List, isModelReference: true,
            removed.Select((child, offset) => new ModelCollectionEntry(child, Index: index + offset)),
            added.Select((child, offset) => new ModelCollectionEntry(child, Index: index + offset)),
            matches: undo => undo ? Matches(index, added, removed, countAfter, undo: true)
                : Matches(index, removed, added, countBefore, undo: false),
            replay: undo =>
            {
                if (undo) ReplayRange(index, added, removed, reorder);
                else ReplayRange(index, removed, added, reorder);
            },
            apply: target =>
            {
                if (changeSource.IsOwner(target)) ReplayRange(index, removed, added, reorder);
                else if (!changeSource.TryApplyNullListRange(target, index, removed.Length, added.Length))
                    changeSource.ResolveTarget<IOrdinaryModelListMembership<TChild>>(target).ApplyRange(index, removed.Length, added);
            },
            isContainment: true, isOrdinaryCollection: IsOrdinaryList, isKeyedContainment: key is not null,
            isTransferRemoval: transferRemoval, allowCompatibleNulls: IsOrdinaryList && !transferRemoval);
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
}
