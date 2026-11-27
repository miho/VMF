using System.Collections;

namespace Vmf.Runtime;

/// <summary>Reconstructs reciprocal memberships before restoring ordered positions and null slots.</summary>
internal static class ReciprocalHydration
{
    internal readonly record struct Field(object Owner, SnapshotProperty Property, object? Value);

    internal static void Apply(IReadOnlyList<Field> fields)
    {
        if (fields.Count == 0) return;
        var declarations = new Dictionary<ReciprocalEndpoint, Declaration>(ReferenceEqualityComparer.Instance);
        foreach (var field in fields)
        {
            var endpoint = field.Property.Reciprocal!(field.Owner);
            object?[] values = endpoint.IsCollection ? ((IEnumerable)field.Value!).Cast<object?>().ToArray()
                : field.Value is null ? [] : [field.Value];
            if (!declarations.TryAdd(endpoint, new(field, values, Count(values))))
            {
                throw new InvalidOperationException($"Duplicate reciprocal field '{endpoint.Name}'.");
            }
        }

        var singles = new Dictionary<ReciprocalEndpoint, object>(ReferenceEqualityComparer.Instance);
        foreach (var (endpoint, declaration) in declarations)
        {
            foreach (var (member, occurrences) in declaration.Counts)
            {
                var peer = endpoint.Opposite(member);
                if (!ReferenceEquals(peer.Opposite(endpoint.Owner), endpoint))
                {
                    throw new InvalidOperationException($"Invalid opposite declaration for '{endpoint.Name}'.");
                }
                Claim(endpoint, member, singles);
                Claim(peer, endpoint.Owner, singles);
                if (!endpoint.IsUnique && !peer.IsUnique && declarations.TryGetValue(peer, out var opposite)
                    && opposite.Counts.TryGetValue(endpoint.Owner, out int inverseOccurrences) && occurrences != inverseOccurrences)
                {
                    throw new InvalidOperationException($"Conflicting reciprocal sequence counts at '{endpoint.Name}'.");
                }
            }
        }

        foreach (var (endpoint, member) in singles)
        {
            if (!ReferenceEquals(endpoint.Value, member)) endpoint.Assign(member);
        }
        foreach (var (endpoint, declaration) in declarations)
        {
            if (!endpoint.IsCollection) continue;
            var current = Count(endpoint.Values);
            foreach (var (member, desiredCount) in declaration.Counts)
            {
                int present = current.GetValueOrDefault(member);
                for (int index = present; index < desiredCount; index++)
                {
                    declaration.Field.Property.Add!(declaration.Field.Owner, member);
                }
            }
        }
        foreach (var (endpoint, declaration) in declarations)
        {
            if (endpoint.IsCollection && endpoint.IsUnique
                && !Count(endpoint.Values).Keys.ToHashSet(ReferenceEqualityComparer.Instance).SetEquals(declaration.Counts.Keys))
            {
                throw new InvalidOperationException($"Conflicting reciprocal membership at '{endpoint.Name}'.");
            }
            if (!endpoint.IsOrdered) continue;
            var sequence = new List<object?>(declaration.Values);
            foreach (object? member in endpoint.Values)
            {
                // Explicit positions take precedence. Opposites omitted from this end are appended.
                if (member is null) continue;
                if (!declaration.Counts.TryGetValue(member, out int remaining) || remaining == 0) sequence.Add(member);
                else declaration.Counts[member] = remaining - 1;
            }
            endpoint.RestoreSnapshotSequence(sequence);
        }
    }

    private static void Claim(ReciprocalEndpoint endpoint, object member, Dictionary<ReciprocalEndpoint, object> singles)
    {
        if (endpoint.IsCollection) return;
        if (endpoint.Value is { } current && !ReferenceEquals(current, member)
            || !singles.TryAdd(endpoint, member) && !ReferenceEquals(singles[endpoint], member))
        {
            throw new InvalidOperationException($"Conflicting reciprocal scalar values at '{endpoint.Name}'.");
        }
    }

    private static Dictionary<object, int> Count(IEnumerable<object?> values)
    {
        var counts = new Dictionary<object, int>(ReferenceEqualityComparer.Instance);
        foreach (var value in values)
        {
            if (value is not null) counts[value] = counts.GetValueOrDefault(value) + 1;
        }
        return counts;
    }

    private sealed record Declaration(Field Field, IReadOnlyList<object?> Values, Dictionary<object, int> Counts);
}
