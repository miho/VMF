namespace Eu.Mihosoft.Vmf.Conformance.Immutableancestry;

public partial class BaseValue
{
    private static partial Behavior CreateDefaultBehavior() => BindingTrace.Record("base:BaseValue", new Binding());

    private sealed class Binding : Behavior
    {
        public string? Render(BaseValue owner, string? prefix) => prefix + owner.Base;
        public int Score(BaseValue owner, int increment) => owner.Base + increment;
    }
}

public sealed partial class InheritedValue
{
    private static partial Behavior CreateDefaultBehavior() => BindingTrace.Record("base:InheritedValue", new Binding());

    private sealed class Binding : Behavior
    {
        public string? Render(InheritedValue owner, string? prefix) => prefix + owner.Base;
        public int Score(InheritedValue owner, int increment) => owner.Base + increment;
    }
}

public partial class SpecialValue
{
    private static partial Behavior_BaseBinding CreateBehavior_BaseBinding() =>
        BindingTrace.Record("base:SpecialValue", new BaseBinding());

    private static partial Behavior_SpecialBinding CreateBehavior_SpecialBinding() =>
        BindingTrace.Record("special:SpecialValue", new SpecialBinding());

    private sealed class BaseBinding : Behavior_BaseBinding
    {
        public string? Render(SpecialValue owner, string? prefix) => prefix + owner.Base;
    }

    private sealed class SpecialBinding : Behavior_SpecialBinding
    {
        public int Score(SpecialValue owner, int increment) => (owner.Base + increment) * owner.Factor;
        public int Adjust(SpecialValue owner, int value) => value * owner.Factor;
    }
}

public sealed partial class LeafValue
{
    private static partial Behavior_BaseBinding CreateBehavior_BaseBinding() =>
        BindingTrace.Record("base:LeafValue", new BaseBinding());

    private static partial Behavior_SpecialBinding CreateBehavior_SpecialBinding() =>
        BindingTrace.Record("special:LeafValue", new SpecialBinding());

    private sealed class BaseBinding : Behavior_BaseBinding
    {
        public string? Render(LeafValue owner, string? prefix) => prefix + owner.Base;
    }

    private sealed class SpecialBinding : Behavior_SpecialBinding
    {
        public int Score(LeafValue owner, int increment) => (owner.Base + increment) * owner.Factor;
        public int Adjust(LeafValue owner, int value) => value * owner.Factor;
    }
}

internal static class BindingTrace
{
    internal static List<string> Values { get; } = [];

    internal static T Record<T>(string name, T binding)
    {
        Values.Add(name);
        return binding;
    }
}
