using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class DeepRoleTests
{
    [Fact]
    public void DeepPrimaryAndSecondaryRolesUseTheirActualOwnerAndSelectedProvider()
    {
        DeepRoleState.Hooks.Clear();
        DeepRoleState.Owners.Clear();
        DeepRoleState.RootCreated = DeepRoleState.ConfigureCreated = DeepRoleState.SpecialCreated = DeepRoleState.PeerCreated = 0;
        var deep = new SpecializedWins();
        var peer = new PeerWins();
        var labeled = new LabeledWins();
        var peerLabel = new PeerWithLabel();
        Assert.Equal(["specialized:0", "peer:0", "configured:0", "peer:0"], DeepRoleState.Hooks);
        Assert.Collection(DeepRoleState.Owners, owner => Assert.Same(deep, owner),
            owner => Assert.Same(peer, owner), owner => Assert.Same(labeled, owner),
            owner => Assert.Same(peerLabel, owner));
        Assert.Equal((11, 22, 33, 44, 55), (deep.RootValue, deep.Setting, deep.Label, deep.Specialty, deep.PeerValue));
        Assert.Equal((0, 1, 1, 2), (DeepRoleState.RootCreated, DeepRoleState.ConfigureCreated,
            DeepRoleState.SpecialCreated, DeepRoleState.PeerCreated));

        Assert.Equal(402, ((IRootService)deep).Choose(1));
        Assert.Equal(403, ((IPeerService)deep).Choose(1));
        Assert.Equal(2, deep.SpecializedCalls());
        Assert.Equal(0, deep.RootCalls());
        Assert.Equal(302, ((ISpecializedService)peer).Choose(1));
        Assert.Equal(303, ((ILabeledService)peer).Choose(1));
        Assert.Equal(2, peer.PeerCalls());
        Assert.Equal(0, peer.SpecializedCalls());
        Assert.Equal(204, ((IConfiguredService)peer).Tune(3));
        Assert.Equal(1, ((ILabeledService)peer).ConfiguredCalls());
        Assert.Equal(2, DeepRoleState.ConfigureCreated);
        Assert.Equal(102, ((IPeerService)labeled).Choose(1));
        Assert.Equal(302, ((ILabeledService)peerLabel).Choose(1));

        peer.ReplaceBehavior(new Replacement(peer));
        Assert.Equal(901, ((ISpecializedService)peer).Choose(1));
        Assert.Equal(902, ((IRootService)peer).Choose(2));
        Assert.Equal(803, ((IConfiguredService)peer).Tune(3));
        Assert.Equal(2, deep.SpecializedCalls());

        var archive = new DeepRoleArchive { Selected = peer };
        archive.Items.AddRange([deep, peer, labeled, peerLabel]);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR_HIERARCHY");
        foreach (bool references in new[] { false, true })
        {
            string suffix = $"-deep-roles-{(references ? "true" : "false")}.json";
            string wire = references ? ModelSnapshot.Write(archive) : ModelSnapshot.WriteWithoutReferences(archive);
            if (exchange is not null)
            {
                string java = File.ReadAllText(Path.Combine(exchange, "java" + suffix));
                Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(wire)));
                File.WriteAllText(Path.Combine(exchange, "csharp" + suffix), wire);
                wire = java;
            }
            var restored = references ? ModelSnapshot.Read<DeepRoleArchive>(wire)
                : ModelSnapshot.ReadWithoutReferences<DeepRoleArchive>(wire);
            Assert.Equal(402, Assert.IsType<SpecializedWins>(restored.Items[0]).Choose(1));
            Assert.Equal(302, Assert.IsType<PeerWins>(restored.Items[1]).Choose(1));
            Assert.Equal(102, Assert.IsType<LabeledWins>(restored.Items[2]).Choose(1));
            Assert.Equal(302, Assert.IsType<PeerWithLabel>(restored.Items[3]).Choose(1));
            if (references) Assert.Same(restored.Items[1], restored.Selected);
            else Assert.Null(restored.Selected);
        }
    }

    private sealed class Replacement(PeerWins expected) : PeerWins.Behavior
    {
        public int Choose(PeerWins caller, int value) => Result(caller, 900 + value);
        public int Tune(IConfiguredService caller, int value) => Result(caller, 800 + value);
        public int RootCalls(IRootService caller) => Result(caller, -1);
        public int ConfiguredCalls(IConfiguredService caller) => Result(caller, -2);
        public int SpecializedCalls(ISpecializedService caller) => Result(caller, -3);
        public int PeerCalls(IPeerService caller) => Result(caller, -4);

        private int Result(object caller, int value)
        {
            Assert.Same(expected, caller);
            return value;
        }
    }
}
