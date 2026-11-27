using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class RepeatedRoleTests
{
    [Fact]
    public void RepeatedSourceRoleSharesProviderAndReplacementAfterComposition()
    {
        RepeatedRoleState.Hooks.Clear();
        RepeatedRoleState.Callers.Clear();
        RepeatedRoleState.RootCreated = RepeatedRoleState.FirstCreated = RepeatedRoleState.SecondCreated = 0;
        var left = new RepeatedLeftWins();
        var right = new RepeatedRightWins();
        var redundant = new RepeatedDirectEdge();
        Assert.Equal(["root:0", "second:0", "root:0"], RepeatedRoleState.Hooks);
        Assert.Collection(RepeatedRoleState.Callers, caller => Assert.Same(left, caller),
            caller => Assert.Same(right, caller), caller => Assert.Same(redundant, caller));
        Assert.Equal((2, 0, 1), (RepeatedRoleState.RootCreated, RepeatedRoleState.FirstCreated, RepeatedRoleState.SecondCreated));
        Assert.Equal((11, 11, 11), (left.Count, right.Count, redundant.Count));

        Assert.Equal(102, ((IRepeatedRight)left).Choose(1));
        Assert.Equal(103, ((IRepeatedLeft)left).Choose(1));
        Assert.Equal(2, ((IRepeatedRoot)left).RootCalls());
        Assert.Equal(302, ((IRepeatedLeft)right).Choose(1));
        Assert.Equal(303, ((IRepeatedRoot)right).Choose(1));
        Assert.Equal(2, right.FromSecond());
        Assert.Equal(0, right.RootCalls());
        Assert.Equal(0, left.FromFirst());
        Assert.Equal(0, left.FromSecond());
        Assert.Equal(102, redundant.Choose(1));
        Assert.Equal((3, 1, 2), (RepeatedRoleState.RootCreated, RepeatedRoleState.FirstCreated, RepeatedRoleState.SecondCreated));

        left.ReplaceBehavior(new Replacement(left));
        Assert.Equal(901, ((IRepeatedRight)left).Choose(1));
        Assert.Equal(902, ((IRepeatedLeft)left).Choose(2));
        Assert.Equal(903, ((IRepeatedRoot)left).Choose(3));
        Assert.Equal(921, ((IRepeatedRoot)left).RootCalls());
        Assert.Equal(304, right.Choose(1));

        var archive = new RepeatedRoleArchive { Selected = left };
        archive.Items.AddRange([left, right, redundant]);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR_HIERARCHY");
        foreach (bool references in new[] { false, true })
        {
            string suffix = $"-repeated-roles-{(references ? "true" : "false")}.json";
            string wire = references ? ModelSnapshot.Write(archive) : ModelSnapshot.WriteWithoutReferences(archive);
            if (exchange is not null)
            {
                string java = File.ReadAllText(Path.Combine(exchange, "java" + suffix));
                Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(wire)));
                File.WriteAllText(Path.Combine(exchange, "csharp" + suffix), wire);
                wire = java;
            }
            var restored = references ? ModelSnapshot.Read<RepeatedRoleArchive>(wire)
                : ModelSnapshot.ReadWithoutReferences<RepeatedRoleArchive>(wire);
            Assert.Equal(102, Assert.IsType<RepeatedLeftWins>(restored.Items[0]).Choose(1));
            Assert.Equal(302, Assert.IsType<RepeatedRightWins>(restored.Items[1]).Choose(1));
            Assert.Equal(102, Assert.IsType<RepeatedDirectEdge>(restored.Items[2]).Choose(1));
            if (references) Assert.Same(restored.Items[0], restored.Selected);
            else Assert.Null(restored.Selected);
        }
    }

    private sealed class Replacement(RepeatedLeftWins owner) : RepeatedLeftWins.Behavior
    {
        public int Choose(RepeatedLeftWins caller, int value) => Result(caller, 900 + value);
        public int RootCalls(RepeatedLeftWins caller) => Result(caller, 921);
        public int FromFirst(IRepeatedFirst caller) => Result(caller, 922);
        public int FromSecond(IRepeatedSecond caller) => Result(caller, 923);

        private int Result(object caller, int value)
        {
            Assert.Same(owner, caller);
            return value;
        }
    }
}
