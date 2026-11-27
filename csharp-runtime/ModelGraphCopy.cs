using System.Collections;

namespace Vmf.Runtime;

public sealed partial class ModelReflectionRegistry
{
    /// <summary>Copies mutable objects through containment and reference edges and preserves shared immutable values.</summary>
    /// <remarks>A read-only root returns a read-only view of the independent copy.</remarks>
    public T DeepCopy<T>(T root) where T : class
    {
        var model = Bind(root);
        object copy = CopyGraph(model);
        return (T)(model.IsReadOnly ? ProjectReadOnly(copy) : copy);
    }

    internal T DeepCopyCheckpoint<T>(T root, Func<object, bool> isLiveInstance) where T : class =>
        (T)CopyGraph(Bind(root), isLiveInstance);

    private object CopyGraph(ModelBinding root, Func<object, bool>? isLiveInstance = null)
    {
        if (root.Schema.Snapshot.IsImmutable) return root.Identity;

        var copies = new Dictionary<object, object>(ReferenceEqualityComparer.Instance);
        var pending = new List<GraphCopy>();

        Discover(root);
        for (int index = 0; index < pending.Count; index++)
        {
            var node = pending[index];
            foreach (var cached in ReferenceCollectionDefaults.CapturedMembers(node.Source.Identity))
            {
                foreach (var member in cached) Discover(Bind(member!));
            }
            foreach (var property in node.Source.Schema.Snapshot.Properties)
            {
                if (ModelProperty.ReferenceDefaultOverride(property, node.Source.Identity) is { } configured)
                {
                    (node.ReferenceOverrides ??= new(StringComparer.Ordinal)).Add(property.ModelName, configured);
                    foreach (var member in configured) Discover(Bind(member!));
                }
                var value = CaptureField(property, node.Source.Identity);
                node.Fields.Add(new(property, value));
                if (!property.IsContainment && !property.IsReference) continue;

                if (property.IsCollection)
                {
                    foreach (var member in (object?[])value!)
                    {
                        if (member is not null) Discover(Bind(member));
                    }
                }
                else if (value is not null)
                {
                    Discover(Bind(value));
                }
            }
        }

        CopyFields(pending, CopyPhase.Scalars);
        CopyFields(pending, CopyPhase.References);
        // Keyed containment validates both scalar and reference keys. Assign those before attaching any child.
        CopyFields(pending, CopyPhase.Containment);
        foreach (var node in pending)
        {
            ReferenceCollectionDefaults.Copy(node.Source.Identity, node.Target, CopyTarget);
            if (node.ReferenceOverrides is null) continue;
            foreach (var (name, members) in node.ReferenceOverrides)
            {
                ModelProperty.StoreReferenceDefaultOverride(node.Target, name, Array.AsReadOnly(members.Select(CopyTarget).ToArray()));
            }
        }

        return copies[root.Identity];

        void Discover(ModelBinding source)
        {
            var type = source.Schema.Snapshot;
            if (type.IsImmutable || copies.ContainsKey(source.Identity)) return;
            var fresh = ReferenceCollectionDefaults.CreateCopyShell(source.Identity, type);
            if (fresh is null || fresh.GetType() != type.RuntimeType || ReferenceEquals(fresh, source.Identity))
            {
                throw new InvalidOperationException($"The factory for '{type.WireName}' must create a new model object.");
            }
            if (isLiveInstance?.Invoke(fresh) == true)
            {
                throw new InvalidOperationException($"The factory for '{type.WireName}' reused a live model object.");
            }
            copies.Add(source.Identity, fresh);
            pending.Add(new(source, fresh));
        }

        void CopyFields(List<GraphCopy> nodes, CopyPhase phase)
        {
            List<ReciprocalHydration.Field>? reciprocals = null;
            foreach (var node in nodes)
            {
                foreach (var field in node.Fields)
                {
                    var property = field.Property;
                    if (FieldPhase(property) != phase) continue;
                    ModelProperty.CopyManagedDefaultOverride(property, node.Source.Identity, node.Target);

                    if (property.Reciprocal is not null)
                    {
                        object? value = property.IsCollection ? ((object?[])field.Value!).Select(CopyTarget).ToArray()
                            : CopyTarget(field.Value);
                        (reciprocals ??= []).Add(new(node.Target, property, value));
                    }
                    else if (property.IsMap)
                    {
                        var entries = ((object Key, object? Value)[])field.Value!;
                        ManagedCollectionDefaults.RestoreMap((IJournalMap)property.Get(node.Target)!, entries);
                    }
                    else if (property.Kind == SnapshotPropertyKind.ContainmentSequence)
                    {
                        var sequence = (IJournalSequence)property.Get(node.Target)!;
                        sequence.Replace(((object?[])field.Value!).Select(CopyTarget).ToArray());
                    }
                    else if (property.IsCollection)
                    {
                        if (ModelProperty.CopiesManagedDefault(property))
                        {
                            ManagedCollectionDefaults.RestoreMembers(property.Get(node.Target)!, (object?[])field.Value!);
                            continue;
                        }
                        foreach (var member in (object?[])field.Value!)
                        {
                            object? value = phase == CopyPhase.Scalars ? member : CopyTarget(member);
                            property.Add!(node.Target, value!);
                        }
                    }
                    else
                    {
                        object? value = phase == CopyPhase.Scalars ? field.Value : CopyTarget(field.Value);
                        property.Set!(node.Target, value);
                    }
                }
            }
            if (reciprocals is not null) ReciprocalHydration.Apply(reciprocals);
        }

        object? CopyTarget(object? model)
        {
            if (model is null) return null;
            var source = Bind(model);
            return source.Schema.Snapshot.IsImmutable ? source.Identity : copies[source.Identity];
        }
    }

    private static object? CaptureField(SnapshotProperty property, object model)
    {
        var value = property.Get(model);
        if (property.IsMap) return ((ISnapshotScalarMap)value!).Entries.ToArray();
        if (property.IsCollection) return ((IEnumerable)value!).Cast<object?>().ToArray();
        return value;
    }

    private static CopyPhase FieldPhase(SnapshotProperty property) => property.IsContainment ? CopyPhase.Containment
        : property.IsReference ? CopyPhase.References : CopyPhase.Scalars;

    private enum CopyPhase { Scalars, References, Containment }

    private sealed class GraphCopy(ModelBinding source, object target)
    {
        internal ModelBinding Source { get; } = source;
        internal object Target { get; } = target;
        internal List<CopyField> Fields { get; } = new();
        internal Dictionary<string, IReadOnlyList<object?>>? ReferenceOverrides { get; set; }
    }

    private sealed record CopyField(SnapshotProperty Property, object? Value);
}
