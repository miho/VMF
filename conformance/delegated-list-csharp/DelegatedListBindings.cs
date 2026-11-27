namespace Eu.Mihosoft.Vmf.Conformance.Delegatedlists;

public partial class Table
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new Binding();

    private sealed class Binding : DefaultBehavior
    {
        private List<object?>? payloads;

        public IList<Row?>? Select(Table caller) => null;
        public IList<OwnedRow?>? SelectOwned(Table caller) => caller.OwnedRows;
        public IList<Scope?>? SelectScopes(Table caller) => caller.Scopes;
        public IList<string?>? SelectLabels(Table caller) => caller.Labels;
        public IList<object?>? Payloads(Table caller) => payloads ??= [4, 4L, null, caller];
        public IList<object?>? DirectPayloads(Table caller) => Payloads(caller);
    }
}

public sealed partial class LiveTable
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new Binding();

    private sealed class Binding : DefaultBehavior
    {
        public IList<Row?>? Select(LiveTable caller) => caller.Rows;
    }
}
