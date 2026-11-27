namespace Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;

public partial class Engine
{
    internal static int DefaultBindingCount { get; private set; }
    internal static List<(Type Model, int Power, int? Rate)> HookValues { get; } = [];

    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        DefaultBindingCount++;
        return new EngineBinding();
    }

    private sealed class EngineBinding : DefaultBehavior
    {
        public void OnEngineInstantiated(Engine caller) =>
            HookValues.Add((caller.GetType(), caller.Power, (caller as InheritedEngine)?.Rate));

        public int Transform(Engine caller, int delta) => caller.Power + delta;

        public string? Label(Engine caller, string? prefix) => prefix + ":" + caller.Power;
    }
}

public partial class FastEngine
{
    internal static List<(int Power, int Bonus)> FastHookValues { get; } = [];

    private static partial DefaultBehavior CreateDefaultBehavior() => new FastBinding();

    private sealed class FastBinding : DefaultBehavior
    {
        public void OnFastEngineInstantiated(FastEngine caller) => FastHookValues.Add((caller.Power, caller.Bonus));

        public int Transform(FastEngine caller, int delta) => caller.Power + caller.Bonus + delta;
    }
}

public partial class IntLookup
{
    internal static int DefaultBindingCount { get; private set; }

    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        DefaultBindingCount++;
        return new LookupBinding();
    }

    private sealed class LookupBinding : DefaultBehavior
    {
        public int Metric(IntLookup caller, int delta) => (caller.Value ?? 0) + delta;
    }
}
