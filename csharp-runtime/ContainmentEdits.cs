namespace Vmf.Runtime;

internal static class ContainmentEdits
{
    internal static void Notify(Action<ModelChange> deliver, ModelChange change, ref Exception? failure)
    {
        try
        {
            deliver(change);
        }
        catch (Exception error)
        {
            failure = ModelObserverFailures.Add(failure, error);
        }
    }
}
