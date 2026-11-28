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
        owner is null ? null : ModelChanges.CaptureDelivery(owner, link!, relationship);
    internal Action<ModelChange>? CapturePropertyDelivery(IContainment? relationship = null) =>
        owner is null ? null : ModelChanges.CapturePropertyDelivery(owner, link!, modelName!, relationship);
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

    internal ModelCollectionChange Create(ModelChangeKind kind, bool isModelReference, ModelCollectionEdit edit,
        bool isContainment = false, ReciprocalEdit reciprocal = default, bool isOrdinaryCollection = false,
        bool isKeyedContainment = false, bool isTransferRemoval = false, bool allowCompatibleNulls = false) =>
        new(owner!, modelName!, kind, isModelReference, edit, isContainment, reciprocal,
            isOrdinaryCollection, isKeyedContainment, isTransferRemoval, allowCompatibleNulls);

    internal ModelContainmentPropertyChange CreateContainment(object? oldValue, object? newValue,
        Func<object?> get, Action<object?> set) =>
        new(owner!, modelName!, oldValue, newValue, get, set, isInverse: false);
}

/// <summary>A member in a collection change. Index identifies a list slot and Key identifies a map entry.</summary>
public sealed record ModelCollectionEntry(object? Value, int? Index = null, object? Key = null);

/// <summary>
/// What an edit of one range of a list removed and added, and how to check, replay and apply it. A change keeps
/// this one object in place of two entry lists and three delegates, and builds its entries when they are read.
/// </summary>
internal abstract class ModelCollectionEdit
{
    internal abstract int RemovedCount { get; }
    internal abstract int AddedCount { get; }
    internal abstract ModelCollectionEntry[] RemovedEntries();
    internal abstract ModelCollectionEntry[] AddedEntries();

    /// <summary>The removed and the added values of a list of objects, else null.</summary>
    internal virtual object?[]? RemovedObjects => null;
    internal virtual object?[]? AddedObjects => null;

    internal abstract bool Matches(bool undo);
    internal abstract void Replay(bool undo);
    internal abstract void Apply(object target);

    /// <summary>The entries of values that follow one another from an index on.</summary>
    internal static ModelCollectionEntry[] Entries<T>(int index, T[] values)
    {
        if (values.Length == 0) return [];
        var entries = new ModelCollectionEntry[values.Length];
        for (int offset = 0; offset < values.Length; offset++) entries[offset] = new(values[offset], Index: index + offset);
        return entries;
    }
}

/// <summary>An applied collection edit with its removed and added slots, members, or entries.</summary>
public sealed class ModelCollectionChange : ModelChange
{
    private readonly Func<bool, bool>? matches;
    private readonly Action<bool>? replay;
    private readonly Action<object>? apply;
    private readonly ModelCollectionEdit? edit;
    private readonly bool allowCompatibleNulls;
    private IReadOnlyList<ModelCollectionEntry>? removed;
    private IReadOnlyList<ModelCollectionEntry>? added;

    internal ModelCollectionChange(object source, string propertyName, ModelChangeKind kind, bool isModelReference,
        IEnumerable<ModelCollectionEntry> removed, IEnumerable<ModelCollectionEntry> added,
        Func<bool, bool> matches, Action<bool> replay, Action<object>? apply, bool isContainment, ReciprocalEdit reciprocal,
        bool isOrdinaryCollection, bool isKeyedContainment, bool isTransferRemoval, bool allowCompatibleNulls)
        : base(source, propertyName, null, null, isModelReference, kind, isContainment, reciprocal.IsInverse)
    {
        this.removed = Array.AsReadOnly(removed.ToArray());
        this.added = Array.AsReadOnly(added.ToArray());
        this.matches = matches;
        this.replay = replay;
        this.apply = apply;
        this.allowCompatibleNulls = allowCompatibleNulls;
        ReciprocalRemoval = reciprocal.Removal;
        RequiresEarlyCollectionCapture = RequiresEarlyCapture(reciprocal, isOrdinaryCollection, isKeyedContainment, isTransferRemoval);
    }

    internal ModelCollectionChange(object source, string propertyName, ModelChangeKind kind, bool isModelReference,
        ModelCollectionEdit edit, bool isContainment, ReciprocalEdit reciprocal,
        bool isOrdinaryCollection, bool isKeyedContainment, bool isTransferRemoval, bool allowCompatibleNulls)
        : base(source, propertyName, null, null, isModelReference, kind, isContainment, reciprocal.IsInverse)
    {
        this.edit = edit;
        this.allowCompatibleNulls = allowCompatibleNulls;
        ReciprocalRemoval = reciprocal.Removal;
        RequiresEarlyCollectionCapture = RequiresEarlyCapture(reciprocal, isOrdinaryCollection, isKeyedContainment, isTransferRemoval);
    }

    private bool RequiresEarlyCapture(ReciprocalEdit reciprocal, bool isOrdinaryCollection, bool isKeyedContainment,
        bool isTransferRemoval) =>
        !reciprocal.IsInverse && (isTransferRemoval || Kind switch
        {
            ModelChangeKind.List when IsContainment => isKeyedContainment || isOrdinaryCollection && (AddedCount != 0 || RemovedCount != 0)
                || AddedCount != 0 && RemovedCount != 0 && RemovedCount != AddedCount,
            ModelChangeKind.List => !IsModelReference || isOrdinaryCollection,
            ModelChangeKind.ReferenceSet => isOrdinaryCollection || isKeyedContainment || IsContainment && HasNoPositions,
            _ => false,
        });

    // The entries of a range edit all carry an index.
    private bool HasNoPositions => edit is not null ? RemovedCount + AddedCount == 0
        : Added.Concat(Removed).All(entry => entry.Index is null);

    public IReadOnlyList<ModelCollectionEntry> Removed => removed ?? Entries(ref removed, edit!.RemovedEntries());
    public IReadOnlyList<ModelCollectionEntry> Added => added ?? Entries(ref added, edit!.AddedEntries());

    private static IReadOnlyList<ModelCollectionEntry> Entries(ref IReadOnlyList<ModelCollectionEntry>? entries,
        ModelCollectionEntry[] built) =>
        Interlocked.CompareExchange(ref entries, Array.AsReadOnly(built), null) ?? entries;

    internal int RemovedCount => edit?.RemovedCount ?? removed!.Count;
    internal int AddedCount => edit?.AddedCount ?? added!.Count;

    /// <summary>The removed and the added values as they are, for the bookkeeping of containment.</summary>
    internal object?[] RemovedObjects => edit?.RemovedObjects ?? Removed.Select(static entry => entry.Value).ToArray();
    internal object?[] AddedObjects => edit?.AddedObjects ?? Added.Select(static entry => entry.Value).ToArray();

    internal ReciprocalRemoval? ReciprocalRemoval { get; }
    internal bool RequiresEarlyCollectionCapture { get; }
    internal bool CanApplyToExplicitTarget => (apply is not null || edit is not null) && (!IsModelReference || allowCompatibleNulls);

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
        if (edit is not null)
        {
            edit.Apply(target);
            return;
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

    internal override bool IsCurrent(bool undo) => edit?.Matches(undo) ?? matches!(undo);

    internal override void SetValue(bool undo)
    {
        if (edit is not null) edit.Replay(undo);
        else replay!(undo);
    }
}
