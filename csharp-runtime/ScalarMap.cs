using System.Collections;
using System.Diagnostics.CodeAnalysis;

namespace Vmf.Runtime;

/// <summary>An insertion-ordered scalar map with non-null keys and nullable values.</summary>
public sealed class ScalarMap<TKey, TValue> : IReadOnlyDictionary<TKey, TValue>, IJournalMap, IModelMapObservation<TKey, TValue>
{
    private readonly Dictionary<Key, LinkedListNode<KeyValuePair<TKey, TValue>>> entries = new();
    private readonly LinkedList<KeyValuePair<TKey, TValue>> ordered = new();
    private readonly ReadOnlyView readOnly;
    private readonly int upperBound;
    private readonly ModelChangeSource changeSource;
    private ModelListeners<MapEdit>? listeners;
    private ModelListeners<MapEdit>? projectedListeners;
    private bool projectionAttached;

    public ScalarMap(int upperBound) : this(upperBound, default)
    {
    }

    public ScalarMap(int upperBound = -1, ModelChangeSource changeSource = default)
    {
        ScalarCollectionBounds.CheckUpperBound(upperBound);
        this.upperBound = upperBound;
        this.changeSource = changeSource;
        readOnly = new ReadOnlyView(this);
    }

    public int Count => entries.Count;
    internal int UpperBound => upperBound;
    public IEnumerable<TKey> Keys => ordered.Select(entry => entry.Key);
    public IEnumerable<TValue> Values => ordered.Select(entry => entry.Value);
    public TValue this[TKey key]
    {
        get => entries[CheckedKey(key)].Value.Value;
        set => Put(key, value);
    }

    public IReadOnlyDictionary<TKey, TValue> AsReadOnly()
    {
        if (!projectionAttached)
        {
            (listeners ??= new()).Add(NotifyProjection, order: ModelChanges.NextObservationOrder(),
                relayActive: () => projectedListeners?.Snapshot.Length > 0);
            projectionAttached = true;
        }
        return readOnly;
    }

    IDisposable IModelMapObservation<TKey, TValue>.AddListener(Action<ModelMapChange<TKey, TValue>> listener) =>
        (listeners ??= new()).Add(edit => listener(new(this, edit.Removed, edit.Added)), order: ModelChanges.NextObservationOrder());

    private void NotifyProjection(MapEdit edit)
    {
        var snapshot = new CollectionCallbackSnapshot<MapEdit>(projectedListeners?.Snapshot);
        ModelObserverFailures.Rethrow(snapshot.Notify(edit, null));
    }

    private MapDelivery CaptureDelivery()
    {
        var callbacks = new CollectionCallbackSnapshot<MapEdit>(listeners?.Snapshot);
        var model = changeSource.IsTracked
            ? ModelChanges.CollectionRelayDelivery.Capture(changeSource.Owner, projectedRelayMayActivate: callbacks.HasProjections)
            : null;
        return new(model, callbacks);
    }

    private readonly record struct MapEdit(IReadOnlyDictionary<TKey, TValue> Removed, IReadOnlyDictionary<TKey, TValue> Added,
        ModelChanges.CollectionRelayContext? Context = null);

    private readonly record struct MapDelivery(ModelChanges.CollectionRelayDelivery? Model,
        CollectionCallbackSnapshot<MapEdit> Callbacks)
    {
        internal bool HasObservers => Model?.HasModelGroups == true || Callbacks.HasObservers;
    }

    public bool ContainsKey(TKey key) => key is not null && entries.ContainsKey(new Key(key));

    public bool TryGetValue(TKey key, [MaybeNullWhen(false)] out TValue value)
    {
        if (key is not null && entries.TryGetValue(new Key(key), out var node))
        {
            value = node.Value.Value;
            return true;
        }
        value = default;
        return false;
    }

    public void Add(TKey key, TValue value)
    {
        if (entries.ContainsKey(CheckedKey(key))) throw new ArgumentException("The map already contains that key.", nameof(key));
        ScalarCollectionBounds.CheckCapacity(upperBound, (long)Count + 1);
        var delivery = CaptureDelivery();
        AddNew(key, value);
        if (delivery.HasObservers) Publish(delivery, [], [new(key, value)]);
    }

    /// <summary>Inserts the key or replaces its value in place. Returns the previous value if present.</summary>
    [return: MaybeNull]
    public TValue Put(TKey key, TValue value)
    {
        if (key is not null && entries.TryGetValue(new Key(key), out var node))
        {
            var previous = node.Value;
            var delivery = CaptureDelivery();
            var replacement = new KeyValuePair<TKey, TValue>(previous.Key, value);
            node.Value = replacement;
            if (delivery.HasObservers) Publish(delivery, [previous], [replacement]);
            return previous.Value;
        }
        Add(key, value);
        return default;
    }

    /// <summary>Checks all keys and final capacity before applying a batch in enumeration order.</summary>
    public void PutAll(IEnumerable<KeyValuePair<TKey, TValue>> values)
    {
        ArgumentNullException.ThrowIfNull(values);
        var changes = new Dictionary<Key, TValue>();
        var changeOrder = new List<Key>();
        foreach (var entry in values)
        {
            var key = CheckedKey(entry.Key);
            if (changes.TryAdd(key, entry.Value)) changeOrder.Add(key);
            else changes[key] = entry.Value;
        }
        if (changes.Count == 0) return;
        int newKeys = changeOrder.Count(key => !entries.ContainsKey(key));
        ScalarCollectionBounds.CheckCapacity(upperBound, (long)Count + newKeys);
        var delivery = CaptureDelivery();
        var removed = delivery.HasObservers ? TouchedEntries(changes) : null;
        foreach (var key in changeOrder) Write(new(key.Value, changes[key]));
        if (delivery.HasObservers) Publish(delivery, removed!, TouchedEntries(changes));
    }

    public bool Remove(TKey key)
    {
        if (key is null || !entries.TryGetValue(new Key(key), out var node)) return false;
        var delivery = CaptureDelivery();
        var removed = node.Value;
        RemoveEntry(key);
        if (delivery.HasObservers) Publish(delivery, [removed], []);
        return true;
    }

    public void Clear()
    {
        if (Count == 0) return;
        var delivery = CaptureDelivery();
        var removed = delivery.HasObservers ? ordered.ToArray() : null;
        entries.Clear();
        ordered.Clear();
        if (delivery.HasObservers) Publish(delivery, removed!, []);
    }

    private void AddNew(TKey key, TValue value)
    {
        var node = new LinkedListNode<KeyValuePair<TKey, TValue>>(new(key, value));
        entries.Add(CheckedKey(key), node);
        ordered.AddLast(node);
    }

    private void Write(KeyValuePair<TKey, TValue> entry)
    {
        if (entries.TryGetValue(CheckedKey(entry.Key), out var node)) node.Value = new(node.Value.Key, entry.Value);
        else AddNew(entry.Key, entry.Value);
    }

    private void RemoveEntry(TKey key)
    {
        if (entries.Remove(CheckedKey(key), out var node)) ordered.Remove(node);
    }

    private KeyValuePair<TKey, TValue>[] TouchedEntries(Dictionary<Key, TValue> changes) =>
        ordered.Where(entry => changes.ContainsKey(new Key(entry.Key))).ToArray();

    private void Publish(MapDelivery delivery, KeyValuePair<TKey, TValue>[] removed,
        KeyValuePair<TKey, TValue>[] added)
    {
        var edit = delivery.Callbacks.Count == 0 ? default : new MapEdit(Snapshot(removed), Snapshot(added));
        var failure = delivery.Model is { } model
            ? model.Dispatch(CreateChange(), delivery.Callbacks, edit, static (value, context) => value with { Context = context })
            : delivery.Callbacks.Notify(edit, null);
        ModelObserverFailures.Rethrow(failure);

        ModelCollectionChange CreateChange() => changeSource.Create(ModelChangeKind.Map, isModelReference: false,
            removed.Select(entry => new ModelCollectionEntry(entry.Value, Key: entry.Key)),
            added.Select(entry => new ModelCollectionEntry(entry.Value, Key: entry.Key)),
            matches: undo => undo ? Matches(added, removed) : Matches(removed, added),
            replay: undo =>
            {
                if (undo) ApplyEntries(added, removed);
                else ApplyEntries(removed, added);
            },
            apply: target =>
            {
                var map = changeSource.IsOwner(target) ? this : changeSource.ResolveTarget<ScalarMap<TKey, TValue>>(target);
                map.ApplyEntries(removed, added);
            });
    }

    private static IReadOnlyDictionary<TKey, TValue> Snapshot(KeyValuePair<TKey, TValue>[] entries)
    {
        var snapshot = new ScalarMap<TKey, TValue>();
        foreach (var entry in entries) snapshot.AddNew(entry.Key, entry.Value);
        return snapshot.readOnly;
    }

    private bool Matches(KeyValuePair<TKey, TValue>[] expected, KeyValuePair<TKey, TValue>[] replacement)
    {
        long size = (long)Count - expected.Length + replacement.Length;
        if (upperBound >= 0 && size > upperBound) return false;

        var keys = new HashSet<TKey>(ScalarEquality<TKey>.Instance);
        foreach (var entry in expected)
        {
            if (!TryGetValue(entry.Key, out var value) || !ScalarEquality<TValue>.Instance.Equals(value, entry.Value))
            {
                return false;
            }
            keys.Add(entry.Key);
        }
        return replacement.All(entry => keys.Contains(entry.Key) || !ContainsKey(entry.Key));
    }

    private void ApplyEntries(KeyValuePair<TKey, TValue>[] removed, KeyValuePair<TKey, TValue>[] added)
    {
        var retained = new HashSet<TKey>(added.Select(entry => entry.Key), ScalarEquality<TKey>.Instance);
        long size = Count;
        size -= removed.Count(entry => !retained.Contains(entry.Key) && ContainsKey(entry.Key));
        size += added.Count(entry => !ContainsKey(entry.Key));
        ScalarCollectionBounds.CheckCapacity(upperBound, size);

        var delivery = CaptureDelivery();
        KeyValuePair<TKey, TValue>[]? before = null;
        if (delivery.HasObservers)
        {
            var affected = new HashSet<TKey>(retained, ScalarEquality<TKey>.Instance);
            affected.UnionWith(removed.Select(entry => entry.Key));
            before = ordered.Where(entry => affected.Contains(entry.Key)).ToArray();
        }
        foreach (var entry in removed)
        {
            if (!retained.Contains(entry.Key)) RemoveEntry(entry.Key);
        }
        foreach (var entry in added) Write(entry);
        if (delivery.HasObservers && (before!.Length != 0 || added.Length != 0))
        {
            Publish(delivery, before!, ordered.Where(entry => retained.Contains(entry.Key)).ToArray());
        }
    }

    public IEnumerator<KeyValuePair<TKey, TValue>> GetEnumerator() => ordered.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    IEnumerable<(object Key, object? Value)> ISnapshotScalarMap.Entries =>
        ordered.Select(entry => ((object)entry.Key!, (object?)entry.Value));

    bool IJournalMap.TryGetEntry(object key, out object? value)
    {
        bool found = TryGetValue((TKey)key, out var typed);
        value = typed;
        return found;
    }

    void IJournalMap.ApplyBuilderReplacement(IEnumerable<(object Key, object? Value)> values) =>
        ManagedCollectionDefaults.ApplyBuilderMap(this, values.Select(ConvertEntry), append: false);

    void IJournalMap.ApplyEntries((object Key, object? Value)[] removed, (object Key, object? Value)[] added)
    {
        ApplyEntries(Array.ConvertAll(removed, ConvertEntry), Array.ConvertAll(added, ConvertEntry));
    }

    private static KeyValuePair<TKey, TValue> ConvertEntry((object Key, object? Value) entry) =>
        new((TKey)entry.Key, (TValue)entry.Value!);

    private static Key CheckedKey(TKey key)
    {
        ArgumentNullException.ThrowIfNull(key);
        return new Key(key);
    }

    private readonly struct Key(TKey value) : IEquatable<Key>
    {
        public TKey Value { get; } = value;
        public bool Equals(Key other) => ScalarEquality<TKey>.Instance.Equals(Value, other.Value);
        public override bool Equals(object? other) => other is Key key && Equals(key);
        public override int GetHashCode() => ScalarEquality<TKey>.Instance.GetHashCode(Value!);
    }

    private sealed class ReadOnlyView(ScalarMap<TKey, TValue> owner)
        : IReadOnlyDictionary<TKey, TValue>, ISnapshotScalarMap, IModelMapObservation<TKey, TValue>, IModelMembershipProjection
    {
        public int Count => owner.Count;
        public IEnumerable<TKey> Keys => owner.Keys;
        public IEnumerable<TValue> Values => owner.Values;
        public TValue this[TKey key] => owner[key];
        public bool ContainsKey(TKey key) => owner.ContainsKey(key);
        public bool TryGetValue(TKey key, [MaybeNullWhen(false)] out TValue value) => owner.TryGetValue(key, out value);
        public IEnumerator<KeyValuePair<TKey, TValue>> GetEnumerator() => owner.GetEnumerator();
        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
        IDisposable IModelMapObservation<TKey, TValue>.AddListener(Action<ModelMapChange<TKey, TValue>> listener) =>
            (owner.projectedListeners ??= new()).Add(edit => listener(new(this, edit.Removed, edit.Added)));
        IDisposable IModelMembershipProjection.AddModelRelay(ModelChanges.ObserverGroup group) =>
            (owner.projectedListeners ??= new()).Add(edit => edit.Context?.Notify(group));
        IEnumerable<(object Key, object? Value)> ISnapshotScalarMap.Entries => ((ISnapshotScalarMap)owner).Entries;
    }
}

internal interface ISnapshotScalarMap
{
    IEnumerable<(object Key, object? Value)> Entries { get; }
}
