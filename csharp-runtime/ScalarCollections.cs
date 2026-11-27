using System.Collections;
using System.ComponentModel;
using System.Diagnostics.CodeAnalysis;

namespace Vmf.Runtime;

/// <summary>A read-only scalar list with VMF value equality for lookup.</summary>
public interface IReadOnlyScalarList<T> : IReadOnlyList<T>
{
    bool Contains(T item);
    int IndexOf(T item);
}

/// <summary>An ordered scalar sequence that also stores immutable model values in generated generic models.</summary>
public sealed class ScalarList<T> : IReadOnlyScalarList<T>, IModelList<T>, IJournalSequence, IManagedDefaultList,
    IModelListObservation<T>, IOrdinaryModelListMembership<T>, IModelListOrdering<T>, IModelListPredicateRemoval<T>,
    IModelListReplacement<T>
{
    private readonly ModelSequence<T> sequence;
    private List<T> items => sequence.Items;
    private readonly IEqualityComparer<T> comparer;
    private readonly ReadOnlyView readOnly;
    private readonly int upperBound;
    private readonly bool unique;
    private readonly bool allowNull;

    public ScalarList(int upperBound, bool unique, bool allowNull) : this(upperBound, unique, allowNull, default)
    {
    }

    public ScalarList(int upperBound = -1, bool unique = false, bool allowNull = false, ModelChangeSource changeSource = default)
        : this(upperBound, unique, allowNull, changeSource, storesImmutableModels: false)
    {
    }

    [EditorBrowsable(EditorBrowsableState.Never)]
    public ScalarList(int upperBound, bool unique, bool allowNull, ModelChangeSource changeSource, bool storesImmutableModels)
        : this(upperBound, unique, allowNull, changeSource, storesImmutableModels, untypedObjectElements: false)
    {
    }

    [EditorBrowsable(EditorBrowsableState.Never)]
    public ScalarList(int upperBound, bool unique, bool allowNull, ModelChangeSource changeSource, bool storesImmutableModels,
        bool untypedObjectElements)
    {
        if (storesImmutableModels && !allowNull)
            throw new ArgumentException("Immutable model values require an ordinary list.", nameof(storesImmutableModels));
        if (untypedObjectElements && (typeof(T) != typeof(object) || storesImmutableModels || unique))
            throw new ArgumentException("Untyped Object values require an ordinary Object list.", nameof(untypedObjectElements));
        ScalarCollectionBounds.CheckUpperBound(upperBound);
        if (allowNull && (unique || upperBound >= 0))
        {
            throw new ArgumentException("Only an unbounded ordinary scalar list permits null elements.", nameof(allowNull));
        }
        this.upperBound = upperBound;
        this.unique = unique;
        this.allowNull = allowNull;
        comparer = untypedObjectElements ? ModelReflectionRegistry.UntypedObjectComparer<T>.Instance : ScalarEquality<T>.Instance;
        sequence = new(changeSource, comparer, ModelChangeKind.List, isModelReference: storesImmutableModels, upperBound, unique);
        readOnly = new ReadOnlyView(this);
    }

    public int Count => items.Count;
    public T this[int index]
    {
        get => items[index];
        set
        {
            ArgumentOutOfRangeException.ThrowIfGreaterThanOrEqual((uint)index, (uint)Count, nameof(index));
            CheckItem(value);
            if (unique)
            {
                int existing = IndexOf(value);
                if (existing >= 0 && existing != index)
                {
                    throw new ArgumentException("The scalar value already occurs at a different index.", nameof(value));
                }
            }
            sequence.Set(index, value);
        }
    }

    public IReadOnlyScalarList<T> AsReadOnly()
    {
        readOnly.Activate();
        return readOnly;
    }

    bool IOrdinaryModelListMembership<T>.SupportsMembershipFilter => !unique;
    void IOrdinaryModelListMembership<T>.RequireWritable() { }
    void IOrdinaryModelListMembership<T>.RemoveSlots(int[] positions) => sequence.RemoveSlots(positions);
    void IOrdinaryModelListMembership<T>.ApplyRange(int index, int removalCount, T[] added) =>
        ApplyEdit(index, removalCount, added);

    int IModelListPredicateRemoval<T>.RemoveWhere(Predicate<T> predicate) => sequence.RemoveWhere(predicate);
    void IModelListReplacement<T>.ReplaceAll(Func<T, T> transform) => sequence.ReplaceAll(transform, allowNull);

    internal void RestoreOrderedSlots(int[] positions, T[] values)
    {
        foreach (var value in values) CheckItem(value);
        sequence.RestoreOrderedSlots(positions, values);
    }

    IDisposable IModelListObservation<T>.AddListener(Action<ModelListChange<T>> listener) =>
        sequence.AddListener(this, listener);

    bool IModelListObservation<T>.TryAddProjection(Action<ModelListListeners<T>.ListEdit> relay,
        Func<bool> isActive, Func<bool> isAlive) =>
        sequence.AddProjection(relay, isActive, isAlive);

    [System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
    public void AttachShallowOwner(ModelChangeSource owner) => sequence.AttachShallowOwner(owner);

    public bool Add(T item)
    {
        CheckItem(item);
        if (unique && Contains(item)) return false;
        ScalarCollectionBounds.CheckCapacity(upperBound, (long)Count + 1);
        sequence.Insert(Count, item);
        return true;
    }

    public void Insert(int index, T item)
    {
        ArgumentOutOfRangeException.ThrowIfGreaterThan((uint)index, (uint)Count, nameof(index));
        CheckItem(item);
        if (unique && Contains(item))
        {
            throw new ArgumentException("The scalar value already occurs in the list.", nameof(item));
        }
        ScalarCollectionBounds.CheckCapacity(upperBound, (long)Count + 1);
        sequence.Insert(index, item);
    }

    public bool AddRange(IEnumerable<T> values) => InsertRange(Count, values);

    public bool InsertRange(int index, IEnumerable<T> values)
    {
        ArgumentOutOfRangeException.ThrowIfGreaterThan((uint)index, (uint)Count, nameof(index));
        ArgumentNullException.ThrowIfNull(values);
        var additions = new List<T>();
        var seen = unique ? new HashSet<T>(items, comparer) : null;
        foreach (var item in values)
        {
            CheckItem(item);
            if (seen is null || seen.Add(item)) additions.Add(item);
        }
        ScalarCollectionBounds.CheckCapacity(upperBound, (long)Count + additions.Count);
        sequence.InsertRange(index, additions);
        return additions.Count != 0;
    }

    public bool Contains(T item) => IndexOf(item) >= 0;
    public int IndexOf(T item) => items.FindIndex(value => comparer.Equals(value, item));

    public bool Remove(T item)
    {
        int index = IndexOf(item);
        if (index < 0) return false;
        sequence.RemoveAt(index);
        return true;
    }

    public void RemoveAt(int index) => sequence.RemoveAt(index);
    public void Clear() => sequence.Clear();

    /// <summary>Moves an element to its final index in the sequence.</summary>
    public void Move(int oldIndex, int newIndex) => sequence.Move(oldIndex, newIndex);

    /// <summary>Stably sorts the list, reporting a sparse edit for an ordinary sequence.</summary>
    public void Sort(Comparison<T> comparison) => sequence.Sort(comparison);

    internal void ApplySort(int[] positions, T[] values)
    {
        foreach (var value in values) CheckItem(value);
        sequence.ApplyScalarOrder(positions, values);
    }

    internal void ApplyEdit(int index, int removalCount, T[] additions)
    {
        foreach (var item in additions) CheckItem(item);
        sequence.ApplyRange(index, removalCount, additions);
    }

    private void CheckItem(T item)
    {
        if (!allowNull) ArgumentNullException.ThrowIfNull(item);
    }

    public IEnumerator<T> GetEnumerator() => items.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    bool ICollection<T>.IsReadOnly => false;
    void ICollection<T>.Add(T item) => Add(item);
    void ICollection<T>.CopyTo(T[] array, int arrayIndex) => items.CopyTo(array, arrayIndex);

    void IJournalSequence.Replace(IReadOnlyList<object?> values)
    {
        var converted = values.Select(value => (T)value!).ToList();
        Clear();
        AddRange(converted);
    }

    void IManagedDefaultList.ReplaceManagedContents(IReadOnlyList<object?> values) =>
        ReplaceManagedContents(values.Select(value => (T)value!));

    internal void ReplaceManagedContents(IEnumerable<T> values)
    {
        ArgumentNullException.ThrowIfNull(values);
        var desired = values.ToArray();
        foreach (T item in desired) CheckItem(item);
        ScalarCollectionBounds.CheckCapacity(upperBound, desired.Length);
        if (unique && desired.Distinct(comparer).Count() != desired.Length)
        {
            throw new ArgumentException("Duplicate member in the ordered scalar default.", nameof(values));
        }
        if (items.SequenceEqual(desired, comparer)) return;
        if (Count == desired.Length)
        {
            sequence.ApplyRange(0, Count, desired);
            return;
        }
        Exception? failure = null;
        try { Clear(); }
        catch (Exception exception) { failure = exception; }
        try
        {
            if (unique && items.Intersect(desired, comparer).Any())
            {
                throw new ArgumentException("Duplicate member introduced during ordered scalar replacement.", nameof(values));
            }
            AddRange(desired);
        }
        catch (Exception exception) { failure = ModelObserverFailures.Add(failure, exception); }
        ModelObserverFailures.Rethrow(failure);
    }

    private sealed class ReadOnlyView(ScalarList<T> owner) : IReadOnlyScalarList<T>, IModelListObservation<T>, IModelListProjection
    {
        private ModelListListeners<T>? observers;

        internal void Activate()
        {
            if (observers is not null) return;
            observers = new();
            owner.sequence.AddProjection(edit =>
            {
                var receivers = observers.Capture();
                if (receivers.HasObservers)
                    ModelObserverFailures.Rethrow(receivers.Notify(edit, null));
            }, () => observers.HasObservers);
        }

        bool IModelListProjection.SupportsRelay => true;
        IDisposable IModelListProjection.AddModelRelay(ModelChanges.ObserverGroup group) => observers!.AddModelRelay(group);

        public int Count => owner.Count;
        public T this[int index] => owner[index];
        public bool Contains(T item) => owner.Contains(item);
        public int IndexOf(T item) => owner.IndexOf(item);
        public IEnumerator<T> GetEnumerator() => owner.GetEnumerator();
        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
        IDisposable IModelListObservation<T>.AddListener(Action<ModelListChange<T>> listener) =>
            observers!.Add(this, listener);

    }
}

/// <summary>A read-only scalar set with VMF value equality for membership.</summary>
public interface IReadOnlyScalarSet<T> : IReadOnlyCollection<T>
{
    bool Contains(T item);
}

/// <summary>An unordered, optionally bounded set of non-null scalar values.</summary>
public sealed class ScalarSet<T> : IReadOnlyScalarSet<T>, IJournalMembership, IModelSetObservation<T>
{
    private readonly ModelSetMembers<T> members;
    private ISetMembers<T> items => members.Items;
    private readonly ReadOnlyView readOnly;
    public ScalarSet(int upperBound) : this(upperBound, default)
    {
    }

    public ScalarSet(int upperBound = -1, ModelChangeSource changeSource = default)
    {
        ScalarCollectionBounds.CheckUpperBound(upperBound);
        members = new(changeSource, ScalarEquality<T>.Instance, ModelChangeKind.Set, isModelReference: false, upperBound);
        readOnly = new ReadOnlyView(this);
    }

    public int Count => items.Count;
    public IReadOnlyScalarSet<T> AsReadOnly() => readOnly;
    IDisposable IModelSetObservation<T>.AddListener(Action<ModelSetChange<T>> listener) =>
        members.AddListener(this, listener, readOnly: false);
    public bool Contains(T item) => items.Contains(item);

    public bool Add(T item) => members.Add(item);
    public bool AddRange(IEnumerable<T> values) => members.AddRange(values);
    public bool Remove(T item) => members.Remove(item);
    public void Clear() => members.Clear();
    internal void ApplyEdit(T[] removed, T[] added) => members.ApplyMembership(removed, added);

    bool IJournalMembership.ContainsMember(object value) => Contains((T)value);

    void IJournalMembership.ApplyMembership(object[] removed, object[] added) =>
        members.ApplyMembership(Array.ConvertAll(removed, value => (T)value), Array.ConvertAll(added, value => (T)value));

    public IEnumerator<T> GetEnumerator() => items.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();


    private sealed class ReadOnlyView(ScalarSet<T> owner)
        : IReadOnlyScalarSet<T>, IModelSetObservation<T>, IModelMembershipProjection
    {
        public int Count => owner.Count;
        public bool Contains(T item) => owner.Contains(item);
        public IEnumerator<T> GetEnumerator() => owner.GetEnumerator();
        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
        IDisposable IModelSetObservation<T>.AddListener(Action<ModelSetChange<T>> listener) =>
            owner.members.AddListener(this, listener, readOnly: true);
        IDisposable IModelMembershipProjection.AddModelRelay(ModelChanges.ObserverGroup group) =>
            owner.members.AddProjectedModelRelay(group);

    }
}

internal static class ScalarCollectionBounds
{
    internal static void CheckUpperBound(int upperBound)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(upperBound, -1);
    }

    internal static void CheckCapacity(int upperBound, long size)
    {
        if (upperBound >= 0 && size > upperBound)
        {
            throw new ArgumentException($"The collection cannot contain more than {upperBound} values.");
        }
    }
}

internal sealed class ScalarEquality<T> : IEqualityComparer<T>
{
    internal static readonly ScalarEquality<T> Instance = new();

    // Java's boxed floating point equality distinguishes signed zero and unifies all NaN payloads.
    public bool Equals(T? left, T? right) => (left, right) switch
    {
        (float value, float other) => CanonicalBits(value) == CanonicalBits(other),
        (double value, double other) => CanonicalBits(value) == CanonicalBits(other),
        _ => EqualityComparer<T>.Default.Equals(left, right)
    };

    public int GetHashCode([DisallowNull] T value) => value switch
    {
        float number => CanonicalBits(number),
        double number => CanonicalBits(number).GetHashCode(),
        _ => EqualityComparer<T>.Default.GetHashCode(value)
    };

    private static int CanonicalBits(float value) =>
        float.IsNaN(value) ? 0x7FC00000 : BitConverter.SingleToInt32Bits(value);

    private static long CanonicalBits(double value) =>
        double.IsNaN(value) ? 0x7FF8000000000000L : BitConverter.DoubleToInt64Bits(value);
}
