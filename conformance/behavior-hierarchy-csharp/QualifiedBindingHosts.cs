namespace Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;

internal static class QualifiedBindingState
{
    internal static int LocalColdCreated { get; set; }
    internal static int LocalHotCreated { get; set; }
    internal static int SharedColdCreated { get; set; }
    internal static int SharedHotCreated { get; set; }
}

public partial class QualifiedLocal
{
    private static partial DefaultBehavior_Binding__eu_pmihosoft_pvmf_pconformance_pbehaviorhierarchy_pQualifiedBindingExchange_pCold_pBinding CreateDefaultBehavior_Binding__eu_pmihosoft_pvmf_pconformance_pbehaviorhierarchy_pQualifiedBindingExchange_pCold_pBinding()
    {
        QualifiedBindingState.LocalColdCreated++;
        return new ColdHost();
    }

    private static partial DefaultBehavior_Binding__eu_pmihosoft_pvmf_pconformance_pbehaviorhierarchy_pQualifiedBindingExchange_pHot_pBinding CreateDefaultBehavior_Binding__eu_pmihosoft_pvmf_pconformance_pbehaviorhierarchy_pQualifiedBindingExchange_pHot_pBinding()
    {
        QualifiedBindingState.LocalHotCreated++;
        return new HotHost();
    }

    private sealed class ColdHost : DefaultBehavior_Binding__eu_pmihosoft_pvmf_pconformance_pbehaviorhierarchy_pQualifiedBindingExchange_pCold_pBinding
    {
        private readonly TrioBindingState state = new();

        public void OnQualifiedLocalInstantiated(QualifiedLocal caller)
        {
            state.Bind(caller);
            if (caller.Level != 0) throw new InvalidOperationException("Hook must run before applying defaults.");
        }

        public int CoolLocal(QualifiedLocal caller) => 100 + state.Advance(caller);
    }

    private sealed class HotHost : DefaultBehavior_Binding__eu_pmihosoft_pvmf_pconformance_pbehaviorhierarchy_pQualifiedBindingExchange_pHot_pBinding
    {
        private readonly TrioBindingState state = new();
        public int WarmLocal(QualifiedLocal caller) => state.Advance(caller);
    }
}

public static partial class ModelBehaviorBindings
{
    private static partial Binding__eu_pmihosoft_pvmf_pconformance_pbehaviorhierarchy_pQualifiedBindingExchange_pCold_pBinding CreateBinding__eu_pmihosoft_pvmf_pconformance_pbehaviorhierarchy_pQualifiedBindingExchange_pCold_pBinding()
    {
        QualifiedBindingState.SharedColdCreated++;
        return new ColdHost();
    }

    private static partial Binding__eu_pmihosoft_pvmf_pconformance_pbehaviorhierarchy_pQualifiedBindingExchange_pHot_pBinding CreateBinding__eu_pmihosoft_pvmf_pconformance_pbehaviorhierarchy_pQualifiedBindingExchange_pHot_pBinding()
    {
        QualifiedBindingState.SharedHotCreated++;
        return new HotHost();
    }

    private sealed class ColdHost : Binding__eu_pmihosoft_pvmf_pconformance_pbehaviorhierarchy_pQualifiedBindingExchange_pCold_pBinding
    {
        private readonly TrioBindingState state = new();
        public int Cool(QualifiedCold caller) => state.Advance(caller);
        public int CoolAgain(IQualifiedColdRole caller) => state.Advance(caller);
    }

    private sealed class HotHost : Binding__eu_pmihosoft_pvmf_pconformance_pbehaviorhierarchy_pQualifiedBindingExchange_pHot_pBinding
    {
        private readonly TrioBindingState state = new();
        public int Warm(IQualifiedHot caller) => state.Advance(caller);
        public int WarmAgain(IQualifiedHotRole caller) => state.Advance(caller);
    }
}
