namespace Eu.Mihosoft.Vmf.Conformance.Immutablebehavior;

internal static class ImmutableToStringBindings
{
    internal static int NullCreated { get; set; }
    internal static int TextCreated { get; set; }
    internal static int InheritedCreated { get; set; }
}

public sealed partial class NullSummary
{
    private static partial Behavior CreateDefaultBehavior()
    {
        ImmutableToStringBindings.NullCreated++;
        return new Binding();
    }

    private sealed class Binding : Behavior
    {
        public string? ToString(NullSummary caller) => null;
    }
}

public partial class TextSummary
{
    private static partial Behavior CreateDefaultBehavior()
    {
        ImmutableToStringBindings.TextCreated++;
        return new Binding();
    }

    private sealed class Binding : Behavior
    {
        private int calls;

        public string? ToString(TextSummary caller) => $"{caller.Label}:{++calls}";

        public int Calls(TextSummary caller) => calls;
    }
}

public sealed partial class InheritedSummary
{
    private static partial Behavior CreateDefaultBehavior()
    {
        ImmutableToStringBindings.InheritedCreated++;
        return new Binding();
    }

    private sealed class Binding : Behavior
    {
        private int calls;

        public string? ToString(InheritedSummary caller) => $"{caller.Label}:{++calls}";

        public int Calls(InheritedSummary caller) => calls;
    }
}
