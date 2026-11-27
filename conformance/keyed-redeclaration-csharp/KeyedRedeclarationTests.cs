using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Keyedredeclaration;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class KeyedRedeclarationTests
{
    private const string Checkpoint = "keyed-inheritance-12";
    private static readonly JournalBinding Binding = new("conformance.keyed-redeclaration", 1, model => model switch
    {
        Warehouse => "warehouse",
        StockEntry { Id: { } id } => id,
        _ => throw new ArgumentException("Unknown keyed model object")
    });

    [Fact]
    public void IdenticalParentLookupsShareOneStorageThroughEveryProjection()
    {
        var warehouse = Specimen();
        IStockOwner stock = warehouse;
        IBackupOwner backup = warehouse;
        ICombinedStock combined = warehouse;
        IReadOnlyCombinedStock view = warehouse.AsReadOnly();
        StockEntry first = combined.FindItemsBySku("A")!;
        Assert.Same(warehouse.Items, stock.Items);
        Assert.Same(stock.Items, backup.Items);
        Assert.Same(stock.Items, combined.Items);
        Assert.Same(first, warehouse.Items[0]);
        Assert.Same(first.AsReadOnly(), view.FindItemsBySku("A"));
        Assert.Same(first.AsReadOnly(), warehouse.AsReadOnly().FindItemsBySku("A"));
        Assert.Throws<ArgumentException>(() => first.Sku = "B");
        Assert.Same(first, stock.FindItemsBySku("A"));
        first.Sku = "C";
        Assert.Same(first, backup.FindItemsBySku("C"));

        var separate = StandardStock.NewBuilder().WithItems([Entry("other", "other")]).Build();
        using var separateJson = JsonDocument.Parse(ModelSnapshot.Write(separate));
        Assert.True(separateJson.RootElement.TryGetProperty("stock", out _));
        Assert.False(separateJson.RootElement.TryGetProperty("receipts", out _));
        separate.Items.Add(first);
        Assert.Null(combined.FindItemsBySku("C"));
        Assert.Same(first, separate.FindItemsBySku("C"));

        var direct = DirectStock.NewBuilder().WithItems([Entry("direct", "D")]).Build();
        using var directJson = JsonDocument.Parse(ModelSnapshot.Write(direct));
        Assert.True(directJson.RootElement.TryGetProperty("direct_stock", out _));
        Assert.Same(direct.Items[0].AsReadOnly(), direct.AsReadOnly().FindItemsBySku("D"));
    }

    [Fact]
    public void RedeclarationChangesSnapshotNameAndRetainsLogicalJournalProperty()
    {
        var model = Specimen();
        string checkpoint = ModelSnapshot.Write(model);
        using var recorder = JournalRecorder<Warehouse>.Start(model, ModelSnapshot.Registry, Binding, Checkpoint, 12);
        model.Items[0].Sku = "C";
        model.Items.Move(1, 0);
        var journal = recorder.Finish();
        var formats = new (string Extension, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read)
        };
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_KEYED_REDECLARATION");
        if (exchange is not null) File.WriteAllText(Path.Combine(exchange, "csharp-checkpoint.json"), checkpoint);
        foreach (var format in formats)
        {
            string output = format.Write(journal);
            Verify(checkpoint, format.Read(output));
            if (exchange is null) continue;
            Verify(File.ReadAllText(Path.Combine(exchange, "java-checkpoint.json")),
                format.Read(File.ReadAllText(Path.Combine(exchange, $"java-journal.{format.Extension}"))));
            File.WriteAllText(Path.Combine(exchange, $"csharp-journal.{format.Extension}"), output);
        }
    }

    private static Warehouse Specimen() => Warehouse.NewBuilder().WithLabel("Warehouse")
        .WithItems([Entry("a", "A"), Entry("b", "B")]).Build();

    private static StockEntry Entry(string id, string sku) => new() { Id = id, Sku = sku };

    private static void Verify(string json, ChangeJournal journal)
    {
        using var doc = JsonDocument.Parse(json);
        Assert.True(doc.RootElement.TryGetProperty("receipts", out _));
        Assert.False(doc.RootElement.TryGetProperty("stock", out _));
        Assert.False(doc.RootElement.TryGetProperty("items", out _));
        var initial = ModelSnapshot.Read<Warehouse>(json);
        Assert.Equal(1, journal.WireVersion);
        var operations = Assert.Single(journal.Transactions).Operations;
        Assert.Equal("a", operations[0].Target);
        Assert.Equal("items", operations[1].Property);
        var replayed = JournalReplayer.Replay(initial, Binding, Checkpoint, journal, ModelSnapshot.Registry);
        Assert.Same(replayed.Items[1], replayed.FindItemsBySku("C"));
        Assert.Equal("a", replayed.Items[1].Id);
        Assert.NotNull(initial.FindItemsBySku("A"));
    }
}
