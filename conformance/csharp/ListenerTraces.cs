using Xunit;

namespace Vmf.CSharp.Conformance;

/// <summary>
/// Compares listener traces of the shared observer fixtures, like the Java conformance programs do.
/// </summary>
/// <remarks>
/// The portable contract orders the listeners of one object (E7) and an object before its ancestors (E8), not
/// listeners of different kinds: model listeners, listeners added through a read-only view, and raw collection
/// listeners. A label names its kind by its first letter (M0, R1, C0, P0, Rnew); any other label is its own kind.
/// A trace matches when every kind receives its calls in the expected order and no call is missing or repeated.
/// </remarks>
internal static class ListenerTraces
{
    internal static void EqualByKind(IEnumerable<string> expected, IEnumerable<string> actual) =>
        Assert.Equal(ByKind(expected), ByKind(actual));

    private static string ByKind(IEnumerable<string> trace)
    {
        var kinds = new SortedDictionary<string, List<string>>(StringComparer.Ordinal);
        foreach (string label in trace)
        {
            string name = label.Contains(':') ? label[..label.IndexOf(':')] : label;
            string kind = IsKindLabel(name) ? name[..1] : name;
            if (!kinds.TryGetValue(kind, out var calls)) kinds.Add(kind, calls = []);
            calls.Add(label);
        }
        return string.Join(" | ", kinds.Select(entry => entry.Key + ": " + string.Join(", ", entry.Value)));
    }

    // [A-Z] followed by digits, "new" or lower-case letters, as in the Java programs.
    private static bool IsKindLabel(string name) =>
        name.Length > 1 && char.IsAsciiLetterUpper(name[0])
            && (name[1..].All(char.IsAsciiDigit) || name[1..] == "new" || name[1..].All(char.IsAsciiLetterLower));
}
