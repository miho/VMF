using Eu.Mihosoft.Vmftest.Managedmap;
using Eu.Mihosoft.Vmftest.Multiplicity;
using Maps = Eu.Mihosoft.Vmftest.Managedmap.ModelSnapshot;
using Membership = Eu.Mihosoft.Vmftest.Multiplicity.ModelSnapshot;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class StockCollectionDefaultTests
{
    [Fact]
    public void InheritedModelUsesItsOwnMapDefaultAcrossConstructionAndReset()
    {
        var settings = new ManagedSettings();
        Assert.Equal(80, settings.Ports["port"]);
        var storage = settings.Ports;
        var field = Maps.Reflection.Reflect(settings).FindProperty("ports")!;
        Assert.False(field.IsSet);
        settings.Ports.Put("port", 443);
        Assert.True(field.IsSet);
        field.Reset();
        Assert.Equal(80, settings.Ports["port"]);
        Assert.Same(storage, settings.Ports);
        Assert.Empty(settings.Scores);

        var omitted = Maps.ReadPlain<ManagedSettings>("{}");
        Assert.Equal(80, omitted.Ports["port"]);
        var empty = ManagedSettings.NewBuilder().WithPorts([]).Build();
        Assert.Empty(Maps.ReadPlain<ManagedSettings>(Maps.WritePlain(empty)).Ports);
    }

    [Fact]
    public void UnchangedJavaSetAndOrderedSetDeclarationsUseTheirLiteralDefaults()
    {
        var sampled = new DefaultGroups();
        Assert.Equal("x", Assert.Single(sampled.Tags));
        Assert.Equal(["a", "b"], sampled.Stages);
        var state = Membership.Reflection.Reflect(sampled);
        sampled.Tags.Clear();
        sampled.Stages.Clear();
        var explicitEmpty = Membership.ReadPlain<DefaultGroups>(Membership.WritePlain(sampled));
        Assert.Empty(explicitEmpty.Tags);
        Assert.Empty(explicitEmpty.Stages);
        state.FindProperty("tags")!.Reset();
        state.FindProperty("stages")!.Reset();
        Assert.Equal("x", Assert.Single(sampled.Tags));
        Assert.Equal(["a", "b"], sampled.Stages);
    }
}
