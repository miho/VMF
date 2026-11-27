namespace Eu.Mihosoft.Vmf.Conformance.Collectiondefaults;

internal static class CollectionDefaultBindings
{
    // These tests use the same mutable provider inputs and invocation count.
    internal const string TestCollection = "Managed collection defaults";

    internal static readonly List<int> Numbers = [3, 5];
    internal static readonly List<string> Labels = ["draft", "published"];
    internal static readonly Dictionary<string, int?> Options = new() { ["present"] = null, ["known"] = 11 };
    internal static int NumberCalls;

    internal static IEnumerable<int> ReadNumbers()
    {
        NumberCalls++;
        return Numbers;
    }

    internal static IEnumerable<long> ReadCodes() => [19L, 23L];
}

public sealed partial class ManagedSamples
{
    private static partial IEnumerable<int> CreateDefaultNumbers() => CollectionDefaultBindings.ReadNumbers();
    private static partial IEnumerable<string> CreateDefaultLabels() => CollectionDefaultBindings.Labels;
    private static partial IEnumerable<long> CreateDefaultCodes() => CollectionDefaultBindings.ReadCodes();
    private static partial IEnumerable<KeyValuePair<string, int?>> CreateDefaultOptions() => CollectionDefaultBindings.Options;
}

public sealed partial class FrozenSamples
{
    private static partial IEnumerable<int> CreateDefaultNumbers() => CollectionDefaultBindings.ReadNumbers();
    private static partial IEnumerable<string> CreateDefaultLabels() => CollectionDefaultBindings.Labels;
    private static partial IEnumerable<long> CreateDefaultCodes() => CollectionDefaultBindings.ReadCodes();
    private static partial IEnumerable<KeyValuePair<string, int?>> CreateDefaultOptions() => CollectionDefaultBindings.Options;
}
