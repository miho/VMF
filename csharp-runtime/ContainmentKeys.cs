namespace Vmf.Runtime;

/// <summary>Validates complete keyed containment inputs before a builder or model applies them.</summary>
public static class ContainmentKeys
{
    [System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
    public static IEnumerable<TChild> RequireNonNullEntries<TChild>(IEnumerable<TChild?> children)
        where TChild : class =>
        children.Select(static child => child ?? throw new ArgumentException("Keyed containment requires non-null entries."));

    public static void Validate<TChild>(IEnumerable<TChild> children, Func<TChild, object?> keySelector,
        bool keyUsesIdentity) where TChild : class
    {
        ArgumentNullException.ThrowIfNull(children);
        ArgumentNullException.ThrowIfNull(keySelector);
        Validate(children, keySelector, Comparer(keyUsesIdentity), propertyName: null);
    }

    internal static IEqualityComparer<object> Comparer(bool keyUsesIdentity) =>
        keyUsesIdentity ? ReferenceEqualityComparer.Instance : ScalarEquality<object>.Instance;

    internal static void Validate<TChild>(IEnumerable<TChild> children, Func<TChild, object?> keySelector,
        IEqualityComparer<object> comparer, string? propertyName) where TChild : class?
    {
        var keys = new HashSet<object>(comparer);
        foreach (var child in children)
        {
            ArgumentNullException.ThrowIfNull(child);
            object key = keySelector(child) ?? throw Missing(propertyName);
            if (!keys.Add(key)) throw Duplicate(propertyName);
        }
    }

    internal static ArgumentException Missing(string? propertyName) =>
        new(propertyName is null ? "Missing containment entry key." : $"Missing containment entry key '{propertyName}'.");

    internal static ArgumentException Duplicate(string? propertyName) =>
        new(propertyName is null ? "Duplicate containment entry key." : $"Duplicate containment entry key '{propertyName}'.");
}

internal interface IKeyedContainment
{
    bool HasKey { get; }
    void HydrateMembers(IReadOnlyList<object?> values);
    void RequireKeyChange<TValue>(object child, string propertyName, TValue value);
}

internal sealed class ContainmentKeyRule<TChild>(string propertyName, Func<TChild, object?> keySelector, bool keyUsesIdentity)
    where TChild : class?
{
    private readonly IEqualityComparer<object> comparer = ContainmentKeys.Comparer(keyUsesIdentity);

    internal TChild? Find<TKey>(IReadOnlyList<TChild> children, TKey key)
    {
        ArgumentNullException.ThrowIfNull(key);
        object sought = key;
        TChild? match = null;
        foreach (TChild child in children)
        {
            if (!comparer.Equals(keySelector(child), sought)) continue;
            if (match is not null) throw new InvalidOperationException("Multiple containment entries have the requested key.");
            match = child;
        }
        return match;
    }

    internal void ValidateRange(IReadOnlyList<TChild> children, int start, int count, TChild[] incoming)
    {
        var next = children.Take(start).Concat(incoming).Concat(children.Skip(start + count));
        ContainmentKeys.Validate(next, keySelector, comparer, propertyName);
    }

    internal void RequireChange<TValue>(IReadOnlyList<TChild> children, object child, string changed, TValue value)
    {
        if (changed != propertyName || !children.Any(entry => ReferenceEquals(entry, child))) return;
        object? key = value;
        if (key is null) throw ContainmentKeys.Missing(propertyName);
        foreach (TChild entry in children)
        {
            if (!ReferenceEquals(entry, child) && comparer.Equals(keySelector(entry), key))
            {
                throw ContainmentKeys.Duplicate(propertyName);
            }
        }
    }
}
