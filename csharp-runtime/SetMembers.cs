using System.Collections;
using System.Diagnostics.CodeAnalysis;

namespace Vmf.Runtime;

internal interface ISetMembers<T> : IReadOnlyCollection<T>
{
    bool Contains(T value);
    bool TryGetValue(T value, [MaybeNullWhen(false)] out T stored);
    bool Add(T value);
    void AddRange(IReadOnlyCollection<T> values);
    bool Remove(T value);
    void Clear();
}

internal sealed class HashedSetMembers<T>(IEqualityComparer<T> comparer) : HashSet<T>(comparer), ISetMembers<T>
{
    public void AddRange(IReadOnlyCollection<T> values) => UnionWith(values);
}

// Unordered relationships publish membership, but a live cursor still selects their backing sequence.
internal sealed class ReferenceSetMembers<T> : ISetMembers<T> where T : class
{
    private LinkedList<T>? sequence;
    private Dictionary<T, LinkedListNode<T>>? members;
    private LinkedList<T> Sequence => sequence ??= new();
    private Dictionary<T, LinkedListNode<T>> Index => members ??= new(ReferenceEqualityComparer.Instance);

    public int Count => members?.Count ?? 0;
    internal long Version { get; private set; }
    internal long StructureVersion { get; private set; }
    internal LinkedListNode<T>? First => sequence?.First;

    public bool Contains(T value) => value is not null && members?.ContainsKey(value) == true;

    public bool TryGetValue(T value, [MaybeNullWhen(false)] out T stored)
    {
        if (Contains(value))
        {
            stored = value;
            return true;
        }
        stored = null;
        return false;
    }

    public bool Add(T value)
    {
        var node = new LinkedListNode<T>(value);
        if (!Index.TryAdd(value, node)) return false;
        Sequence.AddLast(node);
        MarkStructuralEdit();
        return true;
    }

    public void AddRange(IReadOnlyCollection<T> values)
    {
        if (values.Count == 0) return;
        Index.EnsureCapacity(checked(Count + values.Count));
        foreach (var value in values) Add(value);
    }

    public bool Remove(T value)
    {
        if (value is null || members is null || !members.Remove(value, out var node)) return false;
        sequence!.Remove(node);
        MarkStructuralEdit();
        return true;
    }

    public void Clear()
    {
        if (Count == 0) return;
        members!.Clear();
        sequence!.Clear();
        MarkStructuralEdit();
    }

    internal LinkedListNode<T> InsertAfter(LinkedListNode<T>? previous, T value)
    {
        var node = new LinkedListNode<T>(value);
        Index.Add(value, node);
        if (previous is null) Sequence.AddFirst(node);
        else Sequence.AddAfter(previous, node);
        MarkStructuralEdit();
        return node;
    }

    internal void Replace(LinkedListNode<T> node, T value)
    {
        Index.Add(value, node);
        Index.Remove(node.Value);
        node.Value = value;
        Version++;
    }

    public IEnumerator<T> GetEnumerator() => sequence is null ? Enumerable.Empty<T>().GetEnumerator() : sequence.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    private void MarkStructuralEdit()
    {
        Version++;
        StructureVersion++;
    }
}
