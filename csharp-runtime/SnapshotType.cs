namespace Vmf.Runtime;

/// <summary>The wire representation of a modeled property.</summary>
public enum SnapshotPropertyKind
{
    Scalar,
    Containment,
    ContainmentList,
    Reference,
    ReferenceSet,
    ReferenceList,
    OrderedReferenceSet,
    ScalarList,
    OrderedScalarSet,
    ScalarSet,
    ScalarMap,
    ContainmentSequence,
    ContainmentSet,
}

/// <summary>Supplies the model's wire name and native accessors for one property.</summary>
public sealed class SnapshotProperty
{
    public SnapshotProperty(string wireName, SnapshotPropertyKind kind, Type valueType,
        Func<object, object?> get, Action<object, object?>? set, Action<object, object>? add)
        : this(wireName, kind, valueType, get, set, add, keyType: null)
    {
    }

    public SnapshotProperty(string wireName, SnapshotPropertyKind kind, Type valueType,
        Func<object, object?> get, Action<object, object?>? set = null, Action<object, object>? add = null,
        Type? keyType = null, Action<object, object, object?>? put = null,
        bool allowNullElements = false, int upperBound = -1, SnapshotSchemaProperty? schema = null)
        : this(wireName, wireName, kind, valueType, get, set, add, keyType, put, allowNullElements, upperBound, schema)
    {
    }

    public SnapshotProperty(string wireName, string modelName, SnapshotPropertyKind kind, Type valueType,
        Func<object, object?> get, Action<object, object?>? set = null, Action<object, object>? add = null,
        Type? keyType = null, Action<object, object, object?>? put = null,
        bool allowNullElements = false, int upperBound = -1, SnapshotSchemaProperty? schema = null,
        Func<object, ReciprocalEndpoint>? reciprocal = null)
        : this(wireName, modelName, kind, valueType, get, false, set, add, keyType, put, allowNullElements, upperBound, schema,
            reciprocal)
    {
    }

    public SnapshotProperty(string wireName, string modelName, SnapshotPropertyKind kind, Type valueType,
        Func<object, object?> get, bool writeExplicitNull, Action<object, object?>? set = null, Action<object, object>? add = null,
        Type? keyType = null, Action<object, object, object?>? put = null,
        bool allowNullElements = false, int upperBound = -1, SnapshotSchemaProperty? schema = null,
        Func<object, ReciprocalEndpoint>? reciprocal = null)
        : this(wireName, modelName, kind, valueType, get, writeExplicitNull, set, add, keyType, put,
            allowNullElements, upperBound, schema, reciprocal, externalType: null)
    {
    }

    /// <summary>Registers a scalar or ordinary scalar list whose value uses an application-provided external codec.</summary>
    public SnapshotProperty(string wireName, string modelName, SnapshotPropertyKind kind, Type valueType,
        Func<object, object?> get, string externalType, bool writeExplicitNull = false, Action<object, object?>? set = null,
        Action<object, object>? add = null, bool allowNullElements = false, int upperBound = -1, SnapshotSchemaProperty? schema = null)
        : this(wireName, modelName, kind, valueType, get, writeExplicitNull, set, add, null, null,
            allowNullElements, upperBound, schema, null, RequireExternalTypeName(externalType))
    {
    }

    private SnapshotProperty(string wireName, string modelName, SnapshotPropertyKind kind, Type valueType,
        Func<object, object?> get, bool writeExplicitNull, Action<object, object?>? set, Action<object, object>? add,
        Type? keyType, Action<object, object, object?>? put, bool allowNullElements, int upperBound,
        SnapshotSchemaProperty? schema, Func<object, ReciprocalEndpoint>? reciprocal, string? externalType)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(wireName);
        ArgumentException.ThrowIfNullOrWhiteSpace(modelName);
        ArgumentNullException.ThrowIfNull(valueType);
        ArgumentNullException.ThrowIfNull(get);
        if (!Enum.IsDefined(kind))
        {
            throw new ArgumentOutOfRangeException(nameof(kind));
        }

        if (wireName is SnapshotJson.TypeField or SnapshotJson.IdField)
        {
            throw new ArgumentException($"'{wireName}' is reserved for VMF snapshot metadata.", nameof(wireName));
        }

        Kind = kind;
        if (externalType is not null)
        {
            if (kind is not (SnapshotPropertyKind.Scalar or SnapshotPropertyKind.ScalarList))
            {
                throw new ArgumentException("External codecs apply to scalar properties and ordinary scalar lists.", nameof(kind));
            }
            ExternalSnapshotCodecs.RequireHostType(Nullable.GetUnderlyingType(valueType) ?? valueType);
        }
        else if (kind == SnapshotPropertyKind.Scalar || IsScalarCollection || IsMap)
        {
            Type scalar = Nullable.GetUnderlyingType(valueType) ?? valueType;
            if (!SnapshotJson.IsSupportedScalar(scalar) && !IsUntypedObjectValue(kind, scalar))
            {
                throw new ArgumentException($"Unsupported snapshot scalar type '{valueType}'.", nameof(valueType));
            }
        }
        else if (valueType.IsValueType)
        {
            throw new ArgumentException("A model relationship requires a reference type.", nameof(valueType));
        }

        if (IsMap && (keyType is null || !SnapshotJson.IsSupportedScalar(keyType)))
        {
            throw new ArgumentException("A scalar map requires a supported, non-nullable scalar key type.", nameof(keyType));
        }
        ScalarCollectionBounds.CheckUpperBound(upperBound);
        if (allowNullElements && (kind != SnapshotPropertyKind.ScalarList || upperBound >= 0
            || (valueType.IsValueType && Nullable.GetUnderlyingType(valueType) is null)))
        {
            throw new ArgumentException("Null elements require an unbounded ordinary scalar list with a nullable value type.",
                nameof(allowNullElements));
        }

        if (writeExplicitNull && (kind != SnapshotPropertyKind.Scalar
            || valueType.IsValueType && Nullable.GetUnderlyingType(valueType) is null))
        {
            throw new ArgumentException("Explicit null requires a nullable scalar.", nameof(writeExplicitNull));
        }

        if (reciprocal is not null && !IsReference)
        {
            throw new ArgumentException("A reciprocal endpoint requires a reference property.", nameof(reciprocal));
        }

        WireName = wireName;
        ModelName = modelName;
        ValueType = valueType;
        KeyType = keyType;
        Get = get;
        Set = set;
        Add = add;
        Put = put;
        AllowNullElements = allowNullElements;
        UpperBound = upperBound;
        WriteExplicitNull = writeExplicitNull;
        Schema = schema ?? SnapshotSchemaProperty.DefaultMetadata;
        Reciprocal = reciprocal;
        ExternalType = externalType;
    }

    private static string RequireExternalTypeName(string name)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(name);
        return name;
    }

    internal string WireName { get; }
    internal string ModelName { get; }
    internal SnapshotPropertyKind Kind { get; }
    internal Type ValueType { get; }
    internal Type? KeyType { get; }
    internal Func<object, object?> Get { get; }
    internal Action<object, object?>? Set { get; }
    internal Action<object, object>? Add { get; }
    internal Action<object, object, object?>? Put { get; }
    /// <summary>Supplies a generated mutable builder's independently evaluated scalar or managed collection default.</summary>
    [System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
    public Func<object?>? StageBuilderDefault { get; init; }
    /// <summary>Evaluates a declaration's default only when serializing a null scalar.</summary>
    [System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
    public Func<object?>? DeclaredDefaultForNullWrite { get; init; }
    internal bool AllowNullElements { get; }
    internal int UpperBound { get; }
    internal bool WriteExplicitNull { get; }
    internal bool WritesNull() => WriteExplicitNull
        && (DeclaredDefaultForNullWrite is not { } evaluate || evaluate() is not null);
    internal SnapshotSchemaProperty Schema { get; }
    internal Func<object, ReciprocalEndpoint>? Reciprocal { get; }
    internal string? ExternalType { get; }
    internal bool HasOmittedPlainMapDefault => ExternalType == "java.util.Map"
        && Schema.HasExecutableDefault && !WriteExplicitNull;
    internal bool IsUntypedObject => IsUntypedObjectValue(Kind, ValueType) && ExternalType is null;
    private static bool IsUntypedObjectValue(SnapshotPropertyKind kind, Type valueType) =>
        valueType == typeof(object) && kind is (SnapshotPropertyKind.Scalar or SnapshotPropertyKind.ScalarList);
    internal bool IsCollection => Kind is SnapshotPropertyKind.ContainmentList or SnapshotPropertyKind.ContainmentSequence
        or SnapshotPropertyKind.ContainmentSet or SnapshotPropertyKind.ReferenceSet
        or SnapshotPropertyKind.ReferenceList or SnapshotPropertyKind.OrderedReferenceSet || IsScalarCollection;
    internal bool IsScalarCollection => Kind is SnapshotPropertyKind.ScalarList or SnapshotPropertyKind.OrderedScalarSet
        or SnapshotPropertyKind.ScalarSet;
    internal bool IsMap => Kind == SnapshotPropertyKind.ScalarMap;
    internal bool IsContainment => Kind is SnapshotPropertyKind.Containment or SnapshotPropertyKind.ContainmentList
        or SnapshotPropertyKind.ContainmentSequence or SnapshotPropertyKind.ContainmentSet;
    internal bool IsUnorderedSet => Kind is SnapshotPropertyKind.ReferenceSet or SnapshotPropertyKind.ScalarSet
        or SnapshotPropertyKind.ContainmentSet;
    internal bool IsReference => Kind is SnapshotPropertyKind.Reference or SnapshotPropertyKind.ReferenceSet
        or SnapshotPropertyKind.ReferenceList or SnapshotPropertyKind.OrderedReferenceSet;
    internal bool HasNullableModelSlots => Kind is SnapshotPropertyKind.ReferenceList or SnapshotPropertyKind.ContainmentSequence;
    internal bool HasUniqueMembers => IsCollection
        && Kind is not (SnapshotPropertyKind.ReferenceList or SnapshotPropertyKind.ScalarList or SnapshotPropertyKind.ContainmentSequence);
}

/// <summary>Registers a concrete snapshot type and its factory independently of its CLR name.</summary>
public sealed class SnapshotType
{
    public SnapshotType(string wireName, Type runtimeType, Func<object> create,
        IEnumerable<SnapshotProperty> properties, bool writeType = false)
        : this(wireName, runtimeType, create ?? throw new ArgumentNullException(nameof(create)), null, properties, writeType)
    {
    }

    /// <summary>Registers an immutable type whose factory receives the decoded fields by wire name.</summary>
    public SnapshotType(string wireName, Type runtimeType, Func<IReadOnlyDictionary<string, object?>, object> createImmutable,
        IEnumerable<SnapshotProperty> properties, bool writeType = false)
        : this(wireName, runtimeType, null, createImmutable ?? throw new ArgumentNullException(nameof(createImmutable)),
            properties, writeType)
    {
    }

    private SnapshotType(string wireName, Type runtimeType, Func<object>? create,
        Func<IReadOnlyDictionary<string, object?>, object>? createImmutable,
        IEnumerable<SnapshotProperty> properties, bool writeType)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(wireName);
        ArgumentNullException.ThrowIfNull(runtimeType);
        ArgumentNullException.ThrowIfNull(properties);
        if (!runtimeType.IsClass || runtimeType.IsAbstract || runtimeType.ContainsGenericParameters)
        {
            throw new ArgumentException($"Snapshot type '{wireName}' must name a concrete class.", nameof(runtimeType));
        }

        var fields = properties.ToArray();
        var names = new HashSet<string>(StringComparer.Ordinal);
        foreach (var field in fields)
        {
            ArgumentNullException.ThrowIfNull(field);
            if (!names.Add(field.WireName))
            {
                throw new ArgumentException($"Duplicate snapshot field '{wireName}.{field.WireName}'.", nameof(properties));
            }
            if (createImmutable is not null && field.IsContainment)
            {
                throw new ArgumentException($"Immutable snapshot type '{wireName}' cannot have containment.", nameof(properties));
            }
            if (field.StageBuilderDefault is not null && (create is null
                || !(field.Kind == SnapshotPropertyKind.Scalar || ModelProperty.CopiesManagedDefault(field))))
            {
                throw new ArgumentException($"Snapshot builder staging requires a mutable scalar or managed collection '{wireName}.{field.WireName}'.",
                    nameof(properties));
            }
            bool missingAccessor = field.IsMap ? field.Put is null : field.IsCollection ? field.Add is null : field.Set is null;
            if (create is not null && missingAccessor)
            {
                throw new ArgumentException($"Missing write accessor for snapshot property '{wireName}.{field.WireName}'.");
            }
        }

        WireName = wireName;
        RuntimeType = runtimeType;
        Create = create;
        CreateImmutable = createImmutable;
        Properties = fields;
        HasStagedBuilderDefaults = fields.Any(field => field.StageBuilderDefault is not null);
        WriteType = writeType;
    }

    internal string WireName { get; }
    internal Type RuntimeType { get; }
    internal Func<object>? Create { get; }
    internal Func<IReadOnlyDictionary<string, object?>, object>? CreateImmutable { get; }
    /// <summary>Creates an immutable snapshot's builder before resolving its supplied fields.</summary>
    [System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
    public Func<Func<IReadOnlyDictionary<string, object?>, object>>? StageImmutableBuilder { get; init; }
    internal bool IsImmutable => CreateImmutable is not null;
    internal IReadOnlyList<SnapshotProperty> Properties { get; }
    internal bool HasStagedBuilderDefaults { get; }
    internal bool WriteType { get; }
}
