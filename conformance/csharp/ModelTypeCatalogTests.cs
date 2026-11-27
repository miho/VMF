using Vmf.Runtime;
using Xunit;
using Hierarchy = Eu.Mihosoft.Vmf.Conformance.Hierarchy;
using Numeric = Eu.Mihosoft.Vmf.Conformance.Numeric;
using Values = Eu.Mihosoft.Vmf.Conformance.Values;

namespace Vmf.CSharp.Conformance;

public class ModelTypeCatalogTests
{
    [Fact]
    public void InspectEffectiveFieldsWithoutCreatingAnInstance()
    {
        var catalog = Values.ModelSnapshot.TypeCatalog;
        var type = catalog.Find(typeof(Values.DefaultSettings))!;
        Assert.Equal("eu.mihosoft.vmf.conformance.values.DefaultSettings", type.Name);
        Assert.Equal(new[] { "baseline", "entries", "flag", "limit", "mode", "name", "note", "ratio", "sequence",
            "stages", "unsetValue" }, type.Properties.Select(field => field.Name));
        var name = type.FindProperty("name")!;
        Assert.EndsWith(".DefaultBase", name.DeclaringType, StringComparison.Ordinal);
        Assert.Equal("display_name", name.WireName);
        Assert.Equal("idle\nready", name.DeclaredDefault?.GetString());
        Assert.Equal(new[] { "label", "text" }, name.Annotations.Where(annotation => annotation.Key == "role")
            .Select(annotation => annotation.Value));
        var inventory = catalog.Find(typeof(Values.KeyedInventory))!;
        Assert.Equal("sku", inventory.FindProperty("products")?.KeyPropertyName);
        Assert.Null(catalog.Find(typeof(Values.ArchiveNode))!.FindProperty("parent")?.WireName);
        Assert.True(catalog.Find(typeof(Values.ValueLabel))!.IsImmutable);
        Assert.Same(type, catalog.TypeOf(new Values.DefaultSettings().AsReadOnly()));
    }

    [Fact]
    public void GenericApplicationsSubstituteThroughIntermediateTypes()
    {
        var catalog = Numeric.ModelSnapshot.TypeCatalog;
        var root = catalog.Find(typeof(Numeric.NumericSeries<>))!;
        var doubles = catalog.Find(typeof(Numeric.DoubleSeries))!;
        var longs = catalog.Find(typeof(Numeric.LongSeries))!;
        Assert.True(root.IsInterfaceOnly);
        Assert.Same(root, catalog.Find(typeof(Numeric.NumericSeries<double>)));
        Assert.Equal("java.lang.Number", Assert.Single(Assert.Single(root.TypeParameters).UpperBounds).Name);
        Assert.Equal(ModelValueKind.Float64, Assert.Single(Assert.Single(doubles.DirectSupertypes).Arguments).Kind);
        Assert.True(catalog.IsSubtypeOf(doubles.Declaration, root.Declaration));
        var float64 = new ModelTypeUse(ModelValueKind.Float64, "float64");
        var closedRoot = new ModelTypeUse(ModelValueKind.Model, root.Name, arguments: [float64]);
        Assert.True(catalog.IsSubtypeOf(doubles.Declaration, closedRoot));
        Assert.False(catalog.IsSubtypeOf(longs.Declaration, closedRoot));
        Assert.False(catalog.IsSubtypeOf(closedRoot, root.Declaration));
    }

    [Fact]
    public void SecondaryTraitsAndAClosedDiamondShareTheSameDeclarations()
    {
        var catalog = Hierarchy.ModelSnapshot.TypeCatalog;
        var diamond = catalog.Find(typeof(Hierarchy.PartsCatalog))!;
        var root = catalog.Find(typeof(Hierarchy.PartGroup<>))!;
        var part = catalog.Find(typeof(Hierarchy.ConcretePart))!;
        var closedRoot = new ModelTypeUse(ModelValueKind.Model, root.Name, arguments: [part.Declaration]);
        Assert.True(catalog.IsSubtypeOf(diamond.Declaration, closedRoot));
        Assert.Equal(2, diamond.DirectSupertypes.Count);
        Assert.Single(diamond.Properties, property => property.Name == "parts");
        Assert.Equal(part.Name, diamond.FindProperty("parts")?.ValueType.Name);
        Assert.Contains(catalog.TypesAssignableTo(typeof(Hierarchy.IRatedItem)),
            type => type.RuntimeType == typeof(Hierarchy.CatalogItem));
    }
}
