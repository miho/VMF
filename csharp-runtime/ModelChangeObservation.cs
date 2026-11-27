using System.Runtime.CompilerServices;

namespace Vmf.Runtime;

public sealed partial class ModelChanges
{
    private static readonly ConditionalWeakTable<object, ObservationNode> ObservationNodes = new();
    private static long observationOrder;

    internal static long NextObservationOrder() => Interlocked.Increment(ref observationOrder);

    internal static void RegisterContainment(object owner, IContainment containment) =>
        GetObservationNode(owner).Containments.Add(containment);

    internal static IContainmentCursorSlot? FindContainmentSlot(object owner, object child)
    {
        if (!ObservationNodes.TryGetValue(owner, out var node)) return null;
        foreach (var containment in node.Containments)
        {
            if (containment is IContainmentCursorSlot slot && containment.FindChild(child) >= 0
                && ReferenceEquals(containment.GetChildLink(child).CurrentCollection, containment))
            {
                return slot;
            }
        }
        return null;
    }

    private static ObservationNode GetObservationNode(object owner) =>
        ObservationNodes.GetValue(owner, static _ => new ObservationNode());

    private static ObservationRegistration[] SnapshotGroups(object owner) =>
        ObservationNodes.TryGetValue(owner, out var node) ? node.Groups : [];

    internal static bool HasObservedCollections(object owner) =>
        ObservationNodes.TryGetValue(owner, out var node) && node.HasObservedCollections;

    internal static void ReconcileUntrackedContainment(IContainment relationship, IEnumerable<object> removed,
        IEnumerable<object> added, bool useMemberIndex)
    {
        if (Volatile.Read(ref hasStreams) == 0) return;
        foreach (var registration in SnapshotGroups(relationship.OwnerObject))
        {
            registration.Group.Reconcile(relationship, removed, added, useMemberIndex);
        }
    }

    private sealed class ObservationNode
    {
        internal List<IContainment> Containments { get; } = [];
        internal ObservationRegistration[] Groups { get; set; } = [];
        internal bool HasObservedCollections { get; set; }
    }

    private readonly record struct ObservationRegistration(ObserverGroup Group, long Order,
        IReadOnlyDictionary<string, IDisposable>? ProjectionRelays)
    {
        internal bool IsProjected(string name) => ProjectionRelays?.ContainsKey(name) == true;
    }

    internal sealed class ObserverGroup(ModelChanges stream, object owner, bool isRecursive, bool recordingScope = false)
    {
        private readonly ModelListeners<ModelChange> listeners = new();
        private readonly HashSet<object> observed = new(ReferenceEqualityComparer.Instance);
        private bool enabled;
        private Dictionary<string, IDisposable>? projectedRelays;

        internal ModelChanges Stream { get; } = stream;
        internal object Owner => owner;

        internal IDisposable AddListener(Action<ModelChange> callback)
        {
            var subscription = listeners.Add(callback);
            Refresh();
            return new ScopeSubscription(this, subscription);
        }

        internal void Refresh()
        {
            bool shouldEnable = listeners.Snapshot.Length != 0 || (recordingScope && Stream.IsRecording);
            if (shouldEnable == enabled) return;
            enabled = shouldEnable;
            if (enabled)
            {
                ActivateProjectedRelays();
                if (isRecursive) ObserveSubtree(owner, attach: true);
                else
                {
                    observed.Add(owner);
                    AddGroup(owner);
                }
            }
            else
            {
                foreach (object node in observed) RemoveGroup(node);
                observed.Clear();
                if (projectedRelays is not null)
                {
                    foreach (var relay in projectedRelays.Values) relay.Dispose();
                    projectedRelays = null;
                }
            }
        }

        private void ActivateProjectedRelays()
        {
            if (Stream.readOnlyBinding is not { } binding) return;
            foreach (var field in binding.Schema.Fields)
            {
                if (field.Snapshot is not ({ IsCollection: true } or { IsMap: true })) continue;
                var relay = field.Read(binding) switch
                {
                    IModelListProjection { SupportsRelay: true } list => list.AddModelRelay(this),
                    IModelMembershipProjection membership => membership.AddModelRelay(this),
                    _ => null
                };
                if (relay is not null)
                {
                    (projectedRelays ??= new(StringComparer.Ordinal)).Add(field.Field.Name, relay);
                }
            }
        }

        internal Exception? Notify(ModelChange change, IContainment? relationship, Exception? failure)
        {
            failure = NotifyListeners(change, failure);
            ReconcileChange(change, relationship);
            return failure;
        }

        internal Exception? NotifyListeners(ModelChange change, Exception? failure)
        {
            if (change is ReciprocalReferenceChange { RecordingOnly: true }) return failure;
            // The source takes a group snapshot when publishing. A group's listener list is
            // taken only at its turn, so a preceding group can change later subscriptions.
            return ModelListeners<ModelChange>.Notify(listeners.Snapshot, change, recursiveOnly: false, failure);
        }

        internal void ReconcileChange(ModelChange change, IContainment? relationship)
        {
            if (!isRecursive || relationship is null) return;
            if (change is ModelCollectionChange collection)
            {
                Reconcile(relationship, collection.Removed.Select(entry => entry.Value).OfType<object>(),
                    collection.Added.Select(entry => entry.Value).OfType<object>(),
                    collection.Removed.Count + collection.Added.Count > 1);
            }
            else
            {
                Reconcile(relationship, Members(change.OldValue), Members(change.NewValue));
            }
        }

        internal void Reconcile(IContainment relationship, IEnumerable<object> removed, IEnumerable<object> added,
            bool useMemberIndex = false)
        {
            if (!isRecursive || !enabled || !observed.Contains(relationship.OwnerObject)) return;
            var members = useMemberIndex ? new HashSet<object>(relationship.Children, ReferenceEqualityComparer.Instance) : null;

            foreach (object child in added)
            {
                if (ReferenceEquals(relationship.GetChildLink(child).CurrentCollection, relationship)
                    && IsPresent(relationship, child))
                {
                    ObserveSubtree(child, attach: true);
                }
            }
            foreach (object child in removed)
            {
                if (ReferenceEquals(child, owner) || !observed.Contains(child)) continue;
                var current = relationship.GetChildLink(child).CurrentCollection;
                if (current is null || !observed.Contains(current.OwnerObject) || !IsPresent(current, child))
                {
                    ObserveSubtree(child, attach: false);
                }
            }

            bool IsPresent(IContainment current, object child) =>
                ReferenceEquals(current, relationship) && members is not null
                    ? members.Contains(child)
                    : current.FindChild(child) >= 0;
        }

        private void ObserveSubtree(object root, bool attach)
        {
            if (observed.Contains(root) == attach) return;
            var pending = new Stack<object>();
            pending.Push(root);
            while (pending.TryPop(out object? node))
            {
                if (attach)
                {
                    if (!observed.Add(node)) continue;
                    AddGroup(node);
                }
                else
                {
                    if (!observed.Remove(node)) continue;
                    RemoveGroup(node);
                }

                var state = GetObservationNode(node);
                for (int index = state.Containments.Count - 1; index >= 0; index--)
                {
                    IContainment relationship = state.Containments[index];
                    foreach (object child in relationship.Children.Reverse())
                    {
                        if (ReferenceEquals(relationship.GetChildLink(child).CurrentCollection, relationship))
                        {
                            pending.Push(child);
                        }
                    }
                }
            }
        }

        private void AddGroup(object node)
        {
            var state = GetObservationNode(node);
            state.HasObservedCollections = true;
            state.Groups = [.. state.Groups, new ObservationRegistration(this, NextObservationOrder(), projectedRelays)];
        }

        private void RemoveGroup(object node)
        {
            var state = GetObservationNode(node);
            int index = Array.FindIndex(state.Groups, registration => ReferenceEquals(registration.Group, this));
            if (index >= 0) state.Groups = [.. state.Groups.AsSpan(0, index), .. state.Groups.AsSpan(index + 1)];
        }

        private sealed class ScopeSubscription(ObserverGroup owner, IDisposable subscription) : IDisposable
        {
            public void Dispose()
            {
                subscription.Dispose();
                owner.Refresh();
            }
        }
    }

    private static IEnumerable<object> Members(object? member) => member is null ? [] : [member];
}
