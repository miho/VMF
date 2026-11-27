namespace Vmf.Runtime;

/// <summary>Stores a single non-owning model reference with a reciprocal endpoint.</summary>
public sealed class ReciprocalReference<T> where T : class
{
    public ReciprocalReference(ModelChangeSource source, Func<T, ReciprocalEndpoint> opposite)
    {
        ArgumentNullException.ThrowIfNull(opposite);
        Endpoint = new(source, target => opposite((T)target));
    }

    public ReciprocalEndpoint Endpoint { get; }

    public T? Value
    {
        get => (T?)Endpoint.Value;
        set => Endpoint.Assign(value);
    }
}

/// <summary>Identifies the opposite end of a generated reciprocal model reference.</summary>
public sealed class ReciprocalEndpoint
{
    private readonly ModelChangeSource source;
    private readonly Func<object, ReciprocalEndpoint> opposite;
    private readonly ReciprocalCollection? collection;
    private bool updating;

    internal ReciprocalEndpoint(ModelChangeSource source, Func<object, ReciprocalEndpoint> opposite,
        ReciprocalCollection? collection = null)
    {
        if (!source.IsTracked) throw new ArgumentException("A reciprocal reference requires an owner.", nameof(source));
        this.source = source;
        this.opposite = opposite;
        this.collection = collection;
    }

    internal object Owner => source.Owner;
    internal string Name => source.Name;
    internal object? Value { get; private set; }
    internal bool IsCollection => collection is not null;
    internal bool IsOrdered => collection?.RestoreSequence is not null;
    internal bool IsUnique => collection?.Unique ?? true;
    internal IEnumerable<object?> Values => collection?.Values() ?? (Value is null ? [] : [Value]);
    internal ReciprocalEndpoint Opposite(object target) => opposite(target);

    internal void Assign(object? value)
    {
        source.RequireKeyChange(value);
        if (ReferenceEquals(Value, value)) return;
        RequireAvailable();

        object? oldValue = Value;
        var next = value is null ? null : opposite(value);
        var previous = oldValue is null ? null : opposite(oldValue);
        if ((next ?? previous)?.collection is not null) AssignToCollection(oldValue, value, previous, next);
        else AssignToScalar(oldValue, value, previous, next);
    }

    private void AssignToScalar(object? oldValue, object? value, ReciprocalEndpoint? previous, ReciprocalEndpoint? next)
    {
        next?.RequireAvailable();
        var displaced = next?.Value is { } oldOwner ? next.opposite(oldOwner) : null;
        if (displaced == this) displaced = null;
        displaced?.RequireAvailable();
        displaced?.source.RequireKeyChange(null);
        if (previous is not null && previous != this && ReferenceEquals(previous.Value, Owner))
        {
            previous.source.RequireKeyChange(null);
        }
        next?.source.RequireKeyChange(Owner);

        Exception? failure = null;
        updating = true;
        if (next is not null) next.updating = true;
        object? oldInverse = null;
        try
        {
            if (displaced is not null)
            {
                displaced.Value = null;
                displaced.Publish(value, null, isInverse: false, null, ref failure);
            }
            Value = value;
            if (previous is not null && ReferenceEquals(previous.Value, Owner))
            {
                previous.Value = null;
                previous.Publish(Owner, null, isInverse: true, null, ref failure);
            }
            Publish(oldValue, value, isInverse: false, null, ref failure);
            if (next is not null)
            {
                oldInverse = next.Value;
                next.Value = Owner;
            }
        }
        finally
        {
            updating = false;
            if (next is not null) next.updating = false;
        }
        if (next is not null && !ReferenceEquals(oldInverse, Owner))
        {
            next.Publish(oldInverse, Owner, isInverse: true, null, ref failure);
        }
        ModelObserverFailures.Rethrow(failure);
    }

    private void AssignToCollection(object? oldValue, object? value, ReciprocalEndpoint? previous, ReciprocalEndpoint? next)
    {
        var removal = previous?.collection is not null ? new ReciprocalRemoval(this) : null;
        Exception? failure = null;
        updating = true;
        try
        {
            if (removal is not null)
            {
                Attempt(() => previous!.collection!.Remove(Owner, false, ReciprocalEdit.TransferRemoval(removal)), ref failure);
            }
            bool cleared = oldValue is not null && Value is null;
            Value = value;
            Publish(oldValue, value, isInverse: false, cleared ? removal : null, ref failure);
        }
        finally
        {
            updating = false;
            removal?.Clear();
        }
        if (next?.collection is { } target && target.Count(Owner) == 0)
        {
            Attempt(() => target.Add(Owner, ReciprocalEdit.Inverse), ref failure);
        }
        ModelObserverFailures.Rethrow(failure);
    }

    internal void PrepareCollectionEdit<T>(IEnumerable<T> removed, IEnumerable<T> added, ReciprocalEdit edit)
    {
        if (edit.IsInverse) return;
        var deltas = CollectionDeltas(removed, added);
        foreach (var (target, delta) in deltas)
        {
            if (delta == 0) continue;
            var destination = opposite(target);
            if (destination.collection is not null) continue;
            if (edit.Removal?.Scalar == destination) continue;
            destination.RequireAvailable();
            if (delta > 0 && !ReferenceEquals(destination.Value, Owner))
            {
                destination.source.RequireKeyChange(Owner);
            }
            else if (delta < 0 && ReferenceEquals(destination.Value, Owner) && collection!.Count(target) == -delta)
            {
                destination.source.RequireKeyChange(null);
            }
        }
    }

    internal void UpdateCollectionOpposites<T>(IReadOnlyList<T> removed, IReadOnlyList<T> added, ref Exception? failure)
    {
        var deltas = CollectionDeltas(removed, added);
        if (collection is { Unique: false } && (removed.Count == 0 || added.Count == 0)
            && deltas.Values.Any(static delta => delta is < -1 or > 1)
            && deltas.Keys.All(target => opposite(target).collection is { Unique: false }))
        {
            // Ordinary opposite lists observe one mirror edit per occurrence, in source order.
            var occurrences = added.Count == 0 ? removed : added;
            foreach (var target in occurrences)
            {
                if (target is not null) Synchronize(target, added.Count != 0, ref failure, maximumSteps: 1);
            }
        }
        // Recheck the live counts after inverse observers have had a chance to edit the graph.
        foreach (var (target, delta) in deltas)
        {
            if (delta > 0) Synchronize(target, added: true, ref failure);
        }
        foreach (var (target, delta) in deltas)
        {
            if (delta < 0) Synchronize(target, added: false, ref failure);
        }
    }

    // A singular opposite can displace a prior owner. Its explicit removal must
    // precede adoption on the portable tape.
    internal bool HasCollectionOpposites<T>(IReadOnlyList<T> targets)
    {
        for (int index = 0; index < targets.Count; index++)
        {
            if (targets[index] is { } target && opposite(target).collection is null) return false;
        }
        return true;
    }

    private void Synchronize(object target, bool added, ref Exception? failure, int maximumSteps = int.MaxValue)
    {
        var destination = opposite(target);
        int count = collection!.Count(target);
        if (destination == this) return;
        if (destination.collection is { } many)
        {
            int current = many.Count(Owner);
            if (collection.Unique || many.Unique)
            {
                if (count == 0 && current != 0) Attempt(() => many.Remove(Owner, true, ReciprocalEdit.Inverse), ref failure);
                else if (count != 0 && current == 0) Attempt(() => many.Add(Owner, ReciprocalEdit.Inverse), ref failure);
            }
            else
            {
                int budget = Math.Min(Math.Abs(count - current), maximumSteps);
                for (int i = 0; i < budget; i++)
                {
                    count = collection.Count(target);
                    current = many.Count(Owner);
                    if (count == current) break;
                    if (count > current) Attempt(() => many.Add(Owner, ReciprocalEdit.Inverse), ref failure);
                    else Attempt(() => many.Remove(Owner, false, ReciprocalEdit.Inverse), ref failure);
                }
            }
            return;
        }

        object? old = destination.Value;
        if (!added && count == 0 && ReferenceEquals(old, Owner))
        {
            destination.Value = null;
            destination.Publish(old, null, isInverse: true, null, ref failure);
        }
        else if (added && count != 0)
        {
            if (ReferenceEquals(old, Owner))
            {
                RemoveDuplicates(target, destination, ref failure);
                return;
            }
            destination.Value = Owner;
            if (old is not null)
            {
                var previous = destination.opposite(old).collection!;
                Attempt(() => previous.Remove(target, true, ReciprocalEdit.TransferRemoval()), ref failure);
            }
            destination.Publish(old, Owner, isInverse: true, null, ref failure);
            RemoveDuplicates(target, destination, ref failure);
        }
    }

    private void RemoveDuplicates(object target, ReciprocalEndpoint destination, ref Exception? failure)
    {
        var entries = collection!;
        int count = entries.Count(target);
        for (int i = 1; i < count && entries.Count(target) > 1 && ReferenceEquals(destination.Value, Owner); i++)
        {
            Attempt(() => entries.Remove(target, false, ReciprocalEdit.Inverse), ref failure);
        }
    }

    internal void RestoreSnapshotSequence(IReadOnlyList<object?> values)
    {
        var restore = collection?.RestoreSequence ?? throw new InvalidOperationException("The opposite is not an ordered collection.");
        restore(values);
    }

    private void RequireAvailable()
    {
        if (updating) throw new ArgumentException($"A reciprocal reference update for '{Name}' is already in progress.");
    }

    private static Dictionary<object, int> CollectionDeltas<T>(IEnumerable<T> removed, IEnumerable<T> added)
    {
        var deltas = new Dictionary<object, int>(ReferenceEqualityComparer.Instance);
        foreach (var item in added)
        {
            if (item is not null) deltas[item] = deltas.GetValueOrDefault(item) + 1;
        }
        foreach (var item in removed)
        {
            if (item is not null) deltas[item] = deltas.GetValueOrDefault(item) - 1;
        }
        return deltas;
    }

    private void Publish(object? oldValue, object? newValue, bool isInverse, ReciprocalRemoval? removal, ref Exception? failure)
    {
        var deliver = source.CapturePropertyDelivery();
        if (deliver is null) return;
        var change = new ReciprocalReferenceChange(this, oldValue, newValue, isInverse, removal);
        Attempt(() => deliver(change), ref failure);
    }

    internal static void Attempt(Action operation, ref Exception? failure)
    {
        try { operation(); }
        catch (Exception error) { failure = ModelObserverFailures.Add(failure, error); }
    }
}
