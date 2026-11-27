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
            ordered = routes.SelectMany((route, index) => route.Groups.Select(group => (Group: group, Route: index)))
                .OrderBy(entry => entry.Group.Order).ToArray();
        }

        internal bool HasModelGroups => routes.Length != 0;

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
                var publication = new SharedSequencePublication(change, route.Recorders);
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
                publication = new SharedSequencePublication(build(source), recorders: null);
                byOwner.Add(group.Owner, publication);
            }
            ModelObserverFailures.Rethrow(publication.Notify(group, null));
        }
    }

    private sealed record SharedSequenceRoute(ModelChangeSource Source, ObservationRegistration[] Groups,
        List<Recording>? Recorders);

    private sealed class SharedSequencePublication(ModelChange change, List<Recording>? recorders)
    {
        // Distinct owners can belong to the same recursive stream.
        // Deduplicate a stream only within this logical owner.
        private readonly HashSet<ModelChanges> delivered = new(ReferenceEqualityComparer.Instance);
        internal ModelChange Change { get; } = change;

        internal Exception? Notify(ObserverGroup group, Exception? failure) =>
            NotifyRecordingRelay(group, recorders, Change, null, delivered, failure);
    }
}
