namespace Eu.Mihosoft.Vmf.Conformance.Referencedefaults;

internal static class ReferenceDefaultBindings
{
    internal static int MutableCalls;
    internal static int ImmutableCalls;
    internal static bool DuplicateSeed;

    internal static IEnumerable<SelectionTarget> Favorites()
    {
        MutableCalls++;
        var seed = Target("default-favorite");
        return DuplicateSeed ? [seed, seed] : [seed];
    }

    internal static IEnumerable<SelectionTarget> Featured()
    {
        MutableCalls++;
        return [Target("default-first"), Target("default-second")];
    }

    internal static IEnumerable<SelectionToken> Tokens()
    {
        ImmutableCalls++;
        return [Token("seed-first", "seed"), Token("seed-second", "seed")];
    }

    internal static SelectionTarget Target(string code) => new() { Code = code };

    internal static SelectionToken Token(string key, string label) => new(key: key, label: label);
}

public sealed partial class MutablePicks
{
    private static partial IEnumerable<SelectionTarget> CreateDefaultFavorites() => ReferenceDefaultBindings.Favorites();
    private static partial IEnumerable<SelectionTarget> CreateDefaultFeatured() => ReferenceDefaultBindings.Featured();
}

public sealed partial class FrozenPicks
{
    private static partial IEnumerable<SelectionToken> CreateDefaultFavorites() => ReferenceDefaultBindings.Tokens();
    private static partial IEnumerable<SelectionToken> CreateDefaultFeatured() => ReferenceDefaultBindings.Tokens();
}
