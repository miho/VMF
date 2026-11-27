namespace Eu.Mihosoft.Vmf.Conformance.Referencedefaults;

internal static class HookReferenceDefaultBindings
{
    internal static List<string> Events { get; } = [];

    internal static IEnumerable<SelectionTarget> Queue()
    {
        Events.Add("queue");
        return [ReferenceDefaultBindings.Target("seed")];
    }

    internal static string Zulu()
    {
        Events.Add("zulu");
        return "last";
    }

    internal static IEnumerable<int> Earlier()
    {
        Events.Add("earlier");
        return [4];
    }

    internal static void ObserveHook(object owner, object view, int queueCount, int earlierCount, string? zulu, string phase)
    {
        Events.Add(phase);
        var queue = ModelSnapshot.Reflection.Reflect(owner).FindProperty("queue")!;
        var projected = ModelSnapshot.Reflection.Reflect(view).FindProperty("queue")!;
        if (queueCount != 0 || earlierCount != 0 || zulu is not null || queue.DefaultValue is not null || !queue.IsSet)
        {
            throw new InvalidOperationException("The hook must run before constructor defaults.");
        }
        VerifyUnavailable(() => projected.DefaultValue);
        VerifyUnavailable(() => projected.IsSet);
    }

    private static void VerifyUnavailable(Func<object?> read)
    {
        try
        {
            read();
        }
        catch (InvalidOperationException unavailable) when (unavailable.Message.Contains("during construction", StringComparison.Ordinal))
        {
            return;
        }

        throw new InvalidOperationException("The projected reference fallback unexpectedly became available in the hook.");
    }
}

public partial class HookSelectionBase
{
    private static partial IEnumerable<SelectionTarget> CreateDefaultQueue() => HookReferenceDefaultBindings.Queue();
    private static partial string? CreateDefaultZulu() => HookReferenceDefaultBindings.Zulu();
}

public partial class HookSelection
{
    private static partial IEnumerable<int> CreateDefaultEarlier() => HookReferenceDefaultBindings.Earlier();
    private static partial DefaultBehavior CreateDefaultBehavior() => new HookedReferenceBehavior();

    private sealed class HookedReferenceBehavior : DefaultBehavior
    {
        public void OnHookSelectionInstantiated(HookSelection owner)
        {
            HookReferenceDefaultBindings.ObserveHook(owner, owner.AsReadOnly(), owner.Queue.Count,
                owner.Earlier.Count, owner.Zulu, "hook");

            owner.Queue.Add(ReferenceDefaultBindings.Target("hook-member"));
            owner.Zulu = "hook";
            owner.Earlier.Add(90);
        }
    }
}
