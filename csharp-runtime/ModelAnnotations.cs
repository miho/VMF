namespace Vmf.Runtime;

/// <summary>An annotation exposed by the generated model reflection API.</summary>
public sealed record ModelAnnotation(string Key, string Value)
{
    internal static IReadOnlyList<ModelAnnotation> Freeze(IReadOnlyList<ModelAnnotation> annotations)
    {
        ArgumentNullException.ThrowIfNull(annotations);
        return Array.AsReadOnly(annotations.ToArray());
    }

    internal static ModelAnnotation? Find(IReadOnlyList<ModelAnnotation> annotations, string key)
    {
        ArgumentNullException.ThrowIfNull(key);
        return annotations.FirstOrDefault(item => StringComparer.Ordinal.Equals(item.Key, key));
    }

    internal static IReadOnlyList<ModelAnnotation> FindAll(IReadOnlyList<ModelAnnotation> annotations, string key)
    {
        ArgumentNullException.ThrowIfNull(key);
        return Array.AsReadOnly(annotations.Where(item => StringComparer.Ordinal.Equals(item.Key, key)).ToArray());
    }
}

public sealed partial class ModelReflectionType
{
    private readonly IReadOnlyList<ModelAnnotation> annotations = Array.Empty<ModelAnnotation>();

    public IReadOnlyList<ModelAnnotation> Annotations
    {
        get => annotations;
        init => annotations = ModelAnnotation.Freeze(value);
    }
}

public sealed partial class ModelReflectionField
{
    private readonly IReadOnlyList<ModelAnnotation> annotations = Array.Empty<ModelAnnotation>();

    public IReadOnlyList<ModelAnnotation> Annotations
    {
        get => annotations;
        init => annotations = ModelAnnotation.Freeze(value);
    }
}

public sealed partial class ModelReflection
{
    /// <summary>The concrete model type's annotations in reflection order.</summary>
    public IReadOnlyList<ModelAnnotation> Annotations => model.Schema.Annotations;

    public ModelAnnotation? AnnotationByKey(string key) => ModelAnnotation.Find(Annotations, key);

    public IReadOnlyList<ModelAnnotation> AnnotationsByKey(string key) => ModelAnnotation.FindAll(Annotations, key);
}

public sealed partial class ModelProperty
{
    /// <summary>Declared and generated relationship annotations in reflection order.</summary>
    public IReadOnlyList<ModelAnnotation> Annotations => property.Field.Annotations;

    public ModelAnnotation? AnnotationByKey(string key) => ModelAnnotation.Find(Annotations, key);

    public IReadOnlyList<ModelAnnotation> AnnotationsByKey(string key) => ModelAnnotation.FindAll(Annotations, key);
}
