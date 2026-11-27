namespace Vmf.Runtime;

/// <summary>Builds a model. A generated parent builder can defer this call until it builds the parent.</summary>
public interface IModelBuilder<out T>
{
    T Build();
}
