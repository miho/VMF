using System.Collections;

namespace Vmf.Runtime;

/// <summary>Binds stable object IDs, types and property keys to their model implementation.</summary>
public sealed class JournalBinding
{
    private readonly Func<object, string> getId;
    private readonly Func<object, string, string> getPropertyKey;
    private Func<object, string, string>? InboundPropertyName { get; init; }
    private Func<object, string>? ObjectType { get; init; }
    private Func<string, string, object>? ObjectFactory { get; init; }
    private Func<string, string, Func<object>, object>? ImmutableFactory { get; init; }

    public JournalBinding(string model, int version, Func<object, string> getId,
        Func<object, string, string>? getPropertyKey = null)
        : this(model, version, getId, getPropertyKey, JournalValueCodecs.Standard)
    {
    }

    public JournalBinding(string model, int version, Func<object, string> getId,
        Func<object, string, string>? getPropertyKey, JournalValueCodecs valueCodecs)
    {
        Model = ChangeJournal.CheckName(model);
        ArgumentOutOfRangeException.ThrowIfLessThan(version, 1);
        Version = version;
        this.getId = getId ?? throw new ArgumentNullException(nameof(getId));
        this.getPropertyKey = getPropertyKey ?? ((_, property) => property);
        ValueCodecs = valueCodecs ?? throw new ArgumentNullException(nameof(valueCodecs));
    }

    private JournalBinding(JournalBinding source)
        : this(source.Model, source.Version, source.getId, source.getPropertyKey, source.ValueCodecs)
    {
        InboundPropertyName = source.InboundPropertyName;
        ObjectType = source.ObjectType;
        ObjectFactory = source.ObjectFactory;
        ImmutableFactory = source.ImmutableFactory;
    }

    /// <summary>Returns a binding with an explicit wire-key to model-property mapping.</summary>
    /// <remarks>The mapping must invert <see cref="PropertyKey"/> for journal fields, as in Java VMF.</remarks>
    public JournalBinding WithPropertyName(Func<object, string, string> getPropertyName)
    {
        ArgumentNullException.ThrowIfNull(getPropertyName);
        return new(this) { InboundPropertyName = getPropertyName };
    }

    /// <summary>Returns a binding with application-specific journal type names and creation factories.</summary>
    /// <remarks>The factory returns a fresh registered mutable instance with the requested ID, or an
    /// immutable prototype. An optional immutable factory may assign an application ID to the instance
    /// returned by the initializer. Snapshot type names and snapshot factories are independent.</remarks>
    public JournalBinding WithObjectTypes(Func<object, string> getType, Func<string, string, object> create,
        Func<string, string, Func<object>, object>? createImmutable = null)
    {
        ArgumentNullException.ThrowIfNull(getType);
        ArgumentNullException.ThrowIfNull(create);
        return new(this) { ObjectType = getType, ObjectFactory = create, ImmutableFactory = createImmutable };
    }

    public string Model { get; }
    public int Version { get; }
    public JournalValueCodecs ValueCodecs { get; }

    public string Id(object instance) => ChangeJournal.CheckName(getId(instance));

    public string PropertyKey(object instance, string property) =>
        ChangeJournal.CheckName(getPropertyKey(instance, property));

    internal bool HasInboundPropertyName => InboundPropertyName is not null;
    internal bool HasObjectTypes => ObjectType is not null;

    internal string CheckedPropertyKey(object instance, string property)
    {
        string key = PropertyKey(instance, property);
        if (InboundPropertyName is not null && PropertyName(instance, key) != property)
            throw new InvalidOperationException($"Journal property mapping for '{key}' does not round-trip.");
        return key;
    }

    internal string PropertyName(object instance, string key) =>
        ChangeJournal.CheckName(InboundPropertyName!(instance, key));

    internal string TypeName(object instance, SnapshotType type, SnapshotRegistry registry) =>
        ObjectType is null ? registry.OutputName(type) : ChangeJournal.CheckName(ObjectType(instance));

    internal object CreatePrototype(JournalCreation creation) =>
        ObjectFactory!(creation.Type, creation.Id);

    internal object CreateImmutable(JournalCreation creation, Func<object> initialize) =>
        ImmutableFactory is null ? initialize() : ImmutableFactory(creation.Type, creation.Id, initialize);
}

/// <summary>Replays a portable VMF journal on an independent copy of its checkpoint.</summary>
public static class JournalReplayer
{
    public static T Replay<T>(T checkpoint, JournalBinding binding, string checkpointId, ChangeJournal journal,
        SnapshotRegistry registry) where T : class
    {
        ArgumentNullException.ThrowIfNull(checkpoint);
        ArgumentNullException.ThrowIfNull(journal);
        ArgumentNullException.ThrowIfNull(binding);
        ArgumentNullException.ThrowIfNull(registry);
        if (journal.Model != binding.Model || journal.ModelVersion != binding.Version
            || journal.Checkpoint != checkpointId || journal.Root != binding.Id(checkpoint))
        {
            throw new InvalidOperationException("The journal and checkpoint identities or model versions differ.");
        }

        using var original = new JournalWorkspace(checkpoint, binding, registry);
        var copy = original.CopyCheckpoint(checkpoint, preserveReferenceDefaults: true);
        using var replay = new JournalWorkspace(copy, binding, registry);
        replay.ValidateCheckpointCopy(original, copy, journal.Root);
        foreach (var frame in journal.Transactions)
        {
            replay.Apply(frame);
            replay.VerifyReachable(copy);
        }
        return copy;
    }
}

internal sealed partial class JournalWorkspace : IDisposable
{
    private readonly Dictionary<string, object> objects;
    private readonly Dictionary<object, string> identities;
    private readonly JournalBinding binding;
    private readonly SnapshotRegistry registry;
    private readonly bool requiresNativeCheckpointCopy;
    private Func<object, bool>? isLiveInstance;

    internal JournalWorkspace(object root, JournalBinding binding, SnapshotRegistry registry)
    {
        this.binding = binding;
        this.registry = registry;
        objects = IndexReachable(root, binding, registry, out requiresNativeCheckpointCopy);
        identities = objects.ToDictionary(entry => entry.Value, entry => entry.Key, ReferenceEqualityComparer.Instance);
    }

    internal bool ContainsInstance(object source) => identities.ContainsKey(source);

    internal T CopyCheckpoint<T>(T root, bool preserveReferenceDefaults = false) where T : class
    {
        if ((registry.HasUntypedObjectValues || registry.HasOmittedPlainMapDefaults)
            && objects.Values.Any(node => registry.TypeForClass(node.GetType(), "$").Properties
                .Any(property => property.IsUntypedObject || property.HasOmittedPlainMapDefault && property.Get(node) is null)))
        {
            // Java clones live checkpoints to retain boxed types and cleared maps that snapshots omit.
            var reflection = registry.NativeReflection
                ?? throw new InvalidOperationException("This journal checkpoint needs native model reflection.");
            return reflection.DeepCopyCheckpoint(root, ContainsInstance);
        }
        if (registry.NativeReflection is { } native
            && (requiresNativeCheckpointCopy && native.CoversSnapshotTypes(registry)
                || registry.HasExternalValueProperties && objects.Values.Any(node =>
                    registry.TypeForClass(node.GetType(), "$").Properties.Any(property => property.ExternalType is not null))
                || preserveReferenceDefaults && registry.HasReferenceCollectionDefaults
                    && ReferenceCollectionDefaults.RequiresNativeCheckpointCopy(root, registry)
                || registry.HasImmutableTypes && objects.Values.Any(node => registry.TypeForClass(node.GetType(), "$").IsImmutable)
                    && native.CoversSnapshotTypes(registry)))
        {
            // A live copy retains managed defaults and is independent of the document depth limit.
            // Java checkpoint clones share immutable objects, whose IDs can live in an application identity table.
            // External values in a journal likewise use their own codecs, independently of checkpoint documents.
            return native.DeepCopyCheckpoint(root, ContainsInstance);
        }
        return SnapshotJson.ReadCheckpoint<T>(SnapshotJson.Write(root, registry), registry, ContainsInstance);
    }

    internal bool Register(string id, object source)
    {
        if (identities.TryGetValue(source, out var previous))
        {
            if (previous != id)
            {
                throw new InvalidOperationException($"Object ID changed from '{previous}' to '{id}'.");
            }
            return false;
        }
        if (!objects.TryAdd(id, source))
        {
            throw new InvalidOperationException($"Duplicate journal object ID '{id}'.");
        }

        identities.Add(source, id);
        return true;
    }

    internal bool Contains(string id, object source) =>
        objects.TryGetValue(id, out var member) && ReferenceEquals(member, source);

    internal JournalValue Encode(object? value, bool isReference)
    {
        if (value is null || !isReference)
        {
            return binding.ValueCodecs.Encode(value);
        }
        string id = binding.Id(value);
        if (!Contains(id, value))
        {
            throw new InvalidOperationException($"Reference target '{id}' is outside the journal checkpoint.");
        }
        return registry.TypeForClass(value.GetType(), "$").IsImmutable
            ? JournalValue.ImmutableReference(id) : JournalValue.Reference(id);
    }

    internal void Apply(JournalFrame frame)
    {
        foreach (var operation in frame.Operations)
        {
            if (!objects.TryGetValue(operation.Target, out var source) || binding.Id(source) != operation.Target)
            {
                throw new InvalidOperationException($"Unknown or changed journal target '{operation.Target}'.");
            }
            if (registry.TypeForClass(source.GetType(), "$").IsImmutable)
            {
                throw new InvalidOperationException($"Journal target '{operation.Target}' is immutable.");
            }

            PrepareCreations(operation.Created);
            string path = operation.Target + "." + operation.Property;
            var property = FindProperty(source, operation.Property);
            switch (operation)
            {
                case JournalSet set when property.Kind is SnapshotPropertyKind.Scalar or SnapshotPropertyKind.Reference or SnapshotPropertyKind.Containment
                    && property.Set is not null:
                    ApplySingle(source, property, set, path);
                    break;
                case JournalListEdit list:
                    ApplyList(source, property, list, path);
                    break;
                case JournalMembershipEdit membership:
                    ApplyMembership(source, property, membership, path);
                    break;
                case JournalMapEdit map:
                    ApplyMap(source, property, map, path);
                    break;
                default:
                    throw new InvalidOperationException($"Unsupported journal property operation at '{path}'.");
            }

            if (binding.Id(source) != operation.Target)
            {
                throw new InvalidOperationException($"The journal changed checkpoint object ID '{operation.Target}'.");
            }
        }
    }

    private SnapshotProperty FindProperty(object source, string key)
    {
        var type = registry.TypeForClass(source.GetType(), "$");
        string? name = binding.HasInboundPropertyName ? binding.PropertyName(source, key) : null;
        SnapshotProperty? selected = null;
        foreach (var property in type.Properties)
        {
            if (name is null ? binding.PropertyKey(source, property.ModelName) != key : property.ModelName != name)
            {
                continue;
            }
            if (selected is not null)
            {
                throw new InvalidOperationException($"Ambiguous journal property '{key}'.");
            }
            selected = property;
        }
        if (name is not null && (selected is null || binding.PropertyKey(source, name) != key))
            throw new InvalidOperationException($"Invalid journal property '{key}'.");
        return selected ?? throw new InvalidOperationException($"Unknown journal property '{key}'.");
    }

    private void ApplySingle(object source, SnapshotProperty property, JournalSet operation, string path)
    {
        RequireMatching(property.Get(source), operation.Before, HasModelValues(property), path);
        property.Set!(source, Decode(operation.After, property, path));
        RequireMatching(property.Get(source), operation.After, HasModelValues(property), path);
    }

    private void ApplyList(object source, SnapshotProperty property, JournalListEdit edit, string path)
    {
        object? collection = property.Get(source);
        if (collection is not IJournalSequence sequence || collection is not IEnumerable members)
        {
            throw new InvalidOperationException($"Journal property '{path}' has no compatible positional collection.");
        }

        var values = members.Cast<object?>().ToList();
        var indices = edit.Indices;
        for (int position = 0; position < edit.Before.Count; position++)
        {
            int index = indices[position];
            if (index >= values.Count)
            {
                throw new InvalidOperationException($"Journal index {index} is outside '{path}'.");
            }
            RequireMatching(values[index], edit.Before[position], HasModelValues(property), path);
        }
        switch (edit.Kind)
        {
            case JournalListEditKind.Add:
                for (int position = 0; position < indices.Count; position++)
                {
                    int index = indices[position];
                    if (index > values.Count)
                    {
                        throw new InvalidOperationException($"Journal index {index} is outside '{path}'.");
                    }
                    values.Insert(index, Decode(edit.After[position], property, path));
                }
                break;
            case JournalListEditKind.Remove:
                for (int position = indices.Count - 1; position >= 0; position--)
                {
                    values.RemoveAt(indices[position]);
                }
                break;
            case JournalListEditKind.Set:
                for (int position = 0; position < indices.Count; position++)
                {
                    values[indices[position]] = Decode(edit.After[position], property, path);
                }
                break;
        }

        if (property.UpperBound >= 0 && values.Count > property.UpperBound
            || property.HasUniqueMembers && values.Select(value => Encode(value, HasModelValues(property)))
                .Select(value => (value.Kind, value.Text, value.CodecId)).Distinct().Count() != values.Count)
        {
            throw new InvalidOperationException($"Journal edit violates collection bounds or uniqueness at '{path}'.");
        }
        sequence.Replace(values);
    }

    private object? Decode(JournalValue record, SnapshotProperty property, string path)
    {
        object? value;
        if (record.IsReference)
        {
            value = ResolveJournalReference(record, property, path);
        }
        else if (HasModelValues(property) && record.Kind != "NULL")
        {
            throw new InvalidOperationException($"Expected a model reference at '{path}'.");
        }
        else
        {
            value = record.IsExternal ? binding.ValueCodecs.Decode(record) : record.Value;
        }
        if (!Accepts(property.ValueType, value) || value is null && property.IsCollection
            && !property.AllowNullElements && !property.HasNullableModelSlots)
        {
            throw new InvalidOperationException($"Journal value is incompatible with '{path}'.");
        }
        return value;
    }

    private void RequireMatching(object? current, JournalValue expected, bool isReference, string path)
    {
        if (expected.IsExternal) binding.ValueCodecs.Decode(expected);
        var actual = Encode(current, isReference);
        if (actual.Kind != expected.Kind || actual.Text != expected.Text || actual.CodecId != expected.CodecId)
        {
            throw new InvalidOperationException($"Journal value disagrees with the model at '{path}'.");
        }
    }

    private static bool HasModelValues(SnapshotProperty property) => property.IsReference || property.IsContainment;

    private static bool Accepts(Type expected, object? value) =>
        value is null ? !expected.IsValueType || Nullable.GetUnderlyingType(expected) is not null
            : (Nullable.GetUnderlyingType(expected) ?? expected).IsInstanceOfType(value);

    public void Dispose()
    {
        objects.Clear();
        identities.Clear();
    }
}

internal interface IJournalSequence
{
    void Replace(IReadOnlyList<object?> values);
}
