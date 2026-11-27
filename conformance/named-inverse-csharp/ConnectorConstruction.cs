using System.Runtime.CompilerServices;
using Vmf.Runtime;

namespace Eu.Mihosoft.Vmf.Conformance.Namedinverse;

public abstract partial class Connector
{
    internal static ConditionalWeakTable<Connector, IModelList<Edge?>> ObservedLinks { get; } = new();

    private static partial DefaultBehavior CreateDefaultBehavior() => new ObserveLinks();

    private sealed class ObserveLinks : DefaultBehavior
    {
        public void OnConnectorInstantiated(Connector caller)
        {
            ObservedLinks.Add(caller, caller.Links);
        }
    }
}
