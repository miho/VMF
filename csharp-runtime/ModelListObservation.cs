namespace Vmf.Runtime;

/// <summary>A value and its position in a list change.</summary>
public readonly record struct ModelListEntry<T>(int Index, T Value);

/// <summary>A committed edit reported by a mutable list or its read-only view.</summary>
public sealed class ModelListChange<T>
{
    internal ModelListChange(IReadOnlyList<T> source, IReadOnlyList<ModelListEntry<T>> removed,
        IReadOnlyList<ModelListEntry<T>> added)
    {
        Source = source;
        Removed = removed;
        Added = added;
    }

    public IReadOnlyList<T> Source { get; }
    public IReadOnlyList<ModelListEntry<T>> Removed { get; }
    public IReadOnlyList<ModelListEntry<T>> Added { get; }
}

/// <summary>Observes edits directly on VMF lists that support collection listeners.</summary>
public static class ModelListObservation
{
    /// <summary>Subscribes to changes on the supplied list identity. Dispose to unsubscribe.</summary>
    /// <exception cref="NotSupportedException">The list does not expose collection listeners.</exception>
    public static IDisposable AddListener<T>(this IReadOnlyList<T> list, Action<ModelListChange<T>> listener)
    {
        ArgumentNullException.ThrowIfNull(list);
        ArgumentNullException.ThrowIfNull(listener);
        return list is IModelListObservation<T> observable
            ? observable.AddListener(listener)
            : throw new NotSupportedException("This list does not expose VMF collection listeners.");
    }
}

internal interface IModelListObservation<T>
{
    IDisposable AddListener(Action<ModelListChange<T>> listener);
    bool TryAddProjection(Action<ModelListListeners<T>.ListEdit> relay, Func<bool> isActive,
        Func<bool> isAlive) => false;
}

internal interface IModelListProjection
{
    bool SupportsRelay { get; }
    IDisposable AddModelRelay(ModelChanges.ObserverGroup group);
}

internal sealed class ModelListListeners<T>
{
    private readonly ModelListeners<ListEdit> listeners = new();

    internal IDisposable Add(IReadOnlyList<T> list, Action<ModelListChange<T>> listener) =>
        listeners.Add(edit => listener(new(list, edit.Removed, edit.Added)), order: ModelChanges.NextObservationOrder());

    internal bool AddProjection(Action<ListEdit> callback, Func<bool> isActive, Func<bool>? isAlive)
    {
        listeners.Add(callback, order: ModelChanges.NextObservationOrder(), relayActive: isActive, relayAlive: isAlive);
        return true;
    }

    internal IDisposable AddModelRelay(ModelChanges.ObserverGroup group) =>
        listeners.Add(edit => edit.Context?.Notify(group), order: ModelChanges.NextObservationOrder());

    internal bool HasObservers => listeners.Snapshot.Length != 0;

    internal Delivery Capture()
    {
        listeners.PruneDeadRelays();
        return new(listeners.Snapshot);
    }

    internal readonly record struct ListEdit(IReadOnlyList<ModelListEntry<T>> Removed,
        IReadOnlyList<ModelListEntry<T>> Added, ModelChanges.CollectionRelayContext? Context = null);

    internal readonly struct Delivery(ModelListeners<ListEdit>.Subscription[] observers)
    {
        internal CollectionCallbackSnapshot<ListEdit> Callbacks => new(observers);
        internal int Count => Callbacks.Count;
        internal bool HasObservers => Callbacks.HasObservers;
        internal bool HasProjections => Callbacks.HasProjections;

        internal Exception? Notify(int start, T[] removed, T[] added, Exception? failure) => !HasObservers
            ? failure : Notify(CreateEdit(start, removed, added), failure);

        internal Exception? NotifyPositions(int[] positions, T[] removed, T[] added, Exception? failure) => !HasObservers
            ? failure : Notify(CreateEdit(positions, removed, added), failure);

        internal Exception? NotifyRemoval(IReadOnlyList<int> positions, T value, Exception? failure) => !HasObservers
            ? failure : Notify(CreateRemoval(positions, value), failure);

        internal Exception? Notify(ListEdit edit, Exception? failure) =>
            Callbacks.Notify(edit, failure);

        internal static ListEdit CreateEdit(int start, T[] removed, T[] added) =>
            new(Snapshot(start, removed), Snapshot(start, added));

        internal static ListEdit CreateEdit(int[] positions, T[] removed, T[] added) =>
            new(Snapshot(positions, removed), Snapshot(positions, added));

        internal static ListEdit CreateRemoval(IReadOnlyList<int> positions, T value) =>
            new(Array.AsReadOnly(positions.Select(index => new ModelListEntry<T>(index, value)).ToArray()),
                Array.Empty<ModelListEntry<T>>());

        private static IReadOnlyList<ModelListEntry<T>> Snapshot(int start, T[] values) => values.Length == 0
            ? Array.Empty<ModelListEntry<T>>()
            : Array.AsReadOnly(values.Select((value, offset) => new ModelListEntry<T>(start + offset, value)).ToArray());

        private static IReadOnlyList<ModelListEntry<T>> Snapshot(int[] positions, T[] values) =>
            Array.AsReadOnly(values.Select((value, offset) => new ModelListEntry<T>(positions[offset], value)).ToArray());
    }
}
