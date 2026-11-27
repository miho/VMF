namespace Vmf.Runtime;

internal sealed partial class JournalWorkspace
{
    private void ApplyMembership(object source, SnapshotProperty property, JournalMembershipEdit edit, string path)
    {
        if (!property.IsUnorderedSet || edit.References != (property.IsReference || property.IsContainment)
            || property.Get(source) is not IJournalMembership members)
        {
            throw new InvalidOperationException($"Journal property '{path}' has no compatible unordered set.");
        }

        var removed = DecodeMembers(edit.Removed, property, path);
        var added = DecodeMembers(edit.Added, property, path);
        IEqualityComparer<object> comparer = edit.References
            ? ReferenceEqualityComparer.Instance : ScalarEquality<object>.Instance;
        var affected = new HashSet<object>(comparer);
        foreach (var member in removed.Concat(added))
        {
            if (!affected.Add(member))
            {
                throw new InvalidOperationException($"Duplicate journal membership value at '{path}'.");
            }
        }
        if (removed.Any(member => !members.ContainsMember(member)) || added.Any(members.ContainsMember))
        {
            throw new InvalidOperationException($"Journal membership precondition differs at '{path}'.");
        }

        long count = (long)members.Count - removed.Length + added.Length;
        RequireCollectionCapacity(property, count, path);
        members.ApplyMembership(removed, added);
        if (members.Count != count || removed.Any(members.ContainsMember) || added.Any(member => !members.ContainsMember(member)))
        {
            throw new InvalidOperationException($"Journal membership result differs at '{path}'.");
        }
    }

    private object[] DecodeMembers(IReadOnlyList<JournalValue> values, SnapshotProperty property, string path) =>
        values.Select(value => Decode(value, property, path)
            ?? throw new InvalidOperationException($"Null journal membership value at '{path}'.")).ToArray();

    private void ApplyMap(object source, SnapshotProperty property, JournalMapEdit edit, string path)
    {
        if (property.Kind != SnapshotPropertyKind.ScalarMap || (property.KeyType != typeof(string)) != edit.TypedKeys
            || property.Get(source) is not IJournalMap map)
        {
            throw new InvalidOperationException($"Journal property '{path}' has no compatible scalar map.");
        }

        var before = DecodeMapEntries(edit.Before, property, path);
        var after = DecodeMapEntries(edit.After, property, path);
        var comparer = ScalarEquality<object>.Instance;
        var beforeKeys = new HashSet<object>(before.Select(entry => entry.Key), comparer);
        var afterValues = after.ToDictionary(entry => entry.Key, entry => entry.Value, comparer);
        foreach (var entry in before)
        {
            if (!map.TryGetEntry(entry.Key, out var current) || !comparer.Equals(current, entry.Value))
            {
                throw new InvalidOperationException($"Journal map precondition differs at '{path}[{entry.Key}]'.");
            }
        }
        foreach (var entry in after)
        {
            if (!beforeKeys.Contains(entry.Key) && map.TryGetEntry(entry.Key, out _))
            {
                throw new InvalidOperationException($"Journal map key already exists at '{path}[{entry.Key}]'.");
            }
        }

        var expected = new List<(object Key, object? Value)>();
        foreach (var entry in map.Entries)
        {
            if (afterValues.TryGetValue(entry.Key, out var replacement))
            {
                expected.Add((entry.Key, replacement));
            }
            else if (!beforeKeys.Contains(entry.Key))
            {
                expected.Add(entry);
            }
        }
        foreach (var entry in after)
        {
            if (!beforeKeys.Contains(entry.Key)) expected.Add(entry);
        }

        RequireCollectionCapacity(property, expected.Count, path);
        map.ApplyEntries(before, after);
        using var actual = map.Entries.GetEnumerator();
        foreach (var entry in expected)
        {
            if (!actual.MoveNext() || !comparer.Equals(entry.Key, actual.Current.Key)
                || !comparer.Equals(entry.Value, actual.Current.Value))
            {
                throw new InvalidOperationException($"Journal map result differs at '{path}'.");
            }
        }
        if (actual.MoveNext())
        {
            throw new InvalidOperationException($"Journal map result differs at '{path}'.");
        }
    }

    private (object Key, object? Value)[] DecodeMapEntries(IReadOnlyList<JournalMapEntry> entries,
        SnapshotProperty property, string path)
    {
        var decoded = new (object Key, object? Value)[entries.Count];
        var keys = new HashSet<object>(ScalarEquality<object>.Instance);
        for (int index = 0; index < entries.Count; index++)
        {
            var entry = entries[index];
            if (entry.Key.Value is not { } key || entry.Key.IsReference || !Accepts(property.KeyType!, key))
            {
                throw new InvalidOperationException($"Journal map key is incompatible with '{path}'.");
            }
            if (!keys.Add(key))
            {
                throw new InvalidOperationException($"Duplicate journal map key at '{path}[{key}]'.");
            }
            decoded[index] = (key, Decode(entry.Value, property, $"{path}[{key}]"));
        }
        return decoded;
    }

    private static void RequireCollectionCapacity(SnapshotProperty property, long count, string path)
    {
        if (property.UpperBound >= 0 && count > property.UpperBound)
        {
            throw new InvalidOperationException($"Journal edit exceeds the collection upper bound at '{path}'.");
        }
    }
}

internal interface IJournalMembership
{
    int Count { get; }
    bool ContainsMember(object value);
    void ApplyMembership(object[] removed, object[] added);
}

internal interface IJournalMap : ISnapshotScalarMap
{
    bool TryGetEntry(object key, out object? value);
    void ApplyBuilderReplacement(IEnumerable<(object Key, object? Value)> values);
    void ApplyEntries((object Key, object? Value)[] removed, (object Key, object? Value)[] added);
}
