namespace Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;

internal static class SiblingRoleState
{
    internal static int RootCreated { get; set; }
    internal static int SidesCreated { get; set; }
    internal static int OverrideCreated { get; set; }
    internal static List<string> Hooks { get; } = [];
    internal static List<object> Callers { get; } = [];

    internal static void Hook(object caller, string label, int value)
    {
        Hooks.Add($"{label}:{value}");
        Callers.Add(caller);
    }
}

public static partial class ModelBehaviorBindings
{
    private static partial SidesBinding CreateSidesBinding()
    {
        SiblingRoleState.SidesCreated++;
        return new SidesHost();
    }

    private sealed class SidesHost : SidesBinding
    {
        private readonly TrioBindingState state = new();

        public int Left(ISiblingLeft caller) => state.Advance(caller);
        public int Right(ISiblingRight caller) => state.Advance(caller);
        public int Shared(object caller, int value) => 100 + value + state.Advance(caller);

        public void OnSiblingLeftInstantiated(ISiblingLeft caller)
        {
            state.Bind(caller);
            SiblingRoleState.Hook(caller, "left", caller.Value);
        }

        public void OnSiblingRightInstantiated(ISiblingRight caller)
        {
            state.Bind(caller);
            SiblingRoleState.Hook(caller, "right", caller.Value);
        }
    }
}

public partial class SiblingRoot
{
    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        SiblingRoleState.RootCreated++;
        return new RootHost();
    }

    private sealed class RootHost : DefaultBehavior
    {
        private readonly TrioBindingState state = new();

        public int Choose(ISiblingRoot caller, int value) => 100 + value + state.Advance(caller);
        public int RootCalls(ISiblingRoot caller) => state.Calls;
        public void OnSiblingRootInstantiated(ISiblingRoot caller)
        {
            state.Bind(caller);
            SiblingRoleState.Hook(caller, "root", caller.Value);
        }
    }
}

public partial class SiblingRightOverride
{
    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        SiblingRoleState.OverrideCreated++;
        return new OverrideHost();
    }

    private sealed class OverrideHost : DefaultBehavior
    {
        private readonly TrioBindingState state = new();

        public int Choose(ISiblingRightOverride caller, int value) => 200 + value + state.Advance(caller);
        public int OverrideCalls(ISiblingRightOverride caller) => state.Calls;
        public void OnSiblingRightOverrideInstantiated(ISiblingRightOverride caller)
        {
            state.Bind(caller);
            SiblingRoleState.Hook(caller, "override", caller.Value);
        }
    }
}

public sealed partial class SiblingLocal
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new LocalHost();

    private sealed class LocalHost : DefaultBehavior
    {
        public int Choose(SiblingLocal caller, int value) => 301 + value;
        public void OnSiblingLocalInstantiated(SiblingLocal caller) => SiblingRoleState.Hook(caller, "local", caller.Value);
    }
}
