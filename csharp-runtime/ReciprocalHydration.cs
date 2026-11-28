using System.Collections;
using System.Runtime.InteropServices;

namespace Vmf.Runtime;

/// <summary>Reconstructs reciprocal memberships before restoring ordered positions and null slots.</summary>
internal static class ReciprocalHydration
{
    internal readonly record struct Field(object Owner, SnapshotProperty Property, object? Value);

    internal static void Apply(IReadOnlyList<Field> fields)
    {
        if (fields.Count == 0) return;
        var declarations = new Dictionary<ReciprocalEndpoint, Declaration>(fields.Count, ReferenceEqualityComparer.Instance);
        for (int index = 0; index < fields.Count; index++)
        {
            var field = fields[index];
            var endpoint = field.Property.Reciprocal!(field.Owner);
            Declaration declaration;
            if (endpoint.IsCollection)
            {
                object?[] values = ((IEnumerable)field.Value!).Cast<object?>().ToArray();
                declaration = new(field, null, values, Count(values));
            }
            else
            {
                // A single reference names at most one opposite and needs no table of counts.
                declaration = new(field, field.Value, null, null);
            }
            if (!declarations.TryAdd(endpoint, declaration))
            {
                throw new InvalidOperationException($"Duplicate reciprocal field '{endpoint.Name}'.");
            }
        }

        var singles = new Dictionary<ReciprocalEndpoint, object>(fields.Count, ReferenceEqualityComparer.Instance);
        foreach (var (endpoint, declaration) in declarations)
        {
            if (declaration.Counts is null)
            {
                if (declaration.Member is { } single) Declare(endpoint, single, 1);
                continue;
            }
            foreach (var (member, occurrences) in declaration.Counts) Declare(endpoint, member, occurrences);
        }

        foreach (var (endpoint, member) in singles)
        {
            if (!ReferenceEquals(endpoint.Value, member)) endpoint.Assign(member);
        }
        foreach (var (endpoint, declaration) in declarations)
        {
            if (!endpoint.IsCollection) continue;
            var current = Count(endpoint.Values);
            foreach (var (member, desiredCount) in declaration.Counts!)
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
            if (!endpoint.IsCollection) continue;
            var counts = declaration.Counts!;
            if (endpoint.IsUnique
                && !Count(endpoint.Values).Keys.ToHashSet(ReferenceEqualityComparer.Instance).SetEquals(counts.Keys))
            {
                throw new InvalidOperationException($"Conflicting reciprocal membership at '{endpoint.Name}'.");
            }
            if (!endpoint.IsOrdered) continue;
            var sequence = new List<object?>(declaration.Values!);
            foreach (object? member in endpoint.Values)
            {
                // Explicit positions take precedence. Opposites omitted from this end are appended.
                if (member is null) continue;
                if (!counts.TryGetValue(member, out int remaining) || remaining == 0) sequence.Add(member);
                else counts[member] = remaining - 1;
            }
            endpoint.RestoreSnapshotSequence(sequence);
        }

        void Declare(ReciprocalEndpoint endpoint, object member, int occurrences)
        {
            var peer = endpoint.Opposite(member);
            if (!ReferenceEquals(peer.Opposite(endpoint.Owner), endpoint))
            {
                throw new InvalidOperationException($"Invalid opposite declaration for '{endpoint.Name}'.");
            }
            Claim(endpoint, member, singles);
            Claim(peer, endpoint.Owner, singles);
            if (!endpoint.IsUnique && !peer.IsUnique && declarations.TryGetValue(peer, out var opposite)
                && opposite.Counts!.TryGetValue(endpoint.Owner, out int inverseOccurrences) && occurrences != inverseOccurrences)
            {
                throw new InvalidOperationException($"Conflicting reciprocal sequence counts at '{endpoint.Name}'.");
            }
        }
    }

    private static void Claim(ReciprocalEndpoint endpoint, object member, Dictionary<ReciprocalEndpoint, object> singles)
    {
        if (endpoint.IsCollection) return;
        bool conflict = endpoint.Value is { } current && !ReferenceEquals(current, member);
        if (!conflict)
        {
            ref object? claimed = ref CollectionsMarshal.GetValueRefOrAddDefault(singles, endpoint, out bool exists);
            if (exists) conflict = !ReferenceEquals(claimed, member);
            else claimed = member;
        }
        if (conflict) throw new InvalidOperationException($"Conflicting reciprocal scalar values at '{endpoint.Name}'.");
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

    /// <summary>
    /// The opposites that one endpoint declares: the one of a single reference, or the values of a collection
    /// with the number of occurrences of each.
    /// </summary>
    private readonly record struct Declaration(Field Field, object? Member, IReadOnlyList<object?>? Values,
        Dictionary<object, int>? Counts);
}
