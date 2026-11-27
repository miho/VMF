using System.Collections;
using System.Runtime.CompilerServices;
using System.Runtime.ExceptionServices;

namespace Vmf.Runtime;

/// <summary>A live, read-only view of recorded edits or published transactions.</summary>
/// <remarks>Use <see cref="ModelListObservation"/> to observe committed positions.</remarks>
public sealed class ModelHistory<T> : IReadOnlyList<T>, IModelListObservation<T>
{
    private readonly List<T> items = new();
    private readonly ModelListeners<ModelHistoryChange<T>> listeners = new();

    internal ModelHistory()
    {
    }

    public int Count => items.Count;
    public T this[int index] => items[index];

    /// <summary>Observes committed membership changes. A throwing listener ends delivery of that membership event.</summary>
    public IDisposable AddListener(Action<ModelHistoryChange<T>> listener) => listeners.Add(listener);

    IDisposable IModelListObservation<T>.AddListener(Action<ModelListChange<T>> listener) =>
        AddListener(change => listener(change.AsListChange()));

    public IEnumerator<T> GetEnumerator() => items.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    internal Notification? Append(T item)
    {
        int index = items.Count;
        items.Add(item);
        var snapshot = listeners.Snapshot;
        return snapshot.Length == 0 ? null
            : new Notification(new(this, index, Array.AsReadOnly(new[] { item }), Array.Empty<T>()), snapshot);
    }

    internal Notification? RemoveAll()
    {
        if (items.Count == 0) return null;
        var snapshot = listeners.Snapshot;
        var change = snapshot.Length == 0 ? null
            : new Notification(new(this, 0, Array.Empty<T>(), Array.AsReadOnly(items.ToArray())), snapshot);
        items.Clear();
        return change;
    }

    internal sealed record Notification(ModelHistoryChange<T> Change,
        ModelListeners<ModelHistoryChange<T>>.Subscription[] Listeners)
    {
        internal Exception? Notify(Exception? failure) =>
            ModelListeners<ModelHistoryChange<T>>.Notify(Listeners, Change, recursiveOnly: false, failure, failFast: true);
    }
}

/// <summary>A committed membership change to a read-only history view.</summary>
public sealed class ModelHistoryChange<T>
{
    private readonly int index;
    private ModelListChange<T>? listChange;

    internal ModelHistoryChange(ModelHistory<T> source, int index, IReadOnlyList<T> added, IReadOnlyList<T> removed)
    {
        Source = source;
        this.index = index;
        Added = added;
        Removed = removed;
    }

    public ModelHistory<T> Source { get; }
    public IReadOnlyList<T> Added { get; }
    public IReadOnlyList<T> Removed { get; }

    internal ModelListChange<T> AsListChange() => listChange ??= new(Source, Entries(Removed), Entries(Added));

    private IReadOnlyList<ModelListEntry<T>> Entries(IReadOnlyList<T> values) => values.Count == 0
        ? Array.Empty<ModelListEntry<T>>()
        : Array.AsReadOnly(values.Select((value, offset) => new ModelListEntry<T>(index + offset, value)).ToArray());
}

internal sealed class ModelListeners<T>
{
    private bool hasCollectibleRelays;
    internal Subscription[] Snapshot { get; private set; } = [];

    internal IDisposable Add(Action<T> callback, bool recursive = false, long order = 0,
        Func<bool>? relayActive = null, Func<bool>? relayAlive = null)
    {
        ArgumentNullException.ThrowIfNull(callback);
        if (relayAlive is not null)
        {
            PruneDeadRelays();
            hasCollectibleRelays = true;
        }
        var subscription = new Subscription(this, callback, recursive, order, relayActive, relayAlive);
        Snapshot = [.. Snapshot, subscription];
        return subscription;
    }

    internal void PruneDeadRelays()
    {
        if (!hasCollectibleRelays || Array.TrueForAll(Snapshot, static listener => listener.IsAlive)) return;
        Snapshot = Array.FindAll(Snapshot, static listener => listener.IsAlive);
        hasCollectibleRelays = Array.Exists(Snapshot, static listener => listener.CanExpire);
    }

    private void Remove(Subscription subscription)
    {
        int index = Array.IndexOf(Snapshot, subscription);
        if (index >= 0) Snapshot = [.. Snapshot.AsSpan(0, index), .. Snapshot.AsSpan(index + 1)];
    }

    internal static Exception? Notify(Subscription[] snapshot, T value, bool recursiveOnly, Exception? failure,
        bool failFast = false)
    {
        foreach (var subscription in snapshot)
        {
            if (recursiveOnly && !subscription.Recursive) continue;
            try
            {
                subscription.Callback(value);
            }
            catch (Exception error)
            {
                failure = ModelObserverFailures.Add(failure, error);
                if (failFast) break;
            }
        }
        return failure;
    }

    internal sealed class Subscription(ModelListeners<T> owner, Action<T> callback, bool recursive, long order,
        Func<bool>? relayActive, Func<bool>? relayAlive) : IDisposable
    {
        internal Action<T> Callback { get; } = callback;
        internal bool Recursive { get; } = recursive;
        internal long Order { get; } = order;
        internal bool IsStructuralRelay => relayActive is not null;
        internal bool IsActive => relayActive?.Invoke() != false;
        internal bool CanExpire => relayAlive is not null;
        internal bool IsAlive => relayAlive?.Invoke() != false;
        public void Dispose() => owner.Remove(this);
    }
}

internal static class ModelObserverFailures
{
    private static readonly ConditionalWeakTable<Exception, List<Exception>> Suppressed = new();

    internal static Exception Add(Exception? first, Exception next)
    {
        if (first is null) return next;
        if (!ReferenceEquals(first, next))
        {
            var additional = Suppressed.GetValue(first, static _ => new List<Exception>());
            lock (additional) additional.Add(next);
        }
        return first;
    }

    internal static IReadOnlyList<Exception> GetSuppressed(Exception error)
    {
        if (!Suppressed.TryGetValue(error, out var additional)) return Array.Empty<Exception>();
        lock (additional) return Array.AsReadOnly(additional.ToArray());
    }

    internal static void Rethrow(Exception? failure)
    {
        if (failure is not null) ExceptionDispatchInfo.Capture(failure).Throw();
    }
}
