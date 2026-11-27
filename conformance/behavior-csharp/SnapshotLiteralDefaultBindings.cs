using Vmf.Runtime;

namespace Eu.Mihosoft.Vmf.Conformance.Behavior;

public sealed partial class HookedLiteralCollections
{
    private static partial Behavior CreateDefaultBehavior() => new LiteralDefaultBehavior();

    private sealed class LiteralDefaultBehavior : Behavior
    {
        private readonly List<string> mapEdits = [];

        public void OnHookedLiteralCollectionsInstantiated(HookedLiteralCollections caller)
        {
            if (caller.Numbers.Count != 0 || caller.Ordered.Count != 0 || caller.Flags.Count != 0 || caller.Values.Count != 0)
                throw new InvalidOperationException("Managed literal defaults are applied after the hook");
            caller.Numbers.Add(1);
            caller.Ordered.Add("hook");
            caller.Flags.Add(1);
            caller.Values.Add("seed-a", 1);
            caller.Values.Add("seed-b", 2);
            caller.Values.AddListener(edit =>
                mapEdits.Add($"{string.Join(",", edit.Removed.Keys)}>{string.Join(",", edit.Added.Keys)}"));
        }

        public string? ObservedMapEdits(HookedLiteralCollections caller) => string.Join("|", mapEdits);
    }
}
