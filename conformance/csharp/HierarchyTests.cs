using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Hierarchy;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class HierarchyTests
{
    [Fact]
    public void MutableTraitViewsShareStorageAndKeyChecks()
    {
        var catalog = NewCatalog();
        VerifyTraits(catalog);
    }

    [Fact]
    public void GenericDiamondClosesOneSlotPerLogicalProperty()
    {
        var model = NewParts();
        VerifyDiamond(model);
    }

    [Fact]
    public void OpenOwningDiamondKeepsOneTrackedStorageSlotInBothPositions()
    {
        var motor = new ConcretePart { Name = "motor", Number = 101 };
        var drive = new ConcretePart { Name = "drive", Number = 112 };
        var source = OpenPartsCatalog.NewBuilder().WithHighlighted(true).WithSection("north")
            .WithParts([motor, drive]).WithFeatured(drive).Build();
        string document = ModelSnapshot.Write(source);
        Verify(document);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_HIERARCHY") is { } interchange)
        {
            Verify(File.ReadAllText(Path.Combine(interchange, "java-open-diamond.json")));
            File.WriteAllText(Path.Combine(interchange, "csharp-open-diamond.json"), document);
        }

        static void Verify(string wire)
        {
            var catalog = ModelSnapshot.Read<OpenPartsCatalog>(wire);
            OpenParts<ConcretePart> open = catalog;
            ILeftParts<ConcretePart> left = open;
            IRightParts<ConcretePart> right = open;
            IReadOnlyLeftParts<IReadOnlyConcretePart> leftView = catalog.AsReadOnly();
            IReadOnlyRightParts<IReadOnlyConcretePart> rightView = catalog.AsReadOnly();
            Assert.Same(left.Parts, right.Parts);
            Assert.Same(leftView, rightView);
            Assert.Same(leftView.Parts, rightView.Parts);
            Assert.Equal([101, 112], leftView.Parts.Select(part => part.Number));
            Assert.Same(left.Parts[1], right.Featured);
            var moved = left.Parts[0];
            Assert.False(right.Parts.Add(moved));

            var workbench = PartsWorkbench.NewBuilder().WithLabel("transfer").Build();
            IOpenParts<ConcretePart> receiver = workbench;
            Assert.True(receiver.Parts.Add(moved));
            Assert.Same(moved, Assert.Single(receiver.Parts));
            Assert.Single(left.Parts);
            Assert.Single(leftView.Parts);
            Assert.Same(workbench.AsReadOnly(), receiver.AsReadOnly());

            right.Featured = null;
            Assert.Null(left.Featured);
            Assert.Null(leftView.Featured);
            Assert.Equal(["featured", "highlighted", "parts", "section"],
                ModelSnapshot.Reflection.Reflect(catalog).Properties.Select(property => property.Name));
        }
    }

    [Fact]
    public void OpenCompositeKeepsTypedSecondaryAliasesAndNamedReadOnlyProjection()
    {
        var item = new ChoiceItem { Name = "scope" };
        var closed = ItemSelection.NewBuilder().WithVersion(4).WithSelected(item).Build();
        OpenChoice<ChoiceItem> open = closed;
        ISecondaryChoice<ChoiceItem> secondary = open;
        VersionedChoice primary = open;
        IReadOnlyOpenChoice<IReadOnlyChoiceItem> openView = open.AsReadOnly();
        Assert.Same(openView, secondary.AsReadOnly());
        Assert.Same(item, secondary.Selected);
        Assert.Equal(4, primary.Version);
        var next = new ChoiceItem { Name = "next" };
        secondary.Selected = next;
        primary.Version = 7;
        Assert.Same(next, open.Selected);
        Assert.Same(next.AsReadOnly(), openView.Selected);
        Assert.Equal(7, openView.Version);

        var ranked = new RankedChoice { Name = "copper", Rank = 13 };
        var selection = RankedSelection.NewBuilder().WithVersion(9).WithLocked(true)
            .WithEntry(ranked).WithSelected(ranked).Build();
        string wire = ModelSnapshot.Write(selection);
        CheckNamed(wire);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_HIERARCHY") is { } interchange)
        {
            CheckNamed(File.ReadAllText(Path.Combine(interchange, "java-open-choice.json")));
            File.WriteAllText(Path.Combine(interchange, "csharp-open-choice.json"), wire);
        }

        static void CheckNamed(string document)
        {
            using var json = JsonDocument.Parse(document);
            Assert.True(json.RootElement.TryGetProperty("chosen", out _));
            Assert.False(json.RootElement.TryGetProperty("selected", out _));
            var restored = ModelSnapshot.Read<RankedSelection>(document);
            OpenChoice<RankedChoice> named = restored;
            ISecondaryChoice<RankedChoice> namedSecondary = named;
            IReadOnlySecondaryChoice<IReadOnlyRankedChoice> namedView = restored.AsReadOnly();
            Assert.Equal(9, named.Version);
            Assert.True(namedSecondary.Locked);
            Assert.Same(restored.Entry, namedSecondary.Selected);
            Assert.Same(namedSecondary.AsReadOnly(), namedView);
            Assert.Same(restored.Entry!.AsReadOnly(), namedView.Selected);
            Assert.Equal(13, namedView.Selected!.Rank);
            Assert.Equal(typeof(RankedChoice), ModelSnapshot.Reflection.Reflect(restored).FindProperty("selected")!.ValueType);
            namedSecondary.Selected = null;
            Assert.Null(restored.Selected);
            Assert.Null(namedView.Selected);
            Assert.Equal(13, restored.Entry!.Rank);
        }
    }

    [Fact]
    public void TwoOpenGenericRolesKeepIndependentTypesAndOneProjection()
    {
        var choice = new RankedChoice { Rank = 17 };
        var tool = new CalibratedTool { Size = 3, Calibration = 8 };
        var named = RankedDualChoice.NewBuilder().WithSelected(choice).WithBackup(tool).Build();
        DualChoice<RankedChoice, CalibratedTool> open = named;
        ISecondaryChoice<RankedChoice> primary = open;
        IToolBackup<CalibratedTool> secondary = open;
        IReadOnlySecondaryChoice<IReadOnlyRankedChoice> primaryView = named.AsReadOnly();
        IReadOnlyToolBackup<IReadOnlyCalibratedTool> secondaryView = named.AsReadOnly();
        Assert.Same(choice, primary.Selected);
        Assert.Same(tool, secondary.Backup);
        Assert.Same(primaryView, secondaryView);
        Assert.Equal(17, primaryView.Selected!.Rank);
        Assert.Equal(8, secondaryView.Backup!.Calibration);
        secondary.Backup = null;
        Assert.Null(open.Backup);
        Assert.Null(secondaryView.Backup);
        Assert.Same(choice, primary.Selected);
    }

    [Fact]
    public void OpenGenericSecondaryReferenceListKeepsNullableAliasedSlots()
    {
        var choice = new RankedChoice { Name = "gauge", Rank = 17 };
        var queue = RankedChoiceQueue.NewBuilder().WithVersion(5).WithEntry(choice).Build();
        queue.Queue.AddRange([choice, null, choice]);
        string wire = ModelSnapshot.Write(queue);
        Verify(wire);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_HIERARCHY") is { } interchange)
        {
            Verify(File.ReadAllText(Path.Combine(interchange, "java-choice-queue.json")));
            File.WriteAllText(Path.Combine(interchange, "csharp-choice-queue.json"), wire);
        }

        static void Verify(string document)
        {
            var restored = ModelSnapshot.Read<RankedChoiceQueue>(document);
            OpenChoiceQueue<RankedChoice> open = restored;
            IChoiceQueue<RankedChoice> secondary = open;
            IReadOnlyChoiceQueue<IReadOnlyRankedChoice> view = restored.AsReadOnly();
            var slots = view.Queue;
            var choice = restored.Entry!;
            Assert.Equal(5, open.Version);
            Assert.Same(open.Queue, secondary.Queue);
            Assert.Same(secondary.AsReadOnly(), view);
            Assert.Same(choice, secondary.Queue[0]);
            Assert.Same(choice, secondary.Queue[2]);
            Assert.Equal(3, slots.Count);
            Assert.Same(choice.AsReadOnly(), slots[0]);
            Assert.Null(slots[1]);
            Assert.Same(slots[0], slots[2]);
            Assert.Equal(17, slots[0]!.Rank);
            secondary.Queue[1] = choice;
            Assert.Same(slots[0], slots[1]);
            Assert.All(open.Queue, value => Assert.Same(choice, value));
        }
    }

    [Fact]
    public void JavaAndCSharpExchangeTraitContainmentAndDiamondAliases()
    {
        string traits = ModelSnapshot.WriteWithoutReferences(NewCatalog());
        var selected = NewCatalog();
        selected.Selected = selected.Items[0];
        string traitReferences = ModelSnapshot.Write(selected);
        string diamond = ModelSnapshot.Write(NewParts());
        VerifyTraitsWire(traits);
        VerifyTraitReferences(traitReferences);
        VerifyDiamond(ModelSnapshot.Read<PartsCatalog>(diamond));
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_HIERARCHY") is { } interchange)
        {
            VerifyTraitsWire(File.ReadAllText(Path.Combine(interchange, "java-traits.json")));
            VerifyTraitReferences(File.ReadAllText(Path.Combine(interchange, "java-trait-references.json")));
            VerifyDiamond(ModelSnapshot.Read<PartsCatalog>(File.ReadAllText(Path.Combine(interchange, "java-diamond.json"))));
            File.WriteAllText(Path.Combine(interchange, "csharp-traits.json"), traits);
            File.WriteAllText(Path.Combine(interchange, "csharp-trait-references.json"), traitReferences);
            File.WriteAllText(Path.Combine(interchange, "csharp-diamond.json"), diamond);
        }
    }

    private static TraitCatalog NewCatalog() => TraitCatalog.NewBuilder().WithTitle("stock").WithItems([
        CatalogItem.NewBuilder().WithName("gear").WithCode("G-1").WithRating(3).Build(),
        CatalogItem.NewBuilder().WithName("bolt").WithCode("B-2").WithRating(4).Build()
    ]).Build();

    private static PartsCatalog NewParts()
    {
        var motor = ConcretePart.NewBuilder().WithName("motor").WithNumber(101).Build();
        var drive = ConcretePart.NewBuilder().WithName("drive").WithNumber(112).Build();
        return PartsCatalog.NewBuilder().WithHighlighted(true).WithSection("north")
            .WithParts([motor, drive]).WithFeatured(drive).Build();
    }

    private static void VerifyTraitsWire(string json)
    {
        using var document = JsonDocument.Parse(json);
        var first = document.RootElement.GetProperty("items")[0];
        Assert.Equal("gear", first.GetProperty("label").GetString());
        Assert.False(first.TryGetProperty("name", out _));
        VerifyTraits(ModelSnapshot.ReadWithoutReferences<TraitCatalog>(json));
    }

    private static void VerifyTraitReferences(string json)
    {
        var restored = ModelSnapshot.Read<TraitCatalog>(json);
        IItemIndex view = restored;
        IRatedItem selected = Assert.IsAssignableFrom<IRatedItem>(view.Selected);
        Assert.Same(view.Items[0], selected);
        Assert.Same(view.AsReadOnly().Items[0], view.AsReadOnly().Selected);
        selected.Rating = 7;
        Assert.Equal(7, view.Items[0].Rating);
        Assert.Equal(typeof(IRatedItem), ModelSnapshot.Reflection.Reflect(restored).FindProperty("selected")!.ValueType);
    }

    private static void VerifyTraits(TraitCatalog catalog)
    {
        CatalogLabel label = catalog;
        IItemIndex index = catalog;
        Assert.Equal("stock", label.Title);
        Assert.Same(catalog.Items, index.Items);
        label.Title = "updated stock";
        Assert.Equal("updated stock", catalog.Title);
        var first = Assert.IsType<CatalogItem>(index.FindItemsByName("gear"));
        var second = Assert.IsType<CatalogItem>(index.FindItemsByName("bolt"));
        Assert.Same(first, index.Items[0]);
        Assert.Same(second, index.Items[1]);
        Assert.Equal("G-1", first.Code);
        Assert.Equal(3, first.Rating);
        Assert.Equal("B-2", second.Code);
        Assert.Equal(4, second.Rating);
        IReadOnlyItemIndex indexView = index.AsReadOnly();
        Assert.Same(catalog.AsReadOnly(), indexView);
        Assert.Same(first.AsReadOnly(), indexView.FindItemsByName("gear"));

        NamedItem named = first;
        IRatedItem rated = first;
        IReadOnlyRatedItem ratedView = rated.AsReadOnly();
        int notifications = 0;
        using var subscription = rated.VmfChanges.AddLocalListener(edit =>
        {
            Assert.Same(first, edit.Source);
            Assert.Equal("name", edit.PropertyName);
            notifications++;
        });
        named.Name = "spindle";
        Assert.Equal("spindle", rated.Name);
        Assert.Equal("spindle", ratedView.Name);
        rated.Name = "wheel";
        Assert.Equal("wheel", named.Name);
        Assert.Same(first, index.FindItemsByName("wheel"));
        Assert.Throws<ArgumentException>(() => rated.Name = "bolt");
        Assert.Throws<ArgumentException>(() => named.Name = null);
        Assert.Equal(2, notifications);
        Assert.Equal("wheel", first.Name);
        Assert.Equal("wheel", first.AsReadOnly().Name);
        Assert.Equal(["code", "name", "rating"], ModelSnapshot.Reflection.Reflect(first).Properties.Select(p => p.Name));
    }

    private static void VerifyDiamond(PartsCatalog catalog)
    {
        LeftParts<ConcretePart> left = catalog;
        IRightParts<ConcretePart> right = catalog;
        Assert.Same(left.Parts, right.Parts);
        Assert.True(left.Highlighted);
        Assert.Equal("north", right.Section);
        Assert.Equal([101, 112], left.Parts.Select(part => part.Number));
        Assert.Equal(["motor", "drive"], right.Parts.Select(part => part.Name));
        Assert.Same(left.Parts[1], right.Featured);
        var readOnly = catalog.AsReadOnly();
        IReadOnlyLeftParts<IReadOnlyConcretePart> leftView = readOnly;
        IReadOnlyRightParts<IReadOnlyConcretePart> rightView = readOnly;
        Assert.Same(readOnly, right.AsReadOnly());
        right.Featured = left.Parts[0];
        Assert.Same(right.Featured, left.Featured);
        Assert.Same(leftView.Featured, rightView.Parts[0]);
        Assert.Same(leftView.Parts, rightView.Parts);
        Assert.Same(readOnly.Parts, leftView.Parts);
        Assert.Empty(readOnly.Validate());
        var props = ModelSnapshot.Reflection.Reflect(catalog).Properties;
        Assert.Equal(["featured", "highlighted", "parts", "section"], props.Select(property => property.Name));
        Assert.Equal(typeof(ConcretePart), Assert.Single(props, property => property.Name == "parts").ValueType);
    }
}
