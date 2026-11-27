using Eu.Mihosoft.Vmftest.Propertyinheritance;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class StockPropertyInheritanceTests
{
    [Fact]
    public void InheritedPrimitiveDefaultsUseTheDeclaredTypeOnBothViews()
    {
        var point = LocationXY.NewBuilder().WithY(4).Build();
        foreach (object model in new object[] { point, point.AsReadOnly() })
        {
            var reflection = ModelSnapshot.Reflection.Reflect(model);
            var x = reflection.FindProperty("x")!;
            var y = reflection.FindProperty("y")!;
            Assert.Equal(0d, Assert.IsType<double>(x.DefaultValue));
            Assert.Equal(0d, Assert.IsType<double>(y.DefaultValue));
            Assert.False(x.IsSet);
            Assert.True(y.IsSet);
        }
    }

    [Fact]
    public void StockCovariantLocationBranchesUseOneLiveSlot()
    {
        var point = LocationXY.NewBuilder().WithX(3).WithY(4).Build();
        var code = GCode1.NewBuilder().WithLocation(point).Build();
        IReadOnlyWithLocationXY view = code.AsReadOnly();
        IReadOnlyWithLocationX left = code;
        IReadOnlyWithLocationY right = code;
        IReadOnlyWithLocation root = code;
        Assert.Same(point, code.Location);
        Assert.Same(point.AsReadOnly(), left.Location);
        Assert.Same(point.AsReadOnly(), right.Location);
        Assert.Same(point.AsReadOnly(), root.Location);
        Assert.Same(view.Location, ((IReadOnlyWithLocationX)view).Location);
        Assert.Same(view.Location, ((IReadOnlyWithLocationY)view).Location);
        Assert.Same(view.Location, ((IReadOnlyWithLocation)view).Location);

        var field = Assert.Single(ModelSnapshot.Reflection.Reflect(code).Properties);
        Assert.Equal("location", field.Name);
        Assert.Equal(typeof(LocationXY), field.ValueType);
        Assert.Same(point, field.Get());
        var changes = new List<ModelChange>();
        using var watch = code.VmfChanges.AddPropertyListener("location", changes.Add);
        field.Set(null);
        Assert.Null(left.Location);
        Assert.Null(right.Location);
        Assert.Null(root.Location);
        Assert.Null(view.Location);
        field.Set(point);
        Assert.Same(point.AsReadOnly(), view.Location);
        Assert.Equal(2, changes.Count);
        Assert.All(changes, change =>
        {
            Assert.Same(code, change.Source);
            Assert.Equal(ModelChangeKind.Property, change.Kind);
        });
        Assert.Same(point, new GCode2 { Location = point }.Location);
        Assert.Same(point, new GCode3 { Location = point }.Location);
        Assert.Same(point, new GCode4 { Location = point }.Location);
    }
}
