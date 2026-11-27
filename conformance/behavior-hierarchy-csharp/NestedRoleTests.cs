using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class NestedRoleTests
{
    [Fact]
    public void NestedRolesSelectTheFirstEffectiveMethodAndKeepIndependentOverloads()
    {
        NestedRoleTestState.Hooks.Clear();
        NestedRoleTestState.Callers.Clear();
        var primary = new NestedPrimary();
        var secondary = new NestedSecondary();
        var inner = new NestedInnerPrimary();
        var outer = new NestedOuter();
        Assert.Equal(["first:0", "third:0", "inner:0", "outer:0"], NestedRoleTestState.Hooks);
        Assert.Collection(NestedRoleTestState.Callers, caller => Assert.Same(primary, caller),
            caller => Assert.Same(secondary, caller), caller => Assert.Same(inner, caller), caller => Assert.Same(outer, caller));
        Assert.Equal((11, 22, 44, 33), (primary.FirstValue, secondary.SecondValue, inner.InnerValue, outer.ThirdValue));

        Assert.Equal(102, ((INestedSecond)primary).Choose(1));
        Assert.Equal(103, ((INestedThird)primary).Choose(1));
        Assert.Equal((2, 0, 0), (primary.FirstCalls(), primary.SecondCalls(), primary.ThirdCalls()));
        Assert.Equal(203, primary.Choose(2L));
        Assert.Equal(302, primary.Choose("x"));
        Assert.Equal((1, 1), (primary.SecondCalls(), primary.ThirdCalls()));
        Assert.Equal(302, ((INestedPair)secondary).Choose(1));
        Assert.Equal(303, ((INestedFirst)secondary).Choose(1));
        Assert.Equal((0, 2), (secondary.FirstCalls(), secondary.ThirdCalls()));
        Assert.Equal(402, ((INestedThird)inner).Choose(1));
        Assert.Equal(1, inner.InnerCalls());
        Assert.Equal(502, ((INestedInner)outer).Choose(1));
        Assert.Equal(0, outer.InnerCalls());

        secondary.ReplaceBehavior(new Replacement(secondary));
        Assert.Equal(902, ((INestedPair)secondary).Choose(2));
        Assert.Equal(902, ((INestedFirst)secondary).Choose(2));
        Assert.Equal(902, ((INestedThird)secondary).Choose(2));
        Assert.Equal(802, secondary.Choose(2L));
        Assert.Equal(702, secondary.Choose("xy"));

        var graph = new NestedRoleArchive { Selected = secondary };
        graph.Items.AddRange([primary, secondary, inner, outer]);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR_HIERARCHY");
        foreach (bool references in new[] { false, true })
        {
            string suffix = $"-nested-roles-{(references ? "true" : "false")}.json";
            string wire = references ? ModelSnapshot.Write(graph) : ModelSnapshot.WriteWithoutReferences(graph);
            if (exchange is not null)
            {
                string java = File.ReadAllText(Path.Combine(exchange, "java" + suffix));
                Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(wire)));
                File.WriteAllText(Path.Combine(exchange, "csharp" + suffix), wire);
                wire = java;
            }
            var restored = references ? ModelSnapshot.Read<NestedRoleArchive>(wire)
                : ModelSnapshot.ReadWithoutReferences<NestedRoleArchive>(wire);
            Assert.Equal(102, Assert.IsType<NestedPrimary>(restored.Items[0]).Choose(1));
            Assert.Equal(302, Assert.IsType<NestedSecondary>(restored.Items[1]).Choose(1));
            Assert.Equal(402, Assert.IsType<NestedInnerPrimary>(restored.Items[2]).Choose(1));
            Assert.Equal(502, Assert.IsType<NestedOuter>(restored.Items[3]).Choose(1));
            if (references) Assert.Same(restored.Items[1], restored.Selected);
            else Assert.Null(restored.Selected);
        }
    }

    private sealed class Replacement(NestedSecondary owner) : NestedSecondary.Behavior
    {
        public int Choose(NestedSecondary caller, int input) => Verify(caller, 900 + input);
        public int Choose(INestedSecond caller, long input) => Verify(caller, 800 + (int)input);
        public int Choose(INestedThird caller, string? input) => Verify(caller, 700 + input!.Length);
        public int FirstCalls(INestedFirst caller) => Verify(caller, -1);
        public int SecondCalls(INestedSecond caller) => Verify(caller, -2);
        public int ThirdCalls(INestedThird caller) => Verify(caller, -3);

        private int Verify(object caller, int value)
        {
            Assert.Same(owner, caller);
            return value;
        }
    }
}
