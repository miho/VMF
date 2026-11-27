using Vmf.Runtime;

namespace Eu.Mihosoft.Vmf.Conformance.Behavior;

public sealed partial class HookedSnapshotCollections
{
    private static partial Behavior CreateDefaultBehavior() => new SnapshotCollectionBehavior();

    private sealed class SnapshotCollectionBehavior : Behavior
    {
        private int invocations;
        private readonly List<string> mapEdits = [];

        public void OnHookedSnapshotCollectionsInstantiated(HookedSnapshotCollections caller)
        {
            caller.Numbers.Add(1);
            caller.Ordered.Add("seed");
            caller.Flags.Add(1);
            caller.Values.Add("seed-a", 1);
            caller.Values.Add("seed-b", 2);
            caller.Values.AddListener(edit =>
                mapEdits.Add($"{string.Join(",", edit.Removed.Keys)}>{string.Join(",", edit.Added.Keys)}"));
            caller.Legacy.Add("seed");
        }

        public int Invocation(HookedSnapshotCollections caller) => ++invocations;
        public string? ObservedMapEdits(HookedSnapshotCollections caller) => string.Join("|", mapEdits);
    }
}
