using Eu.Mihosoft.Vmf.Conformance.Traits;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class KeyedBuilderCallbackTests
{
    [Fact]
    public void EarlierOwnershipCallbackCanRepairALaterStagedKey()
    {
        var anchor = new RoleKey();
        var owner = KeyRepairWorkspace.NewBuilder().WithEarlier([anchor]).Build();
        var first = KeyRepairItem.NewBuilder().WithKey("same");
        var second = KeyRepairItem.NewBuilder().WithKey("same");
        var staged = KeyRepairWorkspace.NewBuilder().WithEarlier([anchor]).WithLaterBuilders([first, second]);
        int departures = 0;
        using var observe = owner.VmfChanges.AddPropertyListener("earlier", _ =>
        {
            Assert.Empty(owner.Earlier);
            second.WithKey("fixed");
            departures++;
        });

        var built = staged.Build();
        Assert.Equal(1, departures);
        Assert.Same(anchor, Assert.Single(built.Earlier));
        Assert.Same(built.Later[0], built.FindLaterByKey("same"));
        Assert.Same(built.Later[1], built.FindLaterByKey("fixed"));
    }
}
