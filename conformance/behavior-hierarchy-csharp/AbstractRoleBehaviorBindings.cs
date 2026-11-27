namespace Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;

public abstract partial class FlowChild
{
    internal static List<int> HookIds { get; } = [];
    internal static int BindingCount { get; set; }

    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        BindingCount++;
        return new FlowBinding();
    }

    private sealed class FlowBinding : DefaultBehavior
    {
        private int calls;

        public void OnFlowChildInstantiated(IFlowChild caller) => HookIds.Add(OriginId(caller));
        public IList<IFlowScope?>? ParentScopes(IFlowChild caller)
        {
            calls++;
            return caller is IFlowScope scope ? new List<IFlowScope?> { scope, null } : new List<IFlowScope?>();
        }

        public int Calls(IFlowChild caller) => calls;
        public int OriginId(IFlowChild caller) => ((IFlowId)caller).Id;
    }
}
