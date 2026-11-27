namespace Vmf.Runtime;

public sealed partial class ContainmentList<TParent, TChild> : IModelReferenceSetProjectionSource
    where TParent : class
    where TChild : class?
{
    private ReferenceSetProjectionRelay? referenceProjection;

    IDisposable? IModelReferenceSetProjectionSource.AddReferenceSetModelRelay(ModelChanges.ObserverGroup group) =>
        AddReferenceSetModelRelay(group);

    internal IDisposable? AddReferenceSetModelRelay(ModelChanges.ObserverGroup group)
    {
        if (!changeSource.IsTracked || !(unordered || orderedSet && key is not null)) return null;
        if (referenceProjection is null)
        {
            var relay = new ReferenceSetProjectionRelay();
            (listListeners ??= new()).AddProjection(edit => relay.Notify(edit.Context), relay.IsActive, isAlive: null);
            referenceProjection = relay;
        }
        return referenceProjection.Add(group);
    }

    private ListDelivery CaptureListDelivery()
    {
        var direct = listListeners?.Capture() ?? default;
        var ordered = direct.Count != 0 && changeSource.IsTracked
            ? ModelChanges.CollectionRelayDelivery.Capture(changeSource.Owner, this, direct.HasProjections) : null;
        return new(ordered is null ? changeSource.CaptureDelivery(this) : null, direct, ordered);
    }

    private readonly record struct ListDelivery(Action<ModelChange>? Fallback,
        ModelListListeners<TChild>.Delivery Direct, ModelChanges.CollectionRelayDelivery? Ordered)
    {
        internal bool NeedsEdit => Direct.HasObservers || Ordered is { HasModelGroups: true };

        internal bool NeedsModelChange => Fallback is not null || (Ordered is not null && NeedsEdit);

        internal Action<ModelChange>? Prepare(ModelListListeners<TChild>.ListEdit edit, Exception? failure)
        {
            if (Ordered is not { } ordered || !NeedsEdit) return Fallback;
            var direct = Direct;
            return change => ModelObserverFailures.Rethrow(ordered.Dispatch(change, direct, edit, failure));
        }

        internal Exception? Complete(ModelListListeners<TChild>.ListEdit edit, Exception? failure) =>
            Ordered is null && Direct.HasObservers ? Direct.Notify(edit, failure) : failure;
    }
}
