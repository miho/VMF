namespace Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;

internal static class RepeatedRoleState
{
    internal static int RootCreated { get; set; }
    internal static int FirstCreated { get; set; }
    internal static int SecondCreated { get; set; }
    internal static List<string> Hooks { get; } = [];
    internal static List<object> Callers { get; } = [];

    internal static void Hook(object caller, string message)
    {
        Hooks.Add(message);
        Callers.Add(caller);
    }
}

public partial class RepeatedRoot
{
    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        RepeatedRoleState.RootCreated++;
        return new Binding();
    }

    private sealed class Binding : DefaultBehavior
    {
        private readonly TrioBindingState state = new();
        public int Choose(IRepeatedRoot caller, int value) => 100 + value + state.Advance(caller);
        public int RootCalls(IRepeatedRoot caller) => state.Calls;
        public void OnRepeatedRootInstantiated(IRepeatedRoot caller)
        {
            state.Bind(caller);
            RepeatedRoleState.Hook(caller, $"root:{caller.Count}");
        }
    }
}

public partial class RepeatedFirst
{
    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        RepeatedRoleState.FirstCreated++;
        return new Binding();
    }

    private sealed class Binding : DefaultBehavior
    {
        private readonly TrioBindingState state = new();
        public int Choose(IRepeatedFirst caller, int value) => 200 + value + state.Advance(caller);
        public int FromFirst(IRepeatedFirst caller) => state.Calls;
        public void OnRepeatedFirstInstantiated(IRepeatedFirst caller)
        {
            state.Bind(caller);
            RepeatedRoleState.Hook(caller, "first:0");
        }
    }
}

public partial class RepeatedSecond
{
    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        RepeatedRoleState.SecondCreated++;
        return new Binding();
    }

    private sealed class Binding : DefaultBehavior
    {
        private readonly TrioBindingState state = new();
        public int Choose(IRepeatedSecond caller, int value) => 300 + value + state.Advance(caller);
        public int FromSecond(IRepeatedSecond caller) => state.Calls;
        public void OnRepeatedSecondInstantiated(IRepeatedSecond caller)
        {
            state.Bind(caller);
            RepeatedRoleState.Hook(caller, "second:0");
        }
    }
}
