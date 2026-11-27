using System.Collections;

namespace Vmf.Runtime;

/// <summary>Applies filters as one positional edit on a writable VMF list.</summary>
public static class ModelListMembership
{
    /// <summary>Removes the slots accepted by the predicate and returns the number removed.</summary>
    /// <remarks>The predicate is evaluated before removal and must not edit the list.</remarks>
    public static int RemoveWhere<T>(this IModelList<T> list, Predicate<T> predicate)
    {
        ArgumentNullException.ThrowIfNull(list);
        ArgumentNullException.ThrowIfNull(predicate);
        if (list is not IModelListPredicateRemoval<T> sequence)
            throw new NotSupportedException("Predicate removal requires a writable VMF list.");
        return sequence.RemoveWhere(predicate);
    }

    internal static int[] SelectSlots<T>(List<T> values, Predicate<T> predicate)
    {
        List<int>? positions = null;
        int index = 0;
        foreach (T value in values)
        {
            if (predicate(value)) (positions ??= []).Add(index);
            index++;
        }
        return positions?.ToArray() ?? [];
    }

    /// <summary>Removes every slot whose stored value equals one of the candidates.</summary>
    public static bool RemoveAll<T>(this IModelList<T> list, IEnumerable<object?> candidates) =>
        Filter(list, candidates, retain: false);

    /// <summary>Retains every slot whose stored value equals one of the candidates.</summary>
    public static bool RetainAll<T>(this IModelList<T> list, IEnumerable<object?> candidates) =>
        Filter(list, candidates, retain: true);

    /// <summary>Removes matching slots using candidates from a sequence, including scalar value-type sequences.</summary>
    public static bool RemoveAll<T>(this IModelList<T> list, IEnumerable candidates) =>
        Filter(list, candidates, retain: false);

    /// <summary>Retains matching slots using candidates from a sequence, including scalar value-type sequences.</summary>
    public static bool RetainAll<T>(this IModelList<T> list, IEnumerable candidates) =>
        Filter(list, candidates, retain: true);

    private static bool Filter<T>(IModelList<T> list, IEnumerable candidates, bool retain)
    {
        ArgumentNullException.ThrowIfNull(list);
        ArgumentNullException.ThrowIfNull(candidates);
        if (list is not IOrdinaryModelListMembership<T> { SupportsMembershipFilter: true } sequence)
        {
            throw new NotSupportedException("Membership filtering requires an ordinary VMF model list.");
        }

        sequence.RequireWritable();

        // Compare from the stored side. A default identity model need not equal its read-only view.
        var values = candidates.Cast<object?>().ToArray();
        var positions = new List<int>();
        for (int index = 0; index < list.Count; index++)
        {
            var value = list[index];
            if (ContainsValue(values, value) != retain) positions.Add(index);
        }
        if (positions.Count == 0) return false;

        sequence.RemoveSlots(positions.ToArray());
        return true;
    }

    private static bool ContainsValue(object?[] candidates, object? value)
    {
        foreach (var candidate in candidates)
        {
            if (ReferenceEquals(value, candidate) || ScalarEquality<object?>.Instance.Equals(value, candidate)) return true;
        }
        return false;
    }
}

/// <summary>Provides positional replay access to ordinary lists on a generated writable model.</summary>
[System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
public interface IModelListReplayAccess
{
    bool VmfApplyNullListEdit(string modelName, ModelNullListEdit edit);
}

/// <summary>A null-only edit dispatched to a generated writable list.</summary>
[System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
public sealed class ModelNullListEdit
{
    private readonly Action<IModelListNullReplay> apply;

    internal ModelNullListEdit(Action<IModelListNullReplay> apply) => this.apply = apply;

    public bool ApplyTo(object? list)
    {
        if (list is not IModelListNullReplay { SupportsPositionalReplay: true } receiver) return false;
        apply(receiver);
        return true;
    }
}

internal interface IModelListNullReplay
{
    bool SupportsPositionalReplay { get; }
    void RemoveListSlots(int[] positions);
    void ApplyNullRange(int index, int removalCount, int additionCount);
}

internal interface IOrdinaryModelListMembership<T> : IModelListNullReplay
{
    bool SupportsMembershipFilter { get; }
    void RequireWritable();
    void RemoveSlots(int[] positions);
    void ApplyRange(int index, int removalCount, T[] added);

    bool IModelListNullReplay.SupportsPositionalReplay => SupportsMembershipFilter;
    void IModelListNullReplay.RemoveListSlots(int[] positions) => RemoveSlots(positions);
    void IModelListNullReplay.ApplyNullRange(int index, int removalCount, int additionCount)
    {
        if (additionCount != 0 && default(T) is not null)
        {
            throw new ArgumentException("The target list does not accept null elements.");
        }
        ApplyRange(index, removalCount, new T[additionCount]);
    }
}

internal interface IModelListPredicateRemoval<T>
{
    int RemoveWhere(Predicate<T> predicate);
}

internal static class ModelListSlots
{
    internal static void Edit<T>(List<T> items, int[] positions, T[] added)
    {
        if (added.Length != 0)
        {
            for (int i = 0; i < positions.Length; i++) items.Insert(positions[i], added[i]);
        }
        else
        {
            for (int i = positions.Length - 1; i >= 0; i--) items.RemoveAt(positions[i]);
        }
    }

    internal static void CheckBounds<T>(List<T> items, int[] positions, bool adding)
    {
        int upper = adding ? checked(items.Count + positions.Length) : items.Count;
        ArgumentOutOfRangeException.ThrowIfGreaterThanOrEqual(positions[^1], upper, nameof(positions));
    }

    internal static void RestoreRuns<T>(int[] positions, T[] values, Action<int, T[]> insert)
    {
        int start = 0;
        while (start < positions.Length)
        {
            int end = start + 1;
            while (end < positions.Length && positions[end] == positions[end - 1] + 1) end++;
            insert(positions[start], values[start..end]);
            start = end;
        }
    }

    internal static bool Match<T>(List<T> items, int[] positions, T[] expected, int expectedCount,
        IEqualityComparer<T>? comparer = null)
    {
        if (expected.Length == 0) return items.Count == expectedCount;
        if (positions[^1] >= items.Count) return false;
        for (int i = 0; i < positions.Length; i++)
        {
            var actual = items[positions[i]];
            bool equal = comparer is null ? ReferenceEquals(actual, expected[i]) : comparer.Equals(actual, expected[i]);
            if (!equal) return false;
        }
        return true;
    }

    internal static IEnumerable<ModelCollectionEntry> Entries<T>(int[] positions, T[] values) =>
        values.Select((value, offset) => new ModelCollectionEntry(value, Index: positions[offset]));
}
