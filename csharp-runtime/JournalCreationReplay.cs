using System.Collections;
using System.Collections.ObjectModel;
using System.Text.Json;

namespace Vmf.Runtime;

internal sealed partial class JournalWorkspace
{
    private readonly HashSet<string> initializingIds = new(StringComparer.Ordinal);

    internal static Dictionary<string, object> IndexReachable(object root, JournalBinding binding, SnapshotRegistry registry) =>
        IndexReachable(root, binding, registry, out _, inspectCheckpoint: false);

    private static Dictionary<string, object> IndexReachable(object root, JournalBinding binding, SnapshotRegistry registry,
        out bool requiresNativeCheckpointCopy, bool inspectCheckpoint = true)
    {
        var reachable = new Dictionary<string, object>(StringComparer.Ordinal);
        var known = new HashSet<object>(ReferenceEqualityComparer.Instance);
        var pending = new Queue<(object Instance, int DocumentDepth)>();
        var definitions = new List<(object Instance, SnapshotType Type)>();
        pending.Enqueue((root, 1));
        requiresNativeCheckpointCopy = false;
        while (pending.TryDequeue(out var item))
        {
            var (source, documentDepth) = item;
            var type = registry.TypeForClass(source.GetType(), "$");
            if (type.IsImmutable)
            {
                throw new InvalidOperationException("Journal containment requires mutable model objects.");
            }
            Register(source, type);
            if (inspectCheckpoint && !requiresNativeCheckpointCopy)
            {
                requiresNativeCheckpointCopy = ModelProperty.HasManagedDefaultOverride(type, source)
                    || documentDepth >= SnapshotJson.MaxDocumentDepth - 1
                        && ExceedsCheckpointDocumentDepth(source, type, documentDepth);
            }
            foreach (var property in type.Properties.Where(property => property.IsContainment))
            {
                // A containment collection adds an array level before the child object.
                int childDepth = documentDepth + (property.IsCollection ? 2 : 1);
                foreach (var child in NonNullMembers(source, property)) pending.Enqueue((child, childDepth));
            }
        }

        for (int index = 0; index < definitions.Count; index++)
        {
            var (source, type) = definitions[index];
            foreach (var property in type.Properties)
            {
                if (!property.IsReference)
                {
                    if (property.Kind is SnapshotPropertyKind.Scalar or SnapshotPropertyKind.ScalarList
                        && JournalValueCodecs.RequiresCodec(property.ValueType))
                    {
                        foreach (var value in NonNullMembers(source, property)) binding.ValueCodecs.Encode(value);
                    }
                    continue;
                }
                foreach (var target in NonNullMembers(source, property))
                {
                    var targetType = registry.TypeForClass(target.GetType(), "$");
                    if (targetType.IsImmutable)
                    {
                        if (!known.Contains(target)) Register(target, targetType);
                    }
                    else if (type.IsImmutable || !known.Contains(target))
                    {
                        throw new InvalidOperationException("A journal mutable reference target is outside its containment graph.");
                    }
                }
            }
        }
        return reachable;

        void Register(object instance, SnapshotType type)
        {
            string id = binding.Id(instance);
            if (binding.HasObjectTypes) binding.TypeName(instance, type, registry);
            if (!known.Add(instance) || !reachable.TryAdd(id, instance))
            {
                throw new InvalidOperationException($"Duplicate journal graph object ID or containment entry '{id}'.");
            }
            definitions.Add((instance, type));
        }
    }

    private static bool ExceedsCheckpointDocumentDepth(object source, SnapshotType type, int depth)
    {
        if (depth > SnapshotJson.MaxDocumentDepth) return true;
        if (depth == SnapshotJson.MaxDocumentDepth)
        {
            return type.Properties.Any(property => property.IsCollection || property.IsMap);
        }
        // A populated typed map uses an array of entry objects.
        return type.Properties.Any(property => property.IsMap && property.KeyType != typeof(string)
            && property.Get(source) is ISnapshotScalarMap map && map.Entries.Any());
    }

    internal void ValidateCheckpointCopy(JournalWorkspace original, object root, string rootId, bool verifyState = false)
    {
        if (!Contains(rootId, root))
        {
            throw new InvalidOperationException("The checkpoint root ID changed when it was copied.");
        }
        if (original.objects.Count != objects.Count || objects.Keys.Any(id => !original.objects.ContainsKey(id)))
        {
            throw new InvalidOperationException("The checkpoint copy changed its IDs.");
        }
        foreach (var (id, copy) in objects)
        {
            var source = original.objects[id];
            var copyType = registry.TypeForClass(copy.GetType(), id);
            if (original.ContainsInstance(copy) && (!copyType.IsImmutable || !ReferenceEquals(source, copy)))
            {
                throw new InvalidOperationException($"The checkpoint copy reused a live object '{id}'.");
            }
            var sourceType = registry.TypeForClass(source.GetType(), id);
            if (binding.TypeName(copy, copyType, registry) != binding.TypeName(source, sourceType, registry))
            {
                throw new InvalidOperationException($"The checkpoint copy changed an object type for '{id}'.");
            }
            if (verifyState && (sourceType != copyType || !SameProperties(sourceType, source, copy, binding)))
            {
                throw new InvalidOperationException($"The checkpoint copy changed the state of '{id}'.");
            }
        }
        if (binding.HasObjectTypes) isLiveInstance = original.ContainsInstance;
    }

    internal void VerifyReachable(object root)
    {
        foreach (var (id, instance) in IndexReachable(root, binding, registry))
        {
            if (!Contains(id, instance))
            {
                throw new InvalidOperationException($"Unregistered or changed journal graph object ID '{id}'.");
            }
        }
    }

    internal static bool EquivalentGraphs(object expectedRoot, object actualRoot, JournalBinding binding, SnapshotRegistry registry)
    {
        var expected = IndexReachable(expectedRoot, binding, registry);
        var actual = IndexReachable(actualRoot, binding, registry);
        if (expected.Count != actual.Count) return false;
        foreach (var (id, source) in expected)
        {
            if (!actual.TryGetValue(id, out var copy) || source.GetType() != copy.GetType()) return false;
            var type = registry.TypeForClass(source.GetType(), id);
            if (binding.HasObjectTypes && binding.TypeName(source, type, registry) != binding.TypeName(copy, type, registry))
                return false;
            if (!SameProperties(type, source, copy, binding)) return false;
        }
        return true;
    }

    private static bool SameProperties(SnapshotType type, object source, object copy, JournalBinding binding) =>
        type.Properties.All(property => SameProperty(property, property.Get(source), property.Get(copy), binding));

    private static bool SameProperty(SnapshotProperty property, object? left, object? right, JournalBinding binding)
    {
        var comparer = ScalarEquality<object?>.Instance;
        if (property.IsMap)
        {
            return left is ISnapshotScalarMap leftMap && right is ISnapshotScalarMap rightMap
                && SameMapEntries(leftMap.Entries, rightMap.Entries);
        }
        if (!property.IsCollection)
        {
            return comparer.Equals(ComparisonValue(left), ComparisonValue(right));
        }
        if (left is not IEnumerable leftValues || right is not IEnumerable rightValues) return false;
        var leftItems = leftValues.Cast<object?>().Select(ComparisonValue);
        var rightItems = rightValues.Cast<object?>().Select(ComparisonValue);
        return IsUnorderedSet(property)
            ? new HashSet<object?>(leftItems, comparer).SetEquals(rightItems)
            : leftItems.SequenceEqual(rightItems, comparer);

        object? ComparisonValue(object? value) => value is not null && HasModelValues(property) ? binding.Id(value) : value;
    }

    private static IEnumerable<object> NonNullMembers(object source, SnapshotProperty property)
    {
        object? value = property.Get(source);
        if (property.IsCollection)
        {
            if (value is not IEnumerable items)
            {
                throw new InvalidOperationException($"Invalid journal graph collection '{property.ModelName}'.");
            }
            foreach (object? item in items)
            {
                if (item is not null) yield return item;
            }
        }
        else if (value is not null)
        {
            yield return value;
        }
    }

    private object ResolveJournalReference(JournalValue record, SnapshotProperty property, string path)
    {
        string id = record.Text!;
        if (!HasModelValues(property) || !objects.TryGetValue(id, out var target)
            || !initializingIds.Contains(id) && binding.Id(target) != id)
        {
            throw new InvalidOperationException($"Unresolved or changed journal reference '{id}' at '{path}'.");
        }

        bool immutable = registry.TypeForClass(target.GetType(), path).IsImmutable;
        if (immutable != record.IsImmutableReference || immutable && property.IsContainment)
        {
            throw new InvalidOperationException($"Wrong journal reference kind or containment target '{id}' at '{path}'.");
        }
        return target;
    }

    private void PrepareCreations(IReadOnlyList<JournalCreation> creations)
    {
        if (creations.Count == 0) return;

        var creationIds = new HashSet<string>(StringComparer.Ordinal);
        foreach (var creation in creations)
        {
            if (objects.ContainsKey(creation.Id) || !creationIds.Add(creation.Id))
                throw new InvalidOperationException($"Reused or duplicate journal creation ID '{creation.Id}'.");
        }
        var pending = creations.ToDictionary(creation => creation.Id, ResolveCreationType, StringComparer.Ordinal);

        try
        {
            var mutable = new List<(CreationDefinition Definition, object Instance)>();
            foreach (var item in pending.Values.Where(item => !item.Type.IsImmutable))
            {
                object instance = RegisterCreated(item, item.Prototype ?? item.Type.Create!());
                initializingIds.Add(item.Record.Id);
                mutable.Add((item, instance));
            }

            var fields = new List<CreationField>();
            foreach (var (definition, instance) in mutable)
            {
                fields.AddRange(ResolveCreationFields(definition, instance, requireComplete: false));
            }
            foreach (var field in fields.Where(field => !HasModelValues(field.Property)))
            {
                ApplyCreationField(field, DecodeCreationField(field, immutableOwner: false));
            }

            BuildImmutableCreations(pending);
            var relationships = fields.Where(field => HasModelValues(field.Property))
                .Select(field => (Field: field, Value: DecodeCreationField(field, immutableOwner: false))).ToArray();
            RequireDistinctContainment(relationships);
            List<ReciprocalHydration.Field>? reciprocals = null;
            foreach (var (field, expected) in relationships.Where(item => !item.Field.Property.IsContainment))
            {
                if (field.Property.Reciprocal is not null)
                {
                    (reciprocals ??= []).Add(new(field.Owner, field.Property, expected));
                }
                else
                {
                    ApplyCreationField(field, expected);
                }
            }
            if (reciprocals is not null) ReciprocalHydration.Apply(reciprocals);
            foreach (var (field, expected) in relationships.Where(item => item.Field.Property.IsContainment))
            {
                ApplyCreationField(field, expected);
            }
            foreach (var (field, expected) in relationships)
            {
                VerifyCreationField(field, expected);
            }
            foreach (var (definition, instance) in mutable)
            {
                RequireCreatedId(definition, instance);
            }
        }
        finally
        {
            initializingIds.Clear();
        }
    }

    private CreationDefinition ResolveCreationType(JournalCreation creation)
    {
        try
        {
            if (!binding.HasObjectTypes)
                return new(creation, registry.TypeForName(creation.Type, typeof(object), creation.Id), null);

            object instance = binding.CreatePrototype(creation);
            if (instance is null || isLiveInstance?.Invoke(instance) == true || ContainsInstance(instance))
                throw new InvalidOperationException($"Journal factory returned a reused or absent instance for '{creation.Id}'.");
            var type = registry.TypeForClass(instance.GetType(), creation.Id);
            if (binding.TypeName(instance, type, registry) != creation.Type
                || !type.IsImmutable && binding.Id(instance) != creation.Id)
            {
                throw new InvalidOperationException($"Journal factory returned the wrong ID or type for '{creation.Id}'.");
            }
            return new(creation, type, instance);
        }
        catch (JsonException error)
        {
            throw new InvalidOperationException($"Unknown journal creation type '{creation.Type}'.", error);
        }
    }

    private object RegisterCreated(CreationDefinition definition, object? instance)
    {
        if (instance is null || instance.GetType() != definition.Type.RuntimeType
            || isLiveInstance?.Invoke(instance) == true || !Register(definition.Record.Id, instance))
        {
            throw new InvalidOperationException($"Journal factory returned a reused or incompatible instance for '{definition.Record.Id}'.");
        }
        return instance;
    }

    private void RequireCreatedId(CreationDefinition definition, object instance)
    {
        if (binding.Id(instance) != definition.Record.Id
            || binding.HasObjectTypes && binding.TypeName(instance, definition.Type, registry) != definition.Record.Type)
        {
            throw new InvalidOperationException($"Journal creation did not initialize its expected ID or type '{definition.Record.Id}'.");
        }
    }

    private IEnumerable<CreationField> ResolveCreationFields(CreationDefinition definition, object instance, bool requireComplete)
    {
        var properties = new Dictionary<string, SnapshotProperty>(StringComparer.Ordinal);
        foreach (var property in definition.Type.Properties)
        {
            string key = requireComplete
                ? binding.CheckedPropertyKey(instance, property.ModelName)
                : binding.PropertyKey(instance, property.ModelName);
            if (!properties.TryAdd(key, property))
            {
                throw new InvalidOperationException($"Ambiguous journal creation property '{definition.Record.Id}.{key}'.");
            }
        }
        var creation = definition.Record;
        int fieldCount = creation.Properties.Count + creation.MapProperties.Count
            + creation.TypedMapProperties.Count + creation.SetProperties.Count;
        if (requireComplete && properties.Count != fieldCount)
        {
            throw new InvalidOperationException($"Incomplete immutable creation '{definition.Record.Id}'.");
        }

        foreach (var (key, values) in creation.Properties)
        {
            string path = definition.Record.Id + "." + key;
            var property = Find(key, path);
            if (property.IsMap || property.Kind == SnapshotPropertyKind.ScalarSet)
            {
                throw new InvalidOperationException($"Expected a specialized collection creation payload at '{path}'.");
            }
            yield return new(instance, property, values, path);
        }
        foreach (var (key, values) in creation.SetProperties)
        {
            string path = creation.Id + "." + key;
            var property = Find(key, path);
            if (property.Kind != SnapshotPropertyKind.ScalarSet)
            {
                throw new InvalidOperationException($"Expected a scalar set creation property at '{path}'.");
            }
            yield return new(instance, property, values, path);
        }
        foreach (var (key, entries) in creation.MapProperties)
        {
            yield return MapField(key, entries, typed: false);
        }
        foreach (var (key, entries) in creation.TypedMapProperties)
        {
            yield return MapField(key, entries, typed: true);
        }

        SnapshotProperty Find(string key, string path)
        {
            if (!properties.TryGetValue(key, out var property))
                throw new InvalidOperationException($"Unknown journal creation property '{path}'.");
            if (binding.HasInboundPropertyName && binding.PropertyName(instance, key) != property.ModelName)
                throw new InvalidOperationException($"Invalid journal creation property '{path}'.");
            return property;
        }

        CreationField MapField(string key, IReadOnlyList<JournalMapEntry> entries, bool typed)
        {
            string path = creation.Id + "." + key;
            var property = Find(key, path);
            if (!property.IsMap || typed == (property.KeyType == typeof(string)))
            {
                throw new InvalidOperationException($"Journal map creation key kind differs at '{path}'.");
            }
            return new(instance, property, [], path, entries);
        }
    }

    private void BuildImmutableCreations(Dictionary<string, CreationDefinition> pending)
    {
        var immutable = pending.Values.Where(definition => definition.Type.IsImmutable).ToArray();
        var ready = new Queue<CreationDefinition>();
        var unresolved = new Dictionary<string, int>(StringComparer.Ordinal);
        var dependents = new Dictionary<string, List<CreationDefinition>>(StringComparer.Ordinal);
        foreach (var definition in immutable)
        {
            var dependencies = definition.Record.Properties.Values.SelectMany(values => values)
                .Where(value => value.IsImmutableReference && pending.TryGetValue(value.Text!, out var target) && target.Type.IsImmutable)
                .Select(value => value.Text!).ToHashSet(StringComparer.Ordinal);
            if (dependencies.Count == 0) ready.Enqueue(definition);
            unresolved.Add(definition.Record.Id, dependencies.Count);
            foreach (string dependency in dependencies)
            {
                if (!dependents.TryGetValue(dependency, out var next))
                {
                    next = new List<CreationDefinition>();
                    dependents.Add(dependency, next);
                }
                next.Add(definition);
            }
        }

        int built = 0;
        while (ready.TryDequeue(out var definition))
        {
            BuildImmutable(definition);
            built++;
            if (!dependents.TryGetValue(definition.Record.Id, out var next)) continue;
            foreach (var dependent in next)
            {
                if (--unresolved[dependent.Record.Id] == 0) ready.Enqueue(dependent);
            }
        }
        if (built != immutable.Length)
        {
            throw new InvalidOperationException("Cyclic immutable journal creation records.");
        }
    }

    private void BuildImmutable(CreationDefinition definition)
    {
        var factory = definition.Type.CreateImmutable!;
        var fields = new Dictionary<string, object?>(StringComparer.Ordinal);
        var prototype = definition.Prototype ?? factory(fields);
        if (prototype is null || prototype.GetType() != definition.Type.RuntimeType)
        {
            throw new InvalidOperationException($"Invalid immutable journal prototype for '{definition.Record.Id}'.");
        }
        var expected = ResolveCreationFields(definition, prototype, requireComplete: true)
            .Select(field => (Field: field, Value: DecodeCreationField(field, immutableOwner: true))).ToArray();
        foreach (var (field, value) in expected)
        {
            fields.Add(field.Property.WireName, value);
        }
        object? initialized = null;
        var instance = binding.CreateImmutable(definition.Record, () =>
        {
            if (initialized is not null)
                throw new InvalidOperationException("The immutable journal initializer was called more than once.");
            return initialized = factory(new ReadOnlyDictionary<string, object?>(fields));
        });
        if (!ReferenceEquals(instance, initialized))
            throw new InvalidOperationException("The immutable journal factory must return the initialized instance.");
        RegisterCreated(definition, instance);
        RequireCreatedId(definition, instance);
        foreach (var (field, value) in expected)
        {
            VerifyCreationField(field with { Owner = instance }, value);
        }
    }

    private object? DecodeCreationField(CreationField field, bool immutableOwner)
    {
        var property = field.Property;
        if (property.IsMap)
        {
            var entries = DecodeMapEntries(field.MapEntries!, property, field.Path);
            RequireCollectionCapacity(property, entries.Length, field.Path);
            return Array.AsReadOnly(entries.Select(entry =>
                new KeyValuePair<object, object?>(entry.Key, entry.Value)).ToArray());
        }
        if (immutableOwner && field.Values.Any(value => value.IsReference && !value.IsImmutableReference))
        {
            throw new InvalidOperationException($"Mutable reference in immutable journal creation at '{field.Path}'.");
        }
        if (!property.IsCollection)
        {
            if (field.Values.Count != 1)
            {
                throw new InvalidOperationException($"Expected one journal creation value at '{field.Path}'.");
            }
            return Decode(field.Values[0], property, field.Path);
        }

        RequireCollectionCapacity(property, field.Values.Count, field.Path);
        var values = field.Values.Select(value => Decode(value, property, field.Path)).ToArray();
        if (property.HasUniqueMembers && new HashSet<object?>(values, CreationComparer(property)).Count != values.Length)
        {
            throw new InvalidOperationException($"Duplicate journal creation member at '{field.Path}'.");
        }
        return Array.AsReadOnly(values);
    }

    private static void RequireDistinctContainment(IEnumerable<(CreationField Field, object? Value)> relationships)
    {
        var owners = new HashSet<object>(ReferenceEqualityComparer.Instance);
        foreach (var (field, value) in relationships.Where(item => item.Field.Property.IsContainment))
        {
            var children = field.Property.IsCollection ? (IReadOnlyList<object?>)value! : new[] { value };
            if (children.Any(child => child is not null && !owners.Add(child)))
            {
                throw new InvalidOperationException($"Duplicate journal creation containment at '{field.Path}'.");
            }
        }
    }

    private static void ApplyCreationField(CreationField field, object? expected)
    {
        if (field.Property.IsMap)
        {
            if (field.Property.Get(field.Owner) is not IJournalMap map)
            {
                throw new InvalidOperationException($"Unsupported journal creation map at '{field.Path}'.");
            }
            var desired = MapCreationEntries(expected).ToArray();
            var currentEntries = map.Entries.ToArray();
            if (!SameMapEntries(currentEntries, desired))
            {
                map.ApplyEntries(currentEntries, []);
                map.ApplyEntries([], desired);
            }
            VerifyCreationField(field, expected);
            return;
        }
        if (!field.Property.IsCollection)
        {
            var setter = field.Property.Set ?? throw new InvalidOperationException($"Journal creation cannot set '{field.Path}'.");
            setter(field.Owner, expected);
            VerifyCreationField(field, expected);
            return;
        }

        var current = field.Property.Get(field.Owner);
        var values = (IReadOnlyList<object?>)expected!;
        if (current is IJournalSequence sequence)
        {
            sequence.Replace(values);
        }
        else if (current is IJournalMembership membership && current is IEnumerable members)
        {
            var desired = new HashSet<object?>(values, CreationComparer(field.Property));
            membership.ApplyMembership(members.Cast<object>().Where(value => !desired.Contains(value)).ToArray(),
                values.Cast<object>().Where(value => !membership.ContainsMember(value)).ToArray());
        }
        else
        {
            throw new InvalidOperationException($"Unsupported journal creation collection at '{field.Path}'.");
        }
        VerifyCreationField(field, expected);
    }

    private static void VerifyCreationField(CreationField field, object? expected)
    {
        object? actual = field.Property.Get(field.Owner);
        var comparer = CreationComparer(field.Property);
        bool matches;
        if (field.Property.IsMap)
        {
            matches = actual is ISnapshotScalarMap map && SameMapEntries(map.Entries, MapCreationEntries(expected));
        }
        else if (field.Property.IsCollection)
        {
            var values = (IReadOnlyList<object?>)expected!;
            matches = actual is IEnumerable entries
                && (IsUnorderedSet(field.Property)
                    ? new HashSet<object?>(entries.Cast<object?>(), comparer).SetEquals(values)
                    : entries.Cast<object?>().SequenceEqual(values, comparer));
        }
        else
        {
            matches = comparer.Equals(actual, expected);
        }
        if (!matches)
        {
            throw new InvalidOperationException($"Journal creation did not initialize '{field.Path}'.");
        }
    }

    private static IEqualityComparer<object?> CreationComparer(SnapshotProperty property) =>
        HasModelValues(property) ? ReferenceEqualityComparer.Instance : ScalarEquality<object?>.Instance;

    private static bool IsUnorderedSet(SnapshotProperty property) => property.IsUnorderedSet;

    private static IEnumerable<(object Key, object? Value)> MapCreationEntries(object? decoded) =>
        ((IReadOnlyList<KeyValuePair<object, object?>>)decoded!).Select(entry => (entry.Key, entry.Value));

    private static bool SameMapEntries(IEnumerable<(object Key, object? Value)> left,
        IEnumerable<(object Key, object? Value)> right) =>
        left.SequenceEqual(right, MapEntryComparer.Instance);

    private sealed class MapEntryComparer : IEqualityComparer<(object Key, object? Value)>
    {
        internal static readonly MapEntryComparer Instance = new();
        private static readonly ScalarEquality<object?> Scalars = ScalarEquality<object?>.Instance;

        public bool Equals((object Key, object? Value) x, (object Key, object? Value) y) =>
            Scalars.Equals(x.Key, y.Key) && Scalars.Equals(x.Value, y.Value);

        public int GetHashCode((object Key, object? Value) entry) =>
            HashCode.Combine(Scalars.GetHashCode(entry.Key), entry.Value is null ? 0 : Scalars.GetHashCode(entry.Value));
    }

    private sealed record CreationDefinition(JournalCreation Record, SnapshotType Type, object? Prototype);
    private sealed record CreationField(object Owner, SnapshotProperty Property, IReadOnlyList<JournalValue> Values,
        string Path, IReadOnlyList<JournalMapEntry>? MapEntries = null);
}
