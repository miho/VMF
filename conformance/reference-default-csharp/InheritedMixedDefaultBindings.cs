namespace Eu.Mihosoft.Vmf.Conformance.Referencedefaults;

internal static class InheritedMixedDefaultBindings
{
    internal static List<string> Events { get; } = [];

    internal static string Ahead()
    {
        Events.Add("ahead");
        return "early";
    }

    internal static IEnumerable<KeyValuePair<string, int?>> Options()
    {
        Events.Add("options");
        return [new("port", 17)];
    }

    internal static IEnumerable<SelectionTarget> Queue()
    {
        Events.Add("queue");
        return [ReferenceDefaultBindings.Target("first"), ReferenceDefaultBindings.Target("second")];
    }

    internal static IEnumerable<int> Summary()
    {
        Events.Add("summary");
        return [4, 5];
    }

    internal static string Zulu()
    {
        Events.Add("zulu");
        return "last";
    }
}

public partial class ExecutableSelectionBase
{
    private static partial IEnumerable<SelectionTarget> CreateDefaultQueue() => InheritedMixedDefaultBindings.Queue();
    private static partial IEnumerable<int> CreateDefaultSummary() => InheritedMixedDefaultBindings.Summary();
    private static partial string? CreateDefaultZulu() => InheritedMixedDefaultBindings.Zulu();
}

public partial class ExecutableSelectionChild
{
    private static partial string? CreateDefaultAhead() => InheritedMixedDefaultBindings.Ahead();
    private static partial IEnumerable<KeyValuePair<string, int?>> CreateDefaultOptions() => InheritedMixedDefaultBindings.Options();
}

public partial class ManagedSelectionBase
{
    private static partial IEnumerable<SelectionTarget> CreateDefaultQueue() => InheritedMixedDefaultBindings.Queue();
}

public sealed partial class ManagedSelectionLeaf
{
    private static partial IEnumerable<int> CreateDefaultEarlier() => InheritedMixedDefaultBindings.Summary();
}
