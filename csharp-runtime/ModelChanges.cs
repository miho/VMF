using System.Runtime.CompilerServices;

namespace Vmf.Runtime;

/// <summary>Defines what a live model listener observes.</summary>
public enum ModelChangeScope
{
    Local,
    Recursive,
}

/// <summary>Provides synchronous listeners and optional in-memory recording for a mutable model object.</summary>
public sealed partial class ModelChanges
{
    private static readonly ConditionalWeakTable<object, ModelChanges> Streams = new();
    private static int hasStreams;
    private readonly ObserverGroup local;
    private readonly ObserverGroup recursive;
    private readonly ModelReflectionRegistry.ModelBinding? readOnlyBinding;
    private readonly Queue<PendingChange> pending = new();
    // The delivery stamp of each History entry, in the same order (H4).
    private readonly List<long> historyStamps = new();
    private int transactionStart;
    private int scalarRecordingRelayDepth;
    private long recordingGeneration;
    private bool appendingHistory;

    private ModelChanges(object owner, bool followContainment = true, ModelReflectionRegistry.ModelBinding? readOnlyBinding = null)
    {
        this.readOnlyBinding = readOnlyBinding;
        local = new(this, owner, isRecursive: false);
        recursive = new(this, owner, isRecursive: followContainment, recordingScope: true);
    }

    public bool IsRecording { get; private set; }
    public ModelHistory<ModelChange> History { get; } = new();
    public ModelHistory<ModelTransaction> Transactions { get; } = new();
    internal Action<ModelChange>? CaptureBeforeObservers { get; set; }

    /// <summary>Returns the change stream associated with the given model object identity.</summary>
    public static ModelChanges For(object owner)
    {
        ArgumentNullException.ThrowIfNull(owner);
        var stream = Streams.GetValue(owner, static model => new ModelChanges(model));
        Volatile.Write(ref hasStreams, 1);
        return stream;
    }

    public IDisposable AddListener(Action<ModelChange> listener, ModelChangeScope scope = ModelChangeScope.Recursive)
    {
        if (!Enum.IsDefined(scope)) throw new ArgumentOutOfRangeException(nameof(scope));
        return (scope == ModelChangeScope.Recursive ? recursive : local).AddListener(listener);
    }

    public IDisposable AddLocalListener(Action<ModelChange> listener) => AddListener(listener, ModelChangeScope.Local);
    public IDisposable AddRecursiveListener(Action<ModelChange> listener) => AddListener(listener, ModelChangeScope.Recursive);

    public IDisposable AddPropertyListener(string propertyName, Action<ModelChange> listener,
        ModelChangeScope scope = ModelChangeScope.Local)
    {
        ArgumentNullException.ThrowIfNull(propertyName);
        return AddFilteredListener(change => change.PropertyName == propertyName, listener, scope);
    }

    public IDisposable AddFilteredListener(Predicate<ModelChange> filter, Action<ModelChange> listener,
        ModelChangeScope scope = ModelChangeScope.Local)
    {
        ArgumentNullException.ThrowIfNull(filter);
        ArgumentNullException.ThrowIfNull(listener);
        return AddListener(change =>
        {
            if (filter(change)) listener(change);
        }, scope);
    }

    /// <summary>Clears previous history and begins recording edits in the containment tree.</summary>
    public void Start()
    {
        Clear();
        IsRecording = true;
        recursive.Refresh();
        EnableModelVersioning();
    }

    /// <summary>Starts a transaction at the current end of the recorded history.</summary>
    public void StartTransaction()
    {
        if (!IsRecording) throw new InvalidOperationException("Start recording before starting a transaction.");
        transactionStart = History.Count;
    }

    /// <summary>Publishes the pending group of recorded edits, if it has members.</summary>
    public ModelTransaction? PublishTransaction()
    {
        int end = History.Count;
        if (transactionStart >= end) return null;
        var transaction = new ModelTransaction(History.Skip(transactionStart).Take(end - transactionStart));
        transactionStart = end;
        ModelObserverFailures.Rethrow(Transactions.Append(transaction)?.Notify(null));
        return transaction;
    }

    /// <summary>Publishes the remaining group and stops recording. Live listeners stay subscribed.</summary>
    public void Stop()
    {
        try
        {
            PublishTransaction();
        }
        finally
        {
            transactionStart = History.Count;
            IsRecording = false;
            recordingGeneration++;
            pending.Clear();
            DisableModelVersioning();
            recursive.Refresh();
        }
    }

    /// <summary>Clears edit history, then published transactions, without changing the recording state.</summary>
    /// <remarks>An edit-history observer runs before transactions are cleared.</remarks>
    public void Clear()
    {
        recordingGeneration++;
        pending.Clear();
        transactionStart = 0;
        historyStamps.Clear();
        ModelObserverFailures.Rethrow(History.RemoveAll()?.Notify(null));
        ModelObserverFailures.Rethrow(Transactions.RemoveAll()?.Notify(null));
    }

    /// <summary>Returns additional observer errors collected during synchronous notification.</summary>
    public static IReadOnlyList<Exception> GetSuppressedObserverExceptions(Exception error)
    {
        ArgumentNullException.ThrowIfNull(error);
        return ModelObserverFailures.GetSuppressed(error);
    }

    /// <summary>Applies a generated scalar or reference setter and publishes the change to the live model.</summary>
    public static bool SetProperty<TOwner, TValue>(TOwner owner, ContainmentLink link, string modelName,
        ref TValue field, TValue value, Func<TOwner, TValue> get, Action<TOwner, TValue> set, bool isModelReference)
        where TOwner : class
    {
        TValue oldValue = field;
        link.RequireKeyChange(owner, modelName, value);
        if (ModelPropertyValues<TValue>.AreEqualForAssignment(oldValue, value, isModelReference)) return false;
        field = value;

        if (Volatile.Read(ref hasStreams) == 0
            || ModelPropertyValues<TValue>.AreEqual(oldValue, value, isModelReference)) return true;

        var groups = SnapshotGroups(owner);
        var direct = SnapshotDirectPropertyObservers(owner, modelName);
        if (groups.Length != 0 || direct.HasObservers)
        {
            var change = new ModelPropertyChange<TOwner, TValue>(owner, modelName, oldValue, value, get, set, isModelReference);
            var recorders = SnapshotRecorders(groups);
            if (direct.HasObservers) DeliverDirectProperty(groups, recorders, direct, change, null);
            else Deliver(groups, recorders, change, null);
        }
        return true;
    }

    internal static Action<ModelChange>? CaptureDelivery(object owner, IContainment? relationship = null)
    {
        if (Volatile.Read(ref hasStreams) == 0) return null;
        var groups = SnapshotGroups(owner);
        if (groups.Length == 0) return null;
        var recorders = SnapshotRecorders(groups);
        return change => Deliver(groups, recorders, change, relationship);
    }

    private static List<Recording>? SnapshotRecorders(ObservationRegistration[] groups)
    {
        List<Recording>? route = null;
        foreach (var registration in groups)
        {
            var stream = registration.Group.Stream;
            if (!stream.IsRecording || route?.Any(recording => ReferenceEquals(recording.Stream, stream)) == true) continue;
            (route ??= new()).Add(new(stream, stream.recordingGeneration));
        }
        return route;
    }

    private static void PrepareJournalEdit(List<Recording>? recorders, ModelChange change)
    {
        if (recorders is null || change.IsInverse) return;
        bool reference = change is { Kind: ModelChangeKind.Property, IsModelReference: true }
            and not ReciprocalReferenceChange;
        // Scalar, map and set commits can precede a nested scalar's recording relay.
        if (!change.RecordBeforeObservers && !reference
            && change is not ModelCollectionChange { RequiresEarlyCollectionCapture: true }) return;
        foreach (var recorder in recorders)
        {
            if (recorder.CanRecord) recorder.Stream.CaptureBeforeObservers?.Invoke(change);
        }
    }

    private static void Deliver(ObservationRegistration[] groups, List<Recording>? recorders, ModelChange change,
        IContainment? relationship)
    {
        PrepareJournalEdit(recorders, change);
        if ((recorders is not null || groups.Length > 1) && change is
            ({ Kind: ModelChangeKind.Property, IsInverse: false } and not ReciprocalReferenceChange
            or { Kind: ModelChangeKind.List or ModelChangeKind.Map or ModelChangeKind.Set,
                IsModelReference: false, IsInverse: false }))
        {
            DeliverAtRecordingRelay(groups, recorders, change, relationship);
            return;
        }

        // Preserve the native recording phases for collection and relationship edits.
        if (recorders is not null && change.RecordBeforeObservers && !change.IsInverse)
        {
            foreach (var recorder in recorders)
            {
                if (recorder.CanRecord) recorder.Pending = recorder.Stream.Enqueue(change, change);
            }
        }

        Exception? failure = null;
        HashSet<ModelChanges>? merged = null;
        foreach (var registration in groups)
        {
            failure = registration.Group.Notify(change, relationship, failure,
                DeliveryFor(groups, registration.Group, change, ref merged));
        }
        if (recorders is not null)
        {
            foreach (var recorder in recorders)
            {
                // List and reference edits record after delivering their live notification.
                if (!change.RecordBeforeObservers && !change.IsInverse && recorder.CanRecord)
                {
                    if (change is ModelCollectionChange { ReciprocalRemoval: { } removal } collection)
                    {
                        removal.Record(recorder.Stream, recorder.RecordingGeneration, collection);
                    }
                    var recorded = change is ReciprocalReferenceChange opposite
                        ? opposite.ForHistory(recorder.Stream, recorder.RecordingGeneration) : change;
                    if (recorded is not null) recorder.Pending = recorder.Stream.Enqueue(recorded, change);
                }
                if (recorder.Pending is not null) recorder.Pending.IsReady = true;
            }
            foreach (var recorder in recorders) failure = recorder.Stream.AppendReadyHistory(failure);
        }
        ModelObserverFailures.Rethrow(failure);
    }

    private static void DeliverAtRecordingRelay(ObservationRegistration[] groups, List<Recording>? recorders,
        ModelChange change, IContainment? relationship)
    {
        HashSet<ModelChanges>? delivered = groups.Length > 1 ? new(ReferenceEqualityComparer.Instance) : null;
        HashSet<ModelChanges>? merged = null;
        Exception? failure = null;
        foreach (var registration in groups)
        {
            failure = NotifyRecordingRelay(registration.Group, recorders, change, relationship, delivered, failure,
                delivery: DeliveryFor(groups, registration.Group, change, ref merged));
        }
        ModelObserverFailures.Rethrow(failure);
    }

    private static Exception? NotifyRecordingRelay(ObserverGroup group, List<Recording>? recorders,
        ModelChange change, IContainment? relationship, HashSet<ModelChanges>? delivered, Exception? failure,
        bool reconcileAfterRecording = false, ModelListeners<ModelChange>.Subscription[]? delivery = null)
    {
        var stream = group.Stream;
        // Native recording uses the state at the relay. A portable checkpoint only covers later commits.
        bool record = !change.IsInverse && delivered?.Add(stream) != false && stream.IsRecording
            && (stream.CaptureBeforeObservers is null || RecordingOwnsCommit(stream, recorders));
        long generation = stream.recordingGeneration;
        PendingChange? pending = null;
        if (record && change.RecordBeforeObservers)
        {
            pending = stream.Enqueue(change, change);
            stream.scalarRecordingRelayDepth++;
        }

        try
        {
            try
            {
                failure = reconcileAfterRecording ? group.NotifyListeners(change, failure, delivery)
                    : group.Notify(change, relationship, failure, delivery);
            }
            finally
            {
                if (pending is not null) stream.scalarRecordingRelayDepth--;
            }
            if (pending is null && record && stream.IsRecording && generation == stream.recordingGeneration)
            {
                // Ordinary references and positional scalar edits record after this relay's callbacks.
                if (change is ModelCollectionChange { ReciprocalRemoval: { } removal } collection)
                {
                    removal.Record(stream, generation, collection);
                }
                var recorded = change is ReciprocalReferenceChange opposite ? opposite.ForHistory(stream, generation) : change;
                if (recorded is not null) pending = stream.Enqueue(recorded, change);
            }
            if (pending is not null)
            {
                pending.IsReady = true;
                failure = stream.AppendReadyHistory(failure);
            }
            return failure;
        }
        finally
        {
            if (reconcileAfterRecording) group.ReconcileChange(change, relationship);
        }
    }

    private static bool RecordingOwnsCommit(ModelChanges stream, List<Recording>? recorders) =>
        recorders?.Exists(recorder => ReferenceEquals(recorder.Stream, stream) && recorder.CanRecord) == true;

    /// <summary>Queues a change for history; it is ordered by the stamp of the delivered change it records.</summary>
    private PendingChange Enqueue(ModelChange change, ModelChange delivered)
    {
        var entry = new PendingChange(change, delivered.EnsureDeliveryStamp());
        pending.Enqueue(entry);
        return entry;
    }

    /// <summary>
    /// Records a change in delivery order (portable contract H4): before the changes of the pending transaction
    /// that first reached an observer after it. Listeners made those while this change was being delivered.
    /// </summary>
    private ModelHistory<ModelChange>.Notification? InsertHistory(ModelChange change, long stamp)
    {
        int index = History.Count;
        while (index > transactionStart && historyStamps[index - 1] > stamp) index--;
        historyStamps.Insert(index, stamp);
        return History.Insert(index, change);
    }

    /// <summary>
    /// The listeners a group calls for a change (portable contract E7). When both groups of a stream observe the
    /// changed object itself, the first of them calls the listeners of both, in the order they were added, and
    /// the other one calls none. Null: the group calls its own listeners. <paramref name="merged"/> records the
    /// streams of one change that already delivered; it is created when a stream first merges.
    /// </summary>
    private static ModelListeners<ModelChange>.Subscription[]? DeliveryFor(ObservationRegistration[] groups,
        ObserverGroup group, ModelChange change, ref HashSet<ModelChanges>? merged)
    {
        if (!ReferenceEquals(group.Owner, change.Source)) return null;
        var peer = group.Peer;
        bool peerObserves = false;
        foreach (var registration in groups)
        {
            if (!ReferenceEquals(registration.Group, peer)) continue;
            peerObserves = true;
            break;
        }
        if (!peerObserves) return null;
        merged ??= new(ReferenceEqualityComparer.Instance);
        if (!merged.Add(group.Stream)) return [];
        return group.MergeListeners(peer);
    }

    private Exception? AppendReadyHistory(Exception? failure)
    {
        // A relay can clear its old queue and start a fresh generation from a callback.
        // New changes still wait for that notification to complete.
        if (scalarRecordingRelayDepth != 0 || appendingHistory
            || !pending.TryPeek(out var first) || !first.IsReady) return failure;
        appendingHistory = true;
        try
        {
            while (pending.TryPeek(out var entry) && entry.IsReady)
            {
                pending.Dequeue();
                var notification = InsertHistory(entry.Change, entry.Stamp);
                if (notification is not null) failure = notification.Notify(failure);
            }
            return failure;
        }
        finally
        {
            appendingHistory = false;
        }
    }

    private sealed class PendingChange(ModelChange change, long stamp)
    {
        internal ModelChange Change { get; } = change;
        internal long Stamp { get; } = stamp;
        internal bool IsReady { get; set; }
    }

    private sealed record Recording(ModelChanges Stream, long RecordingGeneration)
    {
        internal PendingChange? Pending { get; set; }
        internal bool CanRecord => Stream.IsRecording && RecordingGeneration == Stream.recordingGeneration;
    }
}
