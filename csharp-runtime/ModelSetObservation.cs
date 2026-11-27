namespace Vmf.Runtime;

/// <summary>A committed membership edit on a scalar set or its read-only view.</summary>
public sealed class ModelSetChange<T>
{
    internal ModelSetChange(IReadOnlyScalarSet<T> source, IReadOnlySet<T> removed, IReadOnlySet<T> added)
    {
        Source = source;
        Removed = removed;
        Added = added;
    }

    public IReadOnlyScalarSet<T> Source { get; }
    public IReadOnlySet<T> Removed { get; }
    public IReadOnlySet<T> Added { get; }
}

/// <summary>Observes membership edits directly on VMF scalar sets.</summary>
public static class ModelSetObservation
{
    /// <summary>Subscribes to the supplied set identity. Dispose to unsubscribe.</summary>
    /// <exception cref="NotSupportedException">The set does not expose collection listeners.</exception>
    public static IDisposable AddListener<T>(this IReadOnlyScalarSet<T> set, Action<ModelSetChange<T>> listener)
    {
        ArgumentNullException.ThrowIfNull(set);
        ArgumentNullException.ThrowIfNull(listener);
        return set is IModelSetObservation<T> observable
            ? observable.AddListener(listener)
            : throw new NotSupportedException("This set does not expose VMF collection listeners.");
    }
}

internal interface IModelSetObservation<T>
{
    IDisposable AddListener(Action<ModelSetChange<T>> listener);
}
