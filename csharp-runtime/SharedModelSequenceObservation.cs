namespace Vmf.Runtime;

public sealed partial class ModelChanges
{
    // A shallow copy shares the physical collection and its direct observers.
    // Each owner has its own model delta and recording relays on that source.
    internal sealed class SharedSequenceDelivery
    {
        private readonly SharedModelSequenceOwners owners;
        private readonly SharedSequenceRoute[] routes;
        private readonly (ObservationRegistration Group, int Route)[] ordered;

        private SharedSequenceDelivery(SharedModelSequenceOwners owners, SharedSequenceRoute[] routes)
        {
            this.owners = owners;
            this.routes = routes;
            ordered = Interleave(routes);
        }

        internal bool HasModelGroups => routes.Length != 0;

        /// <summary>
        /// Interleaves the owners' groups by registration order while each owner keeps its own group order,
        /// nearest observers first (portable contract E8).
        /// </summary>
        private static (ObservationRegistration Group, int Route)[] Interleave(SharedSequenceRoute[] routes)
        {
            var result = new List<(ObservationRegistration Group, int Route)>();
            var next = new int[routes.Length];
            while (true)
            {
                int best = -1;
                for (int route = 0; route < routes.Length; route++)
                {
                    if (next[route] >= routes[route].Groups.Length) continue;
                    if (best < 0 || routes[route].Groups[next[route]].Order < routes[best].Groups[next[best]].Order) best = route;
                }
                if (best < 0) return result.ToArray();
                result.Add((routes[best].Groups[next[best]++], best));
            }
        }

        internal static SharedSequenceDelivery? Capture(SharedModelSequenceOwners owners, bool projectedRelayMayActivate)
        {
            List<SharedSequenceRoute>? routes = null;
            if (Volatile.Read(ref hasStreams) != 0)
            {
                foreach (var owner in owners.Sources())
                {
                    var groups = SnapshotGroups(owner.Owner);
                    if (groups.Length != 0)
                    {
                        (routes ??= []).Add(new(owner, groups, SnapshotRecorders(groups)));
                    }
                }
            }
            return routes is null && !projectedRelayMayActivate ? null : new(owners, routes?.ToArray() ?? []);
        }

        internal Exception? Dispatch<T>(Func<ModelChangeSource, ModelChange> build,
            ModelListListeners<T>.Delivery direct, ModelListListeners<T>.ListEdit edit, Exception? failure)
        {
            var callbacks = direct.Callbacks;
            var publications = new SharedSequencePublication[routes.Length];
            Dictionary<object, SharedSequencePublication>? byOwner = callbacks.HasProjections
                ? new(ReferenceEqualityComparer.Instance) : null;
            for (int index = 0; index < routes.Length; index++)
            {
                var route = routes[index];
                var change = build(route.Source);
                PrepareJournalEdit(route.Recorders, change);
                var publication = new SharedSequencePublication(change, route.Recorders, route.Groups);
                publications[index] = publication;
                byOwner?.Add(route.Source.Owner, publication);
            }
            if (byOwner is not null)
            {
                edit = edit with { Context = new(group => NotifyProjected(group, byOwner, build)) };
            }
            int raw = 0;
            foreach (var (registration, index) in ordered)
            {
                while (raw < callbacks.Count && callbacks.OrderOf(raw) < registration.Order)
                {
                    failure = callbacks.NotifyAt(raw++, edit, failure);
                }
                var publication = publications[index];
                if (!registration.IsProjected(publication.Change.PropertyName))
                {
                    failure = publication.Notify(registration.Group, failure);
                }
            }
            while (raw < callbacks.Count) failure = callbacks.NotifyAt(raw++, edit, failure);
            return failure;
        }

        private void NotifyProjected(ObserverGroup group, Dictionary<object, SharedSequencePublication> byOwner,
            Func<ModelChangeSource, ModelChange> build)
        {
            if (!byOwner.TryGetValue(group.Owner, out var publication))
            {
                // An earlier raw callback can subscribe to an existing projection
                // even when that logical owner had no scopes at commit time.
                if (!owners.TryGetSource(group.Owner, out var source)) return;
                publication = new SharedSequencePublication(build(source), recorders: null, groups: []);
                byOwner.Add(group.Owner, publication);
            }
            ModelObserverFailures.Rethrow(publication.Notify(group, null));
        }
    }

    private sealed record SharedSequenceRoute(ModelChangeSource Source, ObservationRegistration[] Groups,
        List<Recording>? Recorders);

    private sealed class SharedSequencePublication(ModelChange change, List<Recording>? recorders,
        ObservationRegistration[] groups)
    {
        // Distinct owners can belong to the same recursive stream.
        // Deduplicate a stream only within this logical owner.
        private readonly HashSet<ModelChanges> delivered = new(ReferenceEqualityComparer.Instance);
        private HashSet<ModelChanges>? merged;
        internal ModelChange Change { get; } = change;

        internal Exception? Notify(ObserverGroup group, Exception? failure) =>
            NotifyRecordingRelay(group, recorders, Change, null, delivered, failure,
                delivery: DeliveryFor(groups, group, Change, ref merged));
    }
}
