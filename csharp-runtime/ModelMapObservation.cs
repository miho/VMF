namespace Vmf.Runtime;

/// <summary>A committed edit reported by a scalar map or its read-only view.</summary>
public sealed class ModelMapChange<TKey, TValue>
{
    internal ModelMapChange(IReadOnlyDictionary<TKey, TValue> source,
        IReadOnlyDictionary<TKey, TValue> removed, IReadOnlyDictionary<TKey, TValue> added)
    {
        Source = source;
        Removed = removed;
        Added = added;
    }

    public IReadOnlyDictionary<TKey, TValue> Source { get; }
    public IReadOnlyDictionary<TKey, TValue> Removed { get; }
    public IReadOnlyDictionary<TKey, TValue> Added { get; }
}

/// <summary>Observes edits directly on VMF scalar maps.</summary>
public static class ModelMapObservation
{
    /// <summary>Subscribes to the supplied map identity. Dispose to unsubscribe.</summary>
    /// <exception cref="NotSupportedException">The map does not expose collection listeners.</exception>
    public static IDisposable AddListener<TKey, TValue>(this IReadOnlyDictionary<TKey, TValue> map,
        Action<ModelMapChange<TKey, TValue>> listener)
    {
        ArgumentNullException.ThrowIfNull(map);
        ArgumentNullException.ThrowIfNull(listener);
        return map is IModelMapObservation<TKey, TValue> observable
            ? observable.AddListener(listener)
            : throw new NotSupportedException("This map does not expose VMF collection listeners.");
    }
}

internal interface IModelMapObservation<TKey, TValue>
{
    IDisposable AddListener(Action<ModelMapChange<TKey, TValue>> listener);
}
