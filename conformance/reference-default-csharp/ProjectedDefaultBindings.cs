namespace Eu.Mihosoft.Vmf.Conformance.Referencedefaults;

internal static class ProjectedDefaultBindings
{
    internal static List<string> Events { get; } = [];

    internal static string Text(string key)
    {
        Events.Add(key);
        return key;
    }

    internal static IEnumerable<int> Marks()
    {
        Events.Add("marks");
        return [6];
    }

    internal static IEnumerable<int> Prunes()
    {
        Events.Add("prunes");
        return [7];
    }

    internal static IEnumerable<int> Numbers()
    {
        Events.Add("numbers");
        return [2, 3];
    }

    internal static IEnumerable<int> Summary()
    {
        Events.Add("summary");
        return [4, 5];
    }

    internal static IEnumerable<SelectionTarget> Favorites()
    {
        Events.Add("favorites");
        return [ReferenceDefaultBindings.Target("favorite")];
    }

    internal static IEnumerable<SelectionTarget> Queue()
    {
        Events.Add("queue");
        return [ReferenceDefaultBindings.Target("first"), ReferenceDefaultBindings.Target("second")];
    }
}

public partial class ProjectionBasis
{
    private static partial string? CreateDefaultBefore() => ProjectedDefaultBindings.Text("before");
    private static partial IEnumerable<int> CreateDefaultNumbers() => ProjectedDefaultBindings.Numbers();
}

public partial class ProjectionPrimary
{
    private static partial string? CreateDefaultPath() => ProjectedDefaultBindings.Text("path");
}

public partial class ProjectedQueueRoot
{
    private static partial string? CreateDefaultZulu() => ProjectedDefaultBindings.Text("zulu");
    private static partial IEnumerable<SelectionTarget> CreateDefaultQueue() => ProjectedDefaultBindings.Queue();
    private static partial IEnumerable<int> CreateDefaultSummary() => ProjectedDefaultBindings.Summary();
}

public partial class ProjectedFavoriteRoot
{
    private static partial string? CreateDefaultAccent() => ProjectedDefaultBindings.Text("accent");
    private static partial IEnumerable<SelectionTarget> CreateDefaultFavorites() => ProjectedDefaultBindings.Favorites();
}

public partial class ProjectedCalibrationRoot
{
    private static partial IEnumerable<int> CreateDefaultPrunes() => ProjectedDefaultBindings.Prunes();
}

public partial class ProjectedCalibrationRole
{
    private static partial IEnumerable<int> CreateDefaultMarks() => ProjectedDefaultBindings.Marks();
}

public sealed partial class ProjectedDefaultsLeaf
{
    private static partial string? CreateDefaultAhead() => ProjectedDefaultBindings.Text("ahead");
}
