using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class SecondaryBehaviorTests
{
    [Fact]
    public void ComposedMethodsRetainPerOwnerBindingsAndExchangeModeledState()
    {
        PoweredCell.HookTrace.Clear();
        PoweredCell.BindingCount = 0;
        MeteredCell.HookTrace.Clear();
        MeteredCell.BindingCount = 0;
        SpecialCell.SpecialHookTrace.Clear();

        var independent = new MeteredCell();
        var composed = new ComposedCell { Energy = 12, Weight = 9, Name = "paired" };
        var special = new SpecialCell { Energy = 14, Weight = 8, Name = "special" };
        var standalone = new StandaloneMeter { Voltage = 17, Weight = 6 };
        Assert.Equal(["composed:0:0"], PoweredCell.HookTrace);
        Assert.Equal(1, PoweredCell.BindingCount);
        Assert.Equal(["metered:0", "standalone:0:0"], MeteredCell.HookTrace);
        Assert.Equal(2, MeteredCell.BindingCount);
        Assert.Equal(["0:0"], SpecialCell.SpecialHookTrace);

        IMeteredCell inherited = composed;
        IMeteredCell overridden = special;
        Assert.True(inherited.CallerIs(inherited));
        Assert.False(inherited.CallerIs(independent));
        Assert.Equal(12, inherited.Advance(2));
        Assert.Equal(1, inherited.Calls());
        Assert.Equal(13, composed.Advance(2));

        Assert.Equal(110, overridden.Advance(2));
        Assert.Equal(0, overridden.Calls());
        Assert.True(overridden.CallerIs(overridden));
        Assert.Equal(0, independent.Calls());
        Assert.Equal(9, standalone.Advance(2));
        Assert.Equal(1, standalone.Calls());
        Assert.Equal(4, MeteredCell.BindingCount);
        Assert.Equal(2, MeteredCell.HookTrace.Count);

        PoweredCell primary = composed;
        Assert.Throws<ArgumentException>(() => primary.ReplaceBehavior(new PrimaryReplacement()));
        composed.ReplaceBehavior(new ComposedReplacement());
        Assert.Equal(202, inherited.Advance(2));
        Assert.Equal(99, inherited.Calls());
        Assert.True(inherited.CallerIs(inherited));
        Assert.Equal(502, primary.Amplify(2));
        Assert.Equal(1, standalone.Calls());
        Assert.Null(typeof(IReadOnlyMeteredCell).GetMethod("Advance"));

        var fleet = new SecondaryBehaviorFleet { Standalone = standalone, Mirror = composed };
        fleet.Cells.AddRange([composed, special]);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR_HIERARCHY");
        foreach (bool references in new[] { true, false })
        {
            string suffix = references ? "-secondary-behavior-reference.json" : "-secondary-behavior-by-value.json";
            string wire = references ? ModelSnapshot.Write(fleet) : ModelSnapshot.WriteWithoutReferences(fleet);
            if (exchange is not null)
            {
                string java = File.ReadAllText(Path.Combine(exchange, "java" + suffix));
                Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(wire)));
                File.WriteAllText(Path.Combine(exchange, "csharp" + suffix), wire);
                wire = java;
            }
            var restored = references ? ModelSnapshot.Read<SecondaryBehaviorFleet>(wire)
                : ModelSnapshot.ReadWithoutReferences<SecondaryBehaviorFleet>(wire);
            var copy = Assert.IsType<ComposedCell>(restored.Cells[0]);
            var specialCopy = Assert.IsType<SpecialCell>(restored.Cells[1]);
            if (references) Assert.Same(copy, restored.Mirror);
            else Assert.Null(restored.Mirror);
            Assert.Equal("paired", copy.Name);
            Assert.Equal(9, copy.Weight);
            Assert.Equal(0, copy.Calls());
            Assert.Equal(12, copy.Advance(2));
            Assert.Equal(24, copy.Amplify(2));
            Assert.True(copy.CallerIs(copy));
            Assert.Null(copy.BehaviorOverride);
            Assert.Equal("special", specialCopy.Name);
            Assert.Equal(110, specialCopy.Advance(2));
            Assert.Equal(0, specialCopy.Calls());
            Assert.Equal(17, restored.Standalone!.Voltage);
            Assert.Equal(0, restored.Standalone.Calls());
        }
    }

    private class PrimaryReplacement : PoweredCell.Behavior
    {
        public int Amplify(PoweredCell caller, int delta) => 500 + delta;
    }

    private sealed class ComposedReplacement : PrimaryReplacement, ComposedCell.Behavior
    {
        public int Advance(IMeteredCell caller, int delta) => 200 + delta;
        public int Calls(IMeteredCell caller) => 99;
        public bool CallerIs(IMeteredCell caller, IMeteredCell? value) => ReferenceEquals(caller, value);
    }
}
