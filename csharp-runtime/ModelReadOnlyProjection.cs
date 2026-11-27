using System.ComponentModel;

namespace Vmf.Runtime;

/// <summary>Returns the canonical read-only view through a generated mutable model's own package.</summary>
[EditorBrowsable(EditorBrowsableState.Never)]
public interface IModelReadOnlyProjection
{
    object VmfAsReadOnly();
}

/// <summary>Identifies immutable values emitted by the model generator.</summary>
[EditorBrowsable(EditorBrowsableState.Never)]
public interface IImmutableModel
{
}

public sealed partial class ModelReflectionRegistry
{
    internal object? ProjectNotificationValue(object? value, bool isModelReference)
    {
        if (value is null) return null;
        if (isModelReference) return ProjectReadOnly(value);
        // An opaque property may hold a generated model from a different registry.
        return value is IModelReadOnlyProjection model ? model.VmfAsReadOnly() : value;
    }

    /// <summary>Returns the registered live read-only view of a model or the immutable value itself.</summary>
    public object ProjectReadOnly(object model)
    {
        var binding = Bind(model);
        if (binding.IsReadOnly)
        {
            return model;
        }

        var project = binding.Schema.ProjectReadOnly ?? throw new InvalidOperationException(
            $"Type '{binding.Schema.Snapshot.WireName}' has no registered read-only projection.");
        object view = project(binding.Identity);
        var projected = Bind(view);
        if (!projected.IsReadOnly || !ReferenceEquals(projected.Identity, binding.Identity))
        {
            throw new InvalidOperationException($"Invalid read-only projection for '{binding.Schema.Snapshot.WireName}'.");
        }
        return view;
    }
}
