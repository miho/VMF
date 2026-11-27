using Vmf.Runtime;

namespace Eu.Mihosoft.Vmf.Conformance.Behavior;

internal static class ManagedDefaultBehaviorState
{
    internal static readonly List<string> Phases = [];
    internal static int Count;
    internal static bool ThrowOnRemoval;
    internal static HookedManagedDefaults? LastOwner;

    internal static void Reset()
    {
        Phases.Clear();
        Count = 0;
        ThrowOnRemoval = false;
        LastOwner = null;
    }

    internal static IEnumerable<int> Numbers()
    {
        Phases.Add($"numbers:{++Count}");
        return [Count];
    }

    internal static int Token()
    {
        Phases.Add($"token:{Count}");
        return Count * 100;
    }

    internal static IEnumerable<KeyValuePair<string, int?>> Values()
    {
        Phases.Add($"values:{Count}");
        return [new($"saved-{Count}", Count)];
    }
}

public sealed partial class UnhookedManagedDefaults
{
    private static partial IEnumerable<int> CreateDefaultNumbers() => ManagedDefaultBehaviorState.Numbers();
    private static partial IEnumerable<KeyValuePair<string, int?>> CreateDefaultValues() => ManagedDefaultBehaviorState.Values();
}

public sealed partial class HookedManagedDefaults
{
    private static partial Behavior CreateDefaultBehavior() => new HookedDefaultsBehavior();
    private static partial IEnumerable<int> CreateDefaultNumbers() => ManagedDefaultBehaviorState.Numbers();
    private static partial int CreateDefaultToken() => ManagedDefaultBehaviorState.Token();
    private static partial IEnumerable<KeyValuePair<string, int?>> CreateDefaultValues() => ManagedDefaultBehaviorState.Values();

    private sealed class HookedDefaultsBehavior : Behavior
    {
        private readonly List<string> edits = [];

        public void OnHookedManagedDefaultsInstantiated(HookedManagedDefaults caller)
        {
            ManagedDefaultBehaviorState.LastOwner = caller;
            ManagedDefaultBehaviorState.Phases.Add($"hook:{caller.Token}");
            caller.Numbers.Add(99);
            caller.Values.Put("seed-a", 1);
            caller.Values.Put("seed-b", 2);
            caller.Values.AddListener(edit =>
            {
                edits.Add($"{string.Join(",", edit.Removed.Keys)}>{string.Join(",", edit.Added.Keys)}");
                if (ManagedDefaultBehaviorState.ThrowOnRemoval && edit.Removed.ContainsKey("seed-a"))
                    throw new InvalidOperationException("stop after seed-a");
            });
        }

        public string? ObservedMapEdits(HookedManagedDefaults caller) => string.Join("|", edits);
    }
}
