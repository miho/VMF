using System.Collections;

namespace Vmf.Runtime;

/// <summary>A live, read-only projection of an ordered model collection.</summary>
public sealed class ReadOnlyList<TSource, TView> : IReadOnlyList<TView>, IModelListObservation<TView>, IModelListProjection
{
    private readonly IReadOnlyList<TSource> source;
    private readonly Func<TSource, TView> view;
    private readonly ModelListListeners<TView> observers = new();
    private readonly ProjectionRelay? sourceRelay;

    public ReadOnlyList(IReadOnlyList<TSource> source, Func<TSource, TView> view)
    {
        ArgumentNullException.ThrowIfNull(source);
        ArgumentNullException.ThrowIfNull(view);
        this.source = source;
        this.view = view;
        if (source is IModelListObservation<TSource> observable)
        {
            var relay = new ProjectionRelay(this);
            if (observable.TryAddProjection(relay.Deliver, relay.IsActive, relay.IsAlive)) sourceRelay = relay;
        }
    }

    public int Count => source.Count;

    public TView this[int index] => view(source[index]);

    public IEnumerator<TView> GetEnumerator() => source.Select(view).GetEnumerator();

    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    IDisposable IModelListObservation<TView>.AddListener(Action<ModelListChange<TView>> listener) =>
        sourceRelay is { } relay ? relay.Retain(this, observers.Add(this, listener))
            : source.AddListener(edit => listener(new(this, Project(edit.Removed), Project(edit.Added))));

    bool IModelListProjection.SupportsRelay => sourceRelay is not null;

    // A keyed ordered set also offers CLR list listeners, but its modeled scopes use the set relay.
    IDisposable IModelListProjection.AddModelRelay(ModelChanges.ObserverGroup group) =>
        (source as IModelReferenceSetProjectionSource)?.AddReferenceSetModelRelay(group)
        ?? sourceRelay!.Retain(this, observers.AddModelRelay(group));

    // Keep the registration position while a caller can still reach a dormant view.
    // A live subscription retains the subscribed source even if the caller drops the view.
    private sealed class ProjectionRelay(ReadOnlyList<TSource, TView> view)
    {
        private readonly WeakReference<ReadOnlyList<TSource, TView>> target = new(view);
        private ReadOnlyList<TSource, TView>? subscribedView;
        private int subscribers;

        internal bool IsAlive() => target.TryGetTarget(out _);
        internal bool IsActive() => subscribedView is not null;

        internal void Deliver(ModelListListeners<TSource>.ListEdit edit) => subscribedView?.Relay(edit);

        internal IDisposable Retain(ReadOnlyList<TSource, TView> source, IDisposable subscription)
        {
            subscribedView = source;
            subscribers++;
            return new RetainedSubscription(this, subscription);
        }

        private void Release()
        {
            if (--subscribers == 0) subscribedView = null;
        }

        private sealed class RetainedSubscription(ProjectionRelay relay, IDisposable initial) : IDisposable
        {
            private IDisposable? subscription = initial;

            public void Dispose()
            {
                if (subscription is not { } current) return;
                subscription = null;
                try { current.Dispose(); }
                finally { relay.Release(); }
            }
        }
    }

    private void Relay(ModelListListeners<TSource>.ListEdit edit)
    {
        var snapshot = observers.Capture();
        if (snapshot.HasObservers)
        {
            ModelObserverFailures.Rethrow(snapshot.Notify(new(Project(edit.Removed), Project(edit.Added), edit.Context), null));
        }
    }

    private IReadOnlyList<ModelListEntry<TView>> Project(IReadOnlyList<ModelListEntry<TSource>> entries) =>
        entries.Count == 0 ? Array.Empty<ModelListEntry<TView>>()
            : Array.AsReadOnly(entries.Select(entry => new ModelListEntry<TView>(entry.Index, view(entry.Value))).ToArray());
}

/// <summary>A covariant read-only collection with identity-based membership.</summary>
public interface IReadOnlyReferenceSet<out T> : IReadOnlyCollection<T> where T : class
{
    bool Contains(object? member);
}

/// <summary>A covariant read-only reference set with indexed access in model order.</summary>
public interface IReadOnlyOrderedReferenceSet<out T> : IReadOnlyList<T>, IReadOnlyReferenceSet<T> where T : class
{
    int IndexOf(object? member);
}

/// <summary>A live projection of a reference collection with identity-based membership.</summary>
public sealed class ReadOnlyReferenceSet<TSource, TView> : IReadOnlyReferenceSet<TView>, IModelMembershipProjection where TView : class
{
    private readonly IReadOnlyCollection<TSource> source;
    private readonly Func<TSource, TView> view;

    public ReadOnlyReferenceSet(IReadOnlyCollection<TSource> source, Func<TSource, TView> view)
    {
        ArgumentNullException.ThrowIfNull(source);
        ArgumentNullException.ThrowIfNull(view);
        this.source = source;
        this.view = view;
    }

    public int Count => source.Count;

    public bool Contains(object? member) => member is not null
        && source.Any(item => ReferenceEquals(item, member) || member is TView && ReferenceEquals(view(item), member));

    public IEnumerator<TView> GetEnumerator() => source.Select(view).GetEnumerator();

    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    IDisposable? IModelMembershipProjection.AddModelRelay(ModelChanges.ObserverGroup group) =>
        (source as IModelReferenceSetProjectionSource)?.AddReferenceSetModelRelay(group);
}

/// <summary>A live, ordered projection with identity lookup for native objects and their views.</summary>
public sealed class ReadOnlyOrderedReferenceSet<TSource, TView> : IReadOnlyOrderedReferenceSet<TView> where TView : class
{
    private readonly IReadOnlyList<TSource> source;
    private readonly Func<TSource, TView> view;

    public ReadOnlyOrderedReferenceSet(IReadOnlyList<TSource> source, Func<TSource, TView> view)
    {
        ArgumentNullException.ThrowIfNull(source);
        ArgumentNullException.ThrowIfNull(view);
        this.source = source;
        this.view = view;
    }

    public int Count => source.Count;

    public TView this[int index] => view(source[index]);

    public bool Contains(object? member) => IndexOf(member) >= 0;

    public int IndexOf(object? member)
    {
        if (member is not null)
        {
            for (int index = 0; index < source.Count; index++)
            {
                TSource item = source[index];
                if (ReferenceEquals(item, member) || member is TView && ReferenceEquals(view(item), member))
                {
                    return index;
                }
            }
        }
        return -1;
    }

    public IEnumerator<TView> GetEnumerator() => source.Select(view).GetEnumerator();

    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
}
