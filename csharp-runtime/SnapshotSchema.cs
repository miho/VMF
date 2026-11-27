using System.Text.Json;
using System.Text.Json.Nodes;

namespace Vmf.Runtime;

/// <summary>Generates a Draft-07 JSON Schema for a registered native VMF snapshot type.</summary>
/// <remarks>Reference resolution, keyed uniqueness, and other cross-object invariants remain runtime checks.</remarks>
public static class SnapshotSchema
{
    private const string Draft = "http://json-schema.org/draft-07/schema#";
    private static readonly JsonSerializerOptions Indented = new(JsonSerializerOptions.Default) { WriteIndented = true };

    public static string Generate<T>(SnapshotRegistry registry, SnapshotSchemaProfile profile = SnapshotSchemaProfile.TypedValues,
        IReadOnlyDictionary<Type, IReadOnlyList<JsonElement>>? typeConstraints = null) where T : class =>
        Generate(typeof(T), registry, profile, typeConstraints);

    public static string Generate(Type rootType, SnapshotRegistry registry, SnapshotSchemaProfile profile = SnapshotSchemaProfile.TypedValues,
        IReadOnlyDictionary<Type, IReadOnlyList<JsonElement>>? typeConstraints = null)
    {
        ArgumentNullException.ThrowIfNull(rootType);
        ArgumentNullException.ThrowIfNull(registry);
        if (!Enum.IsDefined(profile)) throw new ArgumentOutOfRangeException(nameof(profile));

        return new SchemaWriter(registry, profile, typeConstraints).Write(rootType);
    }

    private sealed class SchemaWriter(SnapshotRegistry registry, SnapshotSchemaProfile profile,
        IReadOnlyDictionary<Type, IReadOnlyList<JsonElement>>? typeConstraints)
    {
        private readonly SnapshotType[] types = registry.Types.OrderBy(type => type.WireName, StringComparer.Ordinal).ToArray();
        private readonly Dictionary<Type, SnapshotType[]> compatible = new();

        internal string Write(Type rootType)
        {
            var rootTypes = Candidates(rootType);
            registry.TryTypeForClass(rootType, out var root);
            if (root is null && (!rootType.IsAbstract || rootTypes.Length == 0))
            {
                throw new JsonException($"Unregistered VMF snapshot CLR type '{rootType}' at schema root.");
            }

            var title = rootType.Name.Split('`', 2)[0];
            if (root is not null)
            {
                var fullName = root.WireName.Split('<', 2)[0];
                title = fullName[(fullName.LastIndexOf('.') + 1)..];
            }
            var document = new JsonObject { ["$schema"] = Draft, ["title"] = title };
            if (root is not null && rootTypes.Length == 1)
            {
                foreach (var keyword in ObjectSchema(root)) document.Add(keyword.Key, keyword.Value?.DeepClone());
            }
            else
            {
                // The declared concrete root is the reader's fallback for an untagged object.
                var alternatives = new JsonArray();
                if (root is not null) alternatives.Add(Definition(root));
                foreach (var subtype in rootTypes.Where(type => type != root))
                {
                    alternatives.Add(new JsonObject
                    {
                        ["title"] = registry.OutputName(subtype),
                        ["allOf"] = new JsonArray(Definition(subtype), Required(SnapshotJson.TypeField)),
                    });
                }
                document["type"] = "object";
                document["oneOf"] = alternatives;
            }

            var definitions = new JsonObject();
            foreach (var type in types) definitions.Add(registry.OutputName(type), ObjectSchema(type));
            document["definitions"] = definitions;
            return document.ToJsonString(Indented);
        }

        private JsonObject ObjectSchema(SnapshotType type)
        {
            var properties = new JsonObject();
            var excludedReferences = new JsonObject();
            var required = new JsonArray();
            var schema = new JsonObject { ["type"] = "object", ["properties"] = properties };
            if (profile == SnapshotSchemaProfile.References)
            {
                properties[SnapshotJson.IdField] = IdSchema(nullable: false);
            }
            else
            {
                // Type metadata belongs to the typed-value profile. None belongs to plain model objects.
                schema["not"] = profile == SnapshotSchemaProfile.Plain
                    ? new JsonObject { ["anyOf"] = new JsonArray(Required(SnapshotJson.IdField), Required(SnapshotJson.TypeField)) }
                    : Required(SnapshotJson.IdField);
            }
            if (profile != SnapshotSchemaProfile.Plain)
            {
                properties[SnapshotJson.TypeField] = Discriminator(type);
            }

            int order = 0;
            foreach (var property in type.Properties)
            {
                if (!Included(property))
                {
                    excludedReferences[property.WireName] = AbsentReference(property);
                    continue;
                }
                var field = PropertySchema(property);
                if (!field.ContainsKey("propertyOrder")) field["propertyOrder"] = order;
                properties[property.WireName] = field;
                if (RequiresPresence(property)) required.Add(property.WireName);
                order++;
            }
            if (required.Count > 0) schema["required"] = required;
            if (excludedReferences.Count > 0)
            {
                // Keep omitted relationships out of the advertised projection while rejecting populated mutable references.
                AllOf(schema).Add(new JsonObject { ["properties"] = excludedReferences });
            }
            if (typeConstraints is not null && typeConstraints.TryGetValue(type.RuntimeType, out var assertions) && assertions.Count > 0)
            {
                var allOf = AllOf(schema);
                foreach (var assertion in assertions)
                {
                    if (assertion.ValueKind is not (JsonValueKind.Object or JsonValueKind.True or JsonValueKind.False))
                    {
                        throw new ArgumentException("A Draft-07 type assertion must be an object or boolean.", nameof(typeConstraints));
                    }
                    allOf.Add(Node(assertion));
                }
            }
            return schema;
        }

        private JsonObject PropertySchema(SnapshotProperty property)
        {
            int upper = UpperBound(property);
            var schema = upper == 0 && !property.IsCollection && !property.IsMap ? TypeSchema("null") : property.Kind switch
            {
                SnapshotPropertyKind.Scalar => ScalarProperty(property, property.Schema.LowerBound == 0 && IsNullable(property.ValueType)),
                SnapshotPropertyKind.Containment => Optional(Inline(property.ValueType), property.Schema.LowerBound == 0),
                SnapshotPropertyKind.Reference => Reference(property.ValueType, property.Schema.LowerBound == 0 || RestoresOpposite(property)),
                SnapshotPropertyKind.ScalarMap => MapSchema(property),
                _ => Collection(property),
            };

            if (property.Schema.Default is { } supplied)
            {
                SetMetadata(schema, "default", Node(supplied));
            }
            else if (!property.Schema.HasExecutableDefault && ImplicitDefault(property) is { } inferred)
            {
                SetMetadata(schema, "default", inferred);
            }
            if (property.Schema.Annotations is { } annotations) Conjoin(schema, annotations);
            if (property.Schema.Assertions is { } assertions) Conjoin(schema, assertions);
            return schema;
        }

        private JsonObject Collection(SnapshotProperty property)
        {
            bool nullableModelSlots = property.HasNullableModelSlots;
            var targets = property.IsScalarCollection ? [] : Candidates(property.ValueType);
            var items = property.IsScalarCollection ? ScalarProperty(property, property.AllowNullElements)
                : property.IsContainment ? Optional(Inline(property.ValueType), nullableModelSlots)
                : Reference(property.ValueType, nullableModelSlots);
            if (property.Schema.KeyPropertyName is { } key)
            {
                var keyAssertions = new JsonArray();
                foreach (var target in targets)
                {
                    var entry = target.Properties.SingleOrDefault(field => field.ModelName == key)
                        ?? throw new InvalidOperationException($"Unknown keyed schema property '{target.WireName}.{key}'.");
                    if (!Included(entry)) continue;
                    var assertion = new JsonObject
                    {
                        ["required"] = new JsonArray(entry.WireName),
                        ["properties"] = new JsonObject { [entry.WireName] = new JsonObject { ["not"] = TypeSchema("null") } },
                    };
                    if (targets.Length > 1 && profile != SnapshotSchemaProfile.Plain)
                    {
                        var properties = (JsonObject)assertion["properties"]!;
                        properties[SnapshotJson.TypeField] = new JsonObject { ["const"] = registry.OutputName(target) };
                    }
                    keyAssertions.Add(assertion);
                }
                var shape = new JsonObject { ["anyOf"] = keyAssertions };
                if (keyAssertions.Count == 0) shape = Impossible();
                if (keyAssertions.Count == 1) shape = (JsonObject)keyAssertions[0]!.DeepClone();
                items = new JsonObject { ["allOf"] = new JsonArray(items, shape) };
            }

            int upper = UpperBound(property);
            int lower = property.Schema.LowerBound;
            if (nullableModelSlots && upper == 0) items = TypeSchema("null");
            var array = new JsonObject { ["type"] = "array", ["items"] = items };
            AddBounds(array, "Items", RestoresOpposite(property) ? 0 : lower, nullableModelSlots ? -1 : upper);
            if (nullableModelSlots && lower > 0 && !RestoresOpposite(property))
            {
                // Draft-07 can require a non-null member, but cannot count all non-null slots.
                array["contains"] = new JsonObject { ["not"] = TypeSchema("null") };
            }
            var scalarType = Nullable.GetUnderlyingType(property.ValueType) ?? property.ValueType;
            if (property.IsScalarCollection && property.HasUniqueMembers && !IsFloating(scalarType)
                || profile == SnapshotSchemaProfile.References && property.IsReference && property.HasUniqueMembers
                    && targets.All(target => !target.IsImmutable))
            {
                array["uniqueItems"] = true;
            }
            return array;
        }

        private JsonObject ScalarProperty(SnapshotProperty property, bool nullable)
        {
            if (property.ExternalType is null) return Scalar(property.ValueType, nullable);
            var codec = registry.ExternalValues.Require(property, "schema property '" + property.WireName + "'");
            return Optional((JsonObject)Node(codec.Schema)!, nullable);
        }

        private static JsonObject MapSchema(SnapshotProperty property)
        {
            bool objectMap = property.KeyType == typeof(string);
            var member = Scalar(property.ValueType, nullable: true);
            var schema = objectMap ? new JsonObject { ["type"] = "object", ["additionalProperties"] = member }
                : new JsonObject
                {
                    ["type"] = "array",
                    ["items"] = new JsonObject
                    {
                        ["type"] = "object",
                        ["required"] = new JsonArray("key", "value"),
                        ["additionalProperties"] = false,
                        ["properties"] = new JsonObject
                        {
                            ["key"] = Scalar(property.KeyType!, nullable: false),
                            ["value"] = member,
                        },
                    },
                };
            AddBounds(schema, objectMap ? "Properties" : "Items", property.Schema.LowerBound, UpperBound(property));
            return schema;
        }

        private JsonObject Inline(Type declaredType)
        {
            var candidates = Candidates(declaredType);
            if (candidates.Length == 0) return Impossible();
            if (candidates.Length == 1 && candidates[0].RuntimeType == declaredType) return Definition(candidates[0]);
            return new JsonObject
            {
                ["oneOf"] = new JsonArray(candidates.Select(type => ModelOption(type, type.RuntimeType != declaredType)).ToArray()),
            };
        }

        private JsonObject Reference(Type declaredType, bool nullable)
        {
            var candidates = Candidates(declaredType);
            var options = new JsonArray();
            if (profile == SnapshotSchemaProfile.References) options.Add(IdSchema(nullable));
            foreach (var type in candidates.Where(type => type.IsImmutable))
            {
                options.Add(candidates.Length == 1 && type.RuntimeType == declaredType
                    ? Definition(type)
                    : ModelOption(type, type.RuntimeType != declaredType));
            }
            if (profile != SnapshotSchemaProfile.References && nullable) options.Add(TypeSchema("null"));
            return options.Count switch
            {
                0 => Impossible(),
                1 => (JsonObject)options[0]!.DeepClone(),
                _ => new JsonObject { ["oneOf"] = options },
            };
        }

        private JsonObject ModelOption(SnapshotType type, bool requiresDiscriminator)
        {
            var discriminator = new JsonObject
            {
                ["properties"] = new JsonObject { [SnapshotJson.TypeField] = Discriminator(type, readOnly: true) },
            };
            if (requiresDiscriminator) discriminator["required"] = new JsonArray(SnapshotJson.TypeField);
            return new JsonObject
            {
                ["title"] = registry.OutputName(type),
                ["allOf"] = new JsonArray(Definition(type), discriminator),
            };
        }

        private JsonObject Discriminator(SnapshotType type, bool readOnly = false)
        {
            var names = new JsonArray(registry.AcceptedNames(type).Select(name => (JsonNode?)JsonValue.Create(name)).ToArray());
            var field = new JsonObject { ["type"] = "string", ["enum"] = names };
            if (readOnly) field["readOnly"] = true;
            return field;
        }

        private JsonObject Definition(SnapshotType type)
        {
            var pointer = registry.OutputName(type).Replace("~", "~0", StringComparison.Ordinal).Replace("/", "~1", StringComparison.Ordinal);
            return new JsonObject { ["$ref"] = "#/definitions/" + Uri.EscapeDataString(pointer) };
        }

        private SnapshotType[] Candidates(Type declaredType)
        {
            if (!compatible.TryGetValue(declaredType, out var candidates))
            {
                candidates = types.Where(type => profile == SnapshotSchemaProfile.Plain ? type.RuntimeType == declaredType
                        : declaredType.IsAssignableFrom(type.RuntimeType))
                    .OrderBy(type => type.RuntimeType == declaredType)
                    .ThenBy(type => type.WireName, StringComparer.Ordinal).ToArray();
                compatible.Add(declaredType, candidates);
            }
            return candidates;
        }

        private bool Included(SnapshotProperty property) => !property.IsReference || profile == SnapshotSchemaProfile.References
            || Candidates(property.ValueType).Any(target => target.IsImmutable);

        private bool RestoresOpposite(SnapshotProperty property) => profile == SnapshotSchemaProfile.References
            && property.IsReference && property.Schema.RestoresOpposite;

        private bool RequiresPresence(SnapshotProperty property)
        {
            if (RestoresOpposite(property)) return false;
            if (property.Schema.RequiresPresence is bool explicitRequirement) return explicitRequirement;
            if (property.Schema.LowerBound == 0) return false;
            if (property.IsCollection || property.IsMap) return true;
            if (property.Schema.Default is { } supplied) return supplied.ValueKind == JsonValueKind.Null;
            return property.Kind != SnapshotPropertyKind.Scalar || IsNullable(property.ValueType);
        }
    }

    private static int UpperBound(SnapshotProperty property) => property.Schema.UpperBound
        ?? (property.IsCollection || property.IsMap ? property.UpperBound : 1);

    private static bool IsNullable(Type type) => !type.IsValueType || Nullable.GetUnderlyingType(type) is not null;

    private static bool IsFloating(Type type) => type == typeof(float) || type == typeof(double);

    private static JsonObject Scalar(Type declaredType, bool nullable)
    {
        var type = Nullable.GetUnderlyingType(declaredType) ?? declaredType;
        if (type == typeof(object)) return new JsonObject();
        if (type == typeof(string)) return TypeSchema("string", nullable);
        if (type == typeof(bool)) return TypeSchema("boolean", nullable);
        if (type == typeof(Guid))
        {
            var uuid = TypeSchema("string", nullable);
            uuid["format"] = "uuid";
            uuid["pattern"] = @"^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}(?![\s\S])";
            return uuid;
        }
        if (type == typeof(char))
        {
            var character = TypeSchema("string", nullable);
            character["minLength"] = 1;
            character["maxLength"] = 1;
            character["pattern"] = @"^[\u0000-\uFFFF](?![\s\S])";
            return character;
        }
        if (IsFloating(type))
        {
            var finite = TypeSchema("number", nullable);
            string largest = type == typeof(float) ? "3.4028235e38" : "1.7976931348623157e308";
            finite["minimum"] = JsonNode.Parse("-" + largest);
            finite["maximum"] = JsonNode.Parse(largest);
            return new JsonObject
            {
                ["anyOf"] = new JsonArray(finite, new JsonObject { ["enum"] = new JsonArray("NaN", "Infinity", "-Infinity") }),
            };
        }

        var integer = TypeSchema("integer", nullable);
        (long min, long max) = type == typeof(sbyte) ? (sbyte.MinValue, sbyte.MaxValue)
            : type == typeof(short) ? (short.MinValue, short.MaxValue)
            : type == typeof(int) ? (int.MinValue, int.MaxValue)
            : type == typeof(long) ? (long.MinValue, long.MaxValue)
            : throw new ArgumentException($"Unknown scalar schema type '{type}'.", nameof(declaredType));
        integer["minimum"] = min;
        integer["maximum"] = max;
        return integer;
    }

    private static JsonNode? ImplicitDefault(SnapshotProperty property)
    {
        if (property.IsMap) return property.KeyType == typeof(string) ? new JsonObject() : new JsonArray();
        if (property.IsCollection)
        {
            return property.Kind == SnapshotPropertyKind.ReferenceList || property.AllowNullElements ? null : new JsonArray();
        }
        if (property.Kind != SnapshotPropertyKind.Scalar || property.ExternalType is not null || IsNullable(property.ValueType)) return null;
        if (property.ValueType == typeof(bool)) return JsonValue.Create(false);
        if (property.ValueType == typeof(char)) return JsonValue.Create("\0");
        if (property.ValueType == typeof(Guid)) return JsonValue.Create(Guid.Empty.ToString("D"));
        return JsonValue.Create(0);
    }

    private static JsonObject Optional(JsonObject schema, bool nullable) =>
        nullable ? new JsonObject { ["anyOf"] = new JsonArray(schema, TypeSchema("null")) } : schema;

    private static JsonObject AbsentReference(SnapshotProperty property) => property.Kind switch
    {
        SnapshotPropertyKind.ReferenceList => new JsonObject { ["type"] = "array", ["items"] = TypeSchema("null") },
        SnapshotPropertyKind.ReferenceSet or SnapshotPropertyKind.OrderedReferenceSet =>
            new JsonObject { ["type"] = "array", ["maxItems"] = 0 },
        _ => TypeSchema("null"),
    };

    private static JsonObject TypeSchema(string name, bool nullable = false) =>
        new() { ["type"] = nullable ? new JsonArray(name, "null") : JsonValue.Create(name) };

    private static JsonObject IdSchema(bool nullable) => new()
    {
        ["type"] = nullable ? new JsonArray("string", "integer", "null") : new JsonArray("string", "integer"),
        ["minLength"] = 1,
    };

    private static JsonObject Required(string name) => new() { ["required"] = new JsonArray(name) };

    private static JsonObject Impossible() => new() { ["not"] = new JsonObject() };

    private static JsonNode? Node(JsonElement value) => JsonNode.Parse(value.GetRawText());

    private static void AddBounds(JsonObject schema, string suffix, int lower, int upper)
    {
        if (lower > 0) schema["min" + suffix] = lower;
        if (upper >= 0) schema["max" + suffix] = upper;
    }

    private static void SetMetadata(JsonObject schema, string keyword, JsonNode? value)
    {
        MoveReferenceIntoAllOf(schema);
        schema[keyword] = value;
    }

    private static void Conjoin(JsonObject schema, JsonElement contribution)
    {
        MoveReferenceIntoAllOf(schema);
        // Keep related keywords such as if/then/else together when they cannot be merged.
        if (contribution.TryGetProperty("$ref", out _) || contribution.EnumerateObject().Any(keyword =>
            schema.TryGetPropertyValue(keyword.Name, out var existing) && !JsonNode.DeepEquals(existing, Node(keyword.Value))))
        {
            AllOf(schema).Add(Node(contribution));
            return;
        }
        foreach (var keyword in contribution.EnumerateObject())
        {
            if (!schema.ContainsKey(keyword.Name)) schema[keyword.Name] = Node(keyword.Value);
        }
    }

    private static void MoveReferenceIntoAllOf(JsonObject schema)
    {
        // Draft-07 ignores sibling keywords of a reference.
        if (schema.Remove("$ref", out var reference))
        {
            AllOf(schema).Add(new JsonObject { ["$ref"] = reference });
        }
    }

    private static JsonArray AllOf(JsonObject schema) => (JsonArray)(schema["allOf"] ??= new JsonArray());
}
