using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;
using StockKeyed = Eu.Mihosoft.Vmftest.Keyed;

namespace Vmf.CSharp.Conformance;

public class KeyedTests
{
    private static readonly string Fixture = Path.Combine(AppContext.BaseDirectory, "fixtures", "keyed-inventory.json");

    [Theory]
    [InlineData("add", ModelChangeScope.Local)]
    [InlineData("add", ModelChangeScope.Recursive)]
    [InlineData("sort", ModelChangeScope.Local)]
    [InlineData("sort", ModelChangeScope.Recursive)]
    [InlineData("remove", ModelChangeScope.Local)]
    [InlineData("remove", ModelChangeScope.Recursive)]
    [InlineData("identity", ModelChangeScope.Local)]
    [InlineData("identity", ModelChangeScope.Recursive)]
    [InlineData("transfer", ModelChangeScope.Local)]
    [InlineData("transfer", ModelChangeScope.Recursive)]
    public void ReentrantKeyedEditsPreserveLookupsAndReplayInCausalOrder(string mode, ModelChangeScope scope)
    {
        var binding = new JournalBinding("conformance.keyed-causality", 1, model => model switch
        {
            KeyedInventory => "root",
            KeyedItem item => $"item-{item.Quantity}",
            KeyedAssignment entry => $"entry-{entry.Count}",
            KeyedPart part => part.Name!,
            _ => throw new ArgumentException("Unexpected keyed model."),
        });
        var owner = JournalCheckpoint();
        var source = new KeyedInventory();
        var incoming = new KeyedItem { Sku = "outer", Quantity = 4 };
        var replacement = new KeyedItem { Sku = "nested", Quantity = 5 };
        var target = owner.Parts[0];
        if (mode == "transfer") source.Products.Add(incoming);
        using var departure = source.Products.AddListener(edit =>
        {
            if (edit.Removed.Count == 0) return;
            Assert.Same(source.Products, edit.Source);
            var removed = Assert.Single(edit.Removed);
            Assert.Equal(0, removed.Index);
            Assert.Same(incoming, removed.Value);
            Assert.Same(incoming, owner.FindProductsBySku("outer"));
            Assert.ThrowsAny<ArgumentException>(() => incoming.Sku = "left");
            incoming.Sku = "moved";
        });
        bool observed = false;
        string property = mode == "identity" ? "assignments" : "products";
        using var watch = owner.VmfChanges.AddPropertyListener(property, _ =>
        {
            if (observed) return;
            observed = true;
            switch (mode)
            {
                case "add":
                    Assert.Same(incoming, owner.FindProductsBySku("outer"));
                    owner.Products[3] = replacement;
                    break;
                case "sort": owner.Products[0] = replacement; break;
                case "remove":
                    Assert.Null(owner.FindProductsBySku("middle"));
                    owner.Products.Insert(1, replacement);
                    break;
                case "identity":
                    Assert.Equal(4, owner.FindAssignmentsByPart(target)?.Count);
                    owner.Assignments[0] = new KeyedAssignment { Part = target, Count = 5 };
                    break;
                case "transfer":
                    Assert.Same(incoming, owner.FindProductsBySku("moved"));
                    incoming.Sku = "renamed";
                    Assert.Same(incoming, owner.FindProductsBySku("renamed"));
                    break;
            }
        }, scope);
        using var recorder = JournalRecorder<KeyedInventory>.Start(owner, ModelSnapshot.Registry, binding, "initial", 0);
        switch (mode)
        {
            case "add":
            case "transfer": owner.Products.Add(incoming); break;
            case "sort": owner.Products.Sort((a, b) => StringComparer.Ordinal.Compare(b.Sku, a.Sku)); break;
            case "remove": owner.Products.RemoveAt(1); break;
            case "identity": owner.Assignments.Add(new KeyedAssignment { Part = target, Count = 4 }); break;
        }
        Assert.True(observed);
        Assert.Empty(source.Products);
        var history = owner.VmfChanges.History.OfType<ModelCollectionChange>().Where(edit => edit.PropertyName == property).ToArray();
        Assert.Equal(mode == "transfer" ? 1 : 2, history.Length);
        Assert.Same(mode == "transfer" ? incoming : mode == "identity" ? owner.Assignments[0] : replacement,
            Assert.Single(history[0].Added).Value);
        var journal = recorder.Finish();

        var formats = new (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read), ("yaml", JournalYaml.Write, JournalYaml.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
        };
        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_KEYED");
        foreach (var (format, write, read) in formats)
        {
            string wire = write(journal);
            Verify(read(wire));
            if (interchange is null) continue;
            string filename = $"{mode}-{scope.ToString().ToLowerInvariant()}-journal.{format}";
            Verify(read(File.ReadAllText(Path.Combine(interchange, "java-" + filename))));
            File.WriteAllText(Path.Combine(interchange, "csharp-" + filename), wire);
        }

        void Verify(ChangeJournal recorded)
        {
            Assert.Equal(1, recorded.WireVersion);
            var edits = Assert.Single(recorded.Transactions).Operations;
            Assert.Equal(mode == "transfer" ? 1 : 2, edits.Count);
            var outer = Assert.IsType<JournalListEdit>(edits[0]);
            Assert.Equal(mode == "sort" ? JournalListEditKind.Set
                : mode == "remove" ? JournalListEditKind.Remove : JournalListEditKind.Add, outer.Kind);
            Assert.Equal(mode == "sort" ? [0, 2] : new[] { mode == "remove" ? 1 : mode == "identity" ? 0 : 3 }, outer.Indices);
            var checkpoint = JournalCheckpoint();
            var restored = JournalReplayer.Replay(checkpoint, binding, "initial", recorded, ModelSnapshot.Registry);
            string[] expected = mode switch
            {
                "add" => ["left", "middle", "right", "nested"],
                "sort" => ["nested", "middle", "left"],
                "remove" => ["left", "nested", "right"],
                "transfer" => ["left", "middle", "right", "renamed"],
                _ => ["left", "middle", "right"],
            };
            Assert.Equal(expected, restored.Products.Select(child => child.Sku));
            Assert.All(restored.Products, child => Assert.Same(child, restored.FindProductsBySku(child.Sku!)));
            Assert.Equal(3, checkpoint.Products.Count);
            if (mode == "identity")
            {
                var assignment = Assert.Single(restored.Assignments);
                Assert.Same(assignment, restored.FindAssignmentsByPart(restored.Parts[0]));
                Assert.Equal(5, assignment.Count);
            }
        }
    }

    private static KeyedInventory JournalCheckpoint() => KeyedInventory.NewBuilder()
        .WithProducts([new KeyedItem { Sku = "left", Quantity = 1 }, new KeyedItem { Sku = "middle", Quantity = 2 },
            new KeyedItem { Sku = "right", Quantity = 3 }])
        .WithParts([new KeyedPart { Name = "key" }]).Build();

    [Fact]
    public void KeyedListReportsPositionsAndCanonicalIdentitiesFromBothViews()
    {
        var inventory = JournalCheckpoint();
        var products = inventory.Products;
        var view = inventory.AsReadOnly().Products;
        var oldItem = products[1];
        var replacement = new KeyedItem { Sku = "replacement", Quantity = 7 };
        int mutableEvents = 0;
        int viewEvents = 0;
        using (products.AddListener(edit =>
        {
            mutableEvents++;
            Assert.Same(products, edit.Source);
            var removed = Assert.Single(edit.Removed);
            var added = Assert.Single(edit.Added);
            Assert.Equal(1, removed.Index);
            Assert.Equal(1, added.Index);
            Assert.Same(oldItem, removed.Value);
            Assert.Same(replacement, added.Value);
            Assert.Null(inventory.FindProductsBySku("middle"));
            Assert.Same(replacement, inventory.FindProductsBySku("replacement"));
        }))
        using (view.AddListener(edit =>
        {
            viewEvents++;
            Assert.Same(view, edit.Source);
            var removed = Assert.Single(edit.Removed);
            var added = Assert.Single(edit.Added);
            Assert.Equal(1, removed.Index);
            Assert.Equal(1, added.Index);
            Assert.Same(oldItem.AsReadOnly(), removed.Value);
            Assert.Same(replacement.AsReadOnly(), added.Value);
            Assert.Same(replacement.AsReadOnly(), view[1]);
        }))
        {
            Assert.ThrowsAny<ArgumentException>(() => products[1] = new KeyedItem { Sku = "left" });
            Assert.Equal(0, mutableEvents);
            Assert.Equal(0, viewEvents);
            products[1] = replacement;
        }
        products.RemoveAt(1);
        Assert.Equal(1, mutableEvents);
        Assert.Equal(1, viewEvents);
    }

    [Fact]
    public void ScalarKeysAreLiveAcrossInheritedSettersAndReadOnlyViews()
    {
        var inventory = ReadFixture();
        var item = Assert.IsType<KeyedItem>(inventory.FindProductsBySku("A"));
        Assert.Same(inventory.Products[0], item);
        Assert.Same(item.AsReadOnly(), inventory.AsReadOnly().FindProductsBySku("A"));
        Assert.ThrowsAny<ArgumentException>(() => item.Sku = "B");
        Assert.ThrowsAny<ArgumentException>(() => item.Sku = null);
        Assert.ThrowsAny<ArgumentException>(() => inventory.Products.Add(item));
        Assert.Equal("A", item.Sku);
        Assert.Equal(2, inventory.Products.Count);

        item.Sku = "free";
        Assert.Null(inventory.FindProductsBySku("A"));
        Assert.Same(item, inventory.FindProductsBySku("free"));
        Assert.Same(item.AsReadOnly(), inventory.AsReadOnly().FindProductsBySku("free"));
    }

    [Fact]
    public void KeyedBuilderRejectsDuplicateKeysWithoutReplacingItsStageOrMovingChildren()
    {
        var existingOwner = new KeyedInventory();
        var first = KeyedItem.NewBuilder().WithSku("A").Build();
        existingOwner.Products.Add(first);
        var second = KeyedItem.NewBuilder().WithSku("A").Build();
        var invalid = KeyedItem.NewBuilder().Build();
        var builder = KeyedInventory.NewBuilder().WithProducts([first]);
        Assert.ThrowsAny<ArgumentException>(() => builder.WithProducts([first, second]));
        Assert.ThrowsAny<ArgumentException>(() => builder.WithProducts([invalid]));
        Assert.Same(first, Assert.Single(existingOwner.Products));

        var built = builder.Build();
        Assert.Same(first, Assert.Single(built.Products));
        Assert.Empty(existingOwner.Products);
    }

    [Fact]
    public void LazyScalarKeysAreCheckedAtBuildAndImportsMoveOnlyTheDirectEntries()
    {
        var item = StockKeyed.Item.NewBuilder().WithSku("A");
        var other = StockKeyed.Item.NewBuilder().WithSku("A");
        var stage = StockKeyed.Catalog.NewBuilder().WithItemsBuilders([item, other]);
        var existing = item.Build();
        var target = StockKeyed.Catalog.NewBuilder().WithItems([existing]).Build();
        stage.ApplyTo(target);
        Assert.Same(existing, Assert.Single(target.Items));
        stage.AppendCollections(false).ApplyTo(target);
        Assert.Empty(target.Items);
        Assert.ThrowsAny<ArgumentException>(() => stage.Build());

        other.WithSku("B");
        var first = stage.Build();
        var second = stage.Build();
        Assert.Equal(new[] { "A", "B" }, first.Items.Select(entry => entry.Sku));
        Assert.NotSame(first.FindItemsBySku("B"), second.FindItemsBySku("B"));

        stage.ApplyFrom(first);
        var entry = first.Items[0];
        var imported = stage.Build();
        Assert.Same(entry, imported.FindItemsBySku("A"));
        Assert.Empty(first.Items);
    }

    [Fact]
    public void LazyOrderedIdentityKeysResolveAndTransferTheActualEntries()
    {
        var left = new KeyedPart { Name = "same" };
        var right = new KeyedPart { Name = "same" };
        var first = KeyedAssignment.NewBuilder().WithPart(left);
        var second = KeyedAssignment.NewBuilder().WithPart(left);
        var staged = KeyedInventory.NewBuilder().WithAssignmentsBuilders([first, second]);
        Assert.ThrowsAny<ArgumentException>(() => staged.Build());

        second.WithPart(right);
        var source = staged.WithParts([left, right]).Build();
        Assert.Same(source.Assignments[0], source.FindAssignmentsByPart(left));
        Assert.Same(source.Assignments[1], source.FindAssignmentsByPart(right));
        var direct = source.Assignments.ToArray();
        var occupied = new KeyedAssignment { Part = left };
        var target = KeyedInventory.NewBuilder().WithAssignments([occupied]).Build();
        var collision = KeyedInventory.NewBuilder().WithAssignments([direct[0]]);
        Assert.ThrowsAny<ArgumentException>(() => collision.ApplyTo(target));
        Assert.Same(occupied, Assert.Single(target.Assignments));
        Assert.Same(direct[0], source.Assignments[0]);
        staged.WithParts([]).ApplyTo(target);
        Assert.Single(target.Assignments);
        staged.AppendCollections(false).ApplyTo(target);
        Assert.Empty(target.Assignments);

        staged.ApplyFrom(source).AppendCollections(true).ApplyFrom(source);
        var result = staged.Build();
        Assert.Collection(result.Assignments, entry => Assert.Same(direct[0], entry), entry => Assert.Same(direct[1], entry));
        Assert.Same(result.Parts[0], result.Assignments[0].Part);
        Assert.Same(result.Parts[1], result.Assignments[1].Part);
        Assert.Empty(source.Assignments);
        Assert.Empty(source.Parts);
    }

    [Fact]
    public void ReparentPreflightsKeysAndPendingKeyMutationsAgainstTheDestination()
    {
        var source = KeyedInventory.NewBuilder()
            .WithProducts([KeyedItem.NewBuilder().WithSku("A").Build(), KeyedItem.NewBuilder().WithSku("B").Build()]).Build();
        var item = source.Products[0];
        var destination = KeyedInventory.NewBuilder().WithProducts(
            [KeyedItem.NewBuilder().WithSku("B").Build()]).Build();
        Assert.ThrowsAny<ArgumentException>(() => destination.Products.AddRange(source.Products));
        Assert.Equal(2, source.Products.Count);
        Assert.Single(destination.Products);

        int observed = 0;
        using var watch = source.VmfChanges.AddPropertyListener("products", _ =>
        {
            observed++;
            Assert.Same(item, destination.FindProductsBySku("A"));
            Assert.ThrowsAny<ArgumentException>(() => item.Sku = "B");
            Assert.ThrowsAny<ArgumentException>(() => item.Sku = null);
            Assert.Equal("A", item.Sku);
            item.Sku = "C";
            Assert.Same(item, destination.FindProductsBySku("C"));
        });
        Assert.True(destination.Products.Add(item));
        Assert.Equal(1, observed);
        Assert.Same(item, destination.FindProductsBySku("C"));
        Assert.Null(source.FindProductsBySku("C"));
        Assert.Equal("B", Assert.Single(source.Products).Sku);
    }

    [Fact]
    public void ReferenceKeysUseModelIdentityAndAcceptReadOnlyAliases()
    {
        var inventory = ReadFixture();
        var first = inventory.Parts[0];
        var second = inventory.Parts[1];
        Assert.Equal(first.Name, second.Name);
        Assert.NotSame(first, second);
        Assert.Same(inventory.Assignments[0], inventory.FindAssignmentsByPart(first));
        Assert.Same(inventory.Assignments[1], inventory.FindAssignmentsByPart(second));
        Assert.Same(inventory.Assignments[0].AsReadOnly(), inventory.AsReadOnly().FindAssignmentsByPart(first.AsReadOnly()));

        var entry = inventory.Assignments[0];
        Assert.ThrowsAny<ArgumentException>(() => entry.Part = second);
        Assert.ThrowsAny<ArgumentException>(() => entry.Part = null);
        Assert.Same(first, entry.Part);
        Assert.True(inventory.Assignments.Remove(entry));
        entry.Part = null;
        Assert.Null(inventory.FindAssignmentsByPart(first));
        Assert.ThrowsAny<ArgumentException>(() => inventory.Assignments.Add(entry));
        Assert.Single(inventory.Assignments);
    }

    [Fact]
    public void SnapshotsRestoreScalarAndForwardReferenceKeyedInventoryInBothLanguages()
    {
        var original = ReadFixture();
        string references = ModelSnapshot.Write(original.AsReadOnly());
        VerifyReference(references);

        var scalarOnly = KeyedInventory.NewBuilder().WithProducts(
            [KeyedItem.NewBuilder().WithSku("A").WithQuantity(3).Build(),
             KeyedItem.NewBuilder().WithSku("B").WithQuantity(4).Build()]).Build();
        string plain = ModelSnapshot.WritePlain(scalarOnly.AsReadOnly());
        VerifyPlain(plain);

        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_KEYED");
        if (interchange is not null)
        {
            VerifyReference(File.ReadAllText(Path.Combine(interchange, "java-reference.json")));
            VerifyPlain(File.ReadAllText(Path.Combine(interchange, "java-plain.json")));
            File.WriteAllText(Path.Combine(interchange, "csharp-reference.json"), references);
            File.WriteAllText(Path.Combine(interchange, "csharp-plain.json"), plain);
        }
    }

    [Theory]
    [InlineData("\"sku_code\": \"B\"", "\"sku_code\": \"A\"")]
    [InlineData("\"sku_code\": \"B\"", "\"sku_code\": null")]
    [InlineData("\"part\": \"two\"", "\"part\": \"one\"")]
    [InlineData("\"part\": \"two\"", "\"part\": \"missing\"")]
    [InlineData("\"part\": \"two\"", "\"part\": null")]
    public void InvalidKeyedFieldsAreRejectedOnRead(string valid, string invalid)
    {
        string json = File.ReadAllText(Fixture);
        Assert.Throws<JsonException>(() =>
            ModelSnapshot.Read<KeyedInventory>(json.Replace(valid, invalid, StringComparison.Ordinal)));
    }

    private static KeyedInventory ReadFixture() => ModelSnapshot.Read<KeyedInventory>(File.ReadAllText(Fixture));

    private static void VerifyReference(string json)
    {
        var model = ModelSnapshot.Read<KeyedInventory>(json);
        VerifyProducts(model);
        Assert.Equal(2, model.Parts.Count);
        Assert.Equal(5, model.FindAssignmentsByPart(model.Parts[0])?.Count);
        Assert.Equal(6, model.FindAssignmentsByPart(model.Parts[1])?.Count);
        Assert.Same(model.Parts[0], model.Assignments[0].Part);
        Assert.Same(model.Parts[1], model.Assignments[1].Part);
    }

    private static void VerifyPlain(string json)
    {
        using var document = JsonDocument.Parse(json);
        Assert.Equal("A", document.RootElement.GetProperty("items")[0].GetProperty("sku_code").GetString());
        Assert.False(document.RootElement.TryGetProperty("@vmf-id", out _));
        var model = ModelSnapshot.ReadPlain<KeyedInventory>(json);
        VerifyProducts(model);
        Assert.Empty(model.Assignments);
        Assert.Empty(model.Parts);
    }

    private static void VerifyProducts(KeyedInventory model)
    {
        Assert.Equal(3, model.FindProductsBySku("A")?.Quantity);
        Assert.Equal(4, model.FindProductsBySku("B")?.Quantity);
    }
}
