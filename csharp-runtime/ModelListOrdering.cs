namespace Vmf.Runtime;

/// <summary>Orders a writable VMF list through its native collection.</summary>
public static class ModelListOrdering
{
    /// <summary>Stably sorts the list using its native change semantics.</summary>
    public static void Sort<T>(this IModelList<T> list, Comparison<T> comparison)
    {
        ArgumentNullException.ThrowIfNull(list);
        ArgumentNullException.ThrowIfNull(comparison);
        if (list is not IModelListOrdering<T> ordered)
            throw new NotSupportedException("Sorting requires a writable VMF list.");
        ordered.Sort(comparison);
    }
}

internal interface IModelListOrdering<T>
{
    void Sort(Comparison<T> comparison);
}
