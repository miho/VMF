using System.Buffers;
using System.Collections;
using System.Text;
using System.Text.Json;

namespace Vmf.Runtime;

public static partial class SnapshotJson
{
    private sealed class SnapshotWriter(SnapshotRegistry registry, Profile profile)
    {
        private readonly Dictionary<object, Definition> definitions = new(ReferenceEqualityComparer.Instance);
        private readonly List<Definition> mutableOrder = new();
        private readonly List<Definition> immutableOrder = new();
        private readonly HashSet<object> active = new(ReferenceEqualityComparer.Instance);
        private readonly List<MutableReference> references = new();
        private readonly HashSet<object> targets = new(ReferenceEqualityComparer.Instance);
        private readonly Dictionary<object, int> ids = new(ReferenceEqualityComparer.Instance);
        private readonly HashSet<object> writtenIds = new(ReferenceEqualityComparer.Instance);

        internal string Write(object root, Type expectedType)
        {
            var definition = Capture(root, expectedType, "$");
            foreach (var reference in references)
            {
                if (!definitions.ContainsKey(reference.Target))
                {
                    throw Invalid(reference.Path, "Reference target is outside the root containment tree");
                }
                targets.Add(reference.Target);
            }

            if (profile == Profile.References)
            {
                foreach (var node in mutableOrder.Concat(immutableOrder))
                {
                    if (targets.Contains(node.Instance))
                    {
                        ids.Add(node.Instance, ids.Count + 1);
                    }
                }
            }

            var buffer = new ArrayBufferWriter<byte>();
            using var writer = new Utf8JsonWriter(buffer, new JsonWriterOptions { MaxDepth = MaxDocumentDepth });
            WriteDefinition(writer, definition, expectedType);
            writer.Flush();
            return Encoding.UTF8.GetString(buffer.WrittenSpan);
        }

        private Definition Capture(object value, Type expectedType, string path, RepeatLocation? repeat = null)
        {
            var type = registry.TypeForClass(value.GetType(), path);
            if (profile == Profile.Plain && type.RuntimeType != expectedType)
            {
                throw Invalid(path, $"Plain JSON requires exact type '{expectedType}', got '{type.RuntimeType}'");
            }

            if (definitions.TryGetValue(value, out var existing))
            {
                if (!type.IsImmutable)
                {
                    if (repeat is null || existing.Repeat is not { } first
                        || !ReferenceEquals(first.Collection, repeat.Collection) || first.Property != repeat.Property
                        || active.Contains(value))
                    {
                        throw Invalid(path, "Object occurs more than once in the containment tree");
                    }
                    return existing;
                }
                if (active.Contains(value))
                {
                    throw Invalid(path, "Cyclic immutable definition");
                }
                targets.Add(value);
                return existing;
            }

            var fields = new List<FieldValue>();
            var node = new Definition(value, type, fields, path, repeat);
            definitions.Add(value, node);
            active.Add(value);
            if (type.IsImmutable)
            {
                immutableOrder.Add(node);
            }
            else
            {
                mutableOrder.Add(node);
            }

            foreach (var property in type.Properties)
            {
                string fieldPath = ChildPath(path, property.WireName);
                object? field = property.Get(value);
                if (profile == Profile.TypedValues && property.IsReference
                    && !ProjectReferenceByValue(property, field, fieldPath, out field)) continue;
                if (property.IsMap)
                {
                    fields.Add(new FieldValue(property, CaptureMap(field, property, fieldPath)));
                }
                else if (property.IsCollection)
                {
                    var members = CaptureMembers(field, property, fieldPath, type.IsImmutable);
                    if (profile == Profile.References || !property.IsReference || members.Count != 0
                        || registry.IsImmutableModelType(property.ValueType))
                    {
                        fields.Add(new FieldValue(property, members));
                    }
                }
                else if (field is not null || property.WritesNull())
                {
                    fields.Add(new FieldValue(property,
                        field is null ? null : CaptureValue(field, property, fieldPath, type.IsImmutable)));
                }
            }

            active.Remove(value);
            return node;
        }

        private bool ProjectReferenceByValue(SnapshotProperty property, object? value, string path, out object? projected)
        {
            projected = value;
            if (value is null) return false;
            if (!property.IsCollection) return IsImmutableTarget(value, property, path);
            if (value is not IEnumerable collection) throw Invalid(path, "Expected a model collection");

            var members = new List<object?>();
            bool hasImmutableMember = false;
            foreach (object? member in collection)
            {
                if (member is not null)
                {
                    if (!IsImmutableTarget(member, property, IndexPath(path, members.Count)))
                    {
                        if (property.HasUniqueMembers)
                        {
                            throw Invalid(path, "Mutable reference sets require reference-aware JSON");
                        }
                        // A legacy ordinary list projects as a whole; retaining only its immutable slots would change positions.
                        return false;
                    }
                    hasImmutableMember = true;
                }
                members.Add(member);
            }
            projected = members;
            return hasImmutableMember || registry.IsImmutableModelType(property.ValueType);
        }

        private bool IsImmutableTarget(object value, SnapshotProperty property, string path)
        {
            RequireType(value, property.ValueType, path);
            return registry.TypeForClass(value.GetType(), path).IsImmutable;
        }

        private List<object?> CaptureMembers(object? value, SnapshotProperty property, string path, bool immutableOwner)
        {
            if (value is not IEnumerable collection)
            {
                throw Invalid(path, "Expected a model collection");
            }

            var members = new List<object?>();
            var repeat = property.Kind == SnapshotPropertyKind.ContainmentSequence ? new RepeatLocation(collection, property) : null;
            var comparer = property.IsScalarCollection ? (IEqualityComparer<object>)ScalarEquality<object>.Instance
                : ReferenceEqualityComparer.Instance;
            var seen = property.HasUniqueMembers ? new HashSet<object>(comparer) : null;
            foreach (object? member in collection)
            {
                string memberPath = IndexPath(path, members.Count);
                if (seen is not null && (member is null || !seen.Add(member)))
                {
                    string detail = property.IsScalarCollection ? "value in scalar collection" : "identity in model collection";
                    throw Invalid(memberPath, "Null or duplicate " + detail);
                }
                if (property.IsScalarCollection && member is null && !property.AllowNullElements)
                {
                    throw Invalid(memberPath, "Null element in scalar collection");
                }
                members.Add(member is null ? null : CaptureValue(member, property, memberPath, immutableOwner, repeat));
            }
            if (property.IsScalarCollection)
            {
                CheckScalarCapacity(property, members.Count, path);
            }
            return members;
        }

        private static List<ScalarMapEntry> CaptureMap(object? value, SnapshotProperty property, string path)
        {
            if (value is not ISnapshotScalarMap map)
            {
                throw Invalid(path, "Expected a managed scalar map");
            }
            var entries = new List<ScalarMapEntry>();
            foreach (var entry in map.Entries)
            {
                string entryPath = IndexPath(path, entries.Count);
                if (property.KeyType == typeof(string) && entry.Key is string name)
                {
                    entryPath = ChildPath(path, name);
                }
                CaptureScalar(entry.Key, property.KeyType!, entryPath);
                if (entry.Value is not null)
                {
                    CaptureScalar(entry.Value, property.ValueType, entryPath);
                }
                else if (property.ValueType.IsValueType && Nullable.GetUnderlyingType(property.ValueType) is null)
                {
                    throw Invalid(entryPath, "Null value in a scalar map with a non-nullable value type");
                }
                entries.Add(new(entry.Key, entry.Value));
            }
            CheckScalarCapacity(property, entries.Count, path);
            return entries;
        }

        private object CaptureValue(object value, SnapshotProperty property, string path, bool immutableOwner,
            RepeatLocation? repeat = null)
        {
            if (property.ExternalType is not null)
            {
                return new EmbeddedValue(registry.ExternalValues.Require(property, path).Encode(value, path));
            }
            if (property.IsUntypedObject)
            {
                return new EmbeddedValue(CaptureUntypedValue(value, path));
            }
            if (property.Kind == SnapshotPropertyKind.Scalar || property.IsScalarCollection)
            {
                CaptureScalar(value, property.ValueType, path);
                return value;
            }

            RequireType(value, property.ValueType, path);
            var type = registry.TypeForClass(value.GetType(), path);
            if (property.IsContainment)
            {
                if (type.IsImmutable)
                {
                    throw Invalid(path, "An immutable value cannot participate in containment");
                }
                if (profile == Profile.TypedValues && property.Schema.KeyPropertyName is { } keyName)
                {
                    var key = type.Properties.Single(field => field.ModelName == keyName);
                    string keyPath = ChildPath(path, key.WireName);
                    if (key.IsReference && key.Get(value) is { } target && !IsImmutableTarget(target, key, keyPath))
                    {
                        throw Invalid(keyPath, "A mutable model reference key requires reference-aware JSON");
                    }
                }
                return Capture(value, property.ValueType, path, repeat);
            }

            if (type.IsImmutable)
            {
                if (property.HasUniqueMembers)
                {
                    targets.Add(value);
                }
                return Capture(value, property.ValueType, path);
            }

            if (profile != Profile.References || immutableOwner)
            {
                string owner = immutableOwner ? "An immutable object" : "VMF JSON without references";
                throw Invalid(path, $"{owner} cannot represent a mutable model reference");
            }
            var reference = new MutableReference(value, path);
            references.Add(reference);
            return reference;
        }

        private static void CaptureScalar(object value, Type expectedType, string path)
        {
            RequireType(value, expectedType, path);
            if (value is string text)
            {
                CheckText(text, path);
            }
        }

        private void WriteDefinition(Utf8JsonWriter writer, Definition node, Type expectedType)
        {
            bool hasId = ids.TryGetValue(node.Instance, out int id);
            if (hasId && !writtenIds.Add(node.Instance))
            {
                if (!node.Type.IsImmutable)
                {
                    throw Invalid(node.Path, "A repeated containment definition cannot also carry a VMF reference ID");
                }
                writer.WriteStringValue(FormatId(id));
                return;
            }

            writer.WriteStartObject();
            if (profile != Profile.Plain && (node.Type.WriteType || expectedType != node.Type.RuntimeType))
            {
                writer.WriteString(TypeField, registry.OutputName(node.Type));
            }
            if (hasId)
            {
                writer.WriteString(IdField, FormatId(id));
            }

            foreach (var field in node.Fields)
            {
                var property = field.Property;
                writer.WritePropertyName(property.WireName);
                if (property.IsMap)
                {
                    WriteMap(writer, (List<ScalarMapEntry>)field.Value!, property);
                }
                else if (property.IsCollection)
                {
                    var members = (List<object?>)field.Value!;
                    writer.WriteStartArray();
                    var sequence = profile == Profile.References && property.Kind == SnapshotPropertyKind.ReferenceSet
                        ? members.OrderBy(ReferencePosition)
                        : (IEnumerable<object?>)members;
                    foreach (var member in sequence)
                    {
                        WriteValue(writer, member, property.ValueType);
                    }
                    writer.WriteEndArray();
                }
                else
                {
                    WriteValue(writer, field.Value, property.ValueType);
                }
            }
            writer.WriteEndObject();
        }

        private void WriteMap(Utf8JsonWriter writer, List<ScalarMapEntry> entries, SnapshotProperty property)
        {
            if (property.KeyType == typeof(string))
            {
                writer.WriteStartObject();
                foreach (var entry in entries)
                {
                    writer.WritePropertyName((string)entry.Key);
                    WriteValue(writer, entry.Value, property.ValueType);
                }
                writer.WriteEndObject();
                return;
            }

            writer.WriteStartArray();
            foreach (var entry in entries)
            {
                writer.WriteStartObject();
                writer.WritePropertyName("key");
                WriteScalar(writer, entry.Key);
                writer.WritePropertyName("value");
                WriteValue(writer, entry.Value, property.ValueType);
                writer.WriteEndObject();
            }
            writer.WriteEndArray();
        }

        private void WriteValue(Utf8JsonWriter writer, object? value, Type expectedType)
        {
            switch (value)
            {
                case null:
                    writer.WriteNullValue();
                    break;
                case Definition node:
                    WriteDefinition(writer, node, expectedType);
                    break;
                case MutableReference reference:
                    writer.WriteStringValue(FormatId(ids[reference.Target]));
                    break;
                case EmbeddedValue embedded:
                    embedded.Content.WriteTo(writer);
                    break;
                default:
                    WriteScalar(writer, value);
                    break;
            }
        }

        private int ReferencePosition(object? member) => member switch
        {
            Definition node => ids[node.Instance],
            MutableReference reference => ids[reference.Target],
            _ => throw new InvalidOperationException("Expected an identified reference set member."),
        };

        private sealed record Definition(object Instance, SnapshotType Type, List<FieldValue> Fields, string Path,
            RepeatLocation? Repeat);
        private sealed record RepeatLocation(IEnumerable Collection, SnapshotProperty Property);
        private sealed record FieldValue(SnapshotProperty Property, object? Value);
        private sealed record MutableReference(object Target, string Path);
        private sealed record EmbeddedValue(JsonElement Content);
    }
}
