namespace Vmf.Runtime;

/// <summary>A diagnostic on the supplied model object or one of its logical properties.</summary>
public sealed record ValidationDiagnostic
{
    internal ValidationDiagnostic(ValidationSeverity severity, string code, string path, string message,
        object model, ModelProperty? property, MultiplicityViolation? multiplicityViolation = null)
    {
        Severity = severity;
        Code = code;
        Path = path;
        Message = message;
        Object = model;
        Property = property;
        MultiplicityViolation = multiplicityViolation;
    }

    public ValidationSeverity Severity { get; }
    public string Code { get; }

    /// <summary>A JSON Pointer in logical model property names. The root has an empty path.</summary>
    public string Path { get; }

    public string Message { get; }
    public object Object { get; }
    public ModelProperty? Property { get; }
    public MultiplicityViolation? MultiplicityViolation { get; }
}

/// <summary>The actual number of values for a property at the time of validation.</summary>
public sealed record MultiplicityViolation(object Object, ModelProperty Property, int ActualCount);

/// <summary>An immutable list of diagnostics in traversal and rule order.</summary>
public sealed class ValidationReport
{
    internal ValidationReport(List<ValidationDiagnostic> issues)
    {
        Issues = Array.AsReadOnly(issues.ToArray());
        MultiplicityViolations = Array.AsReadOnly(issues.Select(issue => issue.MultiplicityViolation)
            .OfType<MultiplicityViolation>().ToArray());
        IsValid = issues.All(issue => issue.Severity != ValidationSeverity.Error);
    }

    /// <summary>Warnings and information do not make a model invalid.</summary>
    public bool IsValid { get; }

    public IReadOnlyList<ValidationDiagnostic> Issues { get; }
    public IReadOnlyList<MultiplicityViolation> MultiplicityViolations { get; }
}

/// <summary>Reports why a configured validator rejected a fully formed model.</summary>
public sealed class ModelValidationException : InvalidOperationException
{
    internal ModelValidationException(ValidationReport report)
        : base($"VMF model validation failed ({report.Issues.Count} findings).")
    {
        Report = report;
    }

    public ValidationReport Report { get; }
}
