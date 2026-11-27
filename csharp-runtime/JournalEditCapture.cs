using System.Collections;

namespace Vmf.Runtime;

internal sealed class JournalEditCapture(SnapshotRegistry registry, Func<object, bool> containsInstance,
    Func<ModelChange, IReadOnlyList<JournalOperation>> encodeOperations)
{
    private readonly Dictionary<ModelChange, CapturedOperations> committedEdits = new(ReferenceEqualityComparer.Instance);
    private readonly Dictionary<ModelChange, CapturedOperations> completedEdits = new(ReferenceEqualityComparer.Instance);
    private long nextOrder;
    private long encodingOrder;

    // Run at the mutation's commit point when observers can interleave with portable replay order.
    internal void Committed(ModelChange edit)
    {
        MaterializePendingIntroductions(edit);
        var captured = IntroducedValues(edit).Any()
            ? new CapturedOperations(nextOrder++, edit)
            : CaptureNow(edit);
        committedEdits.Add(edit, captured);
    }

    // Native recording confirms the edit and its transaction membership after notification.
    internal void NotificationsCompleted(ModelChange edit)
    {
        var captured = committedEdits.Remove(edit, out var committed) ? committed : CaptureAtCompletion(edit);
        captured.Materialize(Encode);
        completedEdits.Add(edit, captured);
    }

    internal void RequireNotificationsComplete()
    {
        if (committedEdits.Count != 0)
        {
            throw new InvalidOperationException("A journaled edit is still pending in native history.");
        }
    }

    internal JournalFrame PrepareFrame(long sequence, IEnumerable<ModelChange> edits)
    {
        var frame = new JournalFrame(sequence,
            edits.Select(edit => completedEdits.TryGetValue(edit, out var captured) ? captured
                : throw new InvalidOperationException("The journal did not capture a native edit."))
                .OrderBy(captured => captured.Order).SelectMany(captured => captured.Entries));
        var placed = JournalCreationPlacement.Order(frame.Operations);
        return ReferenceEquals(placed, frame.Operations) ? frame : new JournalFrame(sequence, placed);
    }

    internal void Clear()
    {
        committedEdits.Clear();
        completedEdits.Clear();
    }

    private CapturedOperations CaptureAtCompletion(ModelChange edit)
    {
        MaterializePendingIntroductions(edit);
        return CaptureNow(edit);
    }

    private CapturedOperations CaptureNow(ModelChange edit)
    {
        long order = nextOrder++;
        return new(order, Encode(edit, order));
    }

    private IReadOnlyList<JournalOperation> Encode(ModelChange edit, long order)
    {
        long previous = encodingOrder;
        encodingOrder = order;
        try { return encodeOperations(edit); }
        finally { encodingOrder = previous; }
    }

    private void MaterializePendingIntroductions(ModelChange next)
    {
        if (committedEdits.Count == 0) return;
        foreach (var prepared in committedEdits.Values.OrderBy(prepared => prepared.Order))
        {
            if (prepared.Deferred is not { } deferred) continue;
            if (ReferenceEquals(deferred.Source, next.Source) && deferred.PropertyName == next.PropertyName
                || IsUnknownModel(next.Source) && IntroducesObject(deferred, next.Source))
            {
                prepared.Materialize(Encode);
            }
        }
    }

    private IEnumerable<object> IntroducedValues(ModelChange change)
    {
        if (!change.IsModelReference) yield break;
        if (change is ModelCollectionChange collection)
        {
            foreach (var entry in collection.Added)
            {
                if (entry.Value is { } value && IsUnknownModel(value)) yield return value;
            }
        }
        else
        {
            if (change.NewValue is { } added && IsUnknownModel(added)) yield return added;
        }
    }

    private bool IntroducesObject(ModelChange deferred, object required)
    {
        var visited = new HashSet<object>(ReferenceEqualityComparer.Instance);
        var pending = new Queue<object>(IntroducedValues(deferred));
        while (pending.TryDequeue(out object? model))
        {
            if (!visited.Add(model)) continue;
            if (ReferenceEquals(model, required)) return true;
            foreach (var property in registry.TypeForClass(model.GetType(), "$").Properties)
            {
                if (!property.IsReference && !property.IsContainment) continue;
                object? value = property.Get(model);
                if (value is null) continue;
                if (property.IsCollection)
                {
                    foreach (object? entry in (IEnumerable)value)
                    {
                        if (entry is not null && IsUnknownModel(entry)) pending.Enqueue(entry);
                    }
                }
                else if (IsUnknownModel(value)) pending.Enqueue(value);
            }
        }
        return false;
    }

    private bool IsUnknownModel(object value) => !containsInstance(value);

    internal bool ReservedByEarlierIntroduction(object value) =>
        committedEdits.Values.Any(prepared => prepared.Order < encodingOrder && prepared.Deferred is { } deferred
            && IntroducesObject(deferred, value));

    private sealed class CapturedOperations(long order, IReadOnlyList<JournalOperation> entries)
    {
        internal CapturedOperations(long order, ModelChange deferred) : this(order, Array.Empty<JournalOperation>()) =>
            Deferred = deferred;

        internal long Order { get; } = order;
        internal IReadOnlyList<JournalOperation> Entries { get; private set; } = entries;
        internal ModelChange? Deferred { get; private set; }

        internal void Materialize(Func<ModelChange, long, IReadOnlyList<JournalOperation>> encode)
        {
            if (Deferred is null) return;
            Entries = encode(Deferred, Order);
            Deferred = null;
        }
    }

}
