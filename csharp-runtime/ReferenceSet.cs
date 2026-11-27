using System.Collections;

namespace Vmf.Runtime;

/// <summary>An unordered collection of non-owning model references compared by identity.</summary>
public sealed class ReferenceSet<T> : IReadOnlyReferenceSet<T>, IJournalMembership,
    ReferenceCollectionDefaults.ICopyMembership, IModelCursorListProvider, IModelReferenceSetProjectionSource where T : class
{
    private readonly ModelSetMembers<T> members;
    private readonly ReferenceSetMembers<T> items = new();

    public ReferenceSet() : this(default)
    {
    }

    public ReferenceSet(ModelChangeSource changeSource)
    {
        members = new(changeSource, ReferenceEqualityComparer.Instance, ModelChangeKind.ReferenceSet,
            isModelReference: true, storage: items);
    }

    public ReferenceSet(ModelChangeSource changeSource, Func<T, ReciprocalEndpoint> opposite) : this(changeSource)
    {
        ArgumentNullException.ThrowIfNull(opposite);
        members.Reciprocal = new(changeSource, target => opposite((T)target), new(true, () => items,
            target => items.Contains((T)target) ? 1 : 0,
            (value, edit) => members.ApplyMembership([], [(T)value], edit),
            (value, _, edit) => members.ApplyMembership([(T)value], [], edit)));
    }

    public ReciprocalEndpoint Endpoint => members.Reciprocal ?? throw new InvalidOperationException("The set has no reciprocal endpoint.");

    IDisposable? IModelReferenceSetProjectionSource.AddReferenceSetModelRelay(ModelChanges.ObserverGroup group) =>
        members.AddReferenceSetModelRelay(group);

    public int Count => items.Count;

    public bool Add(T item) => members.Add(item);

    public bool AddRange(IEnumerable<T> values) => members.AddRange(values);

    void ReferenceCollectionDefaults.ICopyMembership.AppendCopyMembers(IReadOnlyList<object?> values) =>
        AddRange(values.Cast<T>());

    public bool Contains(T? item) => Contains((object?)item);
    public bool Contains(object? item) => item is T stored && items.Contains(stored)
        || members.ResolveReadOnlyAlias(item) is T model && items.Contains(model);

    public bool Remove(T? item) => Remove((object?)item);
    public bool Remove(object? item) => item is T stored && members.Remove(stored)
        || members.ResolveReadOnlyAlias(item) is T model && members.Remove(model);

    public void Clear() => members.Clear();

    [System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
    public void ReplaceBuilderContents(IEnumerable<T> values)
    {
        ArgumentNullException.ThrowIfNull(values);
        if (members.Reciprocal is not null)
        {
            throw new InvalidOperationException("Builder replacement requires a reference set without an opposite.");
        }

        var desired = values.ToArray();
        var membership = new HashSet<T>(ReferenceEqualityComparer.Instance);
        foreach (T value in desired)
        {
            ArgumentNullException.ThrowIfNull(value);
            if (!membership.Add(value))
            {
                throw new ArgumentException("Duplicate model reference.", nameof(values));
            }
        }

        var obsolete = items.Where(value => !membership.Contains(value)).ToArray();
        Exception? failure = null;
        try { members.ApplyMembership(obsolete, []); }
        catch (Exception error) { failure = error; }
        try { AddRange(desired); }
        catch (Exception error) { failure = ModelObserverFailures.Add(failure, error); }
        ModelObserverFailures.Rethrow(failure);
    }

    bool IJournalMembership.ContainsMember(object value) => Contains((T)value);

    void IJournalMembership.ApplyMembership(object[] removed, object[] added) =>
        members.ApplyMembership(Array.ConvertAll(removed, value => (T)value), Array.ConvertAll(added, value => (T)value));

    public IEnumerator<T> GetEnumerator() => items.GetEnumerator();

    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    IModelCursorList IModelCursorListProvider.CursorList => new CursorList(this);

    private sealed class CursorList(ReferenceSet<T> owner) : IModelCursorList
    {
        private LinkedListNode<T>? slot;
        private int slotIndex;
        private long cachedStructureVersion;

        public long Version => owner.items.Version;
        public long StructureVersion => owner.items.StructureVersion;
        public int Count => owner.Count;
        public bool CanEditPosition => true;
        public object Get(int index) => Locate(index).Value;
        public bool ContainsIdentity(object child) => child is T value && owner.items.Contains(value);

        public void InsertAt(int index, object? child)
        {
            var value = RequireNewMember(child);
            var previous = index == 0 ? null : Locate(index - 1);
            owner.members.ApplyMembership([], [value], editStorage: () =>
            {
                slot = owner.items.InsertAfter(previous, value);
                slotIndex = index;
                cachedStructureVersion = StructureVersion;
            });
        }

        public void ReplaceAt(int index, object? child)
        {
            var selected = Locate(index);
            if (ReferenceEquals(selected.Value, child)) return;
            var value = RequireNewMember(child);
            owner.members.ApplyMembership([selected.Value], [value], editStorage: () => owner.items.Replace(selected, value));
        }

        private T RequireNewMember(object? child)
        {
            ArgumentNullException.ThrowIfNull(child);
            var value = (T)child;
            if (owner.items.Contains(value)) throw new ArgumentException("Duplicate model reference.", nameof(child));
            return value;
        }

        private LinkedListNode<T> Locate(int index)
        {
            ArgumentOutOfRangeException.ThrowIfNegative(index);
            ArgumentOutOfRangeException.ThrowIfGreaterThanOrEqual(index, Count);
            if (slot is null || cachedStructureVersion != StructureVersion)
            {
                slot = owner.items.First!;
                slotIndex = 0;
                cachedStructureVersion = StructureVersion;
            }
            while (slotIndex < index)
            {
                slot = slot!.Next!;
                slotIndex++;
            }
            while (slotIndex > index)
            {
                slot = slot!.Previous!;
                slotIndex--;
            }
            return slot!;
        }
    }
}
