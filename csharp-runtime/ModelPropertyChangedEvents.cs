using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace Vmf.Runtime;

/// <summary>Connects generated CLR property-change events to direct VMF property notifications.</summary>
[EditorBrowsable(EditorBrowsableState.Never)]
public static class ModelPropertyChangedEvents
{
    private static readonly ConditionalWeakTable<object, Connection> Active = new();

    public static void Add(object sender, PropertyChangedEventHandler handler,
        Func<Action<ModelPropertyNotification>, IDisposable> subscribe, Func<string, string?> memberName)
    {
        lock (Active)
        {
            if (Active.TryGetValue(sender, out var existing))
            {
                Volatile.Write(ref existing.Handlers, existing.Handlers + handler);
                return;
            }
            var connection = new Connection(sender, memberName, handler);
            connection.Subscription = subscribe(connection.Raise);
            Active.Add(sender, connection);
        }
    }

    public static void Remove(object sender, PropertyChangedEventHandler? handler)
    {
        if (handler is null) return;
        lock (Active)
        {
            if (!Active.TryGetValue(sender, out var connection)) return;
            var remaining = connection.Handlers - handler;
            Volatile.Write(ref connection.Handlers, remaining);
            if (remaining is not null) return;
            Active.Remove(sender);
            connection.Subscription!.Dispose();
        }
    }

    private sealed class Connection(object sender, Func<string, string?> memberName, PropertyChangedEventHandler handler)
    {
        internal PropertyChangedEventHandler? Handlers = handler;
        internal IDisposable? Subscription;

        internal void Raise(ModelPropertyNotification change)
        {
            var handlers = Volatile.Read(ref Handlers);
            if (handlers is not null && memberName(change.PropertyName) is { } name)
                handlers(sender, new PropertyChangedEventArgs(name));
        }
    }
}
