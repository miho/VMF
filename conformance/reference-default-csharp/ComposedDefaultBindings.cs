namespace Eu.Mihosoft.Vmf.Conformance.Referencedefaults;

public partial class DefaultPrimary
{
    private static partial IEnumerable<SelectionTarget> CreateDefaultQueue() => InheritedMixedDefaultBindings.Queue();
    private static partial IEnumerable<int> CreateDefaultSummary() => InheritedMixedDefaultBindings.Summary();
    private static partial string? CreateDefaultZulu() => InheritedMixedDefaultBindings.Zulu();
}

public partial class ComposedDefaultSelection
{
    private static partial string? CreateDefaultAhead() => InheritedMixedDefaultBindings.Ahead();
}
