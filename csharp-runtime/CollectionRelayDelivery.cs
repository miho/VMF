namespace Vmf.Runtime;

public sealed partial class ModelChanges
{
    // One publication takes its raw-source registrations before any inverse callback.
    // Prepare runs on the committed edit. Live model groups read their listeners at their turn.
    internal sealed record CollectionRelayContext(Action<ObserverGroup> Notify);

    internal readonly struct CollectionRelayDelivery
    {
        private readonly ObservationRegistration[] groups;
        private readonly List<Recording>? recorders;
        private readonly IContainment? relationship;

        private CollectionRelayDelivery(ObservationRegistration[] groups, IContainment? relationship)
        {
            this.groups = groups;
            this.relationship = relationship;
            recorders = SnapshotRecorders(groups);
        }

        internal bool HasModelGroups => groups.Length != 0;
        internal bool HasPortableRecorder => recorders?.Exists(static route =>
            route.CanRecord && route.Stream.CaptureBeforeObservers is not null) == true;

        internal void Prepare(ModelChange change) => PrepareJournalEdit(recorders, change);

        internal static CollectionRelayDelivery? Capture(object owner, IContainment? relationship = null,
            bool projectedRelayMayActivate = false)
        {
            var groups = Volatile.Read(ref hasStreams) == 0 ? [] : SnapshotGroups(owner);
            return groups.Length == 0 && !projectedRelayMayActivate ? null : new(groups, relationship);
        }

        internal Exception? Dispatch<T>(ModelChange change, ModelListListeners<T>.Delivery direct,
            ModelListListeners<T>.ListEdit edit, Exception? failure = null, bool prepared = false) =>
            Dispatch(change, direct.Callbacks, edit, static (value, context) => value with { Context = context },
                failure: failure, prepared: prepared);

        internal Exception? Dispatch<TEdit>(ModelChange change, CollectionCallbackSnapshot<TEdit> direct,
            TEdit edit, Func<TEdit, CollectionRelayContext, TEdit> attachContext,
            CollectionCallbackSnapshot<TEdit> projectedLane = default, Exception? failure = null, bool prepared = false)
        {
            if (!prepared) Prepare(change);
            bool projected = direct.HasProjections || projectedLane.Count != 0;
            HashSet<ModelChanges>? delivered = groups.Length > 1 || projected ? new(ReferenceEqualityComparer.Instance) : null;
            HashSet<ModelChanges>? merged = null;
            if (projected)
            {
                var plan = this;
                edit = attachContext(edit, new(group =>
                    ModelObserverFailures.Rethrow(plan.DeliverGroup(group, change, delivered, ref merged, null))));
            }
            int raw = 0;
            int model = 0;
            while (raw < direct.Count || model < groups.Length)
            {
                if (raw < direct.Count && (model == groups.Length || direct.OrderOf(raw) < groups[model].Order))
                {
                    failure = direct.NotifyAt(raw++, edit, failure);
                }
                else
                {
                    var registration = groups[model++];
                    if (!registration.IsProjected(change.PropertyName))
                        failure = DeliverGroup(registration.Group, change, delivered, ref merged, failure);
                }
            }
            return projectedLane.Notify(edit, failure);
        }

        private Exception? DeliverGroup(ObserverGroup group, ModelChange change, HashSet<ModelChanges>? delivered,
            ref HashSet<ModelChanges>? merged, Exception? failure) =>
            NotifyRecordingRelay(group, recorders, change, relationship, delivered, failure,
                reconcileAfterRecording: relationship is not null, delivery: DeliveryFor(groups, group, change, ref merged));
    }
}
