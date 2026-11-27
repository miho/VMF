namespace Vmf.Runtime;

/// <summary>Selects how an in-place patch checks the current values of its source graph.</summary>
public enum ModelPatchApplyMode
{
    /// <summary>Replaces intervening values on the affected properties of the original graph.</summary>
    Overwrite,

    /// <summary>Requires every affected property to still have its captured before value.</summary>
    RequireBeforeValues,
}

/// <summary>A captured edit to a logical property on the original model graph.</summary>
/// <remarks>Collection values are read-only snapshots. Modeled members are read-only identity views.</remarks>
public sealed record ModelPatchChange(object Object, string PropertyName, SnapshotPropertyKind Kind,
    object? Before, object? After);

/// <summary>A native graph patch bound to the exact source used to compute it.</summary>
public sealed class ModelPatch
{
    private readonly Action<object, bool, bool> execute;

    internal ModelPatch(object source, ModelPatchChange[] changes, Action<object, bool, bool> execute)
    {
        Source = source;
        Changes = Array.AsReadOnly(changes);
        this.execute = execute;
    }

    public object Source { get; }
    public IReadOnlyList<ModelPatchChange> Changes { get; }

    /// <summary>Applies the captured edits through the source model's normal mutators.</summary>
    public void Apply(object target, ModelPatchApplyMode mode = ModelPatchApplyMode.Overwrite)
    {
        if (!Enum.IsDefined(mode)) throw new ArgumentOutOfRangeException(nameof(mode));
        execute(target, false, mode == ModelPatchApplyMode.RequireBeforeValues);
    }

    /// <summary>Restores the captured before values if every affected field still has its applied value.</summary>
    public void Undo(object target) => execute(target, true, true);
}
