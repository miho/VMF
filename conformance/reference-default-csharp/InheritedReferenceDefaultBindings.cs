namespace Eu.Mihosoft.Vmf.Conformance.Referencedefaults;

internal static class InheritedReferenceDefaultBindings
{
    internal static List<string> Events { get; } = [];

    internal static IEnumerable<SelectionTarget> Queue()
    {
        Events.Add("queue");
        return [ReferenceDefaultBindings.Target("default-first"), ReferenceDefaultBindings.Target("default-second")];
    }

    internal static IEnumerable<SelectionTarget> Saved()
    {
        Events.Add("saved");
        return [ReferenceDefaultBindings.Target("default-saved")];
    }
}

public partial class InheritedSelection
{
    private static partial IEnumerable<SelectionTarget> CreateDefaultQueue() => InheritedReferenceDefaultBindings.Queue();
    private static partial IEnumerable<SelectionTarget> CreateDefaultSaved() => InheritedReferenceDefaultBindings.Saved();
}
