namespace Vmf.Runtime;

public sealed partial class ModelReflectionField
{
    /// <summary>Evaluates a native binding for a declared default expression.</summary>
    public Func<object?>? DeclaredDefaultFactory { get; init; }
}
