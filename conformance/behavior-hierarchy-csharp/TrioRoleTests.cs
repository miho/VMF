using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class TrioRoleTests
{
    [Fact]
    public void ThreeRolesKeepSourceOrderOverloadsAndBindingsOfDistinctClasses()
    {
        ModelBehaviorBindings.TrioHooks.Clear();
        ModelBehaviorBindings.TrioSharedCreated = 0;
        TrioSecond.TrioIndependentCreated = 0;
        var forward = new TrioForward();
        var reverse = new TrioReverse();
        var inherited = new TrioInherited();
        Assert.Equal(["first:0", "second:0", "first:0"], ModelBehaviorBindings.TrioHooks);
        Assert.Equal(2, ModelBehaviorBindings.TrioSharedCreated);
        Assert.Equal(1, TrioSecond.TrioIndependentCreated);

        Assert.Equal(102, forward.Evaluate(1));
        Assert.Equal(103, ((ITrioSecond)forward).Evaluate(1));
        Assert.Equal(104, ((ITrioThird)forward).Evaluate(1));
        Assert.Equal(305, forward.Evaluate("x"));
        Assert.Equal(4, forward.FirstCalls());
        Assert.Equal(4, forward.ThirdCalls());
        Assert.Equal(1, TrioSecond.TrioIndependentCreated);
        Assert.Equal(403, forward.Evaluate(2L));
        Assert.Equal(1003L, ((ITrioThird)forward).Finish("x"));
        Assert.Equal(2, forward.SecondCalls());

        Assert.Equal(202, ((ITrioFirst)reverse).Evaluate(1));
        Assert.Equal(203, ((ITrioThird)reverse).Evaluate(1));
        Assert.Equal(302, reverse.Evaluate("x"));
        Assert.Equal(1, reverse.FirstCalls());
        Assert.Equal(1, reverse.ThirdCalls());
        Assert.Equal(102, inherited.Evaluate(1));
        Assert.Equal(1, inherited.ThirdCalls());
        Assert.Equal(3, ModelBehaviorBindings.TrioHooks.Count);

        forward.ReplaceBehavior(new Replacement(forward));
        Assert.Equal(901, ((ITrioFirst)forward).Evaluate(1));
        Assert.Equal(902, ((ITrioSecond)forward).Evaluate(2));
        Assert.Equal(903, ((ITrioThird)forward).Evaluate(3));
        Assert.Equal(9001L, ((ITrioSecond)forward).Finish("x"));
        Assert.Equal(9002L, ((ITrioThird)forward).Finish("xy"));

        var graph = new TrioArchive { Selected = forward };
        graph.Items.AddRange([forward, reverse, inherited]);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR_HIERARCHY");
        foreach (bool references in new[] { true, false })
        {
            string suffix = references ? "-trio-role-reference.json" : "-trio-role-by-value.json";
            string wire = references ? ModelSnapshot.Write(graph) : ModelSnapshot.WriteWithoutReferences(graph, global::Vmf.Runtime.ReferenceMode.Omit);
            if (exchange is not null)
            {
                var java = File.ReadAllText(Path.Combine(exchange, "java" + suffix));
                Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(wire)));
                File.WriteAllText(Path.Combine(exchange, "csharp" + suffix), wire);
                wire = java;
            }
            var restored = references ? ModelSnapshot.Read<TrioArchive>(wire) : ModelSnapshot.ReadWithoutReferences<TrioArchive>(wire);
            var model = Assert.IsType<TrioForward>(restored.Items[0]);
            Assert.Equal(11, model.FirstValue);
            Assert.Equal(22, model.SecondValue);
            Assert.Equal(33, model.ThirdValue);
            Assert.Equal(102, model.Evaluate(1));
            Assert.Equal(202, Assert.IsType<TrioReverse>(restored.Items[1]).Evaluate(1));
            Assert.Equal(102, Assert.IsType<TrioInherited>(restored.Items[2]).Evaluate(1));
            if (references) Assert.Same(model, restored.Selected);
            else Assert.Null(restored.Selected);
        }
    }

    private sealed class Replacement(TrioForward expected) : TrioForward.Behavior
    {
        public int Evaluate(TrioForward caller, int value)
        {
            Assert.Same(expected, caller);
            return 900 + value;
        }

        public long Finish(TrioForward caller, string? value)
        {
            Assert.Same(expected, caller);
            return 9000 + value!.Length;
        }

        public int Evaluate(ITrioSecond caller, long value) => -1;
        public int Evaluate(ITrioThird caller, string? value) => -2;
        public int FirstCalls(ITrioFirst caller) => -3;
        public int SecondCalls(ITrioSecond caller) => -4;
        public int ThirdCalls(ITrioThird caller) => -5;
    }
}
