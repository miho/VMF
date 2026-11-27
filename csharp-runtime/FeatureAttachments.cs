namespace Vmf.Runtime;

/// <summary>Associates live features with exact owner instances, including read-only views.</summary>
public sealed class FeatureAttachments
{
    private readonly SnapshotRegistry registry;
    private readonly object gate = new();
    private readonly Dictionary<object, List<Attachment>> owners = new(ReferenceEqualityComparer.Instance);

    public FeatureAttachments(SnapshotRegistry registry)
    {
        ArgumentNullException.ThrowIfNull(registry);
        this.registry = registry;
    }

    /// <summary>Attaches one live value per concrete feature type. Reattaching the same instance has no effect.</summary>
    public void Attach(object owner, object feature)
    {
        RequireType(owner, nameof(owner));
        Type type = RequireType(feature, nameof(feature));
        lock (gate)
        {
            if (!owners.TryGetValue(owner, out var group))
            {
                owners.Add(owner, [new(type, feature)]);
                return;
            }

            int index = group.FindIndex(attachment => attachment.Type == type);
            if (index < 0)
            {
                group.Add(new(type, feature));
            }
            else if (!ReferenceEquals(group[index].Value, feature))
            {
                throw new ArgumentException($"Feature already attached: {type}.", nameof(feature));
            }
        }
    }

    /// <summary>Returns the first live feature assignable to the requested type.</summary>
    public T? Find<T>(object? owner) where T : class => (T?)Find(owner, typeof(T));

    public object? Find(object? owner, Type featureType)
    {
        ArgumentNullException.ThrowIfNull(featureType);
        if (owner is null) return null;
        lock (gate)
        {
            if (!owners.TryGetValue(owner, out var group)) return null;
            foreach (var attachment in group)
            {
                if (featureType.IsInstanceOfType(attachment.Value)) return attachment.Value;
            }
            return null;
        }
    }

    /// <summary>Returns an immutable copy of the attachment order containing the exact live feature instances.</summary>
    public IReadOnlyList<object> All(object? owner)
    {
        if (owner is null) return Array.Empty<object>();
        lock (gate)
        {
            return owners.TryGetValue(owner, out var group)
                ? Array.AsReadOnly(group.Select(attachment => attachment.Value).ToArray())
                : Array.Empty<object>();
        }
    }

    /// <summary>Detaches the specified live instance from the exact owner.</summary>
    public bool Detach(object? owner, object? feature)
    {
        if (owner is null || feature is null) return false;
        lock (gate)
        {
            if (!owners.TryGetValue(owner, out var group)) return false;
            int index = group.FindIndex(attachment => ReferenceEquals(attachment.Value, feature));
            if (index < 0) return false;

            group.RemoveAt(index);
            if (group.Count == 0) owners.Remove(owner);
            return true;
        }
    }

    private Type RequireType(object value, string parameter)
    {
        ArgumentNullException.ThrowIfNull(value, parameter);
        var type = registry.ModelIdentity(value).GetType();
        if (!registry.TryTypeForClass(type, out _)) throw new ArgumentException($"Unregistered VMF model type '{type}'.", parameter);
        return type;
    }

    private readonly record struct Attachment(Type Type, object Value);
}
