namespace Eu.Mihosoft.Vmf.Conformance.Immutableancestry;

internal static class OrderedParentBindings
{
    internal static int Created { get; set; }
    internal static List<ValueOrigin> Callers { get; } = [];

    internal sealed class State
    {
        internal State() => Created++;

        internal int Calls { get; private set; }

        internal string Describe(ValueOrigin caller)
        {
            Calls++;
            Callers.Add(caller);
            var caption = (caller as IReadOnlyLeadingCaption)?.Caption;
            return $"{caller.Source}/{caption}";
        }
    }
}

public partial class ValueOrigin
{
    private static partial Behavior CreateDefaultBehavior() => new Binding();

    private sealed class Binding : Behavior
    {
        private readonly OrderedParentBindings.State state = new();
        public string? Describe(ValueOrigin caller) => state.Describe(caller);
        public int Calls(ValueOrigin caller) => state.Calls;
    }
}

public sealed partial class TraitFirstValue
{
    private static partial Behavior CreateDefaultBehavior() => new Binding();

    private sealed class Binding : Behavior
    {
        private readonly OrderedParentBindings.State state = new();
        public string? Describe(TraitFirstValue caller) => state.Describe(caller);
        public int Calls(TraitFirstValue caller) => state.Calls;
    }
}

public sealed partial class BaseFirstValue
{
    private static partial Behavior CreateDefaultBehavior() => new Binding();

    private sealed class Binding : Behavior
    {
        private readonly OrderedParentBindings.State state = new();
        public string? Describe(BaseFirstValue caller) => state.Describe(caller);
        public int Calls(BaseFirstValue caller) => state.Calls;
    }
}
