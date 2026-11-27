namespace Eu.Mihosoft.Vmf.Conformance.Behavior;

public sealed partial class MutableGauge
{
    internal static List<(int Base, string? State)> HookValues { get; } = [];

    private static partial Behavior CreateDefaultBehavior() => new GaugeBehavior();

    private sealed class GaugeBehavior : Behavior
    {
        public void OnMutableGaugeInstantiated(MutableGauge caller) => HookValues.Add((caller.Base, caller.State));

        public int Adjust(MutableGauge caller, int amount) => caller.Base + amount;

        public int? Adjust(MutableGauge caller, int? amount) => caller.Base - amount;

        public CallResult? Compose(MutableGauge caller, CallResult? input, string? suffix) => input is null ? null
            : CallResult.NewBuilder().WithText(input.Text + suffix).Build();

        public string? Diagnose(MutableGauge caller, bool verbose) => verbose ? caller.State : "ok";

        public int Sum(MutableGauge caller, int[] values) => values.Sum();

        public void Submit(MutableGauge caller, string? text) => caller.State = text;
    }
}

public sealed partial class SignalGauge
{
    internal static int BindingCount { get; private set; }

    private static partial Behavior CreateDefaultBehavior()
    {
        BindingCount++;
        return new SignalBehavior();
    }

    private sealed class SignalBehavior : Behavior
    {
        private int calls;

        public int Signal(SignalGauge caller, int increment) => caller.Level + increment + ++calls;
    }
}

public sealed partial class HookedJournalCheckpoint
{
    internal static int HookCount { get; private set; }

    private static partial Behavior CreateDefaultBehavior() => new JournalCheckpointBehavior();

    private sealed class JournalCheckpointBehavior : Behavior
    {
        private int invocations;

        public void OnHookedJournalCheckpointInstantiated(HookedJournalCheckpoint caller)
        {
            HookCount++;
            caller.Slots.Add(1);
        }

        public int Invocation(HookedJournalCheckpoint caller) => ++invocations;
    }
}
