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
                foreach (var member in cached) DiscoverMember(member!);
            }
            var properties = node.Source.Schema.Snapshot.Properties;
            for (int position = 0; position < properties.Count; position++)
            {
                var property = properties[position];
                if (ModelProperty.ReferenceDefaultOverride(property, node.Source.Identity) is { } configured)
                {
                    (node.ReferenceOverrides ??= new(StringComparer.Ordinal)).Add(property.ModelName, configured);
                    foreach (var member in configured) DiscoverMember(member!);
                }
                var value = CaptureForCopy(property, node.Source.Identity);
                node.Values[position] = value;
                if (!property.IsContainment && !property.IsReference) continue;

                if (property.IsCollection)
                {
                    foreach (var member in (object?[])value!)
                    {
                        if (member is not null) DiscoverMember(member);
                    }
                }
                else if (value is not null)
                {
                    DiscoverMember(value);
                }
            }
        }

        CopyFields(pending, CopyPhase.Scalars);
        CopyFields(pending, CopyPhase.References);
        // Keyed containment validates both scalar and reference keys. Assign those before attaching any child.
        CopyFields(pending, CopyPhase.Containment);
        Func<object?, object?> copyTarget = CopyTarget;
        foreach (var node in pending)
        {
            ReferenceCollectionDefaults.Copy(node.Source.Identity, node.Target, copyTarget);
            if (node.ReferenceOverrides is null) continue;
            foreach (var (name, members) in node.ReferenceOverrides)
            {
                ModelProperty.StoreReferenceDefaultOverride(node.Target, name, Array.AsReadOnly(members.Select(copyTarget).ToArray()));
            }
        }

        return copies[root.Identity];

        // Most members of a graph are reached more than once: as a child and through references.
        void DiscoverMember(object member)
        {
            if (!copies.ContainsKey(member)) Discover(Bind(member));
        }

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
                var properties = node.Source.Schema.Snapshot.Properties;
                for (int position = 0; position < properties.Count; position++)
                {
                    var property = properties[position];
                    if (FieldPhase(property) != phase) continue;
                    object? captured = node.Values[position];
                    ModelProperty.CopyManagedDefaultOverride(property, node.Source.Identity, node.Target);

                    if (property.Reciprocal is not null)
                    {
                        object? value = property.IsCollection ? CopyTargets((object?[])captured!) : CopyTarget(captured);
                        (reciprocals ??= []).Add(new(node.Target, property, value));
                    }
                    else if (property.IsMap)
                    {
                        var entries = ((object Key, object? Value)[])captured!;
                        ManagedCollectionDefaults.RestoreMap((IJournalMap)property.Get(node.Target)!, entries);
                    }
                    else if (property.Kind == SnapshotPropertyKind.ContainmentSequence)
                    {
                        var sequence = (IJournalSequence)property.Get(node.Target)!;
                        sequence.Replace(CopyTargets((object?[])captured!));
                    }
                    else if (property.IsCollection)
                    {
                        if (ModelProperty.CopiesManagedDefault(property))
                        {
                            ManagedCollectionDefaults.RestoreMembers(property.Get(node.Target)!, (object?[])captured!);
                            continue;
                        }
                        foreach (var member in (object?[])captured!)
                        {
                            object? value = phase == CopyPhase.Scalars ? member : CopyTarget(member);
                            property.Add!(node.Target, value!);
                        }
                    }
                    else
                    {
                        object? value = phase == CopyPhase.Scalars ? captured : CopyTarget(captured);
                        property.Set!(node.Target, value);
                    }
                }
            }
            if (reciprocals is not null) ReciprocalHydration.Apply(reciprocals);
        }

        object?[] CopyTargets(object?[] members)
        {
            if (members.Length == 0) return members;
            var targets = new object?[members.Length];
            for (int index = 0; index < members.Length; index++) targets[index] = CopyTarget(members[index]);
            return targets;
        }

        object? CopyTarget(object? model)
        {
            if (model is null) return null;
            // A mutable member that was discovered as itself. Views and immutable values are bound first.
            if (copies.TryGetValue(model, out var copy)) return copy;
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

    /// <summary>
    /// Captures a field for a copy, which only reads the result. A collection of objects knows its size, and most
    /// are empty or small: they need no enumeration into a growing buffer, and an empty one no array of its own.
    /// </summary>
    private static object? CaptureForCopy(SnapshotProperty property, object model)
    {
        if (property.IsMap || !property.IsCollection) return CaptureField(property, model);
        var value = property.Get(model);
        if (value is not IReadOnlyCollection<object?> members) return ((IEnumerable)value!).Cast<object?>().ToArray();
        if (members.Count == 0) return Array.Empty<object?>();
        var captured = new object?[members.Count];
        int index = 0;
        foreach (var member in members) captured[index++] = member;
        return captured;
    }

    private static CopyPhase FieldPhase(SnapshotProperty property) => property.IsContainment ? CopyPhase.Containment
        : property.IsReference ? CopyPhase.References : CopyPhase.Scalars;

    private enum CopyPhase { Scalars, References, Containment }

    private sealed class GraphCopy(ModelBinding source, object target)
    {
        internal ModelBinding Source { get; } = source;
        internal object Target { get; } = target;
        /// <summary>The captured value of each property of the source, in the order of its type's properties.</summary>
        internal object?[] Values { get; } = new object?[source.Schema.Snapshot.Properties.Count];
        internal Dictionary<string, IReadOnlyList<object?>>? ReferenceOverrides { get; set; }
    }
}
