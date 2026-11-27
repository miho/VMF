namespace Vmf.Runtime;

/// <summary>The current model location and diagnostic sink supplied to a validation callback.</summary>
public sealed class ValidationContext
{
    private readonly ModelReflectionRegistry.ModelBinding model;
    private readonly List<ValidationDiagnostic> issues;

    internal ValidationContext(object root, ModelReflectionRegistry.ModelBinding model,
        string path, List<ValidationDiagnostic> issues)
    {
        Root = root;
        this.model = model;
        Path = path;
        this.issues = issues;
    }

    public object Root { get; }
    public object Object => model.Input;
    public string Path { get; }

    /// <summary>Reports an issue at the current object.</summary>
    public void Report(ValidationSeverity severity, string code, string message) =>
        Add(severity, code, message, null);

    /// <summary>Reports an issue at a named logical property on the current object.</summary>
    public void Report(ValidationSeverity severity, string code, string propertyName, string message)
    {
        ArgumentNullException.ThrowIfNull(propertyName);
        var property = FindProperty(model, propertyName)
            ?? throw new ArgumentException($"Unknown model property '{propertyName}'.", nameof(propertyName));
        Add(severity, code, message, property);
    }

    private void Add(ValidationSeverity severity, string code, string message, ModelProperty? property)
    {
        if (!Enum.IsDefined(severity)) throw new ArgumentOutOfRangeException(nameof(severity));
        ArgumentException.ThrowIfNullOrWhiteSpace(code);
        ArgumentNullException.ThrowIfNull(message);
        issues.Add(new ValidationDiagnostic(severity, code, property is null ? Path : ChildPath(Path, property.Name),
            message, model.Input, property));
    }

    internal static ModelProperty? FindProperty(ModelReflectionRegistry.ModelBinding model, string name)
    {
        foreach (var field in model.Schema.Fields)
        {
            if (field.Field.Name == name) return new ModelProperty(model, field);
        }
        return null;
    }

    internal static string ChildPath(string path, string property) =>
        path + "/" + property.Replace("~", "~0", StringComparison.Ordinal).Replace("/", "~1", StringComparison.Ordinal);
}
