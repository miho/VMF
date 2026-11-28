using System.ComponentModel;

namespace Vmf.Runtime;

/// <summary>
/// Names the containment link of a generated mutable model object. The runtime keeps what it knows about the
/// object there (who observes it, what it contains) instead of looking that up by the object's identity.
/// </summary>
/// <remarks>
/// The runtime reads the property whenever it needs that state. An implementation returns the one link of the
/// object, the same on every call and of no other object, and has it before the object creates its containment
/// lists and slots. Generated classes create it with a field initializer. A class that does not implement this
/// interface needs no such care: its state is kept in a table.
/// </remarks>
[EditorBrowsable(EditorBrowsableState.Never)]
public interface IModelLinkAccess
{
    ContainmentLink VmfContainmentLink { get; }
}

/// <summary>Provides graph bookkeeping for generated model interfaces.</summary>
[EditorBrowsable(EditorBrowsableState.Never)]
public interface IModelGraphAccess : IModelValidationView, IModelLinkAccess
{
    ModelChanges VmfChanges { get; }
    bool ContentEquals(object? other);
    int ContentHashCode();
    void ValidateInto(List<ValidationIssue> issues, string path, string modelPath, Stack<Action> pending);
}

/// <summary>A read-only model view that exposes its native validation result.</summary>
public interface IModelValidationView
{
    IReadOnlyList<ValidationIssue> Validate();
}

/// <summary>A live, editable containment list shared by all mutable views of an owning model.</summary>
public interface IContainmentCollection<TChild> : IReadOnlyList<TChild> where TChild : class?
{
    bool Contains(TChild? item);
    bool Contains(object? item) => item is TChild model ? Contains(model) : item is null && Contains(default(TChild));
    TChild? FindByKey<TKey>(TKey lookupKey);
    bool Add(TChild item);
    void Insert(int index, TChild item);
    bool AddRange(IEnumerable<TChild> children);
    bool InsertRange(int index, IEnumerable<TChild> children);
    bool Remove(TChild? item);
    bool Remove(object? item) => item is TChild model ? Remove(model) : item is null && Remove(default(TChild));
    void RemoveAt(int index);
    void Clear();
    void Move(int oldIndex, int newIndex);
}
