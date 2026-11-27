namespace Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;

internal static class NestedRoleTestState
{
    internal static List<string> Hooks { get; } = [];
    internal static List<object> Callers { get; } = [];

    internal static void OnHook(object caller, string description)
    {
        Callers.Add(caller);
        Hooks.Add(description);
    }
}

public partial class NestedFirst
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new FirstHost();

    private sealed class FirstHost : DefaultBehavior
    {
        private readonly TrioBindingState state = new();
        public int Choose(INestedFirst caller, int input) => 100 + input + state.Advance(caller);
        public int FirstCalls(INestedFirst caller) => state.Calls;
        public void OnNestedFirstInstantiated(INestedFirst caller)
        {
            state.Bind(caller);
            NestedRoleTestState.OnHook(caller, $"first:{caller.FirstValue}");
        }
    }
}

public partial class NestedSecond
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new SecondHost();

    private sealed class SecondHost : DefaultBehavior
    {
        private readonly TrioBindingState state = new();
        public int Choose(INestedSecond caller, int input) => 200 + input + state.Advance(caller);
        public int Choose(INestedSecond caller, long input) => 200 + (int)input + state.Advance(caller);
        public int SecondCalls(INestedSecond caller) => state.Calls;
        public void OnNestedSecondInstantiated(INestedSecond caller)
        {
            state.Bind(caller);
            NestedRoleTestState.OnHook(caller, $"second:{caller.SecondValue}");
        }
    }
}

public partial class NestedThird
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new ThirdHost();

    private sealed class ThirdHost : DefaultBehavior
    {
        private readonly TrioBindingState state = new();
        public int Choose(INestedThird caller, int input) => 300 + input + state.Advance(caller);
        public int Choose(INestedThird caller, string? input) => 300 + input!.Length + state.Advance(caller);
        public int ThirdCalls(INestedThird caller) => state.Calls;
        public void OnNestedThirdInstantiated(INestedThird caller)
        {
            state.Bind(caller);
            NestedRoleTestState.OnHook(caller, $"third:{caller.ThirdValue}");
        }
    }
}

public partial class NestedInner
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new InnerHost();

    private sealed class InnerHost : DefaultBehavior
    {
        private readonly TrioBindingState state = new();
        public int Choose(INestedInner caller, int input) => 400 + input + state.Advance(caller);
        public int InnerCalls(INestedInner caller) => state.Calls;
        public void OnNestedInnerInstantiated(INestedInner caller)
        {
            state.Bind(caller);
            NestedRoleTestState.OnHook(caller, $"inner:{caller.InnerValue}");
        }
    }
}

public sealed partial class NestedOuter
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new OuterHost();

    private sealed class OuterHost : DefaultBehavior
    {
        private readonly TrioBindingState state = new();
        public int Choose(NestedOuter caller, int input) => 500 + input + state.Advance(caller);
        public void OnNestedOuterInstantiated(NestedOuter caller)
        {
            state.Bind(caller);
            NestedRoleTestState.OnHook(caller, $"outer:{caller.ThirdValue}");
        }
    }
}
