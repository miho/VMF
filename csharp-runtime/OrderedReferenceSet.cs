using System.Collections;

namespace Vmf.Runtime;

/// <summary>An ordered collection of distinct, non-owning model references compared by identity.</summary>
public sealed partial class OrderedReferenceSet<T> : IReadOnlyOrderedReferenceSet<T>, IJournalSequence,
    ReferenceCollectionDefaults.ICopyMembership, IModelCursorList where T : class
{
    private readonly ModelSequence<T> sequence;
    private List<T> items => sequence.Items;

    public OrderedReferenceSet() : this(default)
    {
    }

    public OrderedReferenceSet(ModelChangeSource changeSource)
    {
        sequence = new(changeSource, ReferenceEqualityComparer.Instance, ModelChangeKind.ReferenceSet,
            isModelReference: true, unique: true);
    }

    public OrderedReferenceSet(ModelChangeSource changeSource, Func<T, ReciprocalEndpoint> opposite) : this(changeSource)
    {
        sequence.Reciprocal = ReciprocalCollections.ForSequence(changeSource, sequence, opposite, unique: true);
    }

    public ReciprocalEndpoint Endpoint => sequence.Reciprocal ?? throw new InvalidOperationException("The set has no reciprocal endpoint.");

    public int Count => items.Count;

    long IModelCursorRevision.Version => sequence.CursorVersion;
    long IModelCursorList.StructureVersion => sequence.CursorStructureVersion;
    bool IModelCursorList.CanEditPosition => true;
    object? IModelCursorList.Get(int index) => this[index];
    bool IModelCursorList.ContainsIdentity(object child) => child is T model && IndexOfIdentity(model) >= 0;
    void IModelCursorList.InsertAt(int index, object? child) => Insert(index, (T)child!);
    void IModelCursorList.ReplaceAt(int index, object? child) => this[index] = (T)child!;

    public T this[int index]
    {
        get => items[index];
        set
        {
            var previous = items[index];
            ArgumentNullException.ThrowIfNull(value);
            if (ReferenceEquals(previous, value))
            {
                return;
            }
            RequireAbsent(value);
            sequence.Set(index, value);
        }
    }

    public bool Add(T item)
    {
        ArgumentNullException.ThrowIfNull(item);
        if (IndexOfIdentity(item) >= 0)
        {
            return false;
        }
        sequence.Insert(Count, item);
        return true;
    }

    public void Insert(int index, T item)
    {
        ArgumentNullException.ThrowIfNull(item);
        RequireAbsent(item);
        sequence.Insert(index, item);
    }

    public bool AddRange(IEnumerable<T> values) => InsertRange(Count, values);

    public bool InsertRange(int index, IEnumerable<T> values)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(index);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(index, Count);
        ArgumentNullException.ThrowIfNull(values);
        var additions = new List<T>();
        var seen = new HashSet<T>(items, ReferenceEqualityComparer.Instance);
        foreach (var item in values)
        {
            ArgumentNullException.ThrowIfNull(item);
            if (seen.Add(item)) additions.Add(item);
        }
        sequence.InsertRange(index, additions);
        return additions.Count != 0;
    }

    public bool Contains(T? item) => IndexOf(item) >= 0;
    public bool Contains(object? item) => IndexOf(item) >= 0;

    public int IndexOf(T? item) => IndexOf((object?)item);
    public int IndexOf(object? item)
    {
        int index = item is T stored ? IndexOfIdentity(stored) : -1;
        return index < 0 && sequence.ResolveReadOnlyAlias(item) is T model ? IndexOfIdentity(model) : index;
    }

    public bool Remove(T? item) => Remove((object?)item);
    public bool Remove(object? item)
    {
        int index = IndexOf(item);
        if (index < 0)
        {
            return false;
        }
        sequence.RemoveAt(index);
        return true;
    }

    public void RemoveAt(int index) => sequence.RemoveAt(index);

    public void Clear() => sequence.Clear();

    public void Move(int oldIndex, int newIndex) => sequence.Move(oldIndex, newIndex);

    public IEnumerator<T> GetEnumerator() => items.GetEnumerator();

    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    void IJournalSequence.Replace(IReadOnlyList<object?> values)
    {
        var converted = values.Select(value => (T)value!).ToList();
        if (sequence.Reciprocal is not null)
        {
            sequence.ApplyRange(0, Count, converted.ToArray());
            return;
        }
        Clear();
        AddRange(converted);
    }

    void ReferenceCollectionDefaults.ICopyMembership.AppendCopyMembers(IReadOnlyList<object?> values) =>
        AddRange(values.Cast<T>());

    private int IndexOfIdentity(T item) => items.FindIndex(candidate => ReferenceEquals(candidate, item));

    private void RequireAbsent(T item)
    {
        if (IndexOfIdentity(item) >= 0)
        {
            throw new ArgumentException("The target is already in this ordered reference set.", nameof(item));
        }
    }
}
