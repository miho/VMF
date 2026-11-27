namespace Vmf.Runtime;

/// <summary>How a snapshot treats non-containment references to mutable model objects.</summary>
/// <remarks>References to immutable values are written by value in every mode.</remarks>
public enum ReferenceMode
{
    /// <summary>Written as the <c>@vmf-id</c> of their targets and restored by identity when reading.</summary>
    Ids,

    /// <summary>Not representable: writing a model that has such a reference fails. The default without IDs.</summary>
    Reject,

    /// <summary>Left out of the document, which then holds the containment tree only.</summary>
    Omit,
}
