using System.Text.Json;

namespace Vmf.Runtime;

/// <summary>A model declaration or a portable value used in a model type signature.</summary>
public enum ModelValueKind
{
    Boolean, Int8, Int16, Int32, Int64, Char, Float32, Float64, String, Model, Parameter, External
}

/// <summary>An immutable, structured use of a model or scalar declaration.</summary>
public sealed class ModelTypeUse : IEquatable<ModelTypeUse>
{
    public ModelTypeUse(ModelValueKind kind, string name, bool primitive = false,
        IReadOnlyList<ModelTypeUse>? arguments = null, string? declaringType = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(name);
        Kind = kind;
        Name = name;
        IsPrimitive = primitive;
        Arguments = Array.AsReadOnly(arguments?.ToArray() ?? Array.Empty<ModelTypeUse>());
        DeclaringType = declaringType;
    }

    public ModelValueKind Kind { get; }
    public string Name { get; }
    public bool IsPrimitive { get; }
    public IReadOnlyList<ModelTypeUse> Arguments { get; }
    public string? DeclaringType { get; }

    public bool Equals(ModelTypeUse? other) => other is not null && Kind == other.Kind
        && Name == other.Name && IsPrimitive == other.IsPrimitive && DeclaringType == other.DeclaringType
        && Arguments.SequenceEqual(other.Arguments);

    public override bool Equals(object? other) => other is ModelTypeUse type && Equals(type);

    public override int GetHashCode()
    {
        var hash = new HashCode();
        hash.Add(Kind);
        hash.Add(Name, StringComparer.Ordinal);
        hash.Add(IsPrimitive);
        hash.Add(DeclaringType, StringComparer.Ordinal);
        foreach (var argument in Arguments) hash.Add(argument);
        return hash.ToHashCode();
    }

    public override string ToString() => Arguments.Count == 0 ? Name : $"{Name}<{string.Join(", ", Arguments)}>";
}

public sealed class ModelTypeParameter
{
    public ModelTypeParameter(string name, IReadOnlyList<ModelTypeUse> upperBounds)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(name);
        ArgumentNullException.ThrowIfNull(upperBounds);
        Name = name;
        UpperBounds = Array.AsReadOnly(upperBounds.ToArray());
    }

    public string Name { get; }
    public IReadOnlyList<ModelTypeUse> UpperBounds { get; }
}

public enum ModelFieldKind { Scalar, Reference, Containment, Container, Map }

/// <summary>The unbound metadata of a property, including closed type arguments in derived declarations.</summary>
public sealed class ModelFieldMetadata
{
    private readonly SnapshotSchemaProperty? schema;

    public ModelFieldMetadata(string name, string declaringType, string? wireName, ModelFieldKind kind,
        ModelTypeUse valueType, int lowerBound, int upperBound, bool collection, bool ordered, bool unique,
        bool getterOnly, ModelTypeUse? keyType = null, string? keyPropertyName = null, string? oppositeName = null,
        IReadOnlyList<ModelAnnotation>? annotations = null, SnapshotSchemaProperty? schema = null)
    {
        Name = name;
        DeclaringType = declaringType;
        WireName = wireName;
        Kind = kind;
        ValueType = valueType;
        KeyType = keyType;
        KeyPropertyName = keyPropertyName;
        OppositeName = oppositeName;
        LowerBound = lowerBound;
        UpperBound = upperBound;
        IsCollection = collection;
        IsOrdered = ordered;
        IsUnique = unique;
        IsGetterOnly = getterOnly;
        Annotations = annotations is null ? Array.Empty<ModelAnnotation>() : ModelAnnotation.Freeze(annotations);
        this.schema = schema;
    }

    public string Name { get; }
    public string DeclaringType { get; }
    public string? WireName { get; }
    public ModelFieldKind Kind { get; }
    public ModelTypeUse ValueType { get; }
    public ModelTypeUse? KeyType { get; }
    public string? KeyPropertyName { get; }
    public string? OppositeName { get; }
    public int LowerBound { get; }
    public int UpperBound { get; }
    public bool IsCollection { get; }
    public bool IsOrdered { get; }
    public bool IsUnique { get; }
    public bool IsGetterOnly { get; }
    public IReadOnlyList<ModelAnnotation> Annotations { get; }
    public JsonElement? DeclaredDefault => schema?.Default;
    public bool HasExecutableDefault => schema?.HasExecutableDefault == true;
    public JsonElement? ConstraintAssertions => schema?.Assertions;
}

/// <summary>A generated declaration that can be inspected without constructing a model instance.</summary>
public sealed class ModelTypeMetadata
{
    public ModelTypeMetadata(string name, string wireName, Type runtimeType,
        bool interfaceOnly, bool immutable, IReadOnlyList<ModelTypeParameter> typeParameters,
        IReadOnlyList<ModelTypeUse> directSupertypes, IReadOnlyList<ModelFieldMetadata> properties,
        IReadOnlyList<ModelAnnotation> annotations)
    {
        Name = name;
        WireName = wireName;
        RuntimeType = runtimeType;
        IsInterfaceOnly = interfaceOnly;
        IsImmutable = immutable;
        TypeParameters = Array.AsReadOnly(typeParameters.ToArray());
        DirectSupertypes = Array.AsReadOnly(directSupertypes.ToArray());
        Properties = Array.AsReadOnly(properties.ToArray());
        Annotations = ModelAnnotation.Freeze(annotations);
        Declaration = new(ModelValueKind.Model, name);
    }

    public string Name { get; }
    public string WireName { get; }
    public Type RuntimeType { get; }
    public bool IsInterfaceOnly { get; }
    public bool IsImmutable { get; }
    public ModelTypeUse Declaration { get; }
    public IReadOnlyList<ModelTypeParameter> TypeParameters { get; }
    public IReadOnlyList<ModelTypeUse> DirectSupertypes { get; }
    public IReadOnlyList<ModelFieldMetadata> Properties { get; }
    public IReadOnlyList<ModelAnnotation> Annotations { get; }

    public ModelFieldMetadata? FindProperty(string name) => Properties.FirstOrDefault(field => field.Name == name);
}

/// <summary>Static reflection over all generated model declarations, including open and interface-only types.</summary>
public sealed class ModelTypeCatalog
{
    private readonly Dictionary<string, ModelTypeMetadata> byName;
    private readonly Dictionary<Type, ModelTypeMetadata> byRuntimeType;
    private readonly Func<object?, object?>? resolveReadOnly;

    public ModelTypeCatalog(IEnumerable<ModelTypeMetadata> types, Func<object?, object?>? resolveReadOnly = null)
    {
        ArgumentNullException.ThrowIfNull(types);
        Types = Array.AsReadOnly(types.ToArray());
        byName = Types.ToDictionary(type => type.Name, StringComparer.Ordinal);
        byRuntimeType = Types.ToDictionary(type => type.RuntimeType);
        this.resolveReadOnly = resolveReadOnly;
    }

    /// <summary>Composes independently generated model catalogs in registration order.</summary>
    /// <remarks>Repeated declarations keep their first descriptor. Names and CLR type identities must agree.</remarks>
    public static ModelTypeCatalog Compose(params ModelTypeCatalog[] catalogs)
    {
        ArgumentNullException.ThrowIfNull(catalogs);
        var sources = catalogs.ToArray();
        var types = new List<ModelTypeMetadata>();
        var typesByName = new Dictionary<string, ModelTypeMetadata>(StringComparer.Ordinal);
        var runtimeTypes = new HashSet<Type>();
        foreach (var source in sources)
        {
            ArgumentNullException.ThrowIfNull(source);
            foreach (var type in source.Types)
            {
                if (typesByName.TryGetValue(type.Name, out var previous))
                {
                    if (previous.RuntimeType == type.RuntimeType) continue;
                    throw new ArgumentException($"Conflicting VMF declaration '{type.Name}'.", nameof(catalogs));
                }
                if (!runtimeTypes.Add(type.RuntimeType))
                {
                    throw new ArgumentException($"Conflicting VMF names for CLR type '{type.RuntimeType}'.", nameof(catalogs));
                }
                typesByName.Add(type.Name, type);
                types.Add(type);
            }
        }
        return new(types, ResolveReadOnly);

        object? ResolveReadOnly(object? value)
        {
            foreach (var source in sources)
            {
                var resolved = source.resolveReadOnly?.Invoke(value);
                if (resolved is not null && !ReferenceEquals(value, resolved)) return resolved;
            }
            return value;
        }
    }
    public IReadOnlyList<ModelTypeMetadata> Types { get; }

    public ModelTypeMetadata? Find(string name) => byName.GetValueOrDefault(name);

    public ModelTypeMetadata? Find(Type runtimeType)
    {
        ArgumentNullException.ThrowIfNull(runtimeType);
        return byRuntimeType.GetValueOrDefault(runtimeType.IsConstructedGenericType
            ? runtimeType.GetGenericTypeDefinition() : runtimeType);
    }

    public ModelTypeMetadata? TypeOf(object instance)
    {
        ArgumentNullException.ThrowIfNull(instance);
        return Find((resolveReadOnly?.Invoke(instance) ?? instance).GetType());
    }

    /// <summary>Finds declarations whose generated CLR type implements the supplied contract.</summary>
    /// <remarks>An open generic definition matches any application in the CLR base or interface hierarchy.</remarks>
    public IReadOnlyList<ModelTypeMetadata> TypesAssignableTo(Type contract)
    {
        ArgumentNullException.ThrowIfNull(contract);
        return Array.AsReadOnly(Types.Where(type => MatchesContract(type.RuntimeType, contract)).ToArray());
    }

    private static bool MatchesContract(Type type, Type contract)
    {
        if (!contract.IsGenericTypeDefinition) return contract.IsAssignableFrom(type);
        if (contract.IsInterface)
        {
            return IsApplicationOf(type, contract) || type.GetInterfaces().Any(candidate => IsApplicationOf(candidate, contract));
        }
        for (Type? current = type; current is not null; current = current.BaseType)
        {
            if (IsApplicationOf(current, contract)) return true;
        }
        return false;
    }

    private static bool IsApplicationOf(Type type, Type definition) =>
        type.IsGenericType && type.GetGenericTypeDefinition() == definition;


    /// <summary>Tests proper model inheritance, substituting each ancestor's declared type parameters.</summary>
    public bool IsSubtypeOf(ModelTypeUse type, ModelTypeUse supertype)
    {
        ArgumentNullException.ThrowIfNull(type);
        ArgumentNullException.ThrowIfNull(supertype);
        if (type.Kind != ModelValueKind.Model || supertype.Kind != ModelValueKind.Model || type.Name == supertype.Name)
        {
            return false;
        }
        var visited = new HashSet<ModelTypeUse>();
        return IncludesSupertype(type, supertype, visited);
    }

    private bool IncludesSupertype(ModelTypeUse type, ModelTypeUse supertype, HashSet<ModelTypeUse> visited)
    {
        if (!visited.Add(type) || !byName.TryGetValue(type.Name, out var declaration)
            || type.Arguments.Count != 0 && type.Arguments.Count != declaration.TypeParameters.Count)
        {
            return false;
        }
        var arguments = new Dictionary<string, ModelTypeUse>(StringComparer.Ordinal);
        for (int index = 0; index < type.Arguments.Count; index++)
        {
            arguments.Add(declaration.TypeParameters[index].Name, type.Arguments[index]);
        }
        foreach (var parent in declaration.DirectSupertypes)
        {
            var bound = Substitute(parent, declaration.Name, arguments);
            if (bound.Name == supertype.Name && (supertype.Arguments.Count == 0 || bound.Equals(supertype))
                || IncludesSupertype(bound, supertype, visited))
            {
                return true;
            }
        }
        return false;
    }

    private static ModelTypeUse Substitute(ModelTypeUse type, string declaration, Dictionary<string, ModelTypeUse> arguments)
    {
        if (type.Kind == ModelValueKind.Parameter && type.DeclaringType == declaration
            && arguments.TryGetValue(type.Name, out var replacement))
        {
            return replacement;
        }
        return type.Arguments.Count == 0 ? type : new(type.Kind, type.Name, type.IsPrimitive,
            type.Arguments.Select(argument => Substitute(argument, declaration, arguments)).ToArray(), type.DeclaringType);
    }
}
