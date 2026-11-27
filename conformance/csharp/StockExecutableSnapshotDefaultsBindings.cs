namespace Eu.Mihosoft.Vmf.Jackson.Test.Executabledeserialization;

internal static class SnapshotDefaultInputs
{
    private static int evaluations;

    internal static IEnumerable<int> Numbers() => [++evaluations];

    internal static IEnumerable<KeyValuePair<string, int?>> Settings() =>
        [new($"saved-{evaluations}", evaluations)];
}

public sealed partial class HookedValues
{
    private static partial IEnumerable<int> CreateDefaultNumbers() => SnapshotDefaultInputs.Numbers();
    private static partial IEnumerable<KeyValuePair<string, int?>> CreateDefaultSettings() => SnapshotDefaultInputs.Settings();
    private static partial Behavior CreateDefaultBehavior() => new DefaultHook();

    private sealed class DefaultHook : Behavior
    {
        public void OnHookedValuesInstantiated(HookedValues caller)
        {
            caller.Numbers.Add(99);
            caller.Settings.Put("hook", 99);
        }
    }
}

public sealed partial class HookedReferences
{
    private static partial IEnumerable<int> CreateDefaultNumbers() => SnapshotDefaultInputs.Numbers();
    private static partial IEnumerable<KeyValuePair<string, int?>> CreateDefaultSettings() => SnapshotDefaultInputs.Settings();
    private static partial Behavior CreateDefaultBehavior() => new DefaultHook();

    private sealed class DefaultHook : Behavior
    {
        public void OnHookedReferencesInstantiated(HookedReferences caller)
        {
            caller.Numbers.Add(99);
            caller.Settings.Put("hook", 99);
        }
    }
}

public sealed partial class HookedMetadataPort
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new MetadataHook();

    private sealed class MetadataHook : DefaultBehavior { }
}
