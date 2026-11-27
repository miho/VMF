namespace Eu.Mihosoft.Vmf.Conformance.Referencedefaults;

public partial class RootHookSelection
{
    private static partial IEnumerable<SelectionTarget> CreateDefaultQueue() => HookReferenceDefaultBindings.Queue();
    private static partial IEnumerable<int> CreateDefaultEarlier() => HookReferenceDefaultBindings.Earlier();
    private static partial string? CreateDefaultZulu() => HookReferenceDefaultBindings.Zulu();
    private static partial DefaultBehavior CreateDefaultBehavior() => new RootHookBehavior();

    private sealed class RootHookBehavior : DefaultBehavior
    {
        public void OnRootHookSelectionInstantiated(RootHookSelection owner)
        {
            string phase = "hook-root";
            if (owner is RootHookSelectionLeaf leaf)
            {
                if (leaf.Ahead is not null)
                {
                    throw new InvalidOperationException("The inherited hook runs before the concrete leaf default.");
                }
                leaf.Ahead = "hook-child";
                phase = "hook-leaf";
            }

            HookReferenceDefaultBindings.ObserveHook(owner, owner.AsReadOnly(), owner.Queue.Count,
                owner.Earlier.Count, owner.Zulu, phase);
            owner.Queue.Add(ReferenceDefaultBindings.Target("hook-member"));
            owner.Earlier.Add(90);
            owner.Zulu = "hook";
        }
    }
}

public sealed partial class RootHookSelectionLeaf
{
    private static partial string? CreateDefaultAhead()
    {
        HookReferenceDefaultBindings.Events.Add("ahead");
        return "early";
    }
}

public sealed partial class SingleHookSelection
{
    private static partial IEnumerable<SelectionTarget> CreateDefaultQueue() => HookReferenceDefaultBindings.Queue();
    private static partial IEnumerable<int> CreateDefaultEarlier() => HookReferenceDefaultBindings.Earlier();
    private static partial string? CreateDefaultZulu() => HookReferenceDefaultBindings.Zulu();
    private static partial Behavior CreateDefaultBehavior() => new SingleHookBehavior();

    private sealed class SingleHookBehavior : Behavior
    {
        public void OnSingleHookSelectionInstantiated(SingleHookSelection owner)
        {
            HookReferenceDefaultBindings.ObserveHook(owner, owner.AsReadOnly(), owner.Queue.Count,
                owner.Earlier.Count, owner.Zulu, "hook-single");
            owner.Queue.Add(ReferenceDefaultBindings.Target("hook-member"));
            owner.Earlier.Add(90);
            owner.Zulu = "hook";
        }
    }
}

public sealed partial class RootHookOverride
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new AdditionalHookBehavior();

    private sealed class AdditionalHookBehavior : DefaultBehavior
    {
        public void OnRootHookOverrideInstantiated(RootHookOverride owner)
        {
            HookReferenceDefaultBindings.ObserveHook(owner, owner.AsReadOnly(), owner.Queue.Count,
                owner.Earlier.Count, owner.Zulu, "hook-child-local");
            owner.Queue.Add(ReferenceDefaultBindings.Target("hook-member"));
        }
    }
}
