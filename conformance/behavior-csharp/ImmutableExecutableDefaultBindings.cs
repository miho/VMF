namespace Eu.Mihosoft.Vmf.Conformance.Behavior;

internal static class ImmutableDefaultTrace
{
    internal static readonly List<string> Phases = [];
    internal static bool NullAlpha { get; set; }
}

public sealed partial class ImmutableExecutableDefaults
{
    private static partial string? CreateDefaultAlpha()
    {
        ImmutableDefaultTrace.Phases.Add("alpha");
        return ImmutableDefaultTrace.NullAlpha ? null : "alpha-default";
    }

    private static partial IEnumerable<ImmutableDefaultMember> CreateDefaultMembers()
    {
        ImmutableDefaultTrace.Phases.Add("members");
        return [ImmutableDefaultMember.NewBuilder().WithLabel("default-member").Build()];
    }

    private static partial int CreateDefaultZulu()
    {
        ImmutableDefaultTrace.Phases.Add("zulu");
        return 19;
    }
}

public sealed partial class ImmutableDefaultMember
{
    private static partial string? CreateDefaultLabel()
    {
        ImmutableDefaultTrace.Phases.Add("label");
        return "default-label";
    }
}

public sealed partial class ImmutableDefaultWitness
{
    private static partial string? CreateDefaultTitle()
    {
        ImmutableDefaultTrace.Phases.Add("witness");
        return "default-witness";
    }
}
