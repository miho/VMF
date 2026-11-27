namespace Vmf.Runtime;

/// <summary>Applies a full-range replacement to a writable VMF list.</summary>
public static class ModelListReplacement
{
    /// <summary>Evaluates the transform for every slot before replacing the full range as one edit.</summary>
    /// <remarks>The transform must not edit the list while preparing replacements.</remarks>
    public static void ReplaceAll<T>(this IModelList<T> list, Func<T, T> transform)
    {
        ArgumentNullException.ThrowIfNull(list);
        ArgumentNullException.ThrowIfNull(transform);
        if (list is not IModelListReplacement<T> sequence)
            throw new NotSupportedException("Bulk replacement requires a writable VMF list.");
        sequence.ReplaceAll(transform);
    }

    internal static T[] Stage<T>(List<T> source, Func<T, T> transform)
    {
        var replacement = new T[source.Count];
        int index = 0;
        foreach (T value in source) replacement[index++] = transform(value);
        return replacement;
    }
}

internal interface IModelListReplacement<T>
{
    void ReplaceAll(Func<T, T> transform);
}
