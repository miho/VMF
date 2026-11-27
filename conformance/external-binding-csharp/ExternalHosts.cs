namespace Eu.Mihosoft.Vmf.Conformance.Externalbinding.Host;

public sealed record LexicalUri
{
    public LexicalUri(string text)
    {
        _ = new Uri(text, UriKind.RelativeOrAbsolute);
        Text = text;
    }

    public string Text { get; }
}

public readonly record struct Distance(long Millimetres, string Note, bool Estimated);

public enum DispatchMode
{
    READY = 7,
    UNDER_REVIEW = 19,
    ARCHIVED = 31,
}

public enum ThreadPhase
{
    NEW,
    RUNNABLE,
    BLOCKED,
    WAITING,
    TIMED_WAITING,
    TERMINATED,
}
