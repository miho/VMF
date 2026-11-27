using Vmf.Runtime;
using Xunit;
using Core = Eu.Mihosoft.Vmftest.Staticreflection;
using Numeric = Eu.Mihosoft.Vmf.Conformance.Numeric;
using Plugin = Eu.Mihosoft.Vmftest.Tostring;

namespace Vmf.CSharp.Conformance;

public class StockModelRegistryTests
{
    [Fact]
    public void ComposesIndependentStockModelsAndResolvesTheirReadOnlyViews()
    {
        var core = Core.ModelSnapshot.TypeCatalog;
        var plugin = Plugin.ModelSnapshot.TypeCatalog;
        var root = new Core.Root();
        var child = new Plugin.Child();
        Assert.Null(core.TypeOf(child.AsReadOnly()));
        Assert.Null(plugin.TypeOf(root.AsReadOnly()));

        ModelTypeCatalog[] sources = [core, plugin];
        var composed = ModelTypeCatalog.Compose(sources);
        sources[0] = plugin;

        Assert.Equal(core.Types.Concat(plugin.Types).ToArray(), composed.Types);
        var childType = plugin.Find(typeof(Plugin.Child));
        Assert.Same(childType, composed.Find("eu.mihosoft.vmftest.tostring.Child"));
        Assert.Same(childType, composed.TypeOf(child));
        Assert.Same(childType, composed.TypeOf(child.AsReadOnly()));
        Assert.Same(core.Find(typeof(Core.Root)), composed.TypeOf(root.AsReadOnly()));
        Assert.Null(composed.TypeOf(new object()));
        Assert.Equal(new[] { typeof(Core.TypeB), typeof(Core.TypeC) },
            composed.TypesAssignableTo(typeof(Core.ITypeB)).Select(type => type.RuntimeType));

        var prop = Assert.Single(composed.Find(typeof(Core.Root))!.Properties);
        var concrete = composed.Find(prop.ValueType.Name)!;
        Assert.Equal(new[] { "TypeA", "TypeB" }, concrete.DirectSupertypes.Select(type => type.Name.Split('.').Last()));
        Assert.Equal(3, composed.Find(typeof(Core.TypeA))!.Properties.Count);
        Assert.Equal(3, composed.Find(typeof(Core.TypeB))!.Properties.Count);
        Assert.Equal(6, concrete.Properties.Count);
    }

    [Fact]
    public void RetainsTheFirstDescriptorAndRejectsConflictingDeclarationIdentity()
    {
        var core = Core.ModelSnapshot.TypeCatalog;
        var root = core.Find(typeof(Core.Root))!;
        var duplicate = new ModelTypeCatalog([Metadata(root.Name, root.RuntimeType)]);
        Assert.Same(root, ModelTypeCatalog.Compose(core, duplicate, core).Find(root.Name));

        var duplicateName = new ModelTypeCatalog([Metadata(root.Name, typeof(Plugin.Child))]);
        Assert.Throws<ArgumentException>(() => ModelTypeCatalog.Compose(core, duplicateName));
        var duplicateRuntimeType = new ModelTypeCatalog([Metadata("fixture.OtherRoot", root.RuntimeType)]);
        Assert.Throws<ArgumentException>(() => ModelTypeCatalog.Compose(core, duplicateRuntimeType));
        Assert.Empty(ModelTypeCatalog.Compose().Types);
    }

    [Fact]
    public void PreservesOpenGenericAndInterfaceOnlyDeclarations()
    {
        var core = Core.ModelSnapshot.TypeCatalog;
        var generic = Numeric.ModelSnapshot.TypeCatalog;
        var composed = ModelTypeCatalog.Compose(core, ModelTypeCatalog.Compose(generic, core));
        var number = generic.Find(typeof(Numeric.NumericSeries<>))!;
        var doubles = composed.Find(typeof(Numeric.DoubleSeries))!;
        Assert.True(number.IsInterfaceOnly);
        Assert.Same(number, composed.Find(typeof(Numeric.NumericSeries<double>)));
        Assert.True(composed.IsSubtypeOf(doubles.Declaration, number.Declaration));
        Assert.Equal(core.Types.Count + generic.Types.Count, composed.Types.Count);
    }

    private static ModelTypeMetadata Metadata(string name, Type runtimeType) =>
        new(name, name, runtimeType, interfaceOnly: false, immutable: false,
            typeParameters: [], directSupertypes: [], properties: [], annotations: []);
}
