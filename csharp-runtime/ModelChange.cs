using System.Diagnostics;

namespace Vmf.Runtime;

/// <summary>Identifies the kind of edit carried by a model change.</summary>
public enum ModelChangeKind
{
    Property,
    List,
    Set,
    Map,
    ReferenceSet,
}

/// <summary>An applied edit to a modeled property.</summary>
public abstract class ModelChange
{
    private static long lastTimestamp;

    internal ModelChange(object source, string propertyName, object? oldValue, object? newValue, bool isModelReference,
        ModelChangeKind kind = ModelChangeKind.Property, bool isContainment = false, bool isInverse = false,
        long? timestamp = null)
    {
        Source = source;
        PropertyName = propertyName;
        OldValue = oldValue;
        NewValue = newValue;
        IsModelReference = isModelReference;
        Kind = kind;
        IsContainment = isContainment;
        IsInverse = isInverse;
        Timestamp = timestamp ?? NextTimestamp();
    }

    public object Source { get; }
    public string PropertyName { get; }
    public object? OldValue { get; }
    public object? NewValue { get; }
    public bool IsModelReference { get; }
    public ModelChangeKind Kind { get; }
    public bool IsContainment { get; }
    public bool IsInverse { get; }

    /// <summary>A strictly increasing process-local timestamp in stopwatch ticks.</summary>
    public long Timestamp { get; }

    public bool CanUndo => IsCurrent(undo: true);
    public bool CanRedo => IsCurrent(undo: false);

    /// <summary>Restores the old value when the current value still matches this edit.</summary>
    public bool Undo() => Replay(undo: true);

    /// <summary>Restores the new value when the current value still matches the original old value.</summary>
    public bool Redo() => Replay(undo: false);

    /// <summary>Applies the recorded new value to the source, including ordinary change notifications.</summary>
    public void Apply() => Apply(Source);

    /// <summary>Applies the edit to a compatible object. Edits carrying model objects require their original owner.</summary>
    public abstract void Apply(object target);

    internal abstract bool IsCurrent(bool undo);
    internal abstract void SetValue(bool undo);

    internal bool RecordBeforeObservers => Kind is ModelChangeKind.Set or ModelChangeKind.Map
        || (Kind == ModelChangeKind.Property && !IsModelReference);

    private bool Replay(bool undo)
    {
        if (!IsCurrent(undo)) return false;
        SetValue(undo);
        return true;
    }

    private static long NextTimestamp()
    {
        long timestamp = Stopwatch.GetTimestamp();
        while (true)
        {
            long previous = Volatile.Read(ref lastTimestamp);
            long next = Math.Max(timestamp, previous + 1);
            if (Interlocked.CompareExchange(ref lastTimestamp, next, previous) == previous) return next;
        }
    }
}

internal sealed class ModelContainmentPropertyChange(object source, string propertyName, object? oldValue,
    object? newValue, Func<object?> get, Action<object?> set, bool isInverse)
    : ModelChange(source, propertyName, oldValue, newValue, isModelReference: true,
        isContainment: true, isInverse: isInverse)
{
    public override void Apply(object target)
    {
        if (!ReferenceEquals(target, Source))
        {
            throw new ArgumentException("Containment changes require their original source.", nameof(target));
        }
        set(NewValue);
    }

    internal override bool IsCurrent(bool undo) => ReferenceEquals(get(), undo ? NewValue : OldValue);
    internal override void SetValue(bool undo) => set(undo ? OldValue : NewValue);
}

internal sealed class ModelPropertyChange<TOwner, TValue>(
    TOwner owner, string propertyName, TValue oldValue, TValue newValue,
    Func<TOwner, TValue> get, Action<TOwner, TValue> set, bool isModelReference)
    : ModelChange(owner, propertyName, oldValue, newValue, isModelReference) where TOwner : class
{
    public override void Apply(object target)
    {
        ArgumentNullException.ThrowIfNull(target);
        if (IsModelReference && !ReferenceEquals(target, owner))
        {
            throw new ArgumentException("Model reference edits require their original source.", nameof(target));
        }
        if (target is not TOwner compatible)
        {
            throw new ArgumentException($"The edit requires a target compatible with '{typeof(TOwner)}'.", nameof(target));
        }
        set(compatible, newValue);
    }

    internal override bool IsCurrent(bool undo) =>
        ModelPropertyValues<TValue>.AreEqual(get(owner), undo ? newValue : oldValue, IsModelReference);

    internal override void SetValue(bool undo) => set(owner, undo ? oldValue : newValue);
}

internal static class ModelPropertyValues<TValue>
{
    internal static bool AreEqual(TValue left, TValue right, bool isModelReference) =>
        isModelReference ? ReferenceEquals(left, right) : ScalarEquality<TValue>.Instance.Equals(left, right);

    internal static bool AreEqualForAssignment(TValue left, TValue right, bool isModelReference)
    {
        if (typeof(TValue) == typeof(float)) return (float)(object)left! == (float)(object)right!;
        if (typeof(TValue) == typeof(double)) return (double)(object)left! == (double)(object)right!;
        return AreEqual(left, right, isModelReference);
    }
}
