using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Delegatedlists;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class DelegatedListsTests
{
    [Fact]
    public void ReturnedReferenceAndOwningListsStayWritableAndObserved()
    {
        Assert.Null(new Table().Select());
        Table table = new LiveTable();
        IList<Row?>? rows = table.Select();
        IList<OwnedRow?>? owned = table.SelectOwned();
        Assert.Same(table.Rows, rows);
        Assert.Same(table.OwnedRows, owned);
        Assert.NotNull(rows);
        Assert.NotNull(owned);
        Assert.False(rows.IsReadOnly);
        Assert.False(owned.IsReadOnly);
        Assert.Null(typeof(IReadOnlyTable).GetMethod(nameof(Table.Select)));

        var observed = new List<ModelChange>();
        var changes = table.VmfChanges;
        using var subscription = changes.AddLocalListener(observed.Add);
        changes.Start();
        var row = new Row { Name = "shared" };
        var ownedRow = new OwnedRow { Name = "owner" };

        rows.Add(row);
        rows.Add(null);
        rows.Add(row);
        var copy = new Row?[4];
        rows.CopyTo(copy, 1);
        Assert.Collection(copy, item => Assert.Null(item), item => Assert.Same(row, item),
            item => Assert.Null(item), item => Assert.Same(row, item));
        rows.RemoveAt(0);
        Assert.Collection(table.Rows, item => Assert.Null(item), item => Assert.Same(row, item));

        owned.Add(ownedRow);
        owned.Add(null);
        Assert.Equal(0, owned.IndexOf(ownedRow));
        Assert.Equal(1, owned.IndexOf(null));
        Assert.Same(table, ownedRow.Table);
        Assert.Collection(table.OwnedRows, item => Assert.Same(ownedRow, item), item => Assert.Null(item));
        owned.RemoveAt(0);
        Assert.Null(ownedRow.Table);
        Assert.Null(Assert.Single(table.OwnedRows));
        changes.Stop();

        Assert.Equal(observed, changes.History);
        Assert.Equal(["rows", "rows", "rows", "rows", "ownedRows", "ownedRows", "ownedRows"],
            observed.Select(change => change.PropertyName));
        Assert.All(observed, change =>
        {
            Assert.Equal(ModelChangeKind.List, change.Kind);
            Assert.Same(table, change.Source);
        });
        var addedNull = Assert.IsType<ModelCollectionChange>(observed[1]);
        Assert.Null(Assert.Single(addedNull.Added).Value);
        var addedChild = Assert.IsType<ModelCollectionChange>(observed[4]);
        Assert.Same(ownedRow, Assert.Single(addedChild.Added).Value);
    }

    [Fact]
    public void ExplicitVListsBorrowAbstractModelAndScalarStorage()
    {
        Table table = new LiveTable();
        var scopes = Assert.IsAssignableFrom<IList<Scope?>>(table.SelectScopes());
        var labels = Assert.IsAssignableFrom<IList<string?>>(table.SelectLabels());
        Assert.Same(table.Scopes, scopes);
        Assert.Same(table.Labels, labels);
        Assert.False(labels.IsReadOnly);
        Scope scope = new LocalScope { Name = "local" };
        var observed = new List<ModelChange>();
        using var subscription = table.VmfChanges.AddLocalListener(observed.Add);

        scopes.Add(scope);
        scopes.Add(null);
        labels.Add("label");
        labels.Add(null);
        Assert.Same(scope, table.Scopes[0]);
        Assert.Null(table.Scopes[1]);
        var copy = new string?[3];
        labels.CopyTo(copy, 1);
        Assert.Collection(copy, item => Assert.Null(item), item => Assert.Equal("label", item), item => Assert.Null(item));
        Assert.Equal(["scopes", "scopes", "labels", "labels"], observed.Select(change => change.PropertyName));
        Assert.All(observed, change => Assert.Same(table, change.Source));
    }

    [Fact]
    public void ExplicitObjectListReturnsKeepHostStateOutsideTheModelGraph()
    {
        var table = new Table { Name = "host" };
        string original = ModelSnapshot.WriteWithoutReferences(table);
        var payloads = CheckHostPayloads(table);
        var observed = new List<ModelChange>();
        using var subscription = table.VmfChanges.AddLocalListener(observed.Add);

        var detached = new Row { Name = "unregistered child" };
        payloads[1] = (sbyte)4;
        payloads.Add(detached);
        Assert.Same(payloads, table.DirectPayloads());
        Assert.Equal((sbyte)4, Assert.IsType<sbyte>(payloads[1]));
        Assert.Same(detached, payloads[4]);
        Assert.Empty(observed);
        var reflection = ModelSnapshot.Reflection.Reflect(table);
        Assert.Null(reflection.FindProperty("payloads"));
        Assert.Null(reflection.FindProperty("directPayloads"));
        Assert.Same(table, Assert.Single(ModelSnapshot.Reflection.Traverse(table, ModelTraversalStrategy.UniqueNode)));
        Assert.Null(typeof(IReadOnlyTable).GetMethod(nameof(Table.Payloads)));

        string wire = ModelSnapshot.WriteWithoutReferences(table);
        Assert.Equal(original, wire);
        var restored = ModelSnapshot.ReadWithoutReferences<Table>(wire);
        Assert.NotSame(payloads, CheckHostPayloads(restored));
    }

    private static IList<object?> CheckHostPayloads(Table table)
    {
        var payloads = Assert.IsAssignableFrom<IList<object?>>(table.Payloads());
        Assert.Same(payloads, table.DirectPayloads());
        Assert.False(payloads.IsReadOnly);
        Assert.Collection(payloads,
            item => Assert.Equal(4, Assert.IsType<int>(item)),
            item => Assert.Equal(4L, Assert.IsType<long>(item)),
            item => Assert.Null(item),
            item => Assert.Same(table, item));
        return payloads;
    }

    [Fact]
    public void RestoringASnapshotRetainsInheritedWritableReturnSlots()
    {
        var table = new LiveTable { Name = "live" };
        table.SelectOwned()!.Add(new OwnedRow { Name = "owned" });
        table.SelectScopes()!.Add(new LocalScope { Name = "local", Depth = 2 });
        table.SelectLabels()!.Add("label");
        table.Payloads()!.Add("private payload");
        string wire = ModelSnapshot.WriteWithoutReferences(new TableEnvelope { Table = table });
        const string Expected = """
            {"table":{"@vmf-type":"eu.mihosoft.vmf.conformance.delegatedlists.LiveTable","name":"live","ownedRows":[{"name":"owned"}],"labels":["label"],"scopes":[{"@vmf-type":"eu.mihosoft.vmf.conformance.delegatedlists.LocalScope","name":"local","depth":2}]}}
            """;
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(Expected), JsonNode.Parse(wire)));

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_DELEGATED_LISTS");
        if (exchange is not null)
        {
            Directory.CreateDirectory(exchange);
            File.WriteAllText(Path.Combine(exchange, "csharp-table.json"), wire);
            wire = File.ReadAllText(Path.Combine(exchange, "java-table.json"));
        }

        Table restored = Assert.IsType<LiveTable>(ModelSnapshot.ReadWithoutReferences<TableEnvelope>(wire).Table);
        Assert.Same(restored.Rows, restored.Select());
        Assert.Same(restored.OwnedRows, restored.SelectOwned());
        Assert.Same(restored, Assert.Single(restored.SelectOwned()!)?.Table);
        Assert.Same(restored.Scopes, restored.SelectScopes());
        Assert.Same(restored.Labels, restored.SelectLabels());
        Assert.Equal(2, Assert.IsType<LocalScope>(Assert.Single(restored.SelectScopes()!)).Depth);
        Assert.Equal("label", Assert.Single(restored.SelectLabels()!));
        CheckHostPayloads(restored);
    }
}
