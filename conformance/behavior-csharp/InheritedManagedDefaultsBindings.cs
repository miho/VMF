using Vmf.Runtime;

namespace Eu.Mihosoft.Vmf.Conformance.Behavior;

internal static class InheritedManagedDefaultsState
{
    internal static readonly List<string> Phases = [];
    internal static readonly List<string> NumbersEdits = [];
    internal static readonly List<string> SettingsEdits = [];
    internal static InheritedManagedFields? LastOwner;
    internal static ScalarList<int>? LastNumbers;
    internal static ScalarMap<string, int?>? LastSettings;
    private static int count;

    internal static void Reset()
    {
        Phases.Clear();
        NumbersEdits.Clear();
        SettingsEdits.Clear();
        LastOwner = null;
        LastNumbers = null;
        LastSettings = null;
        count = 0;
    }

    internal static IEnumerable<int> Numbers()
    {
        Phases.Add($"numbers:{++count}");
        return [count];
    }

    internal static IEnumerable<KeyValuePair<string, int?>> Settings()
    {
        Phases.Add($"settings:{count}");
        return [new($"saved-{count}", count)];
    }

    internal static IEnumerable<int> ReplacementNumbers()
    {
        Phases.Add($"alternative-numbers:{++count}");
        return [count + 10];
    }

    internal static IEnumerable<KeyValuePair<string, int?>> ReplacementSettings()
    {
        Phases.Add($"alternative-settings:{count}");
        return [new($"altered-{count}", count + 10)];
    }

    internal static void Seed(InheritedManagedFields owner, string kind)
    {
        if (owner.Numbers.Count != 0 || owner.Settings.Count != 0)
            throw new InvalidOperationException("The hook must run before declared defaults.");
        LastOwner = owner;
        LastNumbers = owner.Numbers;
        LastSettings = owner.Settings;
        Phases.Add($"hook:{kind}");
        owner.Numbers.Add(99);
        owner.Settings.Put("seed-a", 1);
        owner.Settings.Put("seed-b", 2);
        owner.Numbers.AddListener(edit => NumbersEdits.Add(
            $"[{string.Join(", ", edit.Removed.Select(value => value.Value))}]>[{string.Join(", ", edit.Added.Select(value => value.Value))}]"));
        owner.Settings.AddListener(edit => SettingsEdits.Add(
            $"{string.Join(",", edit.Removed.Keys)}>{string.Join(",", edit.Added.Keys)}"));
    }
}

public abstract partial class InheritedManagedFields
{
    private static partial IEnumerable<int> CreateDefaultNumbers() => InheritedManagedDefaultsState.Numbers();
    private static partial IEnumerable<KeyValuePair<string, int?>> CreateDefaultSettings() => InheritedManagedDefaultsState.Settings();
}

public sealed partial class InheritedManagedValues
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new Binding();

    private sealed class Binding : DefaultBehavior
    {
        public void OnInheritedManagedValuesInstantiated(InheritedManagedValues caller) =>
            InheritedManagedDefaultsState.Seed(caller, "values");
    }
}

public partial class InheritedManagedReferences
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new Binding();

    private sealed class Binding : DefaultBehavior
    {
        public void OnInheritedManagedReferencesInstantiated(InheritedManagedReferences caller) =>
            InheritedManagedDefaultsState.Seed(caller, "references");
    }
}


public sealed partial class InheritedManagedOverridden
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new Binding();

    private sealed class Binding : DefaultBehavior
    {
        public void OnInheritedManagedOverriddenInstantiated(InheritedManagedOverridden caller) =>
            InheritedManagedDefaultsState.Seed(caller, "replacement");
    }
}

public sealed partial class RedeclaredManagedReplacement
{
    private static partial IEnumerable<int> CreateDefaultNumbers() => InheritedManagedDefaultsState.ReplacementNumbers();
    private static partial IEnumerable<KeyValuePair<string, int?>> CreateDefaultSettings() => InheritedManagedDefaultsState.ReplacementSettings();
}

public sealed partial class RedeclaredManagedMixed
{
    private static partial IEnumerable<int> CreateDefaultNumbers() => InheritedManagedDefaultsState.ReplacementNumbers();
}
