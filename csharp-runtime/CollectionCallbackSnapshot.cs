namespace Vmf.Runtime;

internal interface IModelMembershipProjection
{
    IDisposable? AddModelRelay(ModelChanges.ObserverGroup group);
}

internal readonly struct CollectionCallbackSnapshot<TEdit>(ModelListeners<TEdit>.Subscription[]? entries)
{
    internal int Count => entries?.Length ?? 0;

    internal bool HasObservers
    {
        get
        {
            foreach (var entry in entries ?? [])
            {
                if (entry.IsActive) return true;
            }
            return false;
        }
    }

    internal bool HasProjections
    {
        get
        {
            foreach (var entry in entries ?? [])
            {
                if (entry.IsStructuralRelay) return true;
            }
            return false;
        }
    }

    internal long OrderOf(int index) => entries![index].Order;

    internal Exception? Notify(TEdit edit, Exception? failure) =>
        ModelListeners<TEdit>.Notify(entries ?? [], edit, recursiveOnly: false, failure);

    internal Exception? NotifyAt(int index, TEdit edit, Exception? failure)
    {
        try { entries![index].Callback(edit); }
        catch (Exception error) { failure = ModelObserverFailures.Add(failure, error); }
        return failure;
    }
}
