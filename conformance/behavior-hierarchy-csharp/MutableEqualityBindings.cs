namespace Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;

public sealed partial class EqualityItem
{
    public static int BindingCount { get; set; }

    private static partial Behavior CreateDefaultBehavior()
    {
        BindingCount++;
        return new CodeEquality();
    }

    internal static string? CodeOf(object? other) => other switch
    {
        EqualityItem item => item.Code,
        IReadOnlyEqualityItem view => view.Code,
        _ => null
    };

    private sealed class CodeEquality : Behavior
    {
        public bool Equals(EqualityItem caller, object? other) =>
            string.Equals(caller.Code, CodeOf(other), StringComparison.OrdinalIgnoreCase);

        public int GetHashCode(EqualityItem caller)
        {
            int hash = 0;
            foreach (char letter in (caller.Code ?? "").ToLowerInvariant()) hash = unchecked(hash * 31 + letter);
            return hash;
        }
    }
}
