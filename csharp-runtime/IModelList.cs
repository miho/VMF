namespace Vmf.Runtime;

/// <summary>A live model sequence as exposed by a parent whose concrete owner supplies its relationship semantics.</summary>
public interface IModelList<T> : IList<T>, IReadOnlyList<T>
{
    new int Count { get; }
    new T this[int index] { get; set; }
    new bool Add(T value);
    bool AddRange(IEnumerable<T> values);
    bool InsertRange(int index, IEnumerable<T> values);
    void Move(int oldIndex, int newIndex);
}
