namespace Vmf.Runtime;

/// <summary>Registers a model's effective reflection field order and view accessors.</summary>
public sealed partial class ModelReflectionType
{
    public ModelReflectionType(Type runtimeType, IEnumerable<ModelReflectionField> fields)
        : this(runtimeType, fields, null)
    {
    }

    public ModelReflectionType(Type runtimeType, IEnumerable<ModelReflectionField> fields,
        Func<object, object>? projectReadOnly)
        : this(runtimeType, fields, projectReadOnly, null)
    {
    }

    /// <summary>Registers a local declared-constraint check that reports paths relative to the instance.</summary>
    public ModelReflectionType(Type runtimeType, IEnumerable<ModelReflectionField> fields,
        Func<object, object>? projectReadOnly, Action<object, List<ValidationIssue>>? validateLocal)
    {
        ArgumentNullException.ThrowIfNull(runtimeType);
        ArgumentNullException.ThrowIfNull(fields);
        RuntimeType = runtimeType;
        Fields = fields.ToArray();
        ProjectReadOnly = projectReadOnly;
        ValidateLocal = validateLocal;
    }

    public Type RuntimeType { get; }
    internal IReadOnlyList<ModelReflectionField> Fields { get; }
    internal Func<object, object>? ProjectReadOnly { get; }
    internal Action<object, List<ValidationIssue>>? ValidateLocal { get; }
}

/// <summary>Supplies the reflection data that supplements the snapshot field descriptor.</summary>
public sealed partial class ModelReflectionField
{
    public ModelReflectionField(string name, Func<object, object?> readOnlyGet, int lowerBound = 0,
        int? upperBound = null, bool ordered = false, bool unique = false, string? keyPropertyName = null,
        string? declaringType = null, Type? inverseType = null, Func<object, object?>? inverseGet = null,
        Action<object, object?>? inverseSet = null, bool participatesInContentEquality = true)
        : this(name, readOnlyGet, null, lowerBound, upperBound, ordered, unique, keyPropertyName,
            declaringType, inverseType, inverseGet, inverseSet, participatesInContentEquality)
    {
    }

    /// <summary>Registers a shallow-copy hook for a materialized ordinary list.</summary>
    public ModelReflectionField(string name, Func<object, object?> readOnlyGet, Action<object, object>? shareSequence,
        int lowerBound = 0, int? upperBound = null, bool ordered = false, bool unique = false, string? keyPropertyName = null,
        string? declaringType = null, Type? inverseType = null, Func<object, object?>? inverseGet = null,
        Action<object, object?>? inverseSet = null, bool participatesInContentEquality = true)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(name);
        ArgumentNullException.ThrowIfNull(readOnlyGet);
        ArgumentOutOfRangeException.ThrowIfNegative(lowerBound);
        if (upperBound is int upper && (upper < -1 || upper >= 0 && upper < lowerBound))
        {
            throw new ArgumentOutOfRangeException(nameof(upperBound));
        }
        if ((inverseType is null) != (inverseGet is null) || inverseSet is not null && inverseGet is null)
        {
            throw new ArgumentException("An inverse field requires its declared type and getter.");
        }

        Name = name;
        ReadOnlyGet = readOnlyGet;
        LowerBound = lowerBound;
        UpperBound = upperBound;
        Ordered = ordered;
        Unique = unique;
        KeyPropertyName = keyPropertyName;
        DeclaringType = declaringType;
        InverseType = inverseType;
        InverseGet = inverseGet;
        InverseSet = inverseSet;
        ShareSequence = shareSequence;
        ParticipatesInContentEquality = participatesInContentEquality && inverseGet is null;
    }

    internal string Name { get; }
    internal Func<object, object?> ReadOnlyGet { get; }
    internal int LowerBound { get; }
    internal int? UpperBound { get; }
    internal bool Ordered { get; }
    internal bool Unique { get; }
    internal string? KeyPropertyName { get; }
    internal string? DeclaringType { get; }
    internal Type? InverseType { get; }
    internal Func<object, object?>? InverseGet { get; }
    internal Action<object, object?>? InverseSet { get; }
    internal Action<object, object>? ShareSequence { get; }
    internal bool ParticipatesInContentEquality { get; }
}

/// <summary>Reflection and traversal for registered generated models and their read-only views.</summary>
public sealed partial class ModelReflectionRegistry
{
    private readonly Dictionary<Type, ModelSchema> models = new();
    private readonly Func<object?, object?>? unwrapView;

    private ModelReflectionRegistry(Func<object?, object?>? unwrapView) => this.unwrapView = unwrapView;

    public ModelReflectionRegistry(SnapshotRegistry snapshots, IEnumerable<ModelReflectionType> types,
        Func<object?, object?>? unwrapView = null) : this(unwrapView)
    {
        ArgumentNullException.ThrowIfNull(snapshots);
        ArgumentNullException.ThrowIfNull(types);
        foreach (var type in types)
        {
            ArgumentNullException.ThrowIfNull(type);
            var snapshot = snapshots.TypeForClass(type.RuntimeType, "reflection registration");
            if (!models.TryAdd(type.RuntimeType, new ModelSchema(snapshot, type)))
            {
                throw new ArgumentException($"Duplicate model reflection registration for '{type.RuntimeType}'.", nameof(types));
            }
        }
        if (snapshots.HasReferenceCollectionDefaults || snapshots.HasUntypedObjectValues
            || snapshots.HasOmittedPlainMapDefaults || snapshots.HasExternalValueProperties
            || snapshots.HasImmutableTypes || CoversSnapshotTypes(snapshots))
        {
            snapshots.NativeReflection ??= this;
        }
    }

    internal static ModelReflectionRegistry? ComposeForSnapshots(SnapshotRegistry snapshots,
        IEnumerable<SnapshotRegistry> sources, Func<object?, object?> unwrapView)
    {
        var available = sources.Select(source => source.NativeReflection)
            .OfType<ModelReflectionRegistry>().ToArray();
        if (available.Length == 0) return null;

        var combined = new ModelReflectionRegistry(unwrapView);
        foreach (var type in snapshots.Types)
        {
            // A public registry may register its own schema for the same CLR type and name.
            // Only reuse metadata for the exact descriptor selected by the combined registry.
            var schema = available.Select(source => source.models.GetValueOrDefault(type.RuntimeType))
                .FirstOrDefault(candidate => ReferenceEquals(candidate?.Snapshot, type));
            if (schema is null) return null;
            combined.models.Add(type.RuntimeType, schema);
        }
        return combined;
    }

    internal bool CoversSnapshotTypes(SnapshotRegistry snapshots) =>
        snapshots.Types.All(type => models.ContainsKey(type.RuntimeType));

    /// <summary>Returns logical fields in VMF reflection order, bound to the supplied instance or view.</summary>
    public ModelReflection Reflect(object model) => new(Bind(model));

    private ModelBinding Bind(object model)
    {
        ArgumentNullException.ThrowIfNull(model);
        object identity = unwrapView?.Invoke(model) ?? model;
        if (!models.TryGetValue(identity.GetType(), out var schema))
        {
            throw new ArgumentException($"Unregistered model reflection type '{identity.GetType()}'.", nameof(model));
        }
        return new(model, identity, schema, this);
    }

    internal ModelBinding BindForValidation(object model) => Bind(model);

    internal readonly record struct ModelBinding(object Input, object Identity, ModelSchema Schema, ModelReflectionRegistry Registry)
    {
        internal bool IsReadOnly => Schema.Snapshot.IsImmutable || !ReferenceEquals(Input, Identity);
    }

    internal sealed class ModelSchema
    {
        internal ModelSchema(SnapshotType snapshot, ModelReflectionType shape)
        {
            Snapshot = snapshot;
            Annotations = shape.Annotations;
            ProjectReadOnly = shape.ProjectReadOnly;
            ValidateLocal = shape.ValidateLocal;
            var remaining = snapshot.Properties.ToDictionary(field => field.ModelName, StringComparer.Ordinal);
            var names = new HashSet<string>(StringComparer.Ordinal);
            var fields = new List<PropertySchema>();
            foreach (var field in shape.Fields)
            {
                ArgumentNullException.ThrowIfNull(field);
                if (!names.Add(field.Name))
                {
                    throw new ArgumentException($"Duplicate reflection field '{snapshot.WireName}.{field.Name}'.");
                }
                if (field.InverseType is not null)
                {
                    if (remaining.ContainsKey(field.Name))
                    {
                        throw new ArgumentException($"Inverse '{snapshot.WireName}.{field.Name}' cannot be a snapshot field.");
                    }
                    fields.Add(new(snapshot.WireName, field, null));
                }
                else if (remaining.Remove(field.Name, out var property))
                {
                    fields.Add(new(snapshot.WireName, field, property));
                }
                else
                {
                    throw new ArgumentException($"Unknown snapshot field '{snapshot.WireName}.{field.Name}'.");
                }
            }
            if (remaining.Count != 0)
            {
                throw new ArgumentException($"The reflection registration for '{snapshot.WireName}' omits {string.Join(", ", remaining.Keys)}.");
            }
            Fields = fields.ToArray();
            ContentFields = fields.Where(property => property.Field.ParticipatesInContentEquality)
                .Select(property => property.Snapshot!).ToArray();
        }

        internal SnapshotType Snapshot { get; }
        internal IReadOnlyList<ModelAnnotation> Annotations { get; }
        internal IReadOnlyList<PropertySchema> Fields { get; }
        internal IReadOnlyList<SnapshotProperty> ContentFields { get; }
        internal Func<object, object>? ProjectReadOnly { get; }
        internal Action<object, List<ValidationIssue>>? ValidateLocal { get; }
    }

    internal sealed class PropertySchema(string typeName, ModelReflectionField field, SnapshotProperty? snapshot)
    {
        internal SnapshotProperty? Snapshot { get; } = snapshot;
        internal ModelReflectionField Field { get; } = field;
        internal string DeclaringType => Field.DeclaringType ?? typeName;
        internal Func<object, object?> MutableGet => Snapshot?.Get ?? Field.InverseGet!;
        internal Action<object, object?>? MutableSet => Snapshot?.Set ?? Field.InverseSet;

        internal object? Read(ModelBinding model) =>
            (model.IsReadOnly ? Field.ReadOnlyGet : MutableGet)(model.Input);
    }
}

/// <summary>The effective model properties of a concrete object or its read-only view.</summary>
public sealed partial class ModelReflection
{
    private readonly ModelReflectionRegistry.ModelBinding model;
    private readonly Dictionary<string, ModelProperty> byName;

    internal ModelReflection(ModelReflectionRegistry.ModelBinding model)
    {
        this.model = model;
        var fields = model.Schema.Fields.Select(property => new ModelProperty(model, property)).ToArray();
        Properties = Array.AsReadOnly(fields);
        byName = fields.ToDictionary(property => property.Name, StringComparer.Ordinal);
    }

    public string TypeName => model.Schema.Snapshot.WireName;
    public Type RuntimeType => model.Schema.Snapshot.RuntimeType;
    public bool IsReadOnly => model.IsReadOnly;
    public bool IsImmutable => model.Schema.Snapshot.IsImmutable;
    public IReadOnlyList<ModelProperty> Properties { get; }

    /// <summary>Finds a field by its logical VMF name, with exact case.</summary>
    public ModelProperty? FindProperty(string name)
    {
        ArgumentNullException.ThrowIfNull(name);
        return byName.GetValueOrDefault(name);
    }
}

/// <summary>A registered field bound to one model or read-only view.</summary>
public sealed partial class ModelProperty
{
    private readonly ModelReflectionRegistry.ModelBinding model;
    private readonly ModelReflectionRegistry.PropertySchema property;

    internal ModelProperty(ModelReflectionRegistry.ModelBinding model, ModelReflectionRegistry.PropertySchema property)
    {
        this.model = model;
        this.property = property;
    }

    public string Name => property.Field.Name;

    /// <summary>The serialized field name, or null for an inverse container.</summary>
    public string? WireName => property.Snapshot?.WireName;
    public string DeclaringType => property.DeclaringType;

    /// <summary>The registered scalar, model, collection element, or map value CLR type.</summary>
    public Type ValueType => property.Snapshot?.ValueType ?? property.Field.InverseType!;
    public Type? KeyType => property.Snapshot?.KeyType;
    public SnapshotPropertyKind? Kind => property.Snapshot?.Kind;
    public bool IsContainer => property.Field.InverseType is not null;
    public bool IsContainment => property.Snapshot?.IsContainment == true;
    public bool IsReference => property.Snapshot?.IsReference == true;
    public bool IsCollection => property.Snapshot is { } field && (field.IsCollection || field.IsMap);
    public bool IsOrdered => property.Field.Ordered;
    public bool IsUnique => property.Field.Unique;
    public int LowerBound => property.Field.LowerBound;
    public int UpperBound => property.Field.UpperBound ?? (IsCollection ? property.Snapshot!.UpperBound : 1);
    public string? KeyPropertyName => property.Field.KeyPropertyName;
    public bool CanWrite => !model.IsReadOnly && property.MutableSet is not null;

    public object? Get() => property.Read(model);

    /// <summary>Sets a writable single-valued field. Mutate a writable collection through its live getter.</summary>
    public void Set(object? value)
    {
        if (model.IsReadOnly || property.MutableSet is not { } set)
        {
            throw new InvalidOperationException($"Model property '{Name}' has no writable setter on this view.");
        }
        set(model.Input, value);
    }
}
