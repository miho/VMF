namespace Eu.Mihosoft.Vmf.Conformance.Immutablebehavior;

internal static class ImmutableCovariantBindings
{
    internal static int BaseCreated { get; set; }
    internal static int ChildCreated { get; set; }
    internal static int GrandchildCreated { get; set; }
}

public partial class BaseValue
{
    private static partial Behavior CreateDefaultBehavior()
    {
        ImmutableCovariantBindings.BaseCreated++;
        return new Binding();
    }

    private sealed class Binding : Behavior
    {
        public ValueResult? Evaluate(BaseValue caller) => caller.Stored;
    }
}

public partial class ChildValue
{
    private static partial Behavior CreateDefaultBehavior()
    {
        ImmutableCovariantBindings.ChildCreated++;
        return new Binding();
    }

    private sealed class Binding : Behavior
    {
        public NarrowValueResult? Evaluate(ChildValue caller) => caller.NarrowStored;
    }
}

public sealed partial class GrandchildValue
{
    private static partial Behavior CreateDefaultBehavior()
    {
        ImmutableCovariantBindings.GrandchildCreated++;
        return new Binding();
    }

    private sealed class Binding : Behavior
    {
        public NarrowValueResult? Evaluate(GrandchildValue caller) => caller.NarrowStored;
    }
}
