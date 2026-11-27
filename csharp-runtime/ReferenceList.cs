using System.Collections;

namespace Vmf.Runtime;

/// <summary>An ordered sequence of non-owning model references that can repeat an object or contain null.</summary>
public sealed class ReferenceList<T> : IModelList<T?>, IJournalSequence, IModelListObservation<T?>,
    IOrdinaryModelListMembership<T?>, IModelListOrdering<T?>, IModelListPredicateRemoval<T?>, IModelListReplacement<T?>,
    IModelCursorList where T : class
{
    private readonly ModelSequence<T?> sequence;
    private readonly Func<object?, object?>? resolveReadOnly;
    private List<T?> items => sequence.Items;

    public ReferenceList() : this(default)
    {
    }

    public ReferenceList(ModelChangeSource changeSource)
    {
        sequence = new(changeSource, ReferenceEqualityComparer.Instance, ModelChangeKind.List, isModelReference: true);
        resolveReadOnly = changeSource.ReadOnlyAliasResolver;
    }

    public ReferenceList(ModelChangeSource changeSource, Func<T, ReciprocalEndpoint> opposite) : this(changeSource)
    {
        ArgumentNullException.ThrowIfNull(opposite);
        sequence.Reciprocal = ReciprocalCollections.ForSequence(changeSource, sequence, value => opposite(value!), unique: false);
    }

    public ReciprocalEndpoint Endpoint => sequence.Reciprocal ?? throw new InvalidOperationException("The list has no reciprocal endpoint.");

    [System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
    public void AttachShallowOwner(ModelChangeSource owner) => sequence.AttachShallowOwner(owner);

    bool IOrdinaryModelListMembership<T?>.SupportsMembershipFilter => true;
    void IOrdinaryModelListMembership<T?>.RequireWritable() { }
    void IOrdinaryModelListMembership<T?>.RemoveSlots(int[] positions) => sequence.RemoveSlots(positions);
    void IOrdinaryModelListMembership<T?>.ApplyRange(int index, int removalCount, T?[] added) =>
        sequence.ApplyRange(index, removalCount, added);

    int IModelListPredicateRemoval<T?>.RemoveWhere(Predicate<T?> predicate) => sequence.RemoveWhere(predicate);
    void IModelListReplacement<T?>.ReplaceAll(Func<T?, T?> transform) => sequence.ReplaceAll(transform, allowNull: true);

    public int Count => items.Count;

    long IModelCursorRevision.Version => sequence.CursorVersion;
    long IModelCursorList.StructureVersion => sequence.CursorStructureVersion;
    bool IModelCursorList.CanEditPosition => true;
    object? IModelCursorList.Get(int index) => this[index];
    bool IModelCursorList.ContainsIdentity(object child) => items.Exists(item => ReferenceEquals(item, child));
    void IModelCursorList.InsertAt(int index, object? child) => Insert(index, (T?)child);
    void IModelCursorList.ReplaceAt(int index, object? child) => this[index] = (T?)child;

    IDisposable IModelListObservation<T?>.AddListener(Action<ModelListChange<T?>> listener) =>
        sequence.AddListener(this, listener);

    bool IModelListObservation<T?>.TryAddProjection(Action<ModelListListeners<T?>.ListEdit> relay,
        Func<bool> isActive, Func<bool> isAlive) =>
        sequence.AddProjection(relay, isActive, isAlive);

    public T? this[int index]
    {
        get => items[index];
        set => sequence.Set(index, value);
    }

    public bool Add(T? item)
    {
        sequence.Insert(Count, item);
        return true;
    }

    public void Insert(int index, T? item) => sequence.Insert(index, item);

    public bool AddRange(IEnumerable<T?> values) => InsertRange(Count, values);

    public bool InsertRange(int index, IEnumerable<T?> values)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(index);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(index, Count);
        ArgumentNullException.ThrowIfNull(values);
        var additions = values.ToList();
        sequence.InsertRange(index, additions);
        return additions.Count != 0;
    }

    public bool Contains(T? item) => IndexOf(item) >= 0;
    public bool Contains(object? item) => IndexOf(item) >= 0;

    public int IndexOf(T? item) => IndexOf((object?)item);
    public int IndexOf(object? item)
    {
        item = ModelChangeSource.ResolveReadOnlyAlias(item, resolveReadOnly) ?? item;
        return item is T target ? items.IndexOf(target) : item is null ? items.IndexOf(null) : -1;
    }

    public bool Remove(T? item) => Remove((object?)item);
    public bool Remove(object? item)
    {
        int index = IndexOf(item);
        if (index < 0) return false;
        sequence.RemoveAt(index);
        return true;
    }

    public void RemoveAt(int index) => sequence.RemoveAt(index);

    public void Clear() => sequence.Clear();

    public void Move(int oldIndex, int newIndex) => sequence.Move(oldIndex, newIndex);

    /// <summary>Stably sorts the references and reports one edit at the changed positions.</summary>
    public void Sort(Comparison<T?> comparison) => sequence.Sort(comparison);

    public IEnumerator<T?> GetEnumerator() => items.GetEnumerator();

    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    bool ICollection<T?>.IsReadOnly => false;
    void ICollection<T?>.Add(T? item) => Add(item);
    void ICollection<T?>.CopyTo(T?[] array, int arrayIndex) => items.CopyTo(array, arrayIndex);

    void IJournalSequence.Replace(IReadOnlyList<object?> values)
    {
        var converted = values.Select(value => (T?)value!).ToList();
        if (sequence.Reciprocal is not null)
        {
            sequence.ApplyRange(0, Count, converted.ToArray());
            return;
        }
        Clear();
        AddRange(converted);
    }
}
