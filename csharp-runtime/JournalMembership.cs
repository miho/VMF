namespace Vmf.Runtime;

/// <summary>A membership edit on an unordered scalar set or identity reference set.</summary>
public sealed class JournalMembershipEdit : JournalOperation
{
    public JournalMembershipEdit(string target, string property, bool references,
        IEnumerable<JournalValue> removed, IEnumerable<JournalValue> added)
        : this(target, property, references, removed, added, [])
    {
    }

    public JournalMembershipEdit(string target, string property, bool references,
        IEnumerable<JournalValue> removed, IEnumerable<JournalValue> added, IEnumerable<JournalCreation> created)
        : base(target, property, created)
    {
        References = references;
        Removed = Array.AsReadOnly(removed.ToArray());
        Added = Array.AsReadOnly(added.ToArray());
        if ((Removed.Count == 0 && Added.Count == 0) || (!references && Created.Count != 0))
        {
            throw new ArgumentException("A membership edit requires changed members.");
        }

        var members = Removed.Concat(Added).ToArray();
        if (members.Any(value => value.IsReference != references || value.Kind == "NULL" || value.IsExternal)
            || members.Select(value => (Kind: references ? "REFERENCE" : value.Kind, value.Text)).Distinct().Count() != members.Length
            || !references && members.Any(value => value.Kind != members[0].Kind))
        {
            throw new ArgumentException("A membership edit requires distinct values of the declared kind.");
        }
    }

    public bool References { get; }
    public IReadOnlyList<JournalValue> Removed { get; }
    public IReadOnlyList<JournalValue> Added { get; }
}

/// <summary>A present key and value in a portable map edit.</summary>
public sealed record JournalMapEntry(JournalValue Key, JournalValue Value);

/// <summary>A keyed edit preserving absent keys, explicit null values, and insertion order.</summary>
public sealed class JournalMapEdit : JournalOperation
{
    public JournalMapEdit(string target, string property, bool typedKeys,
        IEnumerable<JournalMapEntry> before, IEnumerable<JournalMapEntry> after)
        : base(target, property)
    {
        TypedKeys = typedKeys;
        Before = Array.AsReadOnly(before.ToArray());
        After = Array.AsReadOnly(after.ToArray());
        if (Before.Count == 0 && After.Count == 0)
        {
            throw new ArgumentException("A map edit requires affected keys.");
        }

        var entries = Before.Concat(After).ToArray();
        string keyKind = entries[0].Key.Kind;
        if (typedKeys == (keyKind == "STRING") || keyKind == "NULL"
            || entries.Any(entry => entry.Key.Kind != keyKind || entry.Key.IsReference || entry.Value.IsReference
                || entry.Key.IsExternal || entry.Value.IsExternal)
            || !HasUniqueKeys(Before) || !HasUniqueKeys(After))
        {
            throw new ArgumentException("A map edit requires one scalar key type and distinct keys on each side.");
        }
    }

    public bool TypedKeys { get; }
    public IReadOnlyList<JournalMapEntry> Before { get; }
    public IReadOnlyList<JournalMapEntry> After { get; }

    private static bool HasUniqueKeys(IReadOnlyList<JournalMapEntry> entries) =>
        entries.Select(entry => (entry.Key.Kind, entry.Key.Text)).Distinct().Count() == entries.Count;
}
