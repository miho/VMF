using System.Collections;
using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace Vmf.Runtime;

/// <summary>Holds reference-set fallback identities captured by generated constructors and builders.</summary>
[EditorBrowsable(EditorBrowsableState.Never)]
public static class ReferenceCollectionDefaults
{
    private sealed class State
    {
        internal Func<object>? CreateShell { get; set; }
        internal Dictionary<string, IReadOnlyList<object?>> Fields { get; } = new(StringComparer.Ordinal);
    }

    private static readonly ConditionalWeakTable<object, State> Owners = new();

    internal static bool IsBound(SnapshotProperty property) =>
        property.Schema.HasExecutableDefault && property.IsReference && property.IsCollection
        && property.HasUniqueMembers && property.Reciprocal is null;

    public static IReadOnlyList<T> Capture<T>(IEnumerable<T> source) where T : class
    {
        ArgumentNullException.ThrowIfNull(source);
        var seen = new HashSet<T>(ReferenceEqualityComparer.Instance);
        var captured = new List<T>();
        foreach (var member in source)
        {
            ArgumentNullException.ThrowIfNull(member);
            if (!seen.Add(member)) throw new ArgumentException("Duplicate model reference in the declared default.", nameof(source));
            captured.Add(member);
        }
        return captured.AsReadOnly();
    }

    public static void RegisterMutable(object owner, Func<object> createShell) =>
        Owners.GetValue(owner, static _ => new()).CreateShell = createShell;

    public static void Store<T>(object owner, string property, IReadOnlyList<T> captured) where T : class =>
        Owners.GetValue(owner, static _ => new()).Fields.Add(property, captured);

    internal static bool TryGet(object owner, string property, out IReadOnlyList<object?>? values)
    {
        values = null;
        return Owners.TryGetValue(owner, out var state) && state.Fields.TryGetValue(property, out values);
    }

    internal static object CreateCopyShell(object owner, SnapshotType type) =>
        Owners.TryGetValue(owner, out var state) && state.CreateShell is { } create ? create() : type.Create!();

    internal static IEnumerable<IReadOnlyList<object?>> CapturedMembers(object owner) =>
        Owners.TryGetValue(owner, out var state) ? state.Fields.Values : [];

    internal static void Copy(object source, object copy, Func<object?, object?>? remap)
    {
        if (!Owners.TryGetValue(source, out var state)) return;
        var target = Owners.GetValue(copy, static _ => new());
        foreach (var (name, captured) in state.Fields)
        {
            target.Fields.Add(name, remap is null ? captured : Array.AsReadOnly(captured.Select(remap).ToArray()));
        }
    }

    internal static void RestoreMembers(object collection, IReadOnlyList<object?> desired)
    {
        if (collection is IJournalSequence sequence)
        {
            sequence.Replace(desired);
            return;
        }
        var set = (IJournalMembership)collection;
        var wanted = new HashSet<object>(desired.Cast<object>(), ReferenceEqualityComparer.Instance);
        set.ApplyMembership(((IEnumerable)collection).Cast<object>().Where(member => !wanted.Contains(member)).ToArray(),
            wanted.Where(member => !set.ContainsMember(member)).ToArray());
    }

    internal interface ICopyMembership
    {
        void AppendCopyMembers(IReadOnlyList<object?> values);
    }

    internal static bool RequiresNativeCheckpointCopy(object root, SnapshotRegistry registry)
    {
        var pending = new Stack<object>();
        var seen = new HashSet<object>(ReferenceEqualityComparer.Instance);
        pending.Push(registry.ModelIdentity(root));
        while (pending.TryPop(out var current))
        {
            if (!seen.Add(current) || !registry.TryTypeForClass(current.GetType(), out var type)) continue;
            if (Owners.TryGetValue(current, out var state) && state.Fields.Count != 0) return true;
            foreach (var property in type.Properties)
            {
                if (!property.IsContainment && !property.IsReference) continue;
                var field = property.Get(current);
                if (property.IsCollection)
                {
                    foreach (var child in (IEnumerable)field!)
                    {
                        if (child is not null) pending.Push(child);
                    }
                }
                else if (field is not null) pending.Push(field);
            }
        }
        return false;
    }
}
