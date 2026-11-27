namespace Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;

public partial class InheritedEqualityBase
{
    internal static List<InheritedEqualityBase> Callers { get; } = [];
    internal static int BindingCount { get; set; }

    private static partial DefaultBehavior CreateDefaultBehavior()
    {
        BindingCount++;
        return new CodeEquality();
    }

    internal static int? CodeOf(object? candidate) => candidate switch
    {
        InheritedEqualityBase item => item.Code,
        IReadOnlyInheritedEqualityBase view => view.Code,
        _ => null
    };

    private sealed class CodeEquality : DefaultBehavior
    {
        private InheritedEqualityBase? caller;
        private int comparisons;

        public bool Equals(InheritedEqualityBase owner, object? other)
        {
            Bind(owner);
            comparisons++;
            return CodeOf(other) is int candidate && owner.Code % 100 == candidate % 100;
        }

        public int GetHashCode(InheritedEqualityBase owner)
        {
            Bind(owner);
            return owner.Code % 100;
        }

        public int Comparisons(InheritedEqualityBase owner)
        {
            Bind(owner);
            return comparisons;
        }

        private void Bind(InheritedEqualityBase owner)
        {
            if (caller is not null) return;
            caller = owner;
            Callers.Add(owner);
        }
    }
}
