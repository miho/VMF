namespace Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;

public partial class PoweredCell
{
    internal static List<string> HookTrace { get; } = [];
    internal static int BindingCount { get; set; }

    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        BindingCount++;
        return new PowerBinding();
    }

    private sealed class PowerBinding : DefaultBehavior
    {
        public void OnPoweredCellInstantiated(PoweredCell caller) => HookTrace.Add(caller is ComposedCell composed
            ? $"composed:{caller.Energy}:{composed.Weight}"
            : $"powered:{caller.Energy}");

        public int Amplify(PoweredCell caller, int delta) => caller.Energy * delta;
    }
}

public partial class MeteredCell
{
    internal static List<string> HookTrace { get; } = [];
    internal static int BindingCount { get; set; }

    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        BindingCount++;
        return new MeterBinding();
    }

    private sealed class MeterBinding : DefaultBehavior
    {
        private int calls;

        public void OnMeteredCellInstantiated(IMeteredCell caller) => HookTrace.Add(caller is StandaloneMeter standalone
            ? $"standalone:{caller.Weight}:{standalone.Voltage}"
            : $"metered:{caller.Weight}");

        public int Advance(IMeteredCell caller, int delta) => caller.Weight + delta + ++calls;
        public int Calls(IMeteredCell caller) => calls;
        public bool CallerIs(IMeteredCell caller, IMeteredCell? value) => ReferenceEquals(caller, value);
    }
}

public partial class SpecialCell
{
    internal static List<string> SpecialHookTrace { get; } = [];

    private static partial DefaultBehavior CreateDefaultBehavior() => new SpecialBinding();

    private sealed class SpecialBinding : DefaultBehavior
    {
        public void OnSpecialCellInstantiated(SpecialCell caller) => SpecialHookTrace.Add($"{caller.Energy}:{caller.Weight}");

        public int Advance(SpecialCell caller, int delta) => 100 + caller.Weight + delta;
    }
}
