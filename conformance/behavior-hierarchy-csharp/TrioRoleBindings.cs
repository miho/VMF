namespace Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;

internal sealed class TrioBindingState
{
    private object? owner;
    internal int Calls { get; private set; }

    internal int Advance(object caller)
    {
        Bind(caller);
        return ++Calls;
    }

    internal void Bind(object caller)
    {
        owner ??= caller;
        if (!ReferenceEquals(owner, caller)) throw new InvalidOperationException("A binding must keep one actual model owner.");
    }
}

public static partial class ModelBehaviorBindings
{
    internal static int TrioSharedCreated { get; set; }
    internal static List<string> TrioHooks { get; } = [];

    private static partial TrioSharedBinding CreateTrioSharedBinding()
    {
        TrioSharedCreated++;
        return new TrioSharedHost();
    }

    private sealed class TrioSharedHost : TrioSharedBinding
    {
        private readonly TrioBindingState state = new();

        public void OnTrioFirstInstantiated(ITrioFirst caller)
        {
            state.Bind(caller);
            TrioHooks.Add($"first:{caller.FirstValue}");
        }

        public void OnTrioThirdInstantiated(ITrioThird caller)
        {
            state.Bind(caller);
            TrioHooks.Add($"third:{caller.ThirdValue}");
        }

        public int Evaluate(object caller, int value) => 100 + value + state.Advance(caller);
        public int Evaluate(ITrioThird caller, string? value) => 300 + value!.Length + state.Advance(caller);
        public long Finish(ITrioThird caller, string? value) => 2000L + value!.Length + state.Advance(caller);
        public int FirstCalls(ITrioFirst caller) => state.Calls;
        public int ThirdCalls(ITrioThird caller) => state.Calls;
    }
}

public partial class TrioSecond
{
    internal static int TrioIndependentCreated { get; set; }

    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        TrioIndependentCreated++;
        return new TrioIndependentHost();
    }

    private sealed class TrioIndependentHost : DefaultBehavior
    {
        private readonly TrioBindingState state = new();

        public void OnTrioSecondInstantiated(ITrioSecond caller)
        {
            state.Bind(caller);
            ModelBehaviorBindings.TrioHooks.Add($"second:{caller.SecondValue}");
        }

        public int Evaluate(ITrioSecond caller, int value) => 200 + value + state.Advance(caller);
        public int Evaluate(ITrioSecond caller, long value) => 400 + (int)value + state.Advance(caller);
        public long Finish(ITrioSecond caller, string? value) => 1000L + value!.Length + state.Advance(caller);
        public int SecondCalls(ITrioSecond caller) => state.Calls;
    }
}
