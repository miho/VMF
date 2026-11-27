using Eu.Mihosoft.Vmftest.Getteronly;
using Eu.Mihosoft.Vmftest.Propertyinheritance;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class StockScalarGetterBuilderTests
{
    [Fact]
    public void MutableBuilderCanImportAnImmutableGetterTraitThroughTheExistingSetter()
    {
        IReadOnlyWithName source = ImmutableObj.NewBuilder().WithName("frozen").Build();
        var target = MutableObj.NewBuilder().WithName("prior").Build();
        var view = target.AsReadOnly();
        var changes = new List<string>();
        using var watch = target.VmfChanges.AddLocalListener(change => changes.Add(change.PropertyName));

        var staged = MutableObj.NewBuilder().ApplyFrom(source);
        Assert.Empty(changes);
        Assert.Equal("prior", view.Name);
        Assert.Equal("frozen", staged.Build().Name);
        staged.ApplyTo(target);
        Assert.Equal(["name"], changes);
        Assert.Equal("frozen", target.Name);
        Assert.Equal("frozen", view.Name);
        MutableObj.NewBuilder().ApplyFrom(target).ApplyTo(target);
        Assert.Equal(["name"], changes);
        MutableObj.NewBuilder().ApplyFrom(new MutableObj()).ApplyTo(target);
        Assert.Equal(["name", "name"], changes);
        Assert.Null(target.Name);
        Assert.Null(view.Name);
        Assert.Equal("frozen", source.Name);
        Assert.Null(typeof(IReadOnlyWithName).GetProperty(nameof(IReadOnlyWithName.Name))!.SetMethod);
    }

    [Fact]
    public void TwoScalarGetterRolesImportOnlyTheirOwnStagedValues()
    {
        IReadOnlyWithX x = LocationX.NewBuilder().WithX(12).Build();
        IReadOnlyWithY y = LocationY.NewBuilder().WithY(19).Build();
        var target = LocationXY.NewBuilder().WithX(3).WithY(4).Build();
        var view = target.AsReadOnly();
        var changes = new List<string>();
        using var watch = target.VmfChanges.AddLocalListener(change => changes.Add(change.PropertyName));

        var staged = LocationXY.NewBuilder().WithY(8).ApplyFrom(x);
        var built = staged.Build();
        Assert.Equal(12, built.X);
        Assert.Equal(8, built.Y);
        staged.ApplyFrom(y);
        Assert.Empty(changes);
        staged.ApplyTo(target);
        Assert.Equal(12, view.X);
        Assert.Equal(19, view.Y);
        Assert.Equal(["x", "y"], changes);
    }
}
