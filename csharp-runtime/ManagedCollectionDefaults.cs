using System.Collections;
using System.ComponentModel;

namespace Vmf.Runtime;

/// <summary>Copies host-bound defaults into generated managed collection storage.</summary>
[EditorBrowsable(EditorBrowsableState.Never)]
public static class ManagedCollectionDefaults
{
    public static ScalarList<T> CopyInto<T>(ScalarList<T> target, IEnumerable<T> values)
    {
        target.ReplaceManagedContents(values);
        return target;
    }

    public static ScalarSet<T> CopyInto<T>(ScalarSet<T> target, IEnumerable<T> values)
    {
        ArgumentNullException.ThrowIfNull(values);
        var desired = new HashSet<T>(ScalarEquality<T>.Instance);
        foreach (T item in values)
        {
            ArgumentNullException.ThrowIfNull(item);
            desired.Add(item);
        }
        target.ApplyEdit(target.Where(value => !desired.Contains(value)).ToArray(),
            desired.Where(value => !target.Contains(value)).ToArray());
        return target;
    }

    public static ScalarMap<TKey, TValue> CopyInto<TKey, TValue>(ScalarMap<TKey, TValue> target,
        IEnumerable<KeyValuePair<TKey, TValue>> values) where TKey : notnull
    {
        ArgumentNullException.ThrowIfNull(values);
        var prepared = new ScalarMap<TKey, TValue>(target.UpperBound);
        foreach (var entry in values)
        {
            prepared.Add(entry.Key, entry.Value);
        }
        RestoreMap(target, ((ISnapshotScalarMap)prepared).Entries.ToArray());
        return target;
    }

    public static void ApplyBuilderMap<TKey, TValue>(ScalarMap<TKey, TValue> target,
        IEnumerable<KeyValuePair<TKey, TValue>> values, bool append)
    {
        var desired = new ScalarMap<TKey, TValue>(target.UpperBound);
        desired.PutAll(values);
        if (!append)
        {
            // Java VMF publishes each displaced key, then applies the changed entries as one batch.
            var keys = desired.Keys.ToArray();
            int next = 0;
            foreach (TKey key in target.Keys.ToArray())
            {
                if (next < keys.Length && ScalarEquality<TKey>.Instance.Equals(keys[next], key))
                {
                    next++;
                }
                else
                {
                    target.Remove(key);
                }
            }
        }
        target.PutAll(desired.Where(entry => !target.TryGetValue(entry.Key, out var value)
            || !ScalarEquality<TValue>.Instance.Equals(entry.Value, value)));
    }

    internal static void RestoreMembers(object target, IReadOnlyList<object?> values)
    {
        if (target is IManagedDefaultList list)
        {
            list.ReplaceManagedContents(values);
            return;
        }
        var set = (IJournalMembership)target;
        var desired = new HashSet<object>(values.Cast<object>(), ScalarEquality<object>.Instance);
        set.ApplyMembership(((IEnumerable)target).Cast<object>().Where(value => !desired.Contains(value)).ToArray(),
            desired.Where(value => !set.ContainsMember(value)).ToArray());
    }

    internal static void RestoreMap(IJournalMap target, IReadOnlyList<(object Key, object? Value)> entries)
    {
        // Keep the longest prefix of requested keys already in that order and append the remaining keys.
        int next = 0;
        var removed = new List<(object Key, object? Value)>();
        foreach (var current in target.Entries)
        {
            if (next < entries.Count && ScalarEquality<object>.Instance.Equals(entries[next].Key, current.Key))
            {
                next++;
            }
            else
            {
                removed.Add(current);
            }
        }
        Exception? failure = null;
        if (removed.Count != 0)
        {
            try
            {
                target.ApplyEntries(removed.ToArray(), []);
            }
            catch (Exception error)
            {
                failure = error;
            }
        }
        var changed = entries.Where(entry => !target.TryGetEntry(entry.Key, out var value)
            || !ScalarEquality<object?>.Instance.Equals(entry.Value, value)).ToArray();
        if (changed.Length != 0)
        {
            try
            {
                target.ApplyEntries([], changed);
            }
            catch (Exception error)
            {
                failure = ModelObserverFailures.Add(failure, error);
            }
        }
        ModelObserverFailures.Rethrow(failure);
    }
}

internal interface IManagedDefaultList
{
    void ReplaceManagedContents(IReadOnlyList<object?> values);
}
