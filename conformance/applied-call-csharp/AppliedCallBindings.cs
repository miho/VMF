namespace Eu.Mihosoft.Vmf.Conformance.Appliedcalls;

public sealed partial class JaggedCallHost
{
    private static partial Behavior CreateDefaultBehavior() => new Binding();

    private sealed class Binding : Behavior
    {
        public int Mark(JaggedCallHost _, int[]?[]? rows)
        {
            if (rows is null) return -1;
            if (!ReferenceEquals(rows[0], rows[2]) || rows[1] is not null || rows[3] is not { Length: 0 })
                throw new InvalidOperationException("The binder did not receive the original ragged primitive rows.");
            return ++rows[2]![0];
        }

        public Entry? First(JaggedCallHost _, Entry?[]?[]? rows)
        {
            if (rows is null) return null;
            if (!ReferenceEquals(rows[0], rows[2]) || rows[1] is not null
                || rows[3] is not { Length: 0 } || rows[0]![1] is not null)
                throw new InvalidOperationException("The binder did not receive the original ragged model rows.");
            return rows[2]![0];
        }

        public int CountRows(JaggedCallHost _, int[]?[] rows)
        {
            if (!ReferenceEquals(rows[0], rows[2]))
                throw new InvalidOperationException("The binder did not receive shared vararg rows.");
            return rows.Length + (rows[1] is null ? 10 : 0);
        }
    }
}

public sealed partial class AliasCallHost
{
    private static partial Behavior CreateDefaultBehavior() => new Binding();

    private sealed class Binding : Behavior
    {
        public string Classify(AliasCallHost caller, string? input) => "text:" + (input ?? "null");
        public string Classify(AliasCallHost caller, Uri? input) => "uri:" + (input?.OriginalString ?? "null");
        public int ClassifyNumber(AliasCallHost caller, int input) => 100 + input;
        public int ClassifyNumber(AliasCallHost caller, int? input) => input.HasValue ? 200 + input.Value : -1;
    }
}

public partial class AppliedQuery
{
    private static partial Behavior CreateDefaultBehavior() => new Binding();

    private sealed class Binding : Behavior
    {
        public Holder<SpecialEntry>? Forward(AppliedQuery _, Holder<SpecialEntry>? input) => input;
        public Holder<SpecialEntry>?[]? Batch(AppliedQuery _, Holder<SpecialEntry>?[]? inputs) => inputs;
        public Holder<SpecialEntry>?[]? Collect(AppliedQuery _, Holder<SpecialEntry>?[] inputs) => inputs;
        public Series<int?>? Boxed(AppliedQuery _, Series<int?>? input) => input;
        public Numbered<double>? Numeric(AppliedQuery _, Numbered<double>? input) => input;
        public Pair<SpecialEntry, int?>? Pair(AppliedQuery _, Pair<SpecialEntry, int?>? input) => input;
        public Uri? EchoUri(AppliedQuery _, Uri? input) => input;
        public Guid? EchoId(AppliedQuery _, Guid? input) => input;
    }
}

public sealed partial class ReverseQuery
{
    private static partial Behavior_BaseQueryBinding CreateBehavior_BaseQueryBinding() => new InheritedBinding();
    private static partial Behavior_ReverseQueryBinding CreateBehavior_ReverseQueryBinding() => new ReverseBinding();

    private sealed class InheritedBinding : Behavior_BaseQueryBinding
    {
        public Holder<SpecialEntry>? Forward(ReverseQuery _, Holder<SpecialEntry>? input) => input;
        public Holder<SpecialEntry>?[]? Batch(ReverseQuery _, Holder<SpecialEntry>?[]? inputs) => inputs;
        public Series<int?>? Boxed(ReverseQuery _, Series<int?>? input) => input;
        public Numbered<double>? Numeric(ReverseQuery _, Numbered<double>? input) => input;
        public Pair<SpecialEntry, int?>? Pair(ReverseQuery _, Pair<SpecialEntry, int?>? input) => input;
        public Uri? EchoUri(ReverseQuery _, Uri? input) => input;
        public Guid? EchoId(ReverseQuery _, Guid? input) => input;
    }

    private sealed class ReverseBinding : Behavior_ReverseQueryBinding
    {
        public Holder<SpecialEntry>?[]? Collect(ReverseQuery _, Holder<SpecialEntry>?[] inputs) => [.. inputs.Reverse()];
    }
}

public partial class Projector
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new Binding();

    private sealed class Binding : DefaultBehavior
    {
        public Holder<SpecialEntry>? View(Projector _, Holder<SpecialEntry>? input) => null;
    }
}

public sealed partial class UpdatedProjector
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new Binding();

    private sealed class Binding : DefaultBehavior
    {
        public Holder<SpecialEntry>? View(UpdatedProjector _, Holder<SpecialEntry>? input) => input;
    }
}
