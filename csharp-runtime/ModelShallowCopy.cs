namespace Vmf.Runtime;

public sealed partial class ModelReflectionRegistry
{
    /// <summary>Copies the root while preserving its non-owning references and materialized legacy lists.</summary>
    /// <remarks>Managed collections have independent membership. To keep existing ownership and
    /// opposite relationships valid, populated containment and occupied singular opposites require a deep copy.</remarks>
    public T ShallowCopy<T>(T root) where T : class
    {
        var model = Bind(root);
        var type = model.Schema.Snapshot;
        if (type.IsImmutable) return root;

        var ordinary = new List<(SnapshotProperty Property, object? Value)>();
        var opposites = new List<(SnapshotProperty Property, object? Value)>();
        var shared = new List<(SnapshotProperty Property, Action<object, object> Attach)>();
        foreach (var field in model.Schema.Fields)
        {
            if (field.Snapshot is not { } property) continue;
            if (field.Field.ShareSequence is { } share)
            {
                shared.Add((property, share));
                continue;
            }

            object? value = CaptureField(property, model.Identity);
            if (property.IsContainment)
            {
                if (ModelMembers(property, value).Any()) throw Occupied(property);
                if (property.Kind == SnapshotPropertyKind.ContainmentSequence) ordinary.Add((property, value));
            }
            else if (property.Reciprocal is { } resolve)
            {
                var endpoint = resolve(model.Identity);
                if (ModelMembers(property, value).Any(member => !endpoint.Opposite(member).IsCollection))
                {
                    throw Occupied(property);
                }
                opposites.Add((property, value));
            }
            else
            {
                ordinary.Add((property, value));
            }
        }

        var copy = ReferenceCollectionDefaults.CreateCopyShell(model.Identity, type);
        if (copy is null || copy.GetType() != type.RuntimeType || ReferenceEquals(copy, model.Identity))
        {
            throw new InvalidOperationException($"The factory for '{type.WireName}' must create a new model object.");
        }
        foreach (var (property, value) in ordinary)
        {
            ModelProperty.CopyManagedDefaultOverride(property, model.Identity, copy);
            if (property.IsMap && property.Schema.HasExecutableDefault)
            {
                ManagedCollectionDefaults.RestoreMap((IJournalMap)property.Get(copy)!, ((object Key, object? Value)[])value!);
            }
            else if (ReferenceCollectionDefaults.IsBound(property))
            {
                // The copy constructor appends source membership after the construction hook.
                var references = (ReferenceCollectionDefaults.ICopyMembership)property.Get(copy)!;
                references.AppendCopyMembers((object?[])value!);
            }
            else WriteField(copy, property, value);
        }
        bool observed = ModelChanges.HasObservedCollections(model.Identity);
        foreach (var (property, attach) in shared)
        {
            // Java registers an observation relay by first obtaining each native list.
            if (observed) property.Get(model.Identity);
            attach(model.Identity, copy);
        }
        // Bind the copy to existing many-valued opposites only after all structural checks.
        foreach (var (property, value) in opposites) WriteField(copy, property, value);
        ReferenceCollectionDefaults.Copy(model.Identity, copy, remap: null);
        foreach (var property in type.Properties)
        {
            if (ModelProperty.ReferenceDefaultOverride(property, model.Identity) is { } configured)
            {
                ModelProperty.StoreReferenceDefaultOverride(copy, property.ModelName, configured);
            }
        }
        return (T)(model.IsReadOnly ? ProjectReadOnly(copy) : copy);
    }

    private static InvalidOperationException Occupied(SnapshotProperty property) =>
        new($"Shallow copying '{property.ModelName}' would change an existing owner. Use DeepCopy().");
}
