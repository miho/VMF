namespace Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;

internal static class DeepRoleState
{
    internal static int RootCreated { get; set; }
    internal static int ConfigureCreated { get; set; }
    internal static int SpecialCreated { get; set; }
    internal static int PeerCreated { get; set; }
    internal static List<string> Hooks { get; } = [];
    internal static List<object> Owners { get; } = [];

    internal static void Hook(object owner, string label, int value)
    {
        Hooks.Add($"{label}:{value}");
        Owners.Add(owner);
    }
}

public partial class RootService
{
    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        DeepRoleState.RootCreated++;
        return new RootHost();
    }

    private sealed class RootHost : DefaultBehavior
    {
        private readonly TrioBindingState state = new();

        public int Choose(IRootService caller, int value) => 100 + value + state.Advance(caller);
        public int RootCalls(IRootService caller) => state.Calls;
        public void OnRootServiceInstantiated(IRootService caller)
        {
            state.Bind(caller);
            DeepRoleState.Hook(caller, "root", caller.RootValue);
        }
    }
}

public partial class ConfiguredService
{
    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        DeepRoleState.ConfigureCreated++;
        return new ConfiguredHost();
    }

    private sealed class ConfiguredHost : DefaultBehavior
    {
        private readonly TrioBindingState state = new();

        public int Tune(IConfiguredService caller, int value) => 200 + value + state.Advance(caller);
        public int ConfiguredCalls(IConfiguredService caller) => state.Calls;
        public void OnConfiguredServiceInstantiated(IConfiguredService caller)
        {
            state.Bind(caller);
            DeepRoleState.Hook(caller, "configured", caller.Setting);
        }
    }
}

public partial class SpecializedService
{
    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        DeepRoleState.SpecialCreated++;
        return new SpecializedHost();
    }

    private sealed class SpecializedHost : DefaultBehavior
    {
        private readonly TrioBindingState state = new();

        public int Choose(ISpecializedService caller, int value) => 400 + value + state.Advance(caller);
        public int SpecializedCalls(ISpecializedService caller) => state.Calls;
        public void OnSpecializedServiceInstantiated(ISpecializedService caller)
        {
            state.Bind(caller);
            DeepRoleState.Hook(caller, "specialized", caller.Specialty);
        }
    }
}

public partial class PeerService
{
    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        DeepRoleState.PeerCreated++;
        return new PeerHost();
    }

    private sealed class PeerHost : DefaultBehavior
    {
        private readonly TrioBindingState state = new();

        public int Choose(IPeerService caller, int value) => 300 + value + state.Advance(caller);
        public int PeerCalls(IPeerService caller) => state.Calls;
        public void OnPeerServiceInstantiated(IPeerService caller)
        {
            state.Bind(caller);
            DeepRoleState.Hook(caller, "peer", caller.PeerValue);
        }
    }
}
