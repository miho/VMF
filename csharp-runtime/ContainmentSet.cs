using System.Collections;

namespace Vmf.Runtime;

/// <summary>A live, editable unordered containment set shared by mutable views of an owning model.</summary>
public interface IContainmentSet<TChild> : IReadOnlyReferenceSet<TChild> where TChild : class
{
    bool Contains(TChild? item);
    bool Add(TChild item);
    bool AddRange(IEnumerable<TChild> items);
    bool Remove(TChild? item);
    bool Remove(object? item) => item is TChild model && Remove(model);
    void Clear();
}

/// <summary>An unordered owning collection of child objects, compared by identity.</summary>
public sealed class ContainmentSet<TParent, TChild> : IContainmentSet<TChild>, IJournalMembership, IModelCursorListProvider,
    IModelReferenceSetProjectionSource, IKeyedContainment
    where TParent : class where TChild : class
{
    private readonly ContainmentList<TParent, TChild> children;

    public ContainmentSet(TParent owner, ContainmentLink ownerLink, Func<TChild, ContainmentLink> getLink,
        ModelChangeSource changeSource, string? oppositeName = null)
        : this(owner, ownerLink, getLink, changeSource, oppositeName, null, null)
    {
    }

    public ContainmentSet(TParent owner, ContainmentLink ownerLink, Func<TChild, ContainmentLink> getLink,
        ModelChangeSource changeSource, string? oppositeName, string? keyPropertyName,
        Func<TChild, object?>? keySelector, bool keyUsesIdentity = false)
    {
        children = new(owner, ownerLink, getLink, changeSource, oppositeName, unordered: true,
            keyPropertyName, keySelector, keyUsesIdentity);
    }

    IModelCursorList IModelCursorListProvider.CursorList => children;

    IDisposable? IModelReferenceSetProjectionSource.AddReferenceSetModelRelay(ModelChanges.ObserverGroup group) =>
        children.AddReferenceSetModelRelay(group);

    public int Count => children.Count;
    public bool Add(TChild item) => children.Add(item);
    public bool AddRange(IEnumerable<TChild> items) => children.AddRange(items);
    public bool Contains(TChild? item) => item is not null && children.Contains(item);
    public bool Contains(object? item) => item is not null && children.Contains(item);
    public bool Remove(TChild? item) => item is not null && children.Remove(item);
    public bool Remove(object? item) => item is not null && children.Remove(item);
    public void Clear() => children.Clear();

    /// <summary>Finds the entry with the supplied scalar key or model identity.</summary>
    public TChild? FindByKey<TKey>(TKey key) => children.FindByKey(key);

    bool IKeyedContainment.HasKey => ((IKeyedContainment)children).HasKey;
    void IKeyedContainment.HydrateMembers(IReadOnlyList<object?> values) =>
        ((IKeyedContainment)children).HydrateMembers(values);
    void IKeyedContainment.RequireKeyChange<TValue>(object child, string propertyName, TValue value) =>
        ((IKeyedContainment)children).RequireKeyChange(child, propertyName, value);

    [System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
    public void ValidateBuilderContents(IEnumerable<TChild> values, bool append) =>
        children.ValidateSetBuilderContents(values, append);

    [System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
    public void ApplyBuilderContents(IEnumerable<TChild> values, bool append) =>
        children.ApplyUnorderedBuilderContents(values, append);

    bool IJournalMembership.ContainsMember(object value) => value is TChild child && Contains(child);
    void IJournalMembership.ApplyMembership(object[] removed, object[] added) =>
        children.ApplySetMembership(Array.ConvertAll(removed, value => (TChild)value), Array.ConvertAll(added, value => (TChild)value));

    public IEnumerator<TChild> GetEnumerator() => children.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
}
