namespace Vmf.Runtime;

/// <summary>An old and new value delivered directly by a modeled property.</summary>
public sealed class ModelPropertyNotification
{
    internal ModelPropertyNotification(ModelChange change)
        : this(change.Source, change.PropertyName, change.OldValue, change.NewValue, change.IsModelReference)
    {
    }

    private ModelPropertyNotification(object source, string propertyName, object? oldValue, object? newValue,
        bool isModelReference)
    {
        Source = source;
        PropertyName = propertyName;
        OldValue = oldValue;
        NewValue = newValue;
        IsModelReference = isModelReference;
    }

    public object Source { get; }
    public string PropertyName { get; }
    public object? OldValue { get; }
    public object? NewValue { get; }
    public bool IsModelReference { get; }

    internal ModelPropertyNotification Project(ModelReflectionRegistry registry)
    {
        return new(registry.ProjectReadOnly(Source), PropertyName,
            registry.ProjectNotificationValue(OldValue, IsModelReference),
            registry.ProjectNotificationValue(NewValue, IsModelReference), IsModelReference);
    }
}

public sealed partial class ModelChanges
{
    private static int hasDirectPropertyObservers;
    private DirectPropertyObservers? directPropertyObservers;

    /// <summary>Observes property assignments directly on this object in source registration order.</summary>
    public IDisposable AddDirectPropertyListener(Action<ModelPropertyNotification> listener) =>
        AddDirectPropertyListenerCore(null, listener);

    /// <summary>Observes one named property after ordinary direct listeners and model observation.</summary>
    public IDisposable AddDirectPropertyListener(string propertyName, Action<ModelPropertyNotification> listener)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(propertyName);
        return AddDirectPropertyListenerCore(propertyName, listener);
    }

    private IDisposable AddDirectPropertyListenerCore(string? propertyName, Action<ModelPropertyNotification> listener)
    {
        ArgumentNullException.ThrowIfNull(listener);
        if (readOnlyBinding is { } view)
        {
            return For(view.Identity).AddDirectPropertyListenerCore(propertyName, change => listener(change.Project(view.Registry)));
        }

        var observers = LazyInitializer.EnsureInitialized(ref directPropertyObservers);
        var subscription = observers.Add(propertyName, listener);
        Volatile.Write(ref hasDirectPropertyObservers, 1);
        return subscription;
    }

    private static DirectPropertyDelivery SnapshotDirectPropertyObservers(object owner, string propertyName) =>
        Volatile.Read(ref hasDirectPropertyObservers) != 0 && Streams.TryGetValue(owner, out var stream)
            && Volatile.Read(ref stream.directPropertyObservers) is { } observers
            ? observers.Capture(propertyName) : default;

    internal static Action<ModelChange>? CapturePropertyDelivery(object owner, string propertyName,
        IContainment? relationship = null)
    {
        var direct = SnapshotDirectPropertyObservers(owner, propertyName);
        if (!direct.HasObservers) return CaptureDelivery(owner, relationship);

        var groups = SnapshotGroups(owner);
        var recorders = SnapshotRecorders(groups);
        return change => DeliverDirectProperty(groups, recorders, direct, change, relationship);
    }

    private static void DeliverDirectProperty(ObservationRegistration[] groups, List<Recording>? recorders,
        DirectPropertyDelivery direct, ModelChange change, IContainment? relationship)
    {
        PrepareJournalEdit(recorders, change);
        var notification = new ModelPropertyNotification(change);
        HashSet<ModelChanges>? delivered = groups.Length > 1 ? new(ReferenceEqualityComparer.Instance) : null;
        Exception? failure = null;
        int raw = 0;
        foreach (var registration in groups)
        {
            while (raw < direct.Ordinary.Count && direct.Ordinary.OrderOf(raw) < registration.Order)
            {
                failure = direct.Ordinary.NotifyAt(raw++, notification, failure);
            }
            failure = NotifyRecordingRelay(registration.Group, recorders, change, relationship, delivered, failure);
        }
        while (raw < direct.Ordinary.Count)
        {
            failure = direct.Ordinary.NotifyAt(raw++, notification, failure);
        }
        ModelObserverFailures.Rethrow(direct.Named.Notify(notification, failure));
    }

    private readonly record struct DirectPropertyDelivery(
        CollectionCallbackSnapshot<ModelPropertyNotification> Ordinary,
        CollectionCallbackSnapshot<ModelPropertyNotification> Named)
    {
        internal bool HasObservers => Ordinary.Count != 0 || Named.Count != 0;
    }

    private sealed class DirectPropertyObservers
    {
        private readonly object gate = new();
        private readonly ModelListeners<ModelPropertyNotification> ordinary = new();
        private readonly Dictionary<string, ModelListeners<ModelPropertyNotification>> named = new(StringComparer.Ordinal);

        internal IDisposable Add(string? propertyName, Action<ModelPropertyNotification> callback)
        {
            lock (gate)
            {
                var listeners = propertyName is null ? ordinary : NamedListeners(propertyName);
                return new Subscription(this, propertyName, listeners,
                    listeners.Add(callback, order: NextObservationOrder()));
            }
        }

        internal DirectPropertyDelivery Capture(string propertyName)
        {
            lock (gate)
            {
                named.TryGetValue(propertyName, out var listeners);
                return new(new(ordinary.Snapshot), new(listeners?.Snapshot));
            }
        }

        private ModelListeners<ModelPropertyNotification> NamedListeners(string propertyName)
        {
            if (!named.TryGetValue(propertyName, out var listeners))
            {
                named.Add(propertyName, listeners = new());
            }
            return listeners;
        }

        private sealed class Subscription(DirectPropertyObservers owner, string? propertyName,
            ModelListeners<ModelPropertyNotification> listeners, IDisposable inner) : IDisposable
        {
            public void Dispose()
            {
                lock (owner.gate)
                {
                    inner.Dispose();
                    if (propertyName is not null && listeners.Snapshot.Length == 0
                        && owner.named.TryGetValue(propertyName, out var registered) && ReferenceEquals(registered, listeners))
                    {
                        owner.named.Remove(propertyName);
                    }
                }
            }
        }
    }
}
