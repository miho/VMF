namespace Eu.Mihosoft.Vmf.Conformance.Immutablebehavior;

internal sealed class CombinedBehaviorCounter(int offset)
{
    private object? owner;
    private int calls;

    public int Next(object actual, int field)
    {
        owner ??= actual;
        if (!ReferenceEquals(owner, actual)) throw new InvalidOperationException("A binding was shared between values.");
        return offset + field + ++calls;
    }
}

public partial class LeftBehaviorValue
{
    private static partial Behavior CreateDefaultBehavior() => new Binding();

    private sealed class Binding : Behavior
    {
        private readonly CombinedBehaviorCounter counter = new(100);
        public int LeftCall(LeftBehaviorValue caller) => counter.Next(caller, caller.Left);
        public int Preferred(LeftBehaviorValue caller) => LeftCall(caller);
    }
}

public partial class RightBehaviorValue
{
    private static partial Behavior CreateDefaultBehavior() => new Binding();

    private sealed class Binding : Behavior
    {
        private readonly CombinedBehaviorCounter counter = new(200);
        public int RightCall(RightBehaviorValue caller) => counter.Next(caller, caller.Right);
        public int Preferred(RightBehaviorValue caller) => RightCall(caller);
    }
}

public sealed partial class CombinedBehaviorValue
{
    private static partial Behavior_Left CreateBehavior_Left() => new LeftBinding();
    private static partial Behavior_Right CreateBehavior_Right() => new RightBinding();

    private sealed class LeftBinding : Behavior_Left
    {
        private readonly CombinedBehaviorCounter counter = new(100);
        public int LeftCall(CombinedBehaviorValue caller) => counter.Next(caller, caller.Left);
        public int Preferred(CombinedBehaviorValue caller) => LeftCall(caller);
    }

    private sealed class RightBinding : Behavior_Right
    {
        private readonly CombinedBehaviorCounter counter = new(200);
        public int RightCall(CombinedBehaviorValue caller) => counter.Next(caller, caller.Right);
    }
}

public sealed partial class ReversedBehaviorValue
{
    private static partial Behavior_Left CreateBehavior_Left() => new LeftBinding();
    private static partial Behavior_Right CreateBehavior_Right() => new RightBinding();

    private sealed class LeftBinding : Behavior_Left
    {
        private readonly CombinedBehaviorCounter counter = new(100);
        public int LeftCall(ReversedBehaviorValue caller) => counter.Next(caller, caller.Left);
    }

    private sealed class RightBinding : Behavior_Right
    {
        private readonly CombinedBehaviorCounter counter = new(200);
        public int RightCall(ReversedBehaviorValue caller) => counter.Next(caller, caller.Right);
        public int Preferred(ReversedBehaviorValue caller) => RightCall(caller);
    }
}
