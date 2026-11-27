namespace Vmf.Runtime;

public sealed partial class ContainmentList<TParent, TChild> : IModelList<TChild>, IModelListObservation<TChild>,
    IOrdinaryModelListMembership<TChild>, IModelListReplacement<TChild>, IModelListPredicateRemoval<TChild>
    where TParent : class
    where TChild : class?
{
    private ModelListListeners<TChild>? listListeners;

    bool IOrdinaryModelListMembership<TChild>.SupportsMembershipFilter => IsOrdinaryList;
    void IOrdinaryModelListMembership<TChild>.RequireWritable() => RequireWritable();
    void IOrdinaryModelListMembership<TChild>.RemoveSlots(int[] positions) =>
        ReplaceRange(0, positions.Length, [], positions);
    void IOrdinaryModelListMembership<TChild>.ApplyRange(int index, int removalCount, TChild[] added) =>
        ReplaceRange(index, removalCount, added);

    int IModelListPredicateRemoval<TChild>.RemoveWhere(Predicate<TChild> predicate)
    {
        RequireWritable();
        if (unordered || orderedSet)
            throw new NotSupportedException("Predicate removal requires a containment list without set multiplicity.");
        int[] positions = ModelListMembership.SelectSlots(items, predicate);
        if (positions.Length != 0) ReplaceRange(0, positions.Length, [], positions);
        return positions.Length;
    }

    void IModelListReplacement<TChild>.ReplaceAll(Func<TChild, TChild> transform)
    {
        RequireWritable();
        if (unordered || orderedSet)
            throw new NotSupportedException("Bulk replacement requires a containment list without set multiplicity.");
        if (items.Count == 0) return;
        var replacement = ModelListReplacement.Stage(items, transform);
        ReplaceRange(0, replacement.Length, replacement);
    }

    IDisposable IModelListObservation<TChild>.AddListener(Action<ModelListChange<TChild>> listener) =>
        IsOrdinaryList || key is not null
            ? (listListeners ??= new()).Add(this, listener)
            : throw new NotSupportedException("This containment collection does not expose list listeners.");

    bool IModelListObservation<TChild>.TryAddProjection(Action<ModelListListeners<TChild>.ListEdit> relay,
        Func<bool> isActive, Func<bool> isAlive) =>
        (IsOrdinaryList || key is not null) && (listListeners ??= new()).AddProjection(relay, isActive, isAlive);

    bool ICollection<TChild>.IsReadOnly => false;
    void ICollection<TChild>.Add(TChild item) => Add(item);
    void ICollection<TChild>.CopyTo(TChild[] array, int arrayIndex) => items.CopyTo(array, arrayIndex);
    int IList<TChild>.IndexOf(TChild item) => IsOrdinaryList ? IndexOf(item) : IndexOfValue(item);
}
