namespace Vmf.Runtime;

public enum ValidationSeverity
{
    Info,
    Warning,
    Error,
}

/// <summary>A failed model constraint, identified by its code and model path.</summary>
public sealed record ValidationIssue(string Code, string Path, string Message)
{
    public ValidationIssue(string code, string path, string message, string modelCode, string modelPath)
        : this(code, path, message)
    {
        ModelCode = modelCode;
        ModelPath = modelPath;
    }

    public string ModelCode { get; init; } = Code;

    public string ModelPath { get; init; } = Path;

    public ValidationSeverity Severity { get; init; } = ValidationSeverity.Error;
}
