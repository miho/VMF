namespace Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;

internal static class RoleContestTrace
{
    internal static List<string> Hooks { get; } = [];
    internal static int PrimaryBindings { get; set; }
    internal static int RoleBindings { get; set; }
}

public partial class ContestPrimary
{
    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        RoleContestTrace.PrimaryBindings++;
        return new PrimaryBinding();
    }

    private sealed class PrimaryBinding : DefaultBehavior
    {
        private int calls;
        public void OnContestPrimaryInstantiated(IContestPrimary caller) =>
            RoleContestTrace.Hooks.Add("primary:" + (caller.Title ?? "null"));
        public int Evaluate(IContestPrimary caller, int value) => 100 + value + ++calls;
        public int PrimaryCalls(IContestPrimary caller) => calls;
    }
}

public abstract partial class ContestRole
{
    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        RoleContestTrace.RoleBindings++;
        return new RoleBinding();
    }

    private sealed class RoleBinding : DefaultBehavior
    {
        private int calls;
        public void OnContestRoleInstantiated(IContestRole caller) =>
            RoleContestTrace.Hooks.Add("role:" + (caller.Label ?? "null"));
        public int Evaluate(IContestRole caller, int value) => 200 + value + ++calls;
        public int Evaluate(IContestRole caller, long value) => 400 + (int)value + ++calls;
        public int Evaluate(IContestRole caller, string? value) => 200 + value!.Length + ++calls;
        public int RoleCalls(IContestRole caller) => calls;
    }
}

public partial class ContestOverride
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new OverrideBinding();

    private sealed class OverrideBinding : DefaultBehavior
    {
        public void OnContestOverrideInstantiated(ContestOverride caller) =>
            RoleContestTrace.Hooks.Add($"local:{caller.Title ?? "null"}:{caller.Label ?? "null"}");
        public int Evaluate(ContestOverride caller, int value) => 300 + value;
    }
}
