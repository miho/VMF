using Stock = Eu.Mihosoft.Vmftest.Complex.Unparsermodel;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class StockUnparserMarkerTests
{
    [Fact]
    public void StockMarkerSharesTheImmutableValueAndMutableViewContracts()
    {
        var type = Stock.Type.NewBuilder().WithName("text").WithPackageName("grammar").Build();
        var rule = Stock.RuleClass.NewBuilder().WithName("rule").Build();
        Stock.IReadOnlyLangElement immutableMarker = type;
        Stock.IReadOnlyLangElement mutableMarker = rule;
        Assert.Same(type, immutableMarker);
        Assert.Same(immutableMarker, type.AsReadOnly());
        Assert.Same(rule, mutableMarker);
        var view = Assert.IsAssignableFrom<Stock.IReadOnlyLangElement>(rule.AsReadOnly());
        Assert.NotSame(rule, view);
        rule.Name = "updated";
        Assert.Equal("updated", Assert.IsAssignableFrom<Stock.IReadOnlyWithName>(view).Name);

        var implementations = Stock.ModelSnapshot.TypeCatalog.TypesAssignableTo(typeof(Stock.IReadOnlyLangElement));
        Assert.Contains(implementations, item => item.RuntimeType == typeof(Stock.Type));
        Assert.Contains(implementations, item => item.RuntimeType == typeof(Stock.RuleClass));

        var restored = Stock.ModelSnapshot.ReadWithoutReferences<Stock.Type>(
            Stock.ModelSnapshot.WriteWithoutReferences(type));
        Assert.Equal("text", restored.Name);
        Assert.Equal("grammar", restored.PackageName);
        Assert.Same(restored, restored.AsReadOnly());
    }
}
