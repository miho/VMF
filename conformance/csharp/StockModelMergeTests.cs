using Xunit;
using Core = Eu.Mihosoft.Vmftest.Staticreflection;
using Values = Eu.Mihosoft.Vmf.Conformance.Values;

namespace Vmf.CSharp.Conformance;

public class StockModelMergeTests
{
    [Fact]
    public void MergesStockSecondaryBaseFieldsIntoTheConcreteTemplate()
    {
        var template = new Core.TypeC { Name = "template", PropA1 = 17, PropB1 = 2.5 };
        var incoming = new Core.TypeA { PropA2 = "reference" };
        var overlay = new Core.TypeB { PropB1 = 25, PropB2 = incoming };
        var reflection = Core.ModelSnapshot.Reflection;

        var merged = Assert.IsType<Core.TypeC>(reflection.Merge<Core.Root>(template, overlay));
        Assert.NotSame(template, merged);
        Assert.Equal("template", merged.Name);
        Assert.Equal(17, merged.PropA1);
        Assert.Equal(25, merged.PropB1);
        Assert.NotSame(incoming, merged.PropB2);
        Assert.Equal("reference", merged.PropB2?.PropA2);
        Assert.Equal(2.5, template.PropB1);
        Assert.Null(template.PropB2);
        Assert.Same(incoming, overlay.PropB2);
        Assert.Throws<ArgumentException>(() => reflection.Diff(template, overlay));
    }

    [Fact]
    public void IgnoresOverlayOnlyFieldsAndRetainsDefaultsOnTheTemplate()
    {
        var template = new Core.TypeA { PropA1 = 7, PropA2 = "retained" };
        var overlay = new Core.TypeC { Name = "other type", PropA1 = 17, PropB1 = 2.5 };
        var reflection = Core.ModelSnapshot.Reflection;

        var narrow = Assert.IsType<Core.TypeA>(reflection.Merge(template, overlay));
        Assert.NotSame(template, narrow);
        Assert.Equal(17, narrow.PropA1);
        Assert.Equal("retained", narrow.PropA2);
        Assert.Equal(7, template.PropA1);

        var fallback = new Core.TypeB();
        var field = reflection.Reflect(fallback).FindProperty("propB1")!;
        field.SetDefault(42.0);
        Assert.False(field.IsSet);
        var merged = Assert.IsType<Core.TypeC>(reflection.Merge<Core.Root>(overlay, fallback));
        Assert.Equal(2.5, merged.PropB1);
        Assert.Equal(42, fallback.PropB1);
    }

    [Fact]
    public void AReferencedOverlayRootRetainsItsOwnConcreteTypeAndCycle()
    {
        var template = new Core.TypeA();
        var overlay = new Core.TypeC { Name = "reference payload", PropA1 = 9 };
        overlay.Prop = overlay;
        var reflection = Core.ModelSnapshot.Reflection;

        var merged = reflection.Merge(template, overlay);
        var member = Assert.IsType<Core.TypeC>(merged.Prop);
        Assert.NotSame(overlay, member);
        Assert.Same(member, member.Prop);
        Assert.Equal("reference payload", member.Name);
        Assert.Equal(9, merged.PropA1);
        Assert.Same(overlay, overlay.Prop);
        Assert.Null(template.Prop);
    }

    [Fact]
    public void RejectsAnIncompatibleSharedFieldWithoutChangingEitherGraph()
    {
        var template = new Values.DefaultSettings();
        var entry = new Values.ScalarEntry();
        var overlay = new Values.ScalarBatch();
        overlay.Entries.Add(entry);
        var reflection = Values.ModelSnapshot.Reflection;

        var failure = Assert.Throws<ArgumentException>(() => reflection.Merge<object>(template, overlay));
        Assert.Contains("entries", failure.Message);
        Assert.Empty(template.Entries);
        Assert.Same(entry, Assert.Single(overlay.Entries));
    }
}
