namespace Vmf.Runtime;

/// <summary>Resolves a generated writable scalar set or map by its logical property name.</summary>
[System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
public interface IModelScalarCollectionReplayAccess
{
    object? VmfGetScalarCollection(string modelName, Type collectionType);
}

/// <summary>The owner and logical property name of an observed model collection.</summary>
public readonly struct ModelChangeSource
{
    private readonly object? owner;
    private readonly ContainmentLink? link;
    private readonly string? modelName;
    private readonly Func<object, object?>? bindTarget;
    private readonly Func<object?, object?>? resolveReadOnly;

    public ModelChangeSource(object owner, ContainmentLink link, string modelName)
        : this(owner, link, modelName, null)
    {
    }

    public ModelChangeSource(object owner, ContainmentLink link, string modelName, Func<object, object?>? bindTarget)
        : this(owner, link, modelName, bindTarget, null)
    {
    }

    public ModelChangeSource(object owner, ContainmentLink link, string modelName, Func<object, object?>? bindTarget,
        Func<object?, object?>? resolveReadOnly)
    {
        ArgumentNullException.ThrowIfNull(owner);
        ArgumentNullException.ThrowIfNull(link);
        ArgumentException.ThrowIfNullOrWhiteSpace(modelName);
        this.owner = owner;
        this.link = link;
        this.modelName = modelName;
        this.bindTarget = bindTarget;
        this.resolveReadOnly = resolveReadOnly;
    }

    internal bool IsTracked => owner is not null;
    internal object Owner => owner!;
    internal string Name => modelName!;
    internal void RequireKeyChange(object? value) => link!.RequireKeyChange(owner!, modelName!, value);

    internal Action<ModelChange>? CaptureDelivery(IContainment? relationship = null) =>
        owner is null ? null : ModelChanges.CaptureDelivery(owner, relationship);
    internal Action<ModelChange>? CapturePropertyDelivery(IContainment? relationship = null) =>
        owner is null ? null : ModelChanges.CapturePropertyDelivery(owner, modelName!, relationship);
    internal bool IsOwner(object target) => ReferenceEquals(target, owner);

    internal Func<object?, object?>? ReadOnlyAliasResolver => resolveReadOnly;

    internal object? ResolveReadOnlyAlias(object? value) => ResolveReadOnlyAlias(value, resolveReadOnly);

    internal static object? ResolveReadOnlyAlias(object? value, Func<object?, object?>? resolver)
    {
        if (value is not IReadOnlyModelView || resolver is null) return null;
        var model = resolver(value);
        return ReferenceEquals(model, value) ? null : model;
    }

    internal bool TryApplyNullListRange(object target, int index, int removalCount, int additionCount) =>
        target is IModelListReplayAccess access && access.VmfApplyNullListEdit(modelName!,
            new(receiver => receiver.ApplyNullRange(index, removalCount, additionCount)));

    internal bool TryApplyNullListRemoval(object target, int[] positions) =>
        target is IModelListReplayAccess access && access.VmfApplyNullListEdit(modelName!,
            new(receiver => receiver.RemoveListSlots(positions)));

    internal TCollection ResolveTarget<TCollection>(object target) where TCollection : class =>
        bindTarget?.Invoke(target) as TCollection
            ?? (target as IModelScalarCollectionReplayAccess)?.VmfGetScalarCollection(modelName!, typeof(TCollection)) as TCollection
            ?? throw new ArgumentException($"Target has no compatible collection '{modelName}'.", nameof(target));

    internal ModelCollectionChange Create(ModelChangeKind kind, bool isModelReference,
        IEnumerable<ModelCollectionEntry> removed, IEnumerable<ModelCollectionEntry> added,
        Func<bool, bool> matches, Action<bool> replay, Action<object>? apply = null, bool isContainment = false,
        ReciprocalEdit reciprocal = default, bool isOrdinaryCollection = false, bool isKeyedContainment = false,
        bool isTransferRemoval = false, bool allowCompatibleNulls = false) =>
        new(owner!, modelName!, kind, isModelReference, removed, added, matches, replay, apply, isContainment, reciprocal,
            isOrdinaryCollection, isKeyedContainment, isTransferRemoval, allowCompatibleNulls);

    internal ModelContainmentPropertyChange CreateContainment(object? oldValue, object? newValue,
        Func<object?> get, Action<object?> set) =>
        new(owner!, modelName!, oldValue, newValue, get, set, isInverse: false);
}

/// <summary>A member in a collection change. Index identifies a list slot and Key identifies a map entry.</summary>
public sealed record ModelCollectionEntry(object? Value, int? Index = null, object? Key = null);

/// <summary>An applied collection edit with its removed and added slots, members, or entries.</summary>
public sealed class ModelCollectionChange : ModelChange
{
    private readonly Func<bool, bool> matches;
    private readonly Action<bool> replay;
    private readonly Action<object>? apply;
    private readonly bool allowCompatibleNulls;

    internal ModelCollectionChange(object source, string propertyName, ModelChangeKind kind, bool isModelReference,
        IEnumerable<ModelCollectionEntry> removed, IEnumerable<ModelCollectionEntry> added,
        Func<bool, bool> matches, Action<bool> replay, Action<object>? apply, bool isContainment, ReciprocalEdit reciprocal,
        bool isOrdinaryCollection, bool isKeyedContainment, bool isTransferRemoval, bool allowCompatibleNulls)
        : base(source, propertyName, null, null, isModelReference, kind, isContainment, reciprocal.IsInverse)
    {
        Removed = Array.AsReadOnly(removed.ToArray());
        Added = Array.AsReadOnly(added.ToArray());
        this.matches = matches;
        this.replay = replay;
        this.apply = apply;
        this.allowCompatibleNulls = allowCompatibleNulls;
        ReciprocalRemoval = reciprocal.Removal;
        RequiresEarlyCollectionCapture = !reciprocal.IsInverse && (isTransferRemoval || kind switch
        {
            ModelChangeKind.List when isContainment => isKeyedContainment || isOrdinaryCollection && (Added.Count != 0 || Removed.Count != 0)
                || Added.Count != 0 && Removed.Count != 0 && Removed.Count != Added.Count,
            ModelChangeKind.List => !isModelReference || isOrdinaryCollection,
            ModelChangeKind.ReferenceSet => isOrdinaryCollection || isKeyedContainment
                || isContainment && Added.Concat(Removed).All(entry => entry.Index is null),
            _ => false,
        });
    }

    public IReadOnlyList<ModelCollectionEntry> Removed { get; }
    public IReadOnlyList<ModelCollectionEntry> Added { get; }
    internal ReciprocalRemoval? ReciprocalRemoval { get; }
    internal bool RequiresEarlyCollectionCapture { get; }
    internal bool CanApplyToExplicitTarget => apply is not null && (!IsModelReference || allowCompatibleNulls);

    /// <summary>Applies scalar edits and ordinary list edits containing only null slots to a compatible collection.
    /// Edits carrying model objects require their original owner.</summary>
    public override void Apply(object target)
    {
        ArgumentNullException.ThrowIfNull(target);
        if (IsModelReference && !ReferenceEquals(target, Source)
            && (!allowCompatibleNulls || Removed.Any(entry => entry.Value is not null) || Added.Any(entry => entry.Value is not null)))
        {
            throw new ArgumentException("Reference collection edits require their original source.", nameof(target));
        }
        if (apply is not null)
        {
            apply(target);
            return;
        }
        if (!Redo())
        {
            throw new InvalidOperationException("The collection no longer matches the recorded edit.");
        }
    }

    internal override bool IsCurrent(bool undo) => matches(undo);
    internal override void SetValue(bool undo) => replay(undo);
}
