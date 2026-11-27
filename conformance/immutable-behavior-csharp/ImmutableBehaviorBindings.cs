namespace Eu.Mihosoft.Vmf.Conformance.Immutablebehavior;

public sealed partial class IndependentValue
{
    private static partial Behavior_Twin__eu_pmihosoft_pvmf_pconformance_pimmutablebehavior_pImmutableBehaviorConformance_pFirst_pTwin
        CreateBehavior_Twin__eu_pmihosoft_pvmf_pconformance_pimmutablebehavior_pImmutableBehaviorConformance_pFirst_pTwin() =>
        new FirstBinding();

    private static partial Behavior_Twin__eu_pmihosoft_pvmf_pconformance_pimmutablebehavior_pImmutableBehaviorConformance_pSecond_pTwin
        CreateBehavior_Twin__eu_pmihosoft_pvmf_pconformance_pimmutablebehavior_pImmutableBehaviorConformance_pSecond_pTwin() =>
        new SecondBinding();

    private sealed class FirstBinding :
        Behavior_Twin__eu_pmihosoft_pvmf_pconformance_pimmutablebehavior_pImmutableBehaviorConformance_pFirst_pTwin
    {
        private int calls;
        public int First(IndependentValue caller) => 100 + ++calls;
        public int FirstAgain(IndependentValue caller) => 100 + ++calls;
    }

    private sealed class SecondBinding :
        Behavior_Twin__eu_pmihosoft_pvmf_pconformance_pimmutablebehavior_pImmutableBehaviorConformance_pSecond_pTwin
    {
        private int calls;
        public int Second(IndependentValue caller) => 200 + ++calls;
    }
}

public sealed partial class MethodValue
{
    private static int bindingCount;
    internal static int BindingCount => Volatile.Read(ref bindingCount);

    private static partial Behavior CreateDefaultBehavior()
    {
        Interlocked.Increment(ref bindingCount);
        return new MethodBinding();
    }

    private sealed class MethodBinding : Behavior
    {
        private int calls;

        public int Calls(MethodValue caller) => Volatile.Read(ref calls);

        public string? Render(MethodValue caller, string? prefix)
        {
            Interlocked.Increment(ref calls);
            return prefix + caller.Text;
        }

        public string? Render(MethodValue caller, int length)
        {
            Interlocked.Increment(ref calls);
            return caller.Text?[..length];
        }

        public int Sum(MethodValue caller, int[] values) => values.Sum();
    }
}

public sealed partial class HookValue
{
    internal static int BindingCount { get; private set; }

    private static partial Behavior CreateDefaultBehavior()
    {
        BindingCount++;
        return new HookBinding();
    }

    private sealed class HookBinding : Behavior
    {
        public int Score(HookValue caller, int increment) => (caller.Text?.Length ?? 0) + increment;
    }
}

public sealed partial class HookTraitValue
{
    internal static int BindingCount { get; private set; }

    private static partial Behavior CreateDefaultBehavior()
    {
        BindingCount++;
        return new HookTraitBinding();
    }

    private sealed class HookTraitBinding : Behavior
    {
        public int Measure(HookTraitValue caller, int factor) => caller.Base * factor;
    }
}

public sealed partial class TraitValue
{
    internal static int TraitBindings { get; private set; }
    internal static int LocalBindings { get; private set; }

    private static partial Behavior_TraitBinding CreateBehavior_TraitBinding()
    {
        TraitBindings++;
        return new InheritedBinding();
    }

    private static partial Behavior_LocalTraitBinding CreateBehavior_LocalTraitBinding()
    {
        LocalBindings++;
        return new LocalBinding();
    }

    private sealed class InheritedBinding : Behavior_TraitBinding
    {
        public int Metric(TraitValue caller, int delta) => MetricTraitBinding.Measure(caller, delta);
    }

    private sealed class LocalBinding : Behavior_LocalTraitBinding
    {
        public string? Describe(TraitValue caller, string? suffix) => caller.Code + suffix;
    }
}

public abstract partial class MetricTrait
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new MetricBinding();

    private sealed class MetricBinding : DefaultBehavior
    {
        public int Metric(MetricTrait caller, int delta) => MetricTraitBinding.Measure(caller, delta);
    }
}

internal static class MetricTraitBinding
{
    internal static int Measure(IReadOnlyMetricTrait caller, int delta) => caller.Base + delta;
}
