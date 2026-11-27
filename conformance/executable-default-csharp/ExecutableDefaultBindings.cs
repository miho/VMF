namespace Eu.Mihosoft.Vmf.Conformance.Executabledefaults;

public sealed partial class MutableDefaults
{
    private static partial string? CreateDefaultLabel() => ExecutableDefaultValues.FallbackLabel();

    private static partial int CreateDefaultQuota() => ExecutableDefaultValues.Quota();
}

public sealed partial class ImmutableDefaults
{
    private static partial string? CreateDefaultLabel() => ExecutableDefaultValues.FallbackLabel();

    private static partial double? CreateDefaultScale() => ExecutableDefaultValues.Scale();
}

public sealed partial class HookedDefaults
{
    private static partial string? CreateDefaultLabel() => ExecutableDefaultValues.FallbackLabel();

    private static partial int CreateDefaultPriority() => ExecutableDefaultValues.Priority();

    private static partial Behavior CreateDefaultBehavior() => new DefaultBehavior();

    private sealed class DefaultBehavior : Behavior
    {
        public void OnHookedDefaultsInstantiated(HookedDefaults caller)
        {
            ExecutableDefaultValues.RecordHook(caller.Label, caller.Priority);
            caller.Priority = 99;
        }
    }
}

public partial class AncestorDefaults
{
    private static partial string? CreateDefaultLabel() => ExecutableDefaultValues.Sample("base");
}

public sealed partial class JoinedDefaults
{
    private static partial string? CreateDefaultAlias() => ExecutableDefaultValues.Sample("alias");

    private static partial DefaultBehavior CreateDefaultBehavior() => new JoinedDefaultBehavior();

    private sealed class JoinedDefaultBehavior : DefaultBehavior
    {
        public void OnJoinedDefaultsInstantiated(JoinedDefaults caller)
        {
            ExecutableDefaultValues.RecordJoinedHook(caller.Label, caller.Alias);
            caller.Label = "hook-label";
            caller.Alias = "hook-alias";
        }
    }
}

public sealed partial class ReplacedDefaults
{
    private static partial string? CreateDefaultLabel() => ExecutableDefaultValues.Sample("replacement");
}

public partial class AncestorValueDefaults
{
    private static partial string? CreateDefaultLabel() => ExecutableDefaultValues.Sample("base-value");
}

public sealed partial class ReplacedValueDefaults
{
    private static partial string? CreateDefaultLabel() => ExecutableDefaultValues.Sample("derived-value");
}

internal static class ExecutableDefaultValues
{
    private static readonly List<string> Events = [];
    private static int sequence;

    internal static string FallbackLabel()
    {
        Events.Add("label");
        return "prepared";
    }

    internal static int Quota()
    {
        Events.Add("quota");
        return 28;
    }

    internal static double Scale()
    {
        Events.Add("scale");
        return Math.CopySign(0.0, -1.0);
    }

    internal static int Priority()
    {
        Events.Add("priority");
        return 41;
    }

    internal static void RecordHook(string? label, int priority) => Events.Add($"hook:{label ?? "null"}/{priority}");

    internal static void RecordJoinedHook(string? label, string? alias) =>
        Events.Add($"joined-hook:{label ?? "null"}/{alias ?? "null"}");

    internal static string Sample(string name)
    {
        string value = $"{name}-{++sequence}";
        Events.Add(value);
        return value;
    }

    internal static void RestartSequence() => sequence = 0;

    internal static IReadOnlyList<string> TakeEvents()
    {
        var events = Events.ToArray();
        Events.Clear();
        return events;
    }
}
