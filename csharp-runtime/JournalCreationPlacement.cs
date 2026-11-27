namespace Vmf.Runtime;

internal static class JournalCreationPlacement
{
    // Keep established V1 payloads unchanged unless an earlier operation needs a later definition.
    internal static IReadOnlyList<JournalOperation> Order(IReadOnlyList<JournalOperation> operations)
    {
        int lastDefinition = operations.Count - 1;
        while (lastDefinition > 0 && operations[lastDefinition].Created.Count == 0) lastDefinition--;
        if (lastDefinition <= 0) return operations;
        var definitions = new Dictionary<string, Placement>(StringComparer.Ordinal);
        for (int index = 0; index < operations.Count; index++)
        {
            foreach (var creation in operations[index].Created)
            {
                definitions.Add(creation.Id, new(creation, index));
            }
        }
        JournalOperation[]? result = null;
        for (int index = 0; index < operations.Count; index++)
        {
            var operation = operations[index];
            var earlier = new List<JournalCreation>();
            RequireValues(References(operation), index, definitions, earlier);
            var originals = operation.Created.Where(creation => definitions[creation.Id].Position == index).ToArray();
            foreach (var creation in originals)
            {
                RequireMembers(creation, index, definitions, earlier);
            }
            if (earlier.Count != 0 || originals.Length != operation.Created.Count)
            {
                (result ??= operations.ToArray())[index] = WithCreations(operation, earlier.Concat(originals));
            }
        }
        return result ?? operations;
    }

    private static IEnumerable<JournalValue> References(JournalOperation operation) => operation switch
    {
        JournalSet edit => new[] { edit.Before, edit.After },
        JournalListEdit edit => edit.Before.Concat(edit.After),
        JournalMembershipEdit edit => edit.Removed.Concat(edit.Added),
        _ => [],
    };

    private static JournalOperation WithCreations(JournalOperation operation, IEnumerable<JournalCreation> created) => operation switch
    {
        JournalSet edit => new JournalSet(edit.Target, edit.Property, edit.Before, edit.After, created),
        JournalListEdit edit => new JournalListEdit(edit.Kind, edit.Target, edit.Property,
            edit.Indices, edit.Before, edit.After, created),
        JournalMembershipEdit edit => new JournalMembershipEdit(edit.Target, edit.Property, edit.References,
            edit.Removed, edit.Added, created),
        _ => throw new InvalidOperationException("Unexpected definition placement."),
    };

    private static void RequireMembers(JournalCreation creation, int index, Dictionary<string, Placement> definitions,
        List<JournalCreation> earlier)
    {
        foreach (var values in creation.Properties.Values)
        {
            RequireValues(values, index, definitions, earlier);
        }
    }

    private static void RequireValues(IEnumerable<JournalValue> values, int index, Dictionary<string, Placement> definitions,
        List<JournalCreation> earlier)
    {
        foreach (var value in values)
        {
            if (!value.IsReference || !definitions.TryGetValue(value.Text!, out var placement) || placement.Position <= index)
            {
                continue;
            }
            placement.Position = index;
            RequireMembers(placement.Creation, index, definitions, earlier);
            earlier.Add(placement.Creation);
        }
    }

    private sealed class Placement(JournalCreation creation, int position)
    {
        internal JournalCreation Creation { get; } = creation;
        internal int Position { get; set; } = position;
    }
}
