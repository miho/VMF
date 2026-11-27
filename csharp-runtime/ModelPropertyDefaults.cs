using System.Collections;
using System.Collections.Concurrent;
using System.Collections.ObjectModel;
using System.Runtime.CompilerServices;

namespace Vmf.Runtime;

public sealed partial class ModelProperty
{
    private static readonly IReadOnlyDictionary<object, object?> EmptyMap =
        new ReadOnlyDictionary<object, object?>(new Dictionary<object, object?>());
    private static readonly ConditionalWeakTable<object, ConcurrentDictionary<string, object>> Overrides = new();

    /// <summary>Gets this instance's effective default, projecting any model values for a read-only view.</summary>
    public object? DefaultValue
    {
        get
        {
            var value = EffectiveDefault();
            if (model.IsReadOnly && IsReference && value is not null
                && !(model.Schema.Snapshot.IsImmutable && property.Snapshot is { } snapshot
                    && ReferenceCollectionDefaults.IsBound(snapshot)))
            {
                return IsCollection
                    ? Array.AsReadOnly(((IReadOnlyList<object?>)value).Select(ProjectDefaultMember).ToArray())
                    : ProjectDefaultMember(value);
            }
            return value;
        }
    }

    /// <summary>Reports whether a new per-instance default can be configured on this field.</summary>
    public bool CanSetDefault => !model.IsReadOnly && property.Snapshot is { IsContainment: false }
        && (IsCollection || property.MutableSet is not null);

    /// <summary>Indicates whether the current field differs from its default.</summary>
    public bool IsSet => !MatchesDefault(EffectiveDefault());

    /// <summary>Changes this instance's effective default. Null clears the override and restores the declared default.</summary>
    /// <remarks>A field that held its previous default is reset through the native mutation API. Otherwise its value is retained.
    /// Model copies preserve overrides for managed maps, managed scalar collections and non-owning identity sets.
    /// Document loading uses the declared default.</remarks>
    public void SetDefault(object? value)
    {
        if (!CanSetDefault)
        {
            throw new InvalidOperationException($"Model property '{Name}' cannot override its default on this view.");
        }

        var configured = value is null ? null : SnapshotOverride(value, property.Snapshot!);
        bool apply = !IsSet;
        if (configured is not null)
        {
            Overrides.GetValue(model.Identity, static _ => new(StringComparer.Ordinal))[Name] = configured;
        }
        else if (Overrides.TryGetValue(model.Identity, out var defaults))
        {
            defaults.TryRemove(Name, out _);
        }
        if (apply) Reset();
    }

    internal static bool CopiesManagedDefault(SnapshotProperty property) => property.Kind switch
    {
        SnapshotPropertyKind.ScalarMap or SnapshotPropertyKind.ScalarSet or SnapshotPropertyKind.OrderedScalarSet => true,
        SnapshotPropertyKind.ScalarList => property.UpperBound >= 0 || property.Schema.HasExecutableDefault,
        _ => false,
    };

    internal static bool HasManagedDefaultOverride(SnapshotType type, object source) =>
        Overrides.TryGetValue(source, out var defaults) && type.Properties.Any(property =>
            CopiesManagedDefault(property) && defaults.ContainsKey(property.ModelName));

    internal static void CopyManagedDefaultOverride(SnapshotProperty property, object source, object copy)
    {
        if (CopiesManagedDefault(property) && Overrides.TryGetValue(source, out var defaults)
            && defaults.TryGetValue(property.ModelName, out object? value))
        {
            Overrides.GetValue(copy, static _ => new(StringComparer.Ordinal))[property.ModelName] = value;
        }
    }

    internal static IReadOnlyList<object?>? ReferenceDefaultOverride(SnapshotProperty property, object owner) =>
        property.Kind is SnapshotPropertyKind.ReferenceSet or SnapshotPropertyKind.OrderedReferenceSet
            && Overrides.TryGetValue(owner, out var fields) && fields.TryGetValue(property.ModelName, out var value)
            ? (IReadOnlyList<object?>)value : null;

    internal static void StoreReferenceDefaultOverride(object owner, string name, IReadOnlyList<object?> members) =>
        Overrides.GetValue(owner, static _ => new(StringComparer.Ordinal))[name] = members;

    /// <summary>Returns a writable field to its default through its native model operations.</summary>
    public void Reset()
    {
        if (model.IsReadOnly)
        {
            throw new InvalidOperationException($"Model property '{Name}' has no writable field on this view.");
        }

        var expected = EffectiveDefault();
        if (IsCollection && expected is null)
        {
            throw new InvalidOperationException($"The reflected reference default for '{Name}' is not available during construction.");
        }
        if (!IsCollection)
        {
            Set(expected);
            return;
        }

        switch (property.MutableGet(model.Identity))
        {
            case IJournalMap map when property.Snapshot!.Schema.HasExecutableDefault:
                ManagedCollectionDefaults.RestoreMap(map, ((IReadOnlyDictionary<object, object?>)expected!)
                    .Select(entry => (entry.Key, entry.Value)).ToArray());
                break;
            case IJournalMap map:
                ResetMap(map, (IReadOnlyDictionary<object, object?>)expected!);
                break;
            case IManagedDefaultList sequence when property.Snapshot!.Schema.HasExecutableDefault:
                sequence.ReplaceManagedContents((IReadOnlyList<object?>)expected!);
                break;
            case IJournalSequence sequence:
                if (!MatchesDefault(expected)) sequence.Replace((IReadOnlyList<object?>)expected!);
                break;
            case IJournalMembership membership when membership is IEnumerable elements:
                var desired = (IReadOnlyList<object?>)expected!;
                var comparer = MemberComparer();
                var present = elements.Cast<object>();
                membership.ApplyMembership(present.Except(desired.Cast<object>(), comparer).ToArray(),
                    desired.Cast<object>().Except(present, comparer).ToArray());
                break;
            default:
                throw new InvalidOperationException($"Model property '{Name}' cannot reset its collection.");
        }
    }

    private object? EffectiveDefault()
    {
        if (Overrides.TryGetValue(model.Identity, out var defaults) && defaults.TryGetValue(Name, out var configured))
        {
            return configured;
        }
        var snapshot = property.Snapshot;
        if (snapshot is null) return null;
        if (ReferenceCollectionDefaults.IsBound(snapshot))
        {
            if (ReferenceCollectionDefaults.TryGet(model.Identity, snapshot.ModelName, out var captured)) return captured;
            if (model.IsReadOnly)
            {
                throw new InvalidOperationException($"The reflected reference default for '{Name}' is not available during construction.");
            }
            return null;
        }
        if (property.Field.DeclaredDefaultFactory is { } evaluate
            && (snapshot.Kind == SnapshotPropertyKind.Scalar || snapshot.Schema.HasExecutableDefault
                && (snapshot.IsMap || snapshot.IsScalarCollection)))
        {
            var value = evaluate();
            return snapshot.IsMap || snapshot.IsCollection ? SnapshotOverride(value!, snapshot) : value;
        }
        if (snapshot.IsMap) return EmptyMap;
        if (snapshot.IsCollection) return Array.Empty<object?>();
        if (snapshot.Kind != SnapshotPropertyKind.Scalar) return null;
        if (snapshot.Schema.Default is { } declared)
        {
            return SnapshotJson.ReadScalar(declared, snapshot.ValueType, snapshot.WireName);
        }
        return snapshot.ValueType.IsValueType && Nullable.GetUnderlyingType(snapshot.ValueType) is null
            ? Activator.CreateInstance(snapshot.ValueType) : null;
    }

    private object SnapshotOverride(object value, SnapshotProperty snapshot)
    {
        if (snapshot.IsMap)
        {
            var copy = new Dictionary<object, object?>(ScalarEquality<object>.Instance);
            foreach (var (key, member) in MapEntries(value))
            {
                RequireDefaultType(key, snapshot.KeyType!, allowNull: false);
                RequireDefaultType(member, snapshot.ValueType, allowNull: true);
                if (!copy.TryAdd(key, member)) throw InvalidDefault("A map contains a duplicate key.");
            }
            ScalarCollectionBounds.CheckCapacity(UpperBound, copy.Count);
            return new ReadOnlyDictionary<object, object?>(copy);
        }
        if (!snapshot.IsCollection)
        {
            RequireDefaultType(value, snapshot.ValueType, allowNull: false);
            return value;
        }
        if (value is not IEnumerable source || value is string)
        {
            throw InvalidDefault("Expected an enumerable collection.");
        }

        var copyMembers = new List<object?>();
        var unique = snapshot.HasUniqueMembers ? new HashSet<object?>(MemberComparer()) : null;
        bool allowsNull = snapshot.AllowNullElements || snapshot.Kind == SnapshotPropertyKind.ReferenceList;
        foreach (object? member in source)
        {
            RequireDefaultType(member, snapshot.ValueType, allowsNull);
            if (unique is not null && !unique.Add(member))
            {
                if (snapshot.Kind == SnapshotPropertyKind.ScalarSet) continue;
                throw InvalidDefault("A unique collection contains a duplicate member.");
            }
            copyMembers.Add(member);
        }
        ScalarCollectionBounds.CheckCapacity(UpperBound, copyMembers.Count);
        return copyMembers.AsReadOnly();
    }

    private void RequireDefaultType(object? value, Type type, bool allowNull)
    {
        Type expected = Nullable.GetUnderlyingType(type) ?? type;
        if (value is null ? !allowNull : !expected.IsInstanceOfType(value))
        {
            throw InvalidDefault($"Expected a value of type '{type}'.");
        }
    }

    private IEnumerable<(object Key, object? Value)> MapEntries(object value)
    {
        if (value is ISnapshotScalarMap scalar)
        {
            foreach (var entry in scalar.Entries) yield return entry;
            yield break;
        }
        if (value is IDictionary dictionary)
        {
            foreach (DictionaryEntry entry in dictionary) yield return (entry.Key, entry.Value);
            yield break;
        }
        throw InvalidDefault("Expected a dictionary or a managed scalar map.");
    }

    private bool MatchesDefault(object? expected)
    {
        object? value = property.MutableGet(model.Identity);
        if (property.Snapshot is { IsMap: true })
        {
            var desired = (IReadOnlyDictionary<object, object?>)expected!;
            int count = 0;
            foreach (var entry in ((ISnapshotScalarMap)value!).Entries)
            {
                if (!desired.TryGetValue(entry.Key, out var member)
                    || !ScalarEquality<object?>.Instance.Equals(entry.Value, member)) return false;
                count++;
            }
            return count == desired.Count;
        }
        if (IsCollection)
        {
            if (expected is null) return false;
            var members = ((IEnumerable)value!).Cast<object?>();
            var desired = (IReadOnlyList<object?>)expected!;
            var comparer = MemberComparer();
            return Kind is SnapshotPropertyKind.ScalarSet or SnapshotPropertyKind.ReferenceSet
                ? new HashSet<object?>(members, comparer).SetEquals(desired)
                : members.SequenceEqual(desired, comparer);
        }
        return MemberComparer().Equals(value, expected);
    }

    private IEqualityComparer<object?> MemberComparer() =>
        IsReference || IsContainment || IsContainer ? ReferenceEqualityComparer.Instance : ScalarEquality<object?>.Instance;

    private object? ProjectDefaultMember(object? value) => value is null ? null : model.Registry.ProjectReadOnly(value);

    private ArgumentException InvalidDefault(string reason) => new($"Invalid default for model property '{Name}'. {reason}");

    private static void ResetMap(IJournalMap map, IReadOnlyDictionary<object, object?> expected)
    {
        var pending = expected.GetEnumerator();
        bool hasNext = pending.MoveNext();
        var removed = new List<(object Key, object? Value)>();
        foreach (var entry in map.Entries)
        {
            if (hasNext && ScalarEquality<object>.Instance.Equals(entry.Key, pending.Current.Key))
            {
                hasNext = pending.MoveNext();
            }
            else
            {
                removed.Add(entry);
            }
        }
        if (removed.Count != 0) map.ApplyEntries(removed.ToArray(), []);
        var added = expected.Where(entry => !map.TryGetEntry(entry.Key, out var value)
            || !ScalarEquality<object?>.Instance.Equals(entry.Value, value)).Select(entry => (entry.Key, entry.Value)).ToArray();
        if (added.Length != 0) map.ApplyEntries([], added);
    }
}
