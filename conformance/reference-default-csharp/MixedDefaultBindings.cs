namespace Eu.Mihosoft.Vmf.Conformance.Referencedefaults;

internal static class MixedDefaultBindings
{
    internal static List<string> Events { get; } = [];
    internal static bool DuplicateSeed;

    internal static string? Caption()
    {
        Events.Add("caption");
        return "fallback";
    }

    internal static IEnumerable<SelectionTarget> Favorites()
    {
        Events.Add("favorites");
        var seed = new SelectionTarget { Code = "seed" };
        return DuplicateSeed ? [seed, seed] : [seed];
    }

    internal static IEnumerable<int> Numbers()
    {
        Events.Add("numbers");
        return [3, 5];
    }

    internal static IEnumerable<KeyValuePair<string, int?>> Options()
    {
        Events.Add("options");
        return [new("port", 7)];
    }

    internal static IEnumerable<SelectionToken> Tokens()
    {
        Events.Add("tokens");
        return [new(key: "token", label: "label")];
    }
}

public sealed partial class MixedDefaultPicks
{
    private static partial string? CreateDefaultCaption() => MixedDefaultBindings.Caption();
    private static partial IEnumerable<SelectionTarget> CreateDefaultFavorites() => MixedDefaultBindings.Favorites();
    private static partial IEnumerable<int> CreateDefaultNumbers() => MixedDefaultBindings.Numbers();
    private static partial IEnumerable<KeyValuePair<string, int?>> CreateDefaultOptions() => MixedDefaultBindings.Options();
}

public sealed partial class MixedFrozenPicks
{
    private static partial string? CreateDefaultCaption() => MixedDefaultBindings.Caption();
    private static partial IEnumerable<SelectionToken> CreateDefaultFavorites() => MixedDefaultBindings.Tokens();
    private static partial IEnumerable<int> CreateDefaultNumbers() => MixedDefaultBindings.Numbers();
}
