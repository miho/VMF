using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Generickeyed;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class GenericKeyedTests
{
    private const string Checkpoint = "generic-keyed-20";
    private static readonly JournalBinding Binding = new("conformance.generic-keyed", 1, model => model switch
    {
        StorageRegistry => "registry",
        Warehouse { Name: { } name } => name,
        StorageRecord { Identity: { } identity } => identity,
        InventoryCatalogue => "catalogue",
        CatalogueItem { Identity: { } identity } => identity,
        _ => throw new ArgumentException("Unknown generic keyed object")
    });

    [Fact]
    public void OpenAndClosedLookupUseOneIndexThroughBothGenericParents()
    {
        var registry = Specimen();
        Storage<WarehouseEntry> storage = registry.Primary!;
        IOutgoingStorage<WarehouseEntry> secondary = registry.Primary!;
        IReadOnlyStorage<IReadOnlyWarehouseEntry> readOnly = registry.Primary!.AsReadOnly();
        IReadOnlyOutgoingStorage<IReadOnlyWarehouseEntry> readOnlySecondary = registry.Primary.AsReadOnly();
        IReadOnlyStorage<IReadOnlyStorageRecord> widened = storage.AsReadOnly();
        WarehouseEntry first = storage.FindOwnedBySlot("A")!;
        WarehouseEntry second = secondary.FindOwnedBySlot("B")!;
        Assert.Same(first, storage.Owned[0]);
        Assert.Same(second, secondary.Owned[1]);
        Assert.Same(readOnly.Owned[0], readOnlySecondary.FindOwnedBySlot("A"));
        Assert.Same(first.AsReadOnly(), widened.FindOwnedBySlot("A"));
        Assert.Equal("slot", ModelSnapshot.Reflection.Reflect(registry.Primary).FindProperty("owned")!.KeyPropertyName);

        Assert.Throws<ArgumentException>(() => second.Slot = "A");
        Assert.Throws<ArgumentException>(() => registry.Reserve!.Owned.Add(second));
        Assert.Equal("B", second.Slot);
        Assert.Same(second, secondary.Owned[1]);
        Assert.Single(registry.Reserve!.Owned);
        Assert.Throws<ArgumentException>(() =>
            Warehouse.NewBuilder().WithOwned([Entry("x", "duplicate"), Entry("y", "duplicate")]).Build());
        second.Slot = "C";
        Assert.Same(second, secondary.FindOwnedBySlot("C"));
        registry.Reserve.Owned.Add(second);
        Assert.Null(storage.FindOwnedBySlot("B"));
        Assert.Null(storage.FindOwnedBySlot("C"));
        Assert.Same(second, registry.Reserve.FindOwnedBySlot("C"));
    }

    [Fact]
    public void ConcreteBoundPreservesDerivedTypedLookupRekeyAndReplay()
    {
        const string checkpointId = "concrete-key-bound";
        var first = new CatalogueItem { Identity = "first", Sku = "A" };
        var derived = new LabelledItem { Identity = "derived", Sku = "B", Label = "priority" };
        var catalogue = InventoryCatalogue.NewBuilder().WithItems([first, derived]).Build();
        ItemCatalogue<CatalogueItem> open = catalogue;
        IReadOnlyItemCatalogue<IReadOnlyCatalogueItem> view = open.AsReadOnly();
        Assert.Same(derived, open.FindItemsBySku("B"));
        Assert.Same(derived.AsReadOnly(), view.FindItemsBySku("B"));
        var copy = ModelSnapshot.Reflection.DeepCopy(derived);
        ItemCatalogue<LabelledItem> specialized = LabelledCatalogue.NewBuilder().WithItems([copy]).Build();
        LabelledItem result = specialized.FindItemsBySku("B")!;
        Assert.Same(copy, result);
        Assert.Equal("priority", result.Label);
        Assert.Throws<ArgumentException>(() => derived.Sku = "A");
        Assert.Equal("B", derived.Sku);
        Assert.Same(derived, open.FindItemsBySku("B"));

        string checkpoint = ModelSnapshot.Write(catalogue);
        var journal = JournalRecorder<InventoryCatalogue>.RecordFrame(catalogue, ModelSnapshot.Registry, Binding,
            checkpointId, 30, owner =>
            {
                derived.Sku = "C";
                owner.Items.Move(1, 0);
            });
        string wire = JournalJson.Write(journal);
        Verify(checkpoint, wire);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_GENERIC_KEYED") is { } exchange)
        {
            Verify(File.ReadAllText(Path.Combine(exchange, "java-concrete-checkpoint.json")),
                File.ReadAllText(Path.Combine(exchange, "java-concrete-journal.json")));
            File.WriteAllText(Path.Combine(exchange, "csharp-concrete-checkpoint.json"), checkpoint);
            File.WriteAllText(Path.Combine(exchange, "csharp-concrete-journal.json"), wire);
        }

        static void Verify(string checkpoint, string wire)
        {
            using var document = JsonDocument.Parse(checkpoint);
            var entry = document.RootElement.GetProperty("concrete_entries")[1];
            Assert.EndsWith(".LabelledItem", entry.GetProperty("@vmf-type").GetString());
            Assert.Equal("B", entry.GetProperty("stock_code").GetString());
            var original = ModelSnapshot.Read<InventoryCatalogue>(checkpoint);
            var journal = JournalJson.Read(wire);
            Assert.Equal(1, journal.WireVersion);
            Assert.Equal(["sku", "items"], Assert.Single(journal.Transactions).Operations.Select(op => op.Property));
            var replayed = JournalReplayer.Replay(original, Binding, checkpointId, journal, ModelSnapshot.Registry);
            var derived = Assert.IsType<LabelledItem>(replayed.FindItemsBySku("C"));
            Assert.Equal("priority", derived.Label);
            Assert.Same(derived, replayed.Items[0]);
            Assert.Null(replayed.FindItemsBySku("B"));
            Assert.IsType<LabelledItem>(original.FindItemsBySku("B"));
        }
    }

    [Fact]
    public void SnapshotsAndV1ReplayUseEffectiveClosedKeyAndReferenceTypes()
    {
        var root = Specimen();
        string plain = ModelSnapshot.WritePlain(root);
        VerifyPlain(plain);
        var primary = root.Primary!;
        var first = primary.Owned[0];
        var second = primary.Owned[1];
        primary.Current = first;
        primary.Queue.Add(first);
        primary.Queue.Add(second);
        primary.Favorites.Add(second);
        string checkpoint = ModelSnapshot.Write(root);

        using var recorder = JournalRecorder<StorageRegistry>.Start(root, ModelSnapshot.Registry, Binding, Checkpoint, 20);
        first.Slot = "C";
        primary.Owned.Move(1, 0);
        primary.Queue.Move(1, 0);
        primary.Current = second;
        primary.Favorites.Add(first);
        var journal = recorder.Finish();
        var codecs = new (string Extension, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read)
        };

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_GENERIC_KEYED");
        if (exchange is not null)
        {
            VerifyPlain(File.ReadAllText(Path.Combine(exchange, "java-plain.json")));
            File.WriteAllText(Path.Combine(exchange, "csharp-plain.json"), plain);
            File.WriteAllText(Path.Combine(exchange, "csharp-checkpoint.json"), checkpoint);
        }
        foreach (var (extension, write, read) in codecs)
        {
            string output = write(journal);
            VerifyReplay(checkpoint, read(output));
            if (exchange is null) continue;
            VerifyReplay(File.ReadAllText(Path.Combine(exchange, "java-checkpoint.json")),
                read(File.ReadAllText(Path.Combine(exchange, $"java-journal.{extension}"))));
            File.WriteAllText(Path.Combine(exchange, $"csharp-journal.{extension}"), output);
        }
    }

    private static StorageRegistry Specimen() => new()
    {
        Primary = Warehouse.NewBuilder().WithName("primary").WithOwned([Entry("alpha", "A"), Entry("beta", "B")]).Build(),
        Reserve = Warehouse.NewBuilder().WithName("reserve").WithOwned([Entry("reserved", "B")]).Build()
    };

    private static WarehouseEntry Entry(string identity, string slot) => new() { Identity = identity, Slot = slot };

    private static void VerifyPlain(string json)
    {
        using var document = JsonDocument.Parse(json);
        var warehouse = document.RootElement.GetProperty("primary");
        Assert.True(warehouse.TryGetProperty("entries", out _));
        Assert.False(warehouse.TryGetProperty("owned", out _));
        Assert.False(warehouse.TryGetProperty("@vmf-id", out _));
        var restored = ModelSnapshot.ReadPlain<StorageRegistry>(json).Primary!;
        Assert.Same(restored.Owned[0], restored.FindOwnedBySlot("A"));
    }

    private static void VerifyReplay(string json, ChangeJournal journal)
    {
        var checkpoint = ModelSnapshot.Read<StorageRegistry>(json);
        var initial = checkpoint.Primary!;
        Assert.Same(initial.FindOwnedBySlot("A"), initial.Current);
        Assert.Same(initial.FindOwnedBySlot("B"), initial.Queue[1]);
        Assert.Equal(1, journal.WireVersion);
        Assert.Contains(Assert.Single(journal.Transactions).Operations, operation => operation.Property == "owned");

        var replayed = JournalReplayer.Replay(checkpoint, Binding, Checkpoint, journal, ModelSnapshot.Registry);
        var warehouse = replayed.Primary!;
        var first = warehouse.FindOwnedBySlot("C")!;
        var second = warehouse.FindOwnedBySlot("B")!;
        Assert.Null(warehouse.FindOwnedBySlot("A"));
        Assert.Same(second, warehouse.Owned[0]);
        Assert.Same(first, warehouse.Owned[1]);
        Assert.Same(second, warehouse.Current);
        Assert.Same(second, warehouse.Queue[0]);
        Assert.Same(first, warehouse.Queue[1]);
        Assert.Contains(first, warehouse.Favorites);
        Assert.NotNull(initial.FindOwnedBySlot("A"));
        Assert.NotNull(replayed.Reserve!.FindOwnedBySlot("B"));
    }
}
