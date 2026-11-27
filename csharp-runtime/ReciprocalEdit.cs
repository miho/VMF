namespace Vmf.Runtime;

internal sealed record ReciprocalCollection(bool Unique, Func<IEnumerable<object?>> Values, Func<object, int> Count,
    Action<object, ReciprocalEdit> Add, Action<object, bool, ReciprocalEdit> Remove,
    Action<IReadOnlyList<object?>>? RestoreSequence = null);

internal static class ReciprocalCollections
{
    internal static ReciprocalEndpoint ForSequence<T>(ModelChangeSource source, ModelSequence<T> sequence,
        Func<T, ReciprocalEndpoint> opposite, bool unique) where T : class?
    {
        ArgumentNullException.ThrowIfNull(opposite);
        var items = sequence.Items;
        return new(source, target => opposite((T)target), new(unique, () => items,
            target => items.Count(item => ReferenceEquals(item, target)),
            (value, edit) => sequence.ApplyRange(items.Count, 0, [(T)value], edit),
            (value, all, edit) => Remove(sequence, value, all, edit),
            values =>
            {
                var restored = values.Select(static value => (T)value!).ToArray();
                if (unique && (restored.Any(static item => item is null)
                    || restored.Distinct(ReferenceEqualityComparer.Instance).Count() != restored.Length))
                {
                    throw new InvalidOperationException("The snapshot contains null or duplicate reference members.");
                }
                items.Clear();
                items.AddRange(restored);
                sequence.MarkCursorVersion();
            }));
    }

    private static void Remove<T>(ModelSequence<T> sequence, object target, bool all, ReciprocalEdit edit)
    {
        var items = sequence.Items;
        if (!all)
        {
            int index = items.FindIndex(item => ReferenceEquals(item, target));
            if (index >= 0) sequence.ApplyRange(index, 1, [], edit);
            return;
        }
        if (edit.IsInverse)
        {
            sequence.RemoveOppositeOccurrences(target);
            return;
        }
        Exception? failure = null;
        for (int index = items.Count - 1; index >= 0; index--)
        {
            if (index >= items.Count || !ReferenceEquals(items[index], target)) continue;
            int current = index;
            ReciprocalEndpoint.Attempt(() => sequence.ApplyRange(current, 1, [], edit), ref failure);
        }
        ModelObserverFailures.Rethrow(failure);
    }
}

internal readonly record struct ReciprocalEdit(bool IsInverse, ReciprocalRemoval? Removal = null,
    bool IsTransferRemoval = false)
{
    internal static ReciprocalEdit Inverse { get; } = new(true);

    internal static ReciprocalEdit TransferRemoval(ReciprocalRemoval? removal = null) =>
        new(false, removal, IsTransferRemoval: true);
}

internal sealed class ReciprocalRemoval(ReciprocalEndpoint scalar)
{
    private readonly Dictionary<ModelChanges, (long Generation, ModelCollectionChange Change)> recordings =
        new(ReferenceEqualityComparer.Instance);

    internal ReciprocalEndpoint Scalar { get; } = scalar;
    internal void Record(ModelChanges stream, long generation, ModelCollectionChange change) =>
        recordings[stream] = (generation, change);

    internal ModelCollectionChange? RecordedChange(ModelChanges stream, long generation) =>
        recordings.TryGetValue(stream, out var recorded) && recorded.Generation == generation ? recorded.Change : null;

    internal void Clear() => recordings.Clear();
}

internal sealed class ReciprocalReferenceChange(ReciprocalEndpoint endpoint, object? oldValue, object? newValue,
    bool isInverse, ReciprocalRemoval? removal, long? timestamp = null,
    ModelCollectionChange? precedingRemoval = null, object? initialEndpoint = null)
    : ModelChange(endpoint.Owner, endpoint.Name, oldValue, newValue, isModelReference: true, isInverse: isInverse,
        timestamp: timestamp)
{
    /// <summary>
    /// A cleared reference whose opposite is a collection is already reported by the removal from that collection;
    /// this follow-up change only feeds recording.
    /// </summary>
    internal bool RecordingOnly => removal is not null && NewValue is null;

    internal ModelChange? ForHistory(ModelChanges stream, long generation)
    {
        if (removal?.RecordedChange(stream, generation) is not { } prior) return this;
        return NewValue is null ? null : new ReciprocalReferenceChange(endpoint, null, NewValue, false, null,
            Timestamp, prior, OldValue);
    }

    internal bool IsCurrentAfterPrecedingRemoval(IReadOnlyList<ModelChange> transaction) =>
        precedingRemoval is not null && ReferenceEquals(endpoint.Value, initialEndpoint)
            && transaction.Contains(precedingRemoval);

    public override void Apply(object target)
    {
        if (!ReferenceEquals(target, Source))
        {
            throw new ArgumentException("Model reference edits require their original source.", nameof(target));
        }
        endpoint.Assign(NewValue);
    }

    internal override bool IsCurrent(bool undo) => ReferenceEquals(endpoint.Value, undo ? NewValue : OldValue);
    internal override void SetValue(bool undo) => endpoint.Assign(undo ? OldValue : NewValue);
}
