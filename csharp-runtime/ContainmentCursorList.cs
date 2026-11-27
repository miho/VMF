namespace Vmf.Runtime;

internal interface IModelCursorRevision
{
    long Version { get; }
}

internal interface IContainmentCursorSlot : IModelCursorRevision
{
}

internal interface IModelCursorList : IModelCursorRevision
{
    long StructureVersion => Version;
    int Count { get; }
    bool CanEditPosition { get; }
    object? Get(int index);
    bool ContainsIdentity(object child);
    void InsertAt(int index, object? child);
    void ReplaceAt(int index, object? child);
}

internal interface IModelCursorListProvider
{
    IModelCursorList CursorList { get; }
}

public sealed partial class ContainmentList<TParent, TChild> : IModelCursorList
    where TParent : class
    where TChild : class?
{
    private long cursorVersion;

    long IModelCursorRevision.Version => cursorVersion;
    bool IModelCursorList.CanEditPosition => !unordered;
    object? IModelCursorList.Get(int index) => this[index];
    bool IModelCursorList.ContainsIdentity(object child) => child is TChild value && IndexOfIdentity(value) >= 0;
    void IModelCursorList.InsertAt(int index, object? child) => Insert(index, (TChild)child!);
    void IModelCursorList.ReplaceAt(int index, object? child) => this[index] = (TChild)child!;
}
