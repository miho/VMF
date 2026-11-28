using System.Runtime.CompilerServices;

namespace Vmf.Runtime;

public sealed partial class ModelChanges
{
    // The observation state of objects that do not name a containment link to keep it at.
    private static readonly ConditionalWeakTable<object, ObservationNode> ObservationNodes = new();
    private static long observationOrder;

    internal static long NextObservationOrder() => Interlocked.Increment(ref observationOrder);

    internal static void RegisterContainment(object owner, IContainment containment) =>
        GetObservationNode(owner).AddContainment(containment);

    internal static IContainmentCursorSlot? FindContainmentSlot(object owner, object child)
    {
        if (FindObservationNode(owner) is not { } node) return null;
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

    /// <summary>
    /// What the change streams know about a model object, or null if nothing yet. A generated mutable object names
    /// its containment link, and its state is a field of that link; the state of any other object is in a table.
    /// </summary>
    internal static ObservationNode? FindObservationNode(object owner)
    {
        if (owner is IModelLinkAccess { VmfContainmentLink: { } link } && link.IsHeldBy(owner)) return link.Observation;
        return ObservationNodes.TryGetValue(owner, out var node) ? node : null;
    }

    private static ObservationNode GetObservationNode(object owner)
    {
        if (owner is IModelLinkAccess { VmfContainmentLink: { } link } && link.IsHeldBy(owner)) return link.RequireObservation();
        return ObservationNodes.GetValue(owner, static _ => new ObservationNode());
    }

    private static ObservationRegistration[] SnapshotGroups(object owner) => FindObservationNode(owner)?.Groups ?? [];

    internal static bool HasObservedCollections(object owner) =>
        FindObservationNode(owner) is { HasObservedCollections: true };

    internal static void ReconcileUntrackedContainment(IContainment relationship, object?[] removed,
        object?[] added, bool useMemberIndex)
    {
        if (Volatile.Read(ref hasStreams) == 0) return;
        foreach (var registration in SnapshotGroups(relationship.OwnerObject))
        {
            registration.Group.Reconcile(relationship, removed, added, useMemberIndex);
        }
    }

    /// <summary>What the change streams know about one model object.</summary>
    internal sealed class ObservationNode
    {
        private ModelChanges? stream;

        internal IContainment[] Containments { get; private set; } = [];
        internal ObservationRegistration[] Groups { get; set; } = [];
        internal bool HasObservedCollections { get; set; }
        // The containment link of a node that a recursive group walked into; it names the current container (E8).
        internal ContainmentLink? ContainerLink { get; set; }

        /// <summary>The change stream of the object, if one was asked for.</summary>
        internal ModelChanges? Stream => stream;

        internal ModelChanges StreamOf(object owner) =>
            stream ?? Interlocked.CompareExchange(ref stream, new ModelChanges(owner), null) ?? stream;

        internal void AddContainment(IContainment containment) => Containments = [.. Containments, containment];
    }

    internal readonly record struct ObservationRegistration(ObserverGroup Group, long Order,
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

        /// <summary>The stream's other group: the local one for the recursive one and the other way round.</summary>
        internal ObserverGroup Peer => ReferenceEquals(this, Stream.local) ? Stream.recursive : Stream.local;

        /// <summary>The listeners of this group and its peer, in the order they were added (E7).</summary>
        internal ModelListeners<ModelChange>.Subscription[] MergeListeners(ObserverGroup peer)
        {
            var mine = listeners.Snapshot;
            var theirs = peer.listeners.Snapshot;
            if (theirs.Length == 0) return mine;
            if (mine.Length == 0) return theirs;
            ModelListeners<ModelChange>.Subscription[] both = [.. mine, .. theirs];
            // Stable: listeners keep the order in which they were added.
            return both.OrderBy(subscription => subscription.Order).ToArray();
        }

        internal IDisposable AddListener(Action<ModelChange> callback)
        {
            var subscription = listeners.Add(callback, order: NextObservationOrder());
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
                    AddGroup(owner, new FartherObservers(owner));
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

        internal Exception? Notify(ModelChange change, IContainment? relationship, Exception? failure,
            ModelListeners<ModelChange>.Subscription[]? delivery = null)
        {
            failure = NotifyListeners(change, failure, delivery);
            ReconcileChange(change, relationship);
            return failure;
        }

        /// <param name="delivery">The listeners to call instead of this group's own (E7), if not null.</param>
        internal Exception? NotifyListeners(ModelChange change, Exception? failure,
            ModelListeners<ModelChange>.Subscription[]? delivery = null)
        {
            // Stamped before any listener can react, so that edits made by listeners are recorded after it (H4).
            change.EnsureDeliveryStamp();
            if (change is ReciprocalReferenceChange { RecordingOnly: true }) return failure;
            // The source takes a group snapshot when publishing. A group's listener list is
            // taken only at its turn, so a preceding group can change later subscriptions.
            return ModelListeners<ModelChange>.Notify(delivery ?? listeners.Snapshot, change, recursiveOnly: false, failure);
        }

        internal void ReconcileChange(ModelChange change, IContainment? relationship)
        {
            if (!isRecursive || relationship is null || !enabled || !observed.Contains(relationship.OwnerObject)) return;
            if (change is ModelCollectionChange collection)
            {
                Reconcile(relationship, collection.RemovedObjects, collection.AddedObjects,
                    collection.RemovedCount + collection.AddedCount > 1);
            }
            else
            {
                Reconcile(relationship, Members(change.OldValue), Members(change.NewValue));
            }
        }

        internal void Reconcile(IContainment relationship, object?[] removed, object?[] added, bool useMemberIndex = false)
        {
            if (!isRecursive || !enabled || !observed.Contains(relationship.OwnerObject)) return;
            var members = useMemberIndex ? new HashSet<object>(relationship.Children, ReferenceEqualityComparer.Instance) : null;

            foreach (object? child in added)
            {
                if (child is null) continue;
                var link = relationship.GetChildLink(child);
                if (ReferenceEquals(link.CurrentCollection, relationship) && IsPresent(relationship, child))
                {
                    RememberContainerLink(child, link);
                    ObserveSubtree(child, attach: true);
                }
            }
            foreach (object? child in removed)
            {
                if (child is null || ReferenceEquals(child, owner) || !observed.Contains(child)) continue;
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
            var farther = attach ? new FartherObservers(owner) : null;
            var pending = new Stack<object>();
            pending.Push(root);
            while (pending.TryPop(out object? node))
            {
                if (attach)
                {
                    if (!observed.Add(node)) continue;
                    AddGroup(node, farther!);
                }
                else
                {
                    if (!observed.Remove(node)) continue;
                    RemoveGroup(node);
                }

                var state = GetObservationNode(node);
                for (int index = state.Containments.Length - 1; index >= 0; index--)
                {
                    IContainment relationship = state.Containments[index];
                    for (int slot = relationship.SlotCount - 1; slot >= 0; slot--)
                    {
                        if (relationship.ChildAt(slot) is not { } child) continue;
                        var link = relationship.GetChildLink(child);
                        if (ReferenceEquals(link.CurrentCollection, relationship))
                        {
                            if (attach) RememberContainerLink(child, link);
                            pending.Push(child);
                        }
                    }
                }
            }
        }

        private void AddGroup(object node, FartherObservers farther)
        {
            var state = GetObservationNode(node);
            state.HasObservedCollections = true;
            var registration = new ObservationRegistration(this, NextObservationOrder(), projectedRelays);
            // A change reaches the nearest observers first (portable contract E8): the changed object's own groups,
            // then its container's, and so on, whatever the order in which they started to observe. The groups are
            // ordered by distance, so the farther ones form the tail: find where it starts.
            var groups = state.Groups;
            int index = 0;
            int end = groups.Length;
            while (index < end)
            {
                int middle = (index + end) >>> 1;
                if (farther.Contains(groups[middle].Group.Owner)) end = middle;
                else index = middle + 1;
            }
            state.Groups = [.. groups.AsSpan(0, index), registration, .. groups.AsSpan(index)];
        }

        private void RemoveGroup(object node)
        {
            var state = GetObservationNode(node);
            var groups = state.Groups;
            int index = 0;
            while (index < groups.Length && !ReferenceEquals(groups[index].Group, this)) index++;
            if (index == groups.Length) return;
            state.Groups = groups.Length == 1 ? [] : [.. groups.AsSpan(0, index), .. groups.AsSpan(index + 1)];
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

    private static object?[] Members(object? member) => member is null ? [] : [member];

    private static void RememberContainerLink(object child, ContainmentLink link) =>
        GetObservationNode(child).ContainerLink ??= link;

    /// <summary>The current container of a node: of its own link, or of the link a recursive group walked in by.</summary>
    private static object? ContainerOf(object node) =>
        node is IModelLinkAccess linked ? linked.VmfContainmentLink.Owner
            : ObservationNodes.TryGetValue(node, out var state) ? state.ContainerLink?.Owner : null;

    /// <summary>
    /// The observers farther away than the observer of one owner: those whose owner contains it (E8). One instance
    /// serves all objects of a subtree that the observer starts to observe at once.
    /// </summary>
    private sealed class FartherObservers(object owner)
    {
        private HashSet<object>? ancestors;

        internal bool Contains(object other)
        {
            if (ReferenceEquals(other, owner)) return false;
            if (ancestors is null)
            {
                ancestors = new(ReferenceEqualityComparer.Instance);
                for (object? current = ContainerOf(owner); current is not null; current = ContainerOf(current))
                {
                    ancestors.Add(current);
                }
            }
            return ancestors.Contains(other);
        }
    }
}
