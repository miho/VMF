namespace Vmf.Runtime;

internal interface IModelReferenceSetProjectionSource
{
    IDisposable? AddReferenceSetModelRelay(ModelChanges.ObserverGroup group);
}

// VMF's public identity-set view does not expose the list used for model observation.
// First modeled observation reserves one source position, even across later unsubscription.
internal sealed class ReferenceSetProjectionRelay
{
    private readonly ModelListeners<ModelChanges.CollectionRelayContext> listeners = new();

    internal bool IsActive() => listeners.Snapshot.Length != 0;

    internal IDisposable Add(ModelChanges.ObserverGroup group) => listeners.Add(context => context.Notify(group));

    internal void Notify(ModelChanges.CollectionRelayContext? context)
    {
        if (context is null) return;
        ModelObserverFailures.Rethrow(ModelListeners<ModelChanges.CollectionRelayContext>.Notify(
            listeners.Snapshot, context, recursiveOnly: false, failure: null));
    }
}
