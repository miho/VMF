using System.ComponentModel;

namespace Vmf.Runtime;

/// <summary>Provides graph bookkeeping for generated model interfaces.</summary>
[EditorBrowsable(EditorBrowsableState.Never)]
public interface IModelGraphAccess : IModelValidationView
{
    ModelChanges VmfChanges { get; }
    bool ContentEquals(object? other);
    int ContentHashCode();
    ContainmentLink VmfContainmentLink { get; }
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
