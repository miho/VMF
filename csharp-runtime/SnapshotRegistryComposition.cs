namespace Vmf.Runtime;

public sealed partial class SnapshotRegistry
{
    /// <summary>Composes independent model registries using their qualified names and read-only identities.</summary>
    /// <remarks>Each source registry retains its own short wire aliases for ordinary snapshots.</remarks>
    public static SnapshotRegistry CombineCanonical(params SnapshotRegistry[] registries)
    {
        ArgumentNullException.ThrowIfNull(registries);
        var sources = registries.ToArray();
        var types = new Dictionary<Type, SnapshotType>();
        foreach (var registry in sources)
        {
            ArgumentNullException.ThrowIfNull(registry);
            foreach (var type in registry.Types)
            {
                if (!types.TryAdd(type.RuntimeType, type) && types[type.RuntimeType].WireName != type.WireName)
                {
                    throw new ArgumentException($"Conflicting VMF names for type '{type.RuntimeType}'.", nameof(registries));
                }
            }
        }
        var combined = new SnapshotRegistry(types.Values, aliases: null, resolveReadOnly: ResolveReadOnly,
            ExternalSnapshotCodecs.Combine(sources.Select(source => source.ExternalValues)));
        combined.NativeReflection = ModelReflectionRegistry.ComposeForSnapshots(combined, sources, ResolveReadOnly);
        return combined;

        object? ResolveReadOnly(object? value)
        {
            if (value is null) return null;
            foreach (var source in sources)
            {
                object identity = source.ModelIdentity(value);
                if (!ReferenceEquals(identity, value)) return identity;
            }
            return value;
        }
    }
}
