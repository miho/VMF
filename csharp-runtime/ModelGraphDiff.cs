using System.Diagnostics.CodeAnalysis;

namespace Vmf.Runtime;

public sealed partial class ModelReflectionRegistry
{
    /// <summary>Captures the edits that transform a mutable source into the desired graph.</summary>
    /// <remarks>Matching owning edges establish identity before non-owning references are compared.
    /// The source and desired root must have the same concrete registered model type.</remarks>
    public ModelPatch Diff(object source, object desired) => CreatePatch(source, desired, sharedProperties: false);

    /// <summary>Captures edits to compatible logical properties shared by two different model types.</summary>
    /// <remarks>Different root types can share scalar values, scalar collections, and compatible relationships.
    /// Owning and reciprocal properties require matching generated opposite and key metadata.
    /// The source keeps its type and properties absent from the desired model.
    /// The patch can only be applied to the original source.</remarks>
    public ModelPatch DiffSharedProperties(object source, object desired) =>
        CreatePatch(source, desired, sharedProperties: true);

    private ModelPatch CreatePatch(object source, object desired, bool sharedProperties)
    {
        var sourceModel = Bind(source);
        var desiredModel = Bind(desired);
        bool differentRoots = sourceModel.Schema.Snapshot.RuntimeType != desiredModel.Schema.Snapshot.RuntimeType;
        if (differentRoots && !sharedProperties)
        {
            throw new ArgumentException($"Model types '{sourceModel.Schema.Snapshot.WireName}' and '{desiredModel.Schema.Snapshot.WireName}' differ.");
        }
        if (sourceModel.IsReadOnly)
        {
            throw new ArgumentException("A graph patch requires a writable source model.", nameof(source));
        }

        var fields = differentRoots ? SharedPatchProperties(sourceModel, desiredModel) : null;
        Func<PropertySchema, bool>? select = fields is null ? null : field => fields.Contains(field.Field.Name);
        var before = CaptureGraph(sourceModel, select);
        var after = CaptureGraph(desiredModel, select);
        if (differentRoots) RequireCompatibleRootReferences(before, after);
        var counterparts = new Counterparts(this, before, after);
        counterparts.Match();
        var creations = PrepareNewNodes(after, counterparts.Targets, before);
        var edits = new List<PatchEdit>();
        foreach (var next in after.Nodes.Values)
        {
            object target = counterparts.Targets[next.Model.Identity];
            if (!before.Nodes.TryGetValue(target, out var prior) || prior.Model.Schema.Snapshot.IsImmutable) continue;
            foreach (var field in next.Fields)
            {
                var oldField = prior.Field(field.Property.Field.Name);
                var property = oldField.Property.Snapshot!;
                var oldValue = oldField.Value;
                var newValue = Redirect(property, field.Value, counterparts.Targets);
                if (!PatchValuesEqual(property, oldValue, newValue))
                {
                    edits.Add(new PatchEdit(target, property, oldValue, newValue));
                }
            }
        }

        var summaries = edits.Select(edit => new ModelPatchChange(edit.Owner, edit.Property.ModelName, edit.Property.Kind,
            Describe(edit.Property, edit.Before), Describe(edit.Property, edit.After))).ToArray();
        return new ModelPatch(source, summaries, (target, undo, strict) =>
            ExecutePatch(source, target, edits, creations, undo, strict));
    }

    /// <summary>Copies the template, then overlays compatible set values and appends positional collections.</summary>
    /// <remarks>The result keeps the template's concrete type. Fields present only in the overlay are ignored.
    /// A single input is copied and two missing inputs return null. Mutable inputs remain independent of the result.
    /// An immutable template retains its identity when the overlay contains no writes to its fields.</remarks>
    [return: NotNullIfNotNull(nameof(template))]
    [return: NotNullIfNotNull(nameof(overlay))]
    public T? Merge<T>(T? template, T? overlay) where T : class
    {
        if (template is null) return overlay is null ? null : DeepCopy(overlay);
        if (overlay is null) return DeepCopy(template);

        var templateModel = Bind(template);
        var overlayModel = Bind(overlay);
        var destination = templateModel.Schema.Snapshot.Properties.ToDictionary(property => property.ModelName, StringComparer.Ordinal);
        var desired = CaptureGraph(overlayModel, IncludeField);
        foreach (var field in desired.Root.Fields)
        {
            var source = field.Property.Snapshot!;
            var target = destination[source.ModelName];
            bool matchesValue = source.IsReference && !source.IsCollection &&
                (field.Value is null || target.ValueType.IsInstanceOfType(field.Value));
            if (target.Kind != source.Kind || target.KeyType != source.KeyType ||
                !target.ValueType.IsAssignableFrom(source.ValueType) && !matchesValue)
            {
                throw new ArgumentException($"The overlay's '{source.ModelName}' field is incompatible with the template.", nameof(overlay));
            }
        }
        if (templateModel.Schema.Snapshot.IsImmutable)
        {
            // A set or a nonempty append is still a write when its values already match.
            if (desired.Root.Fields.Any(field => !AppendsInMerge(field.Property.Snapshot!) ||
                    field.Value is not Array { Length: 0 }))
            {
                throw new ArgumentException("An immutable merge template cannot accept writes.", nameof(template));
            }
            return template;
        }

        object result = CopyGraph(templateModel);
        var targets = new Dictionary<object, object>(ReferenceEqualityComparer.Instance);
        var copySource = desired;
        if (templateModel.Schema.Snapshot.RuntimeType != overlayModel.Schema.Snapshot.RuntimeType && HasOverlayReference())
        {
            // A referenced overlay retains its own concrete type and all fields, even those absent from the template.
            copySource = CaptureGraph(overlayModel);
        }
        else
        {
            targets.Add(overlayModel.Identity, result);
        }
        var additions = PrepareNewNodes(copySource, targets, reservedTarget: result);
        var edits = new List<PatchEdit>();
        foreach (var field in desired.Root.Fields)
        {
            var property = destination[field.Property.Field.Name];
            object? value = Redirect(property, field.Value, targets);
            var oldValue = CaptureField(property, result);
            if (AppendsInMerge(property))
            {
                var combined = ((object?[])oldValue!).Concat((object?[])value!);
                value = property.HasUniqueMembers ? combined.Distinct(MemberComparer(property)).ToArray() : combined.ToArray();
            }
            if (!SnapshotValuesEqual(property, oldValue, value))
            {
                edits.Add(new PatchEdit(result, property, oldValue, value));
            }
        }
        WritePatch(edits, additions, undo: false);
        return (T)(templateModel.IsReadOnly ? ProjectReadOnly(result) : result);

        bool HasOverlayReference()
        {
            foreach (var node in desired.Nodes.Values)
            {
                foreach (var field in node.Fields)
                {
                    var property = field.Property.Snapshot!;
                    if (HasModelMembers(property) && ModelMembers(property, field.Value)
                        .Contains(overlayModel.Identity, ReferenceEqualityComparer.Instance)) return true;
                }
            }
            return false;
        }

        bool IncludeField(PropertySchema field)
        {
            var source = field.Snapshot!;
            return destination.ContainsKey(source.ModelName) &&
                (AppendsInMerge(source) || new ModelProperty(overlayModel, field).IsSet);
        }
    }

    private static bool AppendsInMerge(SnapshotProperty property) =>
        property.IsCollection && property.Kind != SnapshotPropertyKind.ScalarSet;

    private static HashSet<string> SharedPatchProperties(ModelBinding source, ModelBinding desired)
    {
        var leftFields = source.Schema.Fields.Where(field => field.Snapshot is not null)
            .ToDictionary(field => field.Field.Name, StringComparer.Ordinal);
        var shared = new HashSet<string>(StringComparer.Ordinal);
        foreach (var field in desired.Schema.Fields)
        {
            string name = field.Field.Name;
            if (field.Snapshot is not { } right || !leftFields.TryGetValue(name, out var sourceField)) continue;

            var left = sourceField.Snapshot!;
            if (left.Kind != right.Kind || !left.ValueType.IsAssignableFrom(right.ValueType)
                || left.KeyType != right.KeyType || left.ExternalType != right.ExternalType
                || left.UpperBound != right.UpperBound || left.AllowNullElements != right.AllowNullElements
                || sourceField.Field.LowerBound != field.Field.LowerBound || !MatchesSharedRelationship(sourceField, field))
            {
                throw new ArgumentException($"The models cannot share '{name}' in a cross-type patch.", nameof(desired));
            }
            shared.Add(name);
        }
        return shared;
    }

    private static bool MatchesSharedRelationship(PropertySchema source, PropertySchema desired)
    {
        var left = source.Snapshot!;
        var right = desired.Snapshot!;
        if (!left.IsContainment && left.Reciprocal is null && right.Reciprocal is null) return true;

        string key = left.IsContainment ? "vmf:property:containment-info" : "vmf:property:reference-opposite";
        var leftInfo = ModelAnnotation.FindAll(source.Field.Annotations, key);
        var rightInfo = ModelAnnotation.FindAll(desired.Field.Annotations, key);
        return left.ValueType == right.ValueType
            && (left.Reciprocal is null) == (right.Reciprocal is null)
            && leftInfo.Count == 1 && rightInfo.Count == 1 && leftInfo[0].Value == rightInfo[0].Value
            && source.Field.KeyPropertyName == desired.Field.KeyPropertyName;
    }

    private static void RequireCompatibleRootReferences(GraphImage source, GraphImage desired)
    {
        foreach (var node in desired.Nodes.Values)
        {
            foreach (var field in node.Fields)
            {
                var property = ReferenceEquals(node, desired.Root)
                    ? source.Root.Field(field.Property.Field.Name).Property.Snapshot!
                    : field.Property.Snapshot!;
                if (!HasModelMembers(property) || !ModelMembers(property, field.Value)
                    .Contains(desired.Root.Model.Identity, ReferenceEqualityComparer.Instance)) continue;
                if (property.IsContainment || !property.ValueType.IsInstanceOfType(source.Root.Model.Identity))
                {
                    throw new ArgumentException($"The reference in '{property.ModelName}' cannot be redirected to the source root.", nameof(desired));
                }
            }
        }
    }

    private GraphImage CaptureGraph(ModelBinding root, Func<PropertySchema, bool>? includeRootField = null)
    {
        var nodes = new Dictionary<object, ImageNode>(ReferenceEqualityComparer.Instance);
        var pending = new Queue<ImageNode>();
        Discover(root);
        while (pending.TryDequeue(out var node))
        {
            var fieldSelector = ReferenceEquals(node.Model.Identity, root.Identity) ? includeRootField : null;
            // Merge reads the overlay's selected root fields. Nested immutable values remain shared.
            if (node.Model.Schema.Snapshot.IsImmutable && fieldSelector is null) continue;
            foreach (var field in node.Model.Schema.Fields)
            {
                if (field.Snapshot is not { } property) continue;
                if (fieldSelector is not null && !fieldSelector(field)) continue;
                object? value = CaptureField(property, node.Model.Identity);
                node.Add(new ImageField(field, value));
                if (!HasModelMembers(property)) continue;
                foreach (var member in ModelMembers(property, value))
                {
                    Discover(Bind(member));
                }
            }
        }
        return new GraphImage(nodes[root.Identity], nodes);

        void Discover(ModelBinding model)
        {
            if (nodes.ContainsKey(model.Identity)) return;
            var node = new ImageNode(model);
            nodes.Add(model.Identity, node);
            pending.Enqueue(node);
        }
    }

    private PatchCreations PrepareNewNodes(GraphImage desired, Dictionary<object, object> targets, GraphImage? source = null,
        object? reservedTarget = null)
    {
        var newNodes = new List<(ImageNode Source, object Target)>();
        var instances = new HashSet<object>(targets.Values, ReferenceEqualityComparer.Instance);
        if (reservedTarget is not null) instances.Add(reservedTarget);
        foreach (var node in desired.Nodes.Values)
        {
            var model = node.Model;
            if (targets.ContainsKey(model.Identity)) continue;
            if (model.Schema.Snapshot.IsImmutable)
            {
                targets.Add(model.Identity, model.Identity);
                continue;
            }
            var fresh = model.Schema.Snapshot.Create!();
            if (fresh is null || fresh.GetType() != model.Schema.Snapshot.RuntimeType || desired.Nodes.ContainsKey(fresh)
                || source?.Nodes.ContainsKey(fresh) == true || !instances.Add(fresh))
            {
                throw new InvalidOperationException($"The factory for '{model.Schema.Snapshot.WireName}' must create a new model object.");
            }
            targets.Add(model.Identity, fresh);
            newNodes.Add((node, fresh));
        }

        var relationships = new List<PatchEdit>();
        foreach (var (node, target) in newNodes)
        {
            foreach (var field in node.Fields)
            {
                var property = field.Property.Snapshot!;
                var before = CaptureField(property, target);
                var after = Redirect(property, field.Value, targets);
                if (SnapshotValuesEqual(property, before, after)) continue;
                if (HasModelMembers(property))
                {
                    relationships.Add(new PatchEdit(target, property, before, after));
                }
                else
                {
                    WriteField(target, property, after);
                }
            }
        }
        return new(newNodes.Select(node => node.Target).ToHashSet(ReferenceEqualityComparer.Instance), relationships);
    }

    private void ExecutePatch(object source, object target, List<PatchEdit> edits, PatchCreations additions,
        bool undo, bool strict)
    {
        var targetModel = Bind(target);
        if (targetModel.IsReadOnly)
        {
            throw new ArgumentException("A graph patch requires a writable target model.", nameof(target));
        }
        if (!ReferenceEquals(source, target))
        {
            throw new ArgumentException("The patch is bound to its original source graph.", nameof(target));
        }

        var affected = new HashSet<object>(edits.Select(edit => edit.Owner), ReferenceEqualityComparer.Instance);
        var reachable = new HashSet<object>(Traverse(target, ModelTraversalStrategy.UniqueNode), ReferenceEqualityComparer.Instance);
        if (!affected.IsSubsetOf(reachable))
        {
            throw new InvalidOperationException("An affected model object is no longer reachable from the source graph.");
        }
        foreach (var edit in edits)
        {
            if (strict && !PatchValuesEqual(edit.Property, CaptureField(edit.Property, edit.Owner), undo ? edit.After : edit.Before))
            {
                throw new InvalidOperationException($"The current value of '{edit.Property.ModelName}' differs from the patch.");
            }
        }
        var newOwners = new HashSet<object>(additions.Relationships.Select(edit => edit.Owner), ReferenceEqualityComparer.Instance);
        foreach (var edit in undo ? edits : edits.Concat(additions.Relationships))
        {
            if (!edit.Property.IsContainment) continue;
            foreach (var member in ModelMembers(edit.Property, undo ? edit.Before : edit.After))
            {
                if (member is IModelGraphAccess access && access.VmfContainmentLink.Owner is { } owner
                    && !reachable.Contains(owner) && !newOwners.Contains(owner))
                {
                    throw new InvalidOperationException("A patch cannot move a child from an unrelated model graph.");
                }
            }
        }
        WritePatch(edits, additions, undo, reachable);
    }

    private static void WritePatch(List<PatchEdit> edits, PatchCreations additions, bool undo, IReadOnlySet<object>? reachable = null)
    {
        var sequence = undo ? Enumerable.Reverse(edits).ToArray() : edits.Concat(additions.Relationships).ToArray();
        DetachTransfers(sequence, undo);
        if (!undo && reachable is not null)
        {
            // Establish internal ownership before publishing the new branch to the source graph.
            // Edges involving live nodes still use the normal transfer and notification order.
            var detached = additions.Relationships.Where(edit => edit.Property.IsContainment && IsDetached(edit.Owner)
                && ModelMembers(edit.Property, edit.After).All(IsDetached)
                && ModelMembers(edit.Property, CaptureField(edit.Property, edit.Owner)).All(IsDetached)).ToArray();
            if (detached.Length != 0)
            {
                sequence = [..detached, ..sequence.Except<PatchEdit>(detached, ReferenceEqualityComparer.Instance)];
            }

            bool IsDetached(object member) => additions.Instances.Contains(member) && !reachable.Contains(member);
        }
        foreach (var phase in new[] { CopyPhase.Scalars, CopyPhase.References, CopyPhase.Containment })
        {
            foreach (var edit in sequence)
            {
                if (FieldPhase(edit.Property) == phase)
                {
                    WriteField(edit.Owner, edit.Property, undo ? edit.Before : edit.After);
                }
            }
        }
    }

    private static void DetachTransfers(PatchEdit[] edits, bool undo)
    {
        var destinations = new Dictionary<object, PatchEdit>(ReferenceEqualityComparer.Instance);
        foreach (var edit in edits)
        {
            if (!edit.Property.IsContainment) continue;
            foreach (var member in OwnedIdentities(edit.Property, undo ? edit.Before : edit.After))
            {
                destinations.Add(member, edit);
            }
        }
        foreach (var edit in edits)
        {
            if (!edit.Property.IsContainment) continue;
            var current = CaptureField(edit.Property, edit.Owner);
            if (edit.Property.IsCollection)
            {
                var items = (object?[])current!;
                var retained = items.Where(member => !Moves(member)).ToArray();
                if (retained.Length != items.Length) WriteField(edit.Owner, edit.Property, retained);
            }
            else if (Moves(current))
            {
                WriteField(edit.Owner, edit.Property, null);
            }

            bool Moves(object? member) => member is not null && destinations.TryGetValue(member, out var next)
                && !ReferenceEquals(edit, next);
        }
    }

    private object? Describe(SnapshotProperty property, object? value)
    {
        if (property.IsMap) return Array.AsReadOnly(((ValueTuple<object, object?>[])value!).ToArray());
        if (property.IsCollection)
        {
            var members = (object?[])value!;
            return Array.AsReadOnly(HasModelMembers(property) ? members.Select(ProjectMember).ToArray() : members.ToArray());
        }
        return HasModelMembers(property) ? ProjectMember(value) : value;

        object? ProjectMember(object? member) => member is null ? null : ProjectReadOnly(member);
    }

    private static object? Redirect(SnapshotProperty property, object? value, Dictionary<object, object> targets)
    {
        if (!HasModelMembers(property)) return value;
        return property.IsCollection
            ? ((object?[])value!).Select(member => member is null ? null : targets[member]).ToArray()
            : value is null ? null : targets[value];
    }

    private static bool HasModelMembers(SnapshotProperty property) => property.IsContainment || property.IsReference;

    private static IEnumerable<object> ModelMembers(SnapshotProperty property, object? value)
    {
        if (property.IsCollection)
        {
            foreach (object? member in (object?[])value!)
            {
                if (member is not null) yield return member;
            }
        }
        else if (value is not null)
        {
            yield return value;
        }
    }

    private static IEnumerable<object> OwnedIdentities(SnapshotProperty property, object? value)
    {
        var members = ModelMembers(property, value);
        return property.Kind == SnapshotPropertyKind.ContainmentSequence
            ? members.Distinct(ReferenceEqualityComparer.Instance) : members;
    }

    private static IEqualityComparer<object?> MemberComparer(SnapshotProperty property) =>
        HasModelMembers(property) ? ReferenceEqualityComparer.Instance : ScalarEquality<object?>.Instance;

    private static bool PatchValuesEqual(SnapshotProperty property, object? first, object? second)
    {
        if (property.IsMap)
        {
            var firstEntries = (ValueTuple<object, object?>[])first!;
            var secondEntries = (ValueTuple<object, object?>[])second!;
            if (firstEntries.Length != secondEntries.Length) return false;
            var indexed = secondEntries.ToDictionary(entry => entry.Item1, entry => entry.Item2, ScalarEquality<object>.Instance);
            return firstEntries.All(entry => indexed.TryGetValue(entry.Item1, out var value)
                && ScalarEquality<object?>.Instance.Equals(entry.Item2, value));
        }

        var comparer = MemberComparer(property);
        if (!property.IsCollection) return comparer.Equals(first, second);
        var left = (object?[])first!;
        var right = (object?[])second!;
        return property.IsUnorderedSet
            ? new HashSet<object?>(left, comparer).SetEquals(right)
            : left.SequenceEqual(right, comparer);
    }

    private static bool SnapshotValuesEqual(SnapshotProperty property, object? first, object? second)
    {
        // A graph write preserves the captured insertion order even though map equality compares mappings.
        if (!property.IsMap) return PatchValuesEqual(property, first, second);
        var before = ((object Key, object? Value)[])first!;
        var after = ((object Key, object? Value)[])second!;
        return before.Length == after.Length && before.Zip(after).All(pair =>
            ScalarEquality<object>.Instance.Equals(pair.First.Key, pair.Second.Key)
            && ScalarEquality<object?>.Instance.Equals(pair.First.Value, pair.Second.Value));
    }

    private static void WriteField(object owner, SnapshotProperty property, object? value)
    {
        var current = CaptureField(property, owner);
        if (SnapshotValuesEqual(property, current, value)) return;
        if (property.IsMap)
        {
            var comparer = ScalarEquality<object>.Instance;
            var beforeEntries = ((object Key, object? Value)[])current!;
            var afterEntries = ((object Key, object? Value)[])value!;
            var before = beforeEntries.ToDictionary(entry => entry.Key, entry => entry.Value, comparer);
            var after = afterEntries.ToDictionary(entry => entry.Key, entry => entry.Value, comparer);
            var map = (IJournalMap)property.Get(owner)!;
            // A value update keeps its key in place. Reinsert only when this would change the captured order.
            var keysAfterDelta = beforeEntries.Where(entry => after.ContainsKey(entry.Key)).Select(entry => entry.Key)
                .Concat(afterEntries.Where(entry => !before.ContainsKey(entry.Key)).Select(entry => entry.Key));
            if (!keysAfterDelta.SequenceEqual(afterEntries.Select(entry => entry.Key), comparer))
            {
                ScalarCollectionBounds.CheckCapacity(property.UpperBound, afterEntries.Length);
                ManagedCollectionDefaults.RestoreMap(map, afterEntries);
                return;
            }

            bool Changed(KeyValuePair<object, object?> entry, Dictionary<object, object?> other) =>
                !other.TryGetValue(entry.Key, out var otherValue) || !ScalarEquality<object?>.Instance.Equals(entry.Value, otherValue);
            var removed = before.Where(entry => Changed(entry, after)).Select(entry => (entry.Key, entry.Value)).ToArray();
            var added = after.Where(entry => Changed(entry, before)).Select(entry => (entry.Key, entry.Value)).ToArray();
            map.ApplyEntries(removed, added);
        }
        else if (property.IsUnorderedSet)
        {
            var comparer = MemberComparer(property);
            var before = (object[])current!;
            var after = (object[])value!;
            ((IJournalMembership)property.Get(owner)!).ApplyMembership(
                before.Except(after, comparer).ToArray(), after.Except(before, comparer).ToArray());
        }
        else if (property.IsCollection)
        {
            ((IJournalSequence)property.Get(owner)!).Replace((object?[])value!);
        }
        else
        {
            property.Set!(owner, value);
        }
    }

    private sealed record PatchEdit(object Owner, SnapshotProperty Property, object? Before, object? After);
    private sealed record PatchCreations(HashSet<object> Instances, List<PatchEdit> Relationships);
    private sealed record GraphImage(ImageNode Root, Dictionary<object, ImageNode> Nodes);
    private sealed record ImageField(PropertySchema Property, object? Value);

    private sealed class ImageNode(ModelBinding model)
    {
        private readonly Dictionary<string, ImageField> fields = new(StringComparer.Ordinal);
        internal ModelBinding Model { get; } = model;
        internal IEnumerable<ImageField> Fields => fields.Values;
        internal void Add(ImageField field) => fields.Add(field.Property.Field.Name, field);
        internal ImageField Field(string name) => fields[name];
    }
}
