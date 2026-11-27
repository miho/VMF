namespace Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;

public partial class NeutralRole
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new NeutralHost();

    private sealed class NeutralHost : DefaultBehavior
    {
        public int Baseline(NeutralRole caller) => 1;
    }
}

public partial class WideRole
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new WideHost();

    private sealed class WideHost : DefaultBehavior
    {
        public Result? Choose(IWideRole caller) => throw new InvalidOperationException("The later wide provider was selected.");
    }
}

public partial class NarrowRole
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new NarrowHost();

    private sealed class NarrowHost : DefaultBehavior
    {
        public RankedResult? Choose(INarrowRole caller) => caller.Stored;
    }
}

public static partial class ModelBehaviorBindings
{
    internal static int CovariantSharedCreated { get; set; }

    private static partial Shared CreateShared()
    {
        CovariantSharedCreated++;
        return new SharedHost();
    }

    private sealed class SharedHost : Shared
    {
        public RankedResult? Sample(object caller) => ((IResultData)caller).Stored;
    }
}
