namespace Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;

public partial class MutableCaption
{
    public static int BindingCount { get; set; }

    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        BindingCount++;
        return new CaptionBinding();
    }

    private sealed class CaptionBinding : DefaultBehavior
    {
        private int calls;

        public string? ToString(MutableCaption caller) => $"{caller.Label}:{++calls}";

        public int Calls(MutableCaption caller) => calls;
    }
}

public sealed partial class NullCaption
{
    public static int BindingCount { get; set; }

    private static partial Behavior CreateDefaultBehavior()
    {
        BindingCount++;
        return new NullBinding();
    }

    private sealed class NullBinding : Behavior
    {
        public string? ToString(NullCaption caller) => null;
    }
}
