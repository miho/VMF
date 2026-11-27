using System.Diagnostics.CodeAnalysis;
using System.Text.Json;

namespace Vmf.Runtime;

/// <summary>An allowlist of concrete model types, factories, and optional wire aliases.</summary>
public sealed partial class SnapshotRegistry
{
    private readonly Dictionary<string, SnapshotType> typesByName = new(StringComparer.Ordinal);
    private readonly Dictionary<Type, SnapshotType> typesByClass = new();
    private readonly Dictionary<string, string> outputAliases = new(StringComparer.Ordinal);
    private readonly Func<object?, object?>? resolveReadOnly;
    internal bool HasReferenceCollectionDefaults { get; }
    internal bool HasUntypedObjectValues { get; }
    internal bool HasOmittedPlainMapDefaults { get; }
    internal bool HasExternalValueProperties { get; }
    internal bool HasImmutableTypes { get; }
    internal ModelReflectionRegistry? NativeReflection { get; set; }

    public SnapshotRegistry(IEnumerable<SnapshotType> types, IReadOnlyDictionary<string, string>? aliases = null)
        : this(types, aliases, null)
    {
    }

    public SnapshotRegistry(IEnumerable<SnapshotType> types, IReadOnlyDictionary<string, string>? aliases,
        Func<object?, object?>? resolveReadOnly)
        : this(types, aliases, resolveReadOnly, ExternalSnapshotCodecs.Empty)
    {
    }

    private SnapshotRegistry(IEnumerable<SnapshotType> types, IReadOnlyDictionary<string, string>? aliases,
        Func<object?, object?>? resolveReadOnly, ExternalSnapshotCodecs externalValues)
    {
        ArgumentNullException.ThrowIfNull(types);
        this.resolveReadOnly = resolveReadOnly;
        ExternalValues = externalValues;
        foreach (var type in types)
        {
            if (!typesByName.TryAdd(type.WireName, type) || !typesByClass.TryAdd(type.RuntimeType, type))
            {
                throw new ArgumentException($"Duplicate registered VMF snapshot type '{type.WireName}'.", nameof(types));
            }
        }
        HasReferenceCollectionDefaults = typesByClass.Values.Any(type => type.Properties.Any(ReferenceCollectionDefaults.IsBound));
        HasUntypedObjectValues = typesByClass.Values.Any(type => type.Properties.Any(property => property.IsUntypedObject));
        HasOmittedPlainMapDefaults = typesByClass.Values.Any(type => type.Properties.Any(property => property.HasOmittedPlainMapDefault));
        HasExternalValueProperties = typesByClass.Values.Any(type => type.Properties.Any(property => property.ExternalType is not null));
        HasImmutableTypes = typesByClass.Values.Any(type => type.IsImmutable);

        if (aliases is not null)
        {
            foreach (var (alias, typeName) in aliases)
            {
                RegisterAlias(alias, typeName, allowMultipleNames: false);
            }
        }
    }

    private void RegisterAlias(string alias, string typeName, bool allowMultipleNames)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(alias);
        ArgumentException.ThrowIfNullOrWhiteSpace(typeName);
        if (!typesByName.TryGetValue(typeName, out var type) || type.WireName != typeName)
        {
            throw new ArgumentException($"Unknown snapshot type '{typeName}' for alias '{alias}'.", "aliases");
        }
        if (alias == typeName) return;

        string conflict = $"Conflicting snapshot type alias '{alias}' for '{typeName}'.";
        if (!typesByName.TryAdd(alias, type)
            && (!allowMultipleNames || !MatchesName(type, alias)))
        {
            throw new ArgumentException(conflict, "aliases");
        }
        if (allowMultipleNames) outputAliases[typeName] = alias;
        else if (!outputAliases.TryAdd(typeName, alias))
        {
            throw new ArgumentException(conflict, "aliases");
        }
    }

    internal SnapshotType TypeForClass(Type runtimeType, string path)
    {
        if (TryTypeForClass(runtimeType, out var type))
        {
            return type;
        }

        throw new JsonException($"Unregistered VMF snapshot CLR type '{runtimeType}' at {path}.");
    }

    internal bool TryTypeForClass(Type runtimeType, [NotNullWhen(true)] out SnapshotType? type) =>
        typesByClass.TryGetValue(runtimeType, out type);

    internal object ModelIdentity(object value) => resolveReadOnly?.Invoke(value) ?? value;

    internal SnapshotType TypeForName(string wireName, Type expectedType, string path)
    {
        if (!typesByName.TryGetValue(wireName, out var type))
        {
            throw new JsonException($"Unregistered VMF snapshot type '{wireName}' at {path}.");
        }

        if (!expectedType.IsAssignableFrom(type.RuntimeType))
        {
            throw new JsonException($"VMF snapshot type '{wireName}' is not compatible with '{expectedType}' at {path}.");
        }

        return type;
    }

    internal string OutputName(SnapshotType type) => outputAliases.GetValueOrDefault(type.WireName, type.WireName);

    internal string[] AcceptedNames(SnapshotType type)
    {
        string preferred = OutputName(type);
        var otherAliases = typesByName.Where(entry => ReferenceEquals(entry.Value, type)
                && entry.Key != preferred && entry.Key != type.WireName)
            .Select(entry => entry.Key)
            .Order(StringComparer.Ordinal);
        return preferred == type.WireName
            ? [preferred, .. otherAliases]
            : [preferred, type.WireName, .. otherAliases];
    }

    internal bool MatchesName(SnapshotType type, string name) =>
        typesByName.TryGetValue(name, out var registered) && ReferenceEquals(type, registered);

    internal IEnumerable<SnapshotType> Types => typesByClass.Values;

    internal ExternalSnapshotCodecs ExternalValues { get; }

    /// <summary>Returns a registry with additional application-local aliases for its registered model types.</summary>
    /// <remarks>Keys are read aliases. The last supplied alias for each qualified type is preferred when writing.
    /// Existing aliases remain readable. A registered name cannot be reassigned to another type.</remarks>
    public SnapshotRegistry WithTypeAliases(IReadOnlyDictionary<string, string> aliases)
    {
        ArgumentNullException.ThrowIfNull(aliases);
        var copy = CopyWithCodecs(ExternalValues);
        foreach (var (alias, typeName) in aliases)
        {
            copy.RegisterAlias(alias, typeName, allowMultipleNames: true);
        }
        return copy;
    }

    /// <summary>Returns a registry using the given application-local external value codecs.</summary>
    public SnapshotRegistry WithExternalValueCodecs(ExternalSnapshotCodecs codecs)
    {
        ArgumentNullException.ThrowIfNull(codecs);
        return CopyWithCodecs(codecs);
    }

    private SnapshotRegistry CopyWithCodecs(ExternalSnapshotCodecs codecs)
    {
        var copy = new SnapshotRegistry(Types, aliases: null, resolveReadOnly, codecs) { NativeReflection = NativeReflection };
        foreach (var (name, type) in typesByName)
        {
            if (name != type.WireName) copy.typesByName.Add(name, type);
        }
        foreach (var (name, alias) in outputAliases) copy.outputAliases.Add(name, alias);
        return copy;
    }

    internal bool IsImmutableModelType(Type declaredType)
    {
        bool found = false;
        foreach (var type in typesByClass.Values)
        {
            if (declaredType.IsAssignableFrom(type.RuntimeType))
            {
                if (!type.IsImmutable)
                {
                    return false;
                }
                found = true;
            }
        }
        return found;
    }
}
