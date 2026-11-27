namespace Eu.Mihosoft.Vmf.Conformance.Referencedefaults;

public partial class HookedProjection
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new ProjectedConstructionBehavior();

    private sealed class ProjectedConstructionBehavior : DefaultBehavior
    {
        private int calls;

        public string? Describe(HookedProjection owner, string? prefix) =>
            $"{prefix ?? "null"}:{++calls}:{owner.Queue.Count}:{owner.Favorites.Count}:{owner.Summary.Count}:{owner.Before ?? "null"}";

        public void OnHookedProjectionInstantiated(HookedProjection owner)
        {
            var leaf = owner as HookedProjectionLeaf;
            ProjectedDefaultBindings.Events.Add(leaf is null ? "hook" : "hook-leaf");
            IProjectedQueueRoot queues = owner;
            IProjectedFavoriteRoot favorites = owner;
            if (queues.Queue.Count != 0 || favorites.Favorites.Count != 0 || owner.Numbers.Count != 0
                || queues.Summary.Count != 0 || owner.Before is not null || owner.Path is not null
                || queues.Zulu is not null || favorites.Accent is not null || leaf?.Ahead is not null)
            {
                throw new InvalidOperationException("The concrete owner must expose all slots before providers run.");
            }

            CheckUnavailable(queues, queues.AsReadOnly(), "queue");
            CheckUnavailable(favorites, favorites.AsReadOnly(), "favorites");
            if (owner.Describe("hook") != "hook:1:0:0:0:null")
            {
                throw new InvalidOperationException("The hook must dispatch to its own provider before initialization.");
            }

            queues.Queue.Add(ReferenceDefaultBindings.Target("hook-queue"));
            favorites.Favorites.Add(ReferenceDefaultBindings.Target("hook-favorite"));
            queues.Summary.Add(90);
            owner.Numbers.Add(80);
            owner.Before = "hook";
            queues.Zulu = "hook";
            if (leaf is not null) leaf.Ahead = "hook";
        }

        private static void CheckUnavailable(object owner, object view, string name)
        {
            var property = ModelSnapshot.Reflection.Reflect(owner).FindProperty(name)!;
            var projected = ModelSnapshot.Reflection.Reflect(view).FindProperty(name)!;
            if (property.DefaultValue is not null || !property.IsSet)
            {
                throw new InvalidOperationException("The fallback must still be unavailable in the hook.");
            }
            RequireUnavailable(() => projected.DefaultValue);
            RequireUnavailable(() => projected.IsSet);
        }

        private static void RequireUnavailable(Func<object?> read)
        {
            try
            {
                read();
            }
            catch (InvalidOperationException unavailable) when (unavailable.Message.Contains("during construction", StringComparison.Ordinal))
            {
                return;
            }
            throw new InvalidOperationException("The projected fallback unexpectedly became available.");
        }
    }
}

public sealed partial class HookedProjectionLeaf
{
    private static partial string? CreateDefaultAhead() => ProjectedDefaultBindings.Text("ahead");
}
