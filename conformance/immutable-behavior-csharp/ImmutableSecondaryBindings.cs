namespace Eu.Mihosoft.Vmf.Conformance.Immutablebehavior;

internal static class ImmutableSecondaryBindings
{
    internal static int ComposedCreated { get; set; }
    internal static int SecondaryCreated { get; set; }
    internal static int ChildCreated { get; set; }
    internal static List<(ISecondaryBehavior Owner, object Provider)> Calls { get; } = [];

    internal sealed class Counter
    {
        private ISecondaryBehavior? owner;
        private int calls;

        internal int Next(ISecondaryBehavior actual, object provider)
        {
            owner ??= actual;
            if (!ReferenceEquals(owner, actual)) throw new InvalidOperationException("A provider crossed immutable values.");
            Calls.Add((actual, provider));
            return actual.Metric + ++calls;
        }
    }
}

public partial class SecondaryBehavior
{
    private static partial Behavior CreateDefaultBehavior()
    {
        ImmutableSecondaryBindings.SecondaryCreated++;
        return new Binding();
    }

    private sealed class Binding : Behavior
    {
        private readonly ImmutableSecondaryBindings.Counter counter = new();
        public int Counter(SecondaryBehavior caller) => counter.Next(caller, this);
    }
}

public partial class ComposedValue
{
    private static partial Behavior CreateDefaultBehavior()
    {
        ImmutableSecondaryBindings.ComposedCreated++;
        return new Binding();
    }

    private sealed class Binding : Behavior
    {
        private readonly ImmutableSecondaryBindings.Counter counter = new();
        public int Counter(ComposedValue caller) => counter.Next(caller, this);
    }
}

public sealed partial class ComposedChild
{
    private static partial Behavior CreateDefaultBehavior()
    {
        ImmutableSecondaryBindings.ChildCreated++;
        return new Binding();
    }

    private sealed class Binding : Behavior
    {
        private readonly ImmutableSecondaryBindings.Counter counter = new();
        public int Counter(ComposedChild caller) => counter.Next(caller, this);
    }
}

internal static class HookedSecondaryBindings
{
    internal static int Created { get; set; }
    internal static List<IHookedSecondary> Callers { get; } = [];

    internal sealed class Counter
    {
        private int calls;

        internal int Next(IHookedSecondary caller)
        {
            Callers.Add(caller);
            return caller.Scale + ++calls;
        }
    }
}

public partial class HookedSecondary
{
    private static partial Behavior CreateDefaultBehavior()
    {
        HookedSecondaryBindings.Created++;
        return new Binding();
    }

    private sealed class Binding : Behavior
    {
        private readonly HookedSecondaryBindings.Counter counter = new();
        public int Counter(HookedSecondary caller) => counter.Next(caller);
    }
}

public sealed partial class HookedComposed
{
    private static partial Behavior CreateDefaultBehavior()
    {
        HookedSecondaryBindings.Created++;
        return new Binding();
    }

    private sealed class Binding : Behavior
    {
        private readonly HookedSecondaryBindings.Counter counter = new();
        public int Counter(HookedComposed caller) => counter.Next(caller);
    }
}
