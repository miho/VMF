using Eu.Mihosoft.Vmf.Conformance.Warehousepromotion;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class WarehousePromotionTests
{
    private const string Checkpoint = "warehouse-4";
    private static readonly JournalBinding Binding = new("conformance.warehouse-promotion", 1,
        model => model is Warehouse ? "warehouse" : ((Item)model).Sku!);

    [Fact]
    public void KeyedBuildersValidateStagedNullableSourcesBeforeAdoptingEntries()
    {
        var child = new Item { Sku = "imported" };
        IUnkeyedInventory loose = new LooseWarehouse();
        loose.Items.Add(child);
        var builder = Warehouse.NewBuilder().ApplyFrom(loose);
        Assert.Null(child.__vmfContainerLink.Owner);
        var warehouse = builder.Build();
        Assert.Same(child, warehouse.FindItemsBySku("imported"));
        loose.Items.Add(null);
        var invalid = Warehouse.NewBuilder().AppendCollections(false).ApplyFrom(loose);
        Assert.Throws<ArgumentException>(() => invalid.Build());
        Assert.Throws<ArgumentException>(() => invalid.ApplyTo(warehouse));
        Assert.Same(child, loose.Items[0]);
        Assert.Null(loose.Items[1]);
        Assert.Same(warehouse, child.__vmfContainerLink.Owner);
    }

    [Fact]
    public void SharedOwnershipUsesTheActualOwningListThroughEveryContract()
    {
        var warehouse = new Warehouse();
        UnkeyedInventory inherited = warehouse;
        IUnkeyedInventory ordinary = warehouse;
        IInventory keyed = warehouse;
        IExtraInventory alternate = warehouse;
        var first = new Item { Sku = "A" };
        var second = new Item { Sku = "B" };
        Assert.Same(warehouse.Items, inherited.Items);
        Assert.Same(warehouse.Items, ordinary.Items);
        Assert.Same(warehouse.Items, keyed.Items);
        Assert.Same(warehouse.Items, alternate.Items);
        ordinary.Items.AddRange([first, second]);
        Assert.Same(first, keyed.FindItemsBySku("A"));
        Assert.Same(warehouse, first.__vmfContainerLink.Owner);
        Assert.ThrowsAny<ArgumentException>(() => ordinary.Items.Add(null));
        Assert.ThrowsAny<ArgumentException>(() => ordinary.Items.Add(new Item { Sku = "A" }));
        Assert.ThrowsAny<ArgumentException>(() => first.Sku = "B");
        Assert.Same(first, keyed.FindItemsBySku("A"));
        first.Sku = "C";
        var view = warehouse.AsReadOnly();
        IReadOnlyUnkeyedInventory unkeyedView = view;
        IReadOnlyInventory keyedView = view;
        Assert.Same(view.Items, unkeyedView.Items);
        Assert.Same(view.Items, keyedView.Items);
        Assert.Same(first.AsReadOnly(), view.FindItemsBySku("C"));
        var effective = ModelSnapshot.Reflection.Reflect(warehouse).FindProperty("items")!;
        Assert.True(effective.IsContainment);
        Assert.Equal("sku", effective.KeyPropertyName);

        var copy = ModelSnapshot.Reflection.DeepCopy(warehouse);
        var copied = copy.FindItemsBySku("C")!;
        Assert.NotSame(first, copied);
        Assert.Same(copy, copied.__vmfContainerLink.Owner);
        Assert.Same(copy.Items, ((IUnkeyedInventory)copy).Items);
    }

    [Fact]
    public void IndependentUnkeyedOwnerKeepsItsOrdinaryReferenceList()
    {
        var warehouse = Warehouse.NewBuilder().WithItems([new Item { Sku = "A" }]).Build();
        var first = warehouse.Items[0];
        var loose = new LooseWarehouse();
        loose.Items.AddRange([first, first, null]);
        Assert.IsType<ReferenceList<Item>>(loose.Items);
        Assert.Equal(3, loose.Items.Count);
        Assert.Same(loose.Items[0], loose.Items[1]);
        Assert.Null(loose.Items[2]);
        Assert.Same(warehouse, first.__vmfContainerLink.Owner);
        var effective = ModelSnapshot.Reflection.Reflect(loose).FindProperty("items")!;
        Assert.True(effective.IsReference);
        Assert.Null(effective.KeyPropertyName);
        var overlay = new LooseOverlay();
        IUnkeyedInventory secondary = overlay;
        Assert.Same(overlay.Items, secondary.Items);
        secondary.Items.AddRange([first, first, null]);
        Assert.Equal(3, overlay.Items.Count);
        Assert.Same(warehouse, first.__vmfContainerLink.Owner);
    }

    [Fact]
    public void EditsThroughOrdinaryViewExchangeAsV1Containment()
    {
        var warehouse = Warehouse.NewBuilder().WithItems([new Item { Sku = "C" }, new Item { Sku = "B" }]).Build();
        string checkpoint = ModelSnapshot.Write(warehouse);
        using var recorder = JournalRecorder<Warehouse>.Start(warehouse, ModelSnapshot.Registry, Binding, Checkpoint, 4);
        ((IUnkeyedInventory)warehouse).Items.Move(1, 0);
        var journal = recorder.Finish();
        Assert.Equal(1, journal.WireVersion);
        var edit = Assert.Single(Assert.Single(journal.Transactions).Operations);
        Assert.Equal("items", edit.Property);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_WAREHOUSE_PROMOTION");
        if (exchange is not null) File.WriteAllText(Path.Combine(exchange, "csharp-checkpoint.json"), checkpoint);
        foreach (var (extension, write, read) in new (string, Func<ChangeJournal, string>, Func<string, ChangeJournal>)[]
                 {
                     ("json", JournalJson.Write, JournalJson.Read), ("xml", JournalXml.Write, JournalXml.Read),
                     ("yaml", JournalYaml.Write, JournalYaml.Read)
                 })
        {
            string output = write(journal);
            Verify(checkpoint, read(output));
            if (exchange is null) continue;
            Verify(File.ReadAllText(Path.Combine(exchange, "java-checkpoint.json")),
                read(File.ReadAllText(Path.Combine(exchange, $"java-journal.{extension}"))));
            File.WriteAllText(Path.Combine(exchange, $"csharp-journal.{extension}"), output);
        }
    }

    private static void Verify(string checkpoint, ChangeJournal journal)
    {
        var initial = ModelSnapshot.Read<Warehouse>(checkpoint);
        var restored = JournalReplayer.Replay(initial, Binding, Checkpoint, journal, ModelSnapshot.Registry);
        Assert.Same(initial.Items[0], initial.FindItemsBySku("C"));
        Assert.Same(restored.Items[0], restored.FindItemsBySku("B"));
        Assert.Same(restored.Items, ((IUnkeyedInventory)restored).Items);
    }
}
