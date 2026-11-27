namespace Eu.Mihosoft.Vmf.Conformance.Immutablebehavior;

public sealed partial class ImmutableEqualityValue
{
    internal static int BindingCount { get; set; }

    private static partial Behavior CreateDefaultBehavior()
    {
        BindingCount++;
        return new CodeEquality();
    }

    private sealed class CodeEquality : Behavior
    {
        private int comparisons;

        public bool Equals(ImmutableEqualityValue caller, object? other)
        {
            comparisons++;
            return other is ImmutableEqualityValue value && caller.Code % 100 == value.Code % 100;
        }

        public int GetHashCode(ImmutableEqualityValue caller) => caller.Code % 100;

        public int Comparisons(ImmutableEqualityValue caller) => comparisons;
    }
}
