using System.Collections;

namespace Vmf.Runtime;

/// <summary>Records portable changes and verifies each published frame on a private copy of the checkpoint.</summary>
public sealed class JournalRecorder<T> : IDisposable where T : class
{
    private readonly T working;
    private readonly T shadow;
    private readonly JournalBinding binding;
    private readonly SnapshotRegistry registry;
    private readonly JournalWorkspace workingObjects;
    private readonly JournalWorkspace shadowObjects;
    private readonly ModelChanges changes;
    private readonly IDisposable publication;
    private readonly IDisposable capture;
    private readonly string root;
    private readonly string checkpoint;
    private readonly long baseSequence;
    private readonly List<JournalFrame> frames = new();
    private readonly JournalEditCapture editCapture;
    private Exception? captureFailure;
    private int consumed;
    private int publications;
    private int verifiedPublications;
    private bool publishingFrame;
    private bool closed;

    private JournalRecorder(T working, SnapshotRegistry registry, JournalBinding binding, string checkpoint, long baseSequence)
    {
        this.working = working ?? throw new ArgumentNullException(nameof(working));
        this.binding = binding ?? throw new ArgumentNullException(nameof(binding));
        this.registry = registry ?? throw new ArgumentNullException(nameof(registry));
        this.checkpoint = ChangeJournal.CheckName(checkpoint);
        ArgumentOutOfRangeException.ThrowIfNegative(baseSequence);
        this.baseSequence = baseSequence;
        changes = ModelChanges.For(working);
        if (changes.IsRecording || changes.IsModelVersioningEnabled)
        {
            throw new InvalidOperationException("The model already has an active recording or versioning session.");
        }
        root = binding.Id(working);

        workingObjects = new JournalWorkspace(working, binding, registry);
        shadow = workingObjects.CopyCheckpoint(working);
        shadowObjects = new JournalWorkspace(shadow, binding, registry);
        shadowObjects.ValidateCheckpointCopy(workingObjects, shadow, root, verifyState: true);

        editCapture = new JournalEditCapture(registry, workingObjects.ContainsInstance, ToOperations);
        changes.Start();
        changes.CaptureBeforeObservers = OnCommitted;
        capture = changes.History.AddListener(OnHistory);
        publication = changes.Transactions.AddListener(OnTransaction);
    }

    /// <summary>Starts an exclusive recording when the working model has no active recording or versioning session.</summary>
    public static JournalRecorder<T> Start(T working, SnapshotRegistry registry, JournalBinding binding,
        string checkpoint, long baseSequence) => new(working, registry, binding, checkpoint, baseSequence);

    /// <summary>Executes one model edit and returns its verified single-frame journal.</summary>
    /// <remarks>The callback leaves native recording and transaction publication to this method.</remarks>
    public static ChangeJournal RecordFrame(T working, SnapshotRegistry registry, JournalBinding binding,
        string checkpoint, long baseSequence, Action<T> edit)
    {
        ArgumentNullException.ThrowIfNull(edit);
        using var recorder = Start(working, registry, binding, checkpoint, baseSequence);
        edit(working);
        recorder.CheckOwnership();
        if (recorder.changes.Transactions.Count != 0)
        {
            throw new InvalidOperationException("The edit published a native transaction.");
        }

        var journal = recorder.Finish();
        if (journal.Transactions.Count > 1)
        {
            throw new InvalidOperationException("The edit and publication produced more than one portable frame.");
        }
        return journal;
    }

    /// <summary>Publishes and verifies the changes in one pending frame.</summary>
    /// <remarks>Publication listeners may inspect the graph. Edits during publication invalidate the frame.</remarks>
    public JournalFrame? PublishFrame()
    {
        BeginPublication();
        try
        {
            CheckOwnership();
            if (publications != verifiedPublications)
            {
                throw new InvalidOperationException("Native recording changed outside PublishFrame.");
            }

            int start = consumed;
            int end = changes.History.Count;
            int previousFrames = frames.Count;
            int expectedPublications = verifiedPublications + (start == end ? 0 : 1);
            var transaction = changes.PublishTransaction();
            VerifyFramePublication(end, end - start, expectedPublications, transaction);
            VerifyShadow();
            VerifyFramePublication(end, end - start, expectedPublications, transaction);
            verifiedPublications = expectedPublications;
            return frames.Count == previousFrames ? null : frames[previousFrames];
        }
        catch (Exception failure)
        {
            try
            {
                CloseRecording();
            }
            catch (Exception closeFailure)
            {
                ModelObserverFailures.Add(failure, closeFailure);
            }
            throw;
        }
        finally
        {
            publishingFrame = false;
        }
    }

    /// <summary>Publishes pending work, stops native recording, and returns the verified journal.</summary>
    public ChangeJournal Finish()
    {
        BeginPublication();
        try
        {
            CheckOwnership();
            changes.PublishTransaction();
            CheckOwnership();
            var entries = new List<JournalFrame>();
            for (int index = consumed; index < changes.History.Count; index++)
            {
                Stage(entries, [changes.History[index]]);
            }
            VerifyShadow();
            frames.AddRange(entries);
            return new ChangeJournal(binding.Model, binding.Version, root, checkpoint, baseSequence, frames);
        }
        finally
        {
            try
            {
                CloseRecording();
            }
            finally
            {
                publishingFrame = false;
            }
        }
    }

    private void BeginPublication()
    {
        ObjectDisposedException.ThrowIf(closed, this);
        RequireIdlePublication();
        publishingFrame = true;
    }

    private void RequireIdlePublication()
    {
        if (publishingFrame)
        {
            throw new InvalidOperationException("A portable frame is being published.");
        }
    }

    private void VerifyFramePublication(int end, int groupSize, int expectedPublications, ModelTransaction? transaction)
    {
        CheckOwnership();
        if (changes.History.Count != end)
        {
            throw new InvalidOperationException("The model was edited during frame publication or verification.");
        }
        if (consumed != end || publications != expectedPublications || (transaction?.Changes.Count ?? 0) != groupSize)
        {
            throw new InvalidOperationException("Native publication did not match the pending frame.");
        }
    }

    private void OnTransaction(ModelHistoryChange<ModelTransaction> change)
    {
        if (captureFailure is not null) return;
        try
        {
            editCapture.RequireNotificationsComplete();
            if (change.Removed.Count != 0 || change.Added.Count != 1
                || publications + 1 != changes.Transactions.Count)
            {
                throw new InvalidOperationException("Native history changed during journal publication.");
            }

            var transaction = change.Added[0].Changes;
            var entries = new List<JournalFrame>();
            int index = consumed;
            while (index < changes.History.Count && !ReferenceEquals(changes.History[index], transaction[0]))
            {
                Stage(entries, [changes.History[index++]]);
            }
            foreach (var edit in transaction)
            {
                if (index == changes.History.Count || !ReferenceEquals(changes.History[index++], edit))
                {
                    throw new InvalidOperationException("The native transaction does not match the recorded changes.");
                }
            }
            Stage(entries, transaction);
            VerifyShadow();
            frames.AddRange(entries);
            consumed = index;
            publications++;
        }
        catch (Exception failure)
        {
            // Preserve the published native group. The portable recorder reports its failure.
            captureFailure = new InvalidOperationException("Published transaction state could not be reproduced.", failure);
        }
    }

    private void Stage(List<JournalFrame> entries, IEnumerable<ModelChange> edits)
    {
        var frame = editCapture.PrepareFrame(checked(baseSequence + frames.Count + entries.Count + 1), edits);
        shadowObjects.Apply(frame);
        shadowObjects.VerifyReachable(shadow);
        entries.Add(frame);
    }

    private void CheckOwnership()
    {
        if (captureFailure is not null)
        {
            throw new InvalidOperationException("The journal could not capture all native changes.", captureFailure);
        }
        editCapture.RequireNotificationsComplete();
        if (closed || !changes.IsRecording || changes.Transactions.Count != publications || changes.History.Count < consumed)
        {
            throw new InvalidOperationException("The journal recording session no longer owns the model history.");
        }
    }

    private void OnCommitted(ModelChange edit)
    {
        if (captureFailure is not null) return;
        try
        {
            editCapture.Committed(edit);
        }
        catch (Exception failure)
        {
            captureFailure = failure;
        }
    }

    private void OnHistory(ModelHistoryChange<ModelChange> change)
    {
        if (captureFailure is not null) return;
        try
        {
            if (change.Removed.Count != 0)
            {
                throw new InvalidOperationException("Native history was removed during journal recording.");
            }
            foreach (var edit in change.Added)
            {
                editCapture.NotificationsCompleted(edit);
            }
        }
        catch (Exception failure)
        {
            captureFailure = failure;
        }
    }

    private IReadOnlyList<JournalOperation> ToOperations(ModelChange change)
    {
        string id = binding.Id(change.Source);
        if (!workingObjects.Contains(id, change.Source))
        {
            throw new InvalidOperationException("This journal profile requires existing checkpoint targets.");
        }

        string key = binding.CheckedPropertyKey(change.Source, change.PropertyName);
        var created = new List<JournalCreation>();
        if (change.Kind == ModelChangeKind.Property)
        {
            return [new JournalSet(id, key, Freeze(change.OldValue, change.IsModelReference, created),
                Freeze(change.NewValue, change.IsModelReference, created), created)];
        }

        if (change is not ModelCollectionChange edit)
        {
            throw new InvalidOperationException("Unsupported native journal change.");
        }

        if (edit.Kind == ModelChangeKind.Map)
        {
            object keyTypeSample = edit.Removed.Concat(edit.Added).First().Key!;
            return [new JournalMapEdit(id, key, typedKeys: keyTypeSample is not string,
                edit.Removed.Select(MapEntry), edit.Added.Select(MapEntry))];
        }

        var before = edit.Removed.Select(entry => Freeze(entry.Value, edit.IsModelReference, created)).ToArray();
        var after = edit.Added.Select(entry => Freeze(entry.Value, edit.IsModelReference, created)).ToArray();
        if (edit.Kind == ModelChangeKind.Set || edit.Kind == ModelChangeKind.ReferenceSet
            && edit.Removed.Concat(edit.Added).All(entry => !entry.Index.HasValue))
        {
            return [new JournalMembershipEdit(id, key, edit.IsModelReference, before, after, created)];
        }

        int[] beforePositions = Positions(edit.Removed);
        int[] afterPositions = Positions(edit.Added);
        if (beforePositions.Length == 0)
        {
            return [new JournalListEdit(JournalListEditKind.Add, id, key, afterPositions, before, after, created)];
        }
        if (afterPositions.Length == 0)
        {
            return [new JournalListEdit(JournalListEditKind.Remove, id, key, beforePositions, before, after, created)];
        }
        if (!beforePositions.SequenceEqual(afterPositions))
        {
            return
            [
                new JournalListEdit(JournalListEditKind.Remove, id, key, beforePositions, before, []),
                new JournalListEdit(JournalListEditKind.Add, id, key, afterPositions, [], after, created),
            ];
        }
        return [new JournalListEdit(JournalListEditKind.Set, id, key, beforePositions, before, after, created)];
    }

    private JournalValue Freeze(object? value, bool reference, List<JournalCreation> created)
    {
        if (!reference || value is null)
        {
            return workingObjects.Encode(value, isReference: false);
        }

        string id = binding.Id(value);
        if (!workingObjects.ContainsInstance(value) && editCapture.ReservedByEarlierIntroduction(value))
        {
            // The later edit owns its snapshot, but the earlier edit still owns this definition.
            return registry.TypeForClass(value.GetType(), "$").IsImmutable
                ? JournalValue.ImmutableReference(id) : JournalValue.Reference(id);
        }
        if (workingObjects.Register(id, value))
        {
            var type = registry.TypeForClass(value.GetType(), "$");
            var fields = new Dictionary<string, IReadOnlyList<JournalValue>>(StringComparer.Ordinal);
            var maps = new Dictionary<string, IReadOnlyList<JournalMapEntry>>(StringComparer.Ordinal);
            var typedMaps = new Dictionary<string, IReadOnlyList<JournalMapEntry>>(StringComparer.Ordinal);
            var sets = new Dictionary<string, IReadOnlyList<JournalValue>>(StringComparer.Ordinal);
            foreach (var property in type.Properties)
            {
                string key = binding.CheckedPropertyKey(value, property.ModelName);
                object? member = property.Get(value);
                if (property.IsMap)
                {
                    if (member is not ISnapshotScalarMap map)
                    {
                        throw new InvalidOperationException($"Unsupported managed map in journal creation at '{id}.{key}'.");
                    }
                    var entries = map.Entries.Select(entry =>
                        new JournalMapEntry(JournalValue.FromScalar(entry.Key), JournalValue.FromScalar(entry.Value))).ToArray();
                    (property.KeyType == typeof(string) ? maps : typedMaps).Add(key, entries);
                    continue;
                }
                if (property.Kind == SnapshotPropertyKind.ScalarSet)
                {
                    sets.Add(key, ((IEnumerable)member!).Cast<object?>().Select(JournalValue.FromScalar).ToArray());
                    continue;
                }

                bool isModel = property.IsReference || property.IsContainment;
                var members = property.IsCollection
                    ? ((IEnumerable)member!).Cast<object?>().Select(item => Freeze(item, isModel, created)).ToArray()
                    : [Freeze(member, isModel, created)];
                fields.Add(key, members);
            }
            created.Add(new JournalCreation(id, binding.TypeName(value, type, registry), fields, maps, typedMaps, sets));
        }
        return workingObjects.Encode(value, isReference: true);
    }

    private static JournalMapEntry MapEntry(ModelCollectionEntry entry) =>
        new(JournalValue.FromScalar(entry.Key), JournalValue.FromScalar(entry.Value));

    private static int[] Positions(IEnumerable<ModelCollectionEntry> entries) =>
        entries.Select(entry => entry.Index ?? throw new InvalidOperationException("The journal edit requires positions.")).ToArray();

    private void VerifyShadow()
    {
        if (!JournalWorkspace.EquivalentGraphs(working, shadow, binding, registry))
        {
            throw new InvalidOperationException("The journal frame did not reproduce the working model.");
        }
    }

    public void Dispose()
    {
        RequireIdlePublication();
        CloseRecording();
    }

    private void CloseRecording()
    {
        if (closed)
        {
            return;
        }
        closed = true;
        if (changes.CaptureBeforeObservers == OnCommitted) changes.CaptureBeforeObservers = null;
        publication.Dispose();
        capture.Dispose();
        if (changes.IsRecording)
        {
            changes.StartTransaction();
            changes.Stop();
        }
        workingObjects.Dispose();
        shadowObjects.Dispose();
        editCapture.Clear();
    }
}
