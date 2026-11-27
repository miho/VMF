using Eu.Mihosoft.Vmf.Conformance.Genericbounds;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class GenericRoleMetadataTests
{
    [Fact]
    public void OpenGenericContractsDiscoverTheirRegisteredClrApplications()
    {
        var catalog = ModelSnapshot.TypeCatalog;
        Type[] baseApplications = [typeof(MutableQualifiedLabel), typeof(MutableStringQualifiedView),
            typeof(StringQualifiedView), typeof(WorkingQualifiedLabel)];
        Assert.Equal(baseApplications, ClrTypes(typeof(QualifiedView<string>)));
        Assert.Equal(baseApplications.Take(2).Append(typeof(QualifiedView<>)).Concat(baseApplications.Skip(2)),
            ClrTypes(typeof(QualifiedView<>)));

        Type[] readOnlyApplications = [typeof(PermanentQualifiedLabel), typeof(StringQualifiedView), typeof(WorkingQualifiedLabel)];
        Assert.Equal(readOnlyApplications, ClrTypes(typeof(IReadOnlyQualifiedView<>)));
        Assert.Equal(readOnlyApplications, ClrTypes(typeof(IReadOnlyQualifiedView<string>)));
        Assert.Empty(ClrTypes(typeof(IReadOnlyQualifiedView<int>)));
        Assert.IsAssignableFrom<IReadOnlyQualifiedView<string>>(MutableQualifiedLabel.NewBuilder().Build().AsReadOnly());

        IEnumerable<Type> ClrTypes(Type contract) => catalog.TypesAssignableTo(contract).Select(metadata => metadata.RuntimeType);
    }

    [Fact]
    public void InheritedGenericFieldsReflectTheirClosedValuesThroughBothModelViews()
    {
        var catalog = ModelSnapshot.TypeCatalog;
        var role = catalog.Find(typeof(QualifiedView<>))!;
        var working = WorkingQualifiedLabel.NewBuilder().WithQualifier("working").Build();
        var permanent = PermanentQualifiedLabel.NewBuilder().WithQualifier("permanent").Build();
        foreach (var (instance, view, expected) in new (object, object, string)[]
        {
            (working, working.AsReadOnly(), "working"),
            (permanent, permanent.AsReadOnly(), "permanent")
        })
        {
            var metadata = catalog.TypeOf(instance)!;
            Assert.Same(metadata, catalog.TypeOf(view));
            Assert.True(catalog.IsSubtypeOf(metadata.Declaration, role.Declaration));
            var field = metadata.FindProperty("qualifier")!;
            Assert.Equal(role.Name, field.DeclaringType);
            Assert.Equal(ModelValueKind.String, field.ValueType.Kind);
            foreach (object model in new[] { instance, view })
            {
                var property = ModelSnapshot.Reflection.Reflect(model).FindProperty("qualifier")!;
                Assert.Equal(typeof(string), property.ValueType);
                Assert.Equal(expected, property.Get());
                Assert.Equal("none", property.AnnotationByKey("vmf:property:containment-info")?.Value);
            }
        }
    }
}
