namespace Eu.Mihosoft.Vmf.Conformance.Appliedcalls;

public sealed partial class ArgumentCallHost
{
    private static partial Behavior CreateDefaultBehavior() => new Binding();

    private sealed class Binding : Behavior
    {
        public PrimaryArgument? Rename(ArgumentCallHost caller, PrimaryArgument? value, string? next)
        {
            if (value is not null) value.Name = next;
            return value;
        }

        public ISecondaryArgument? Relabel(ArgumentCallHost caller, ISecondaryArgument? value, string? next)
        {
            if (value is not null) value.Label = next;
            return value;
        }
    }
}
