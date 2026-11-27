using System.Collections;
using System.Collections.ObjectModel;
using System.Text.Json;

namespace Vmf.Runtime;

public static partial class SnapshotJson
{
    private sealed class SnapshotReader(SnapshotRegistry registry, Profile profile, Func<object, bool>? isLiveInstance = null)
    {
        private readonly Dictionary<string, Definition> definitions = new(StringComparer.Ordinal);
        private readonly List<PendingField> pending = new();
        private readonly List<PendingKeyedContainment> keyed = new();

        internal object Read(JsonElement json, Type rootType)
        {
            var definition = ReadDefinition(json, rootType, "$");
            var root = Build(definition);
            var resolved = new List<object?>(pending.Count);
            // Inline mutable values can introduce their own pending references when constructed.
            for (int i = 0; i < pending.Count; i++) resolved.Add(ResolveField(pending[i].Field, immutableOwner: false));
            List<ReciprocalHydration.Field>? reciprocals = null;
            for (int i = 0; i < pending.Count; i++)
            {
                var item = pending[i];
                var property = item.Field.Property;
                if (property.Reciprocal is not null)
                {
                    (reciprocals ??= []).Add(new(item.Owner, property, resolved[i]));
                }
                else
                {
                    Apply(item.Owner, property, resolved[i]);
                }
            }
            if (reciprocals is not null)
            {
                try
                {
                    ReciprocalHydration.Apply(reciprocals);
                }
                catch (Exception error) when (error is ArgumentException or InvalidOperationException)
                {
                    throw new JsonException("Invalid reciprocal references in the VMF snapshot.", error);
                }
            }
            foreach (var containment in keyed)
            {
                try
                {
                    containment.Collection.HydrateMembers(containment.Members);
                }
                catch (ArgumentException error)
                {
                    throw new JsonException($"Invalid keyed containment at {containment.Path}.", error);
                }
            }
            return root;
        }

        private Definition ReadDefinition(JsonElement node, Type expectedType, string path)
        {
            if (node.ValueKind != JsonValueKind.Object)
            {
                throw Invalid(path, "Expected a VMF model object");
            }

            if (profile == Profile.Plain && (node.TryGetProperty(TypeField, out _) || node.TryGetProperty(IdField, out _)))
            {
                string field = node.TryGetProperty(TypeField, out _) ? TypeField : IdField;
                throw Invalid(ChildPath(path, field), "Plain JSON cannot contain VMF graph metadata");
            }
            if (profile == Profile.TypedValues && node.TryGetProperty(IdField, out _))
            {
                throw Invalid(ChildPath(path, IdField), "VMF JSON by value cannot contain a model ID");
            }
            var type = ReadType(node, expectedType, path);
            string? id = node.TryGetProperty(IdField, out var idField)
                ? ReadId(idField, ChildPath(path, IdField))
                : null;
            var definition = new Definition(type, path, id);
            if (id is not null && !definitions.TryAdd(id, definition))
            {
                throw Invalid(path, $"Duplicate {IdField} '{id}'");
            }

            foreach (var property in type.Properties)
            {
                if (node.TryGetProperty(property.WireName, out var field))
                {
                    string fieldPath = ChildPath(path, property.WireName);
                    definition.Fields.Add(new FieldValue(property, fieldPath, ReadField(field, property, fieldPath)));
                }
                else if (property.StageBuilderDefault is not null)
                {
                    definition.Fields.Add(new FieldValue(property, ChildPath(path, property.WireName), null, UsesStagedDefault: true));
                }
                else if (!type.IsImmutable && ModelProperty.CopiesManagedDefault(property) && !property.Schema.HasExecutableDefault)
                {
                    // Java builders stage empty managed collections in the absence of a declared default.
                    object empty = property.IsMap ? Array.Empty<ScalarMapEntry>() : Array.Empty<object?>();
                    definition.Fields.Add(new FieldValue(property, path, empty));
                }
            }
            if (type.IsImmutable && type.StageImmutableBuilder is not null && definition.Fields.Count > 1)
            {
                // Scoped to staged immutable builders, whose nested providers follow the input field order.
                var positions = new Dictionary<string, int>(StringComparer.Ordinal);
                foreach (var field in node.EnumerateObject()) positions.TryAdd(field.Name, positions.Count);
                definition.Fields.Sort((left, right) =>
                    positions[left.Property.WireName].CompareTo(positions[right.Property.WireName]));
            }
            if (profile == Profile.Plain)
            {
                var known = type.Properties.Select(property => property.WireName).ToHashSet(StringComparer.Ordinal);
                foreach (var field in node.EnumerateObject())
                {
                    if (!known.Contains(field.Name))
                    {
                        CheckFields(field.Value, ChildPath(path, field.Name), plain: true);
                    }
                }
            }
            return definition;
        }

        private SnapshotType ReadType(JsonElement node, Type expectedType, string path)
        {
            if (!node.TryGetProperty(TypeField, out var discriminator))
            {
                return registry.TypeForClass(expectedType, path);
            }

            string fieldPath = ChildPath(path, TypeField);
            string? name = discriminator.ValueKind == JsonValueKind.String ? ReadText(discriminator, fieldPath) : null;
            if (string.IsNullOrWhiteSpace(name))
            {
                throw Invalid(fieldPath, "Expected a non-empty VMF type name");
            }
            return registry.TypeForName(name, expectedType, path);
        }

        private object? ReadField(JsonElement field, SnapshotProperty property, string path)
        {
            if (property.Kind == SnapshotPropertyKind.Scalar)
            {
                return ReadScalarValue(field, property, path);
            }
            if (property.IsMap)
            {
                return ReadMap(field, property, path);
            }
            if (property.IsScalarCollection)
            {
                return ReadScalarMembers(field, property, path);
            }
            if (!property.IsCollection)
            {
                return ReadMember(field, property, path);
            }

            if (field.ValueKind != JsonValueKind.Array)
            {
                throw Invalid(path, "Expected an array of model relationships");
            }
            var members = new object?[field.GetArrayLength()];
            int index = 0;
            foreach (var member in field.EnumerateArray())
            {
                members[index] = ReadMember(member, property, IndexPath(path, index));
                index++;
            }
            return members;
        }

        private object? ReadScalarValue(JsonElement value, SnapshotProperty property, string path) =>
            property.ExternalType is not null && value.ValueKind != JsonValueKind.Null
                ? registry.ExternalValues.Require(property, path).Decode(value, path)
                : ReadScalar(value, property.ValueType, path);

        private object?[] ReadScalarMembers(JsonElement field, SnapshotProperty property, string path)
        {
            if (field.ValueKind != JsonValueKind.Array)
            {
                throw Invalid(path, "Expected an array of scalar values");
            }
            CheckScalarCapacity(property, field.GetArrayLength(), path);
            var members = new object?[field.GetArrayLength()];
            var seen = property.HasUniqueMembers ? new HashSet<object>(ScalarEquality<object>.Instance) : null;
            int index = 0;
            foreach (var member in field.EnumerateArray())
            {
                string memberPath = IndexPath(path, index);
                if (member.ValueKind == JsonValueKind.Null && !property.AllowNullElements)
                {
                    throw Invalid(memberPath, "Null element in scalar collection");
                }
                object? scalar = ReadScalarValue(member, property, memberPath);
                if (seen is not null && !seen.Add(scalar!))
                {
                    throw Invalid(memberPath, "Duplicate value in scalar collection");
                }
                members[index++] = scalar;
            }
            return members;
        }

        private static List<ScalarMapEntry> ReadMap(JsonElement field, SnapshotProperty property, string path)
        {
            var entries = new List<ScalarMapEntry>();
            if (property.KeyType == typeof(string))
            {
                if (field.ValueKind != JsonValueKind.Object)
                {
                    throw Invalid(path, "Expected a JSON object for a string-key scalar map");
                }
                foreach (var entry in field.EnumerateObject())
                {
                    entries.Add(new(entry.Name, ReadScalar(entry.Value, property.ValueType, ChildPath(path, entry.Name))));
                }
            }
            else
            {
                if (field.ValueKind != JsonValueKind.Array)
                {
                    throw Invalid(path, "Expected an array of typed scalar map entries");
                }
                CheckScalarCapacity(property, field.GetArrayLength(), path);
                var seen = new HashSet<object>(ScalarEquality<object>.Instance);
                foreach (var entry in field.EnumerateArray())
                {
                    string entryPath = IndexPath(path, entries.Count);
                    if (entry.ValueKind != JsonValueKind.Object || !entry.TryGetProperty("key", out var key)
                        || !entry.TryGetProperty("value", out var value) || entry.EnumerateObject().Count() != 2)
                    {
                        throw Invalid(entryPath, "Expected a map entry with key and value");
                    }
                    string keyPath = ChildPath(entryPath, "key");
                    object mapKey = ReadScalar(key, property.KeyType!, keyPath)!;
                    if (!seen.Add(mapKey))
                    {
                        throw Invalid(keyPath, "Duplicate scalar map key");
                    }
                    entries.Add(new(mapKey, ReadScalar(value, property.ValueType, ChildPath(entryPath, "value"))));
                }
            }
            CheckScalarCapacity(property, entries.Count, path);
            return entries;
        }

        private object? ReadMember(JsonElement member, SnapshotProperty property, string path)
        {
            if (member.ValueKind == JsonValueKind.Null)
            {
                if (property.HasUniqueMembers)
                {
                    throw Invalid(path, "Null member in a unique model collection");
                }
                return null;
            }

            if (property.IsReference && member.ValueKind != JsonValueKind.Object)
            {
                if (profile != Profile.References)
                {
                    throw Invalid(path, "VMF JSON without references cannot contain a model reference ID");
                }
                return new ReferenceId(ReadId(member, path));
            }

            var nested = ReadDefinition(member, property.ValueType, path);
            if (property.IsContainment && nested.Type.IsImmutable)
            {
                throw Invalid(path, "An immutable value cannot participate in containment");
            }
            if (property.IsReference && !nested.Type.IsImmutable)
            {
                if (profile != Profile.TypedValues)
                {
                    throw Invalid(path, "A mutable model reference must use an ID in reference-aware JSON");
                }
                if (property.HasUniqueMembers) throw Invalid(path, "Mutable reference sets require reference-aware JSON");
            }
            return nested;
        }

        private object Build(Definition definition)
        {
            if (definition.Instance is not null)
            {
                return definition.Instance;
            }

            if (definition.Type.IsImmutable)
            {
                if (definition.Building)
                {
                    throw Invalid(definition.Path, $"Cyclic immutable definition '{definition.Id}'");
                }
                definition.Building = true;
                var complete = definition.Type.StageImmutableBuilder?.Invoke() ?? definition.Type.CreateImmutable!;
                var fields = new Dictionary<string, object?>(StringComparer.Ordinal);
                foreach (var field in definition.Fields)
                {
                    fields.Add(field.Property.WireName, ResolveField(field, immutableOwner: true));
                }
                definition.Instance = CheckFactory(definition, complete(new ReadOnlyDictionary<string, object?>(fields)));
                definition.Building = false;
                return definition.Instance;
            }

            if (definition.Type.HasStagedBuilderDefaults) StageBuilderDefaults(definition.Fields);
            object result = CheckFactory(definition, definition.Type.Create!());
            if (isLiveInstance?.Invoke(result) == true)
            {
                throw new InvalidOperationException(
                    $"The snapshot factory for '{definition.Type.WireName}' reused a live model object at {definition.Path}.");
            }
            definition.Instance = result;
            foreach (var field in definition.Fields)
            {
                if (field.Property.IsReference)
                {
                    pending.Add(new PendingField(result, field));
                }
                else if (field.Property.IsContainment && field.Property.IsCollection
                    && field.Property.Get(result) is IKeyedContainment { HasKey: true } collection)
                {
                    var members = (IReadOnlyList<object?>)ResolveField(field, immutableOwner: false)!;
                    keyed.Add(new PendingKeyedContainment(collection, members, field.Path));
                }
                else
                {
                    Apply(result, field.Property, ResolveField(field, immutableOwner: false));
                }
            }
            return result;
        }

        private static void StageBuilderDefaults(List<FieldValue> fields)
        {
            // Java's model builder evaluates these defaults even when the wire supplies an override.
            for (int i = 0; i < fields.Count; i++)
            {
                var field = fields[i];
                if (field.Property.StageBuilderDefault is not { } stage) continue;
                object? prepared = stage();
                if (!field.UsesStagedDefault) continue;
                object? value = field.Property.IsMap
                    ? ((ISnapshotScalarMap)prepared!).Entries.Select(entry => new ScalarMapEntry(entry.Key, entry.Value)).ToArray()
                    : field.Property.IsScalarCollection ? ((IEnumerable)prepared!).Cast<object?>().ToArray() : prepared;
                fields[i] = field with { Value = value };
            }
        }

        private static object CheckFactory(Definition definition, object? result)
        {
            if (result is null || result.GetType() != definition.Type.RuntimeType)
            {
                throw Invalid(definition.Path,
                    $"Factory for '{definition.Type.WireName}' did not create the registered concrete type");
            }
            return result;
        }

        private object? ResolveField(FieldValue field, bool immutableOwner)
        {
            if (field.Property.IsMap)
            {
                if (!immutableOwner) return field.Value;
                var entries = (IReadOnlyList<ScalarMapEntry>)field.Value!;
                return Array.AsReadOnly(entries.Select(entry => new KeyValuePair<object, object?>(entry.Key, entry.Value)).ToArray());
            }
            if (field.Property.IsScalarCollection)
            {
                return immutableOwner ? Array.AsReadOnly((object?[])field.Value!) : field.Value;
            }
            if (!field.Property.IsCollection)
            {
                return ResolveMember(field.Value, field.Property, field.Path, immutableOwner);
            }

            var members = (object?[])field.Value!;
            var values = new object?[members.Length];
            var seen = new HashSet<object>(ReferenceEqualityComparer.Instance);
            for (int i = 0; i < members.Length; i++)
            {
                string path = IndexPath(field.Path, i);
                var target = ResolveMember(members[i], field.Property, path, immutableOwner);
                if (field.Property.HasUniqueMembers && (target is null || !seen.Add(target)))
                {
                    throw Invalid(path, "Duplicate reference identity in model collection");
                }
                values[i] = target;
            }
            return Array.AsReadOnly(values);
        }

        private object? ResolveMember(object? value, SnapshotProperty property, string path, bool immutableOwner)
        {
            Definition target;
            if (value is ReferenceId reference)
            {
                if (!definitions.TryGetValue(reference.Id, out var resolved))
                {
                    throw Invalid(path, $"Unresolved reference '{reference.Id}'");
                }
                target = resolved;
                if (!property.ValueType.IsAssignableFrom(target.Type.RuntimeType))
                {
                    throw Invalid(path, $"Wrong target type for reference '{reference.Id}'. Expected '{property.ValueType}', "
                        + $"got '{target.Type.RuntimeType}'");
                }
            }
            else if (value is Definition nested)
            {
                target = nested;
            }
            else
            {
                return value;
            }

            if (immutableOwner && !target.Type.IsImmutable)
            {
                throw Invalid(path, "An immutable object cannot refer to a mutable model object");
            }
            return Build(target);
        }

        private static void Apply(object owner, SnapshotProperty property, object? value)
        {
            if (property.IsMap)
            {
                if (property.Get(owner) is IJournalMap map)
                {
                    map.ApplyBuilderReplacement(((IReadOnlyList<ScalarMapEntry>)value!)
                        .Select(entry => (entry.Key, entry.Value)));
                    return;
                }
                foreach (var entry in (IReadOnlyList<ScalarMapEntry>)value!)
                {
                    property.Put!(owner, entry.Key, entry.Value);
                }
            }
            else if (property.IsCollection)
            {
                if (ReferenceCollectionDefaults.IsBound(property))
                {
                    ReferenceCollectionDefaults.RestoreMembers(property.Get(owner)!, (IReadOnlyList<object?>)value!);
                    return;
                }
                if (ModelProperty.CopiesManagedDefault(property)
                    && property.Get(owner) is { } managed
                    && managed is IManagedDefaultList or IJournalMembership)
                {
                    ManagedCollectionDefaults.RestoreMembers(managed, (IReadOnlyList<object?>)value!);
                    return;
                }
                foreach (object? member in (IReadOnlyList<object?>)value!)
                {
                    property.Add!(owner, member!);
                }
            }
            else
            {
                property.Set!(owner, value);
            }
        }

        private sealed class Definition(SnapshotType type, string path, string? id)
        {
            internal SnapshotType Type { get; } = type;
            internal string Path { get; } = path;
            internal string? Id { get; } = id;
            internal List<FieldValue> Fields { get; } = new();
            internal object? Instance { get; set; }
            internal bool Building { get; set; }
        }

        private sealed record FieldValue(SnapshotProperty Property, string Path, object? Value, bool UsesStagedDefault = false);
        private sealed record ReferenceId(string Id);
        private sealed record PendingKeyedContainment(IKeyedContainment Collection, IReadOnlyList<object?> Members, string Path);

        private sealed record PendingField(object Owner, FieldValue Field);
    }
}
