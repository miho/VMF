namespace Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;

internal static class LocalBindingState
{
    internal static int ColdCreated { get; set; }
    internal static int HotCreated { get; set; }
    internal static int SharedCreated { get; set; }
    internal static List<string> Hooks { get; } = [];
    internal static List<LocalComposite> HookCallers { get; } = [];
}

public static partial class ModelBehaviorBindings
{
    private static partial SharedDelegate CreateSharedDelegate()
    {
        LocalBindingState.SharedCreated++;
        return new LocalSharedHost();
    }

    private sealed class LocalSharedHost : SharedDelegate
    {
        private readonly TrioBindingState state = new();

        public void OnLocalLeftInstantiated(LocalLeft caller) => LocalBindingState.Hooks.Add("left");
        public void OnLocalRightInstantiated(ILocalRight caller) => LocalBindingState.Hooks.Add("right");
        public int Left(LocalLeft caller) => state.Advance(caller);
        public int Right(ILocalRight caller) => state.Advance(caller);
        public int Joined(LocalComposite caller) => state.Advance(caller);
    }
}

public partial class LocalComposite
{
    private static partial DefaultBehavior_ColdDelegate CreateDefaultBehavior_ColdDelegate()
    {
        LocalBindingState.ColdCreated++;
        return new ColdHost();
    }

    private static partial DefaultBehavior_HotDelegate CreateDefaultBehavior_HotDelegate()
    {
        LocalBindingState.HotCreated++;
        return new HotHost();
    }

    private sealed class ColdHost : DefaultBehavior_ColdDelegate
    {
        private readonly TrioBindingState state = new();

        public void OnLocalCompositeInstantiated(LocalComposite caller)
        {
            state.Bind(caller);
            LocalBindingState.HookCallers.Add(caller);
            LocalBindingState.Hooks.Add($"cold:{caller.LeftValue}:{caller.RightValue}:{caller.LocalValue}");
        }

        public int Cool(LocalComposite caller) => 100 + state.Advance(caller);
    }

    private sealed class HotHost : DefaultBehavior_HotDelegate
    {
        private readonly TrioBindingState state = new();

        public int Heat(LocalComposite caller) => state.Advance(caller);
        public int Warm(LocalComposite caller) => state.Advance(caller);
    }
}
