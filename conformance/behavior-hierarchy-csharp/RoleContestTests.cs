using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class RoleContestTests
{
    [Fact]
    public void ExactCallPrecedencePreservesIndependentOverloadsAndNativeReplacement()
    {
        RoleContestTrace.Hooks.Clear();
        RoleContestTrace.PrimaryBindings = 0;
        RoleContestTrace.RoleBindings = 0;
        var primary = new ContestPrimaryWins();
        var reversed = new ContestRoleWins();
        var local = new ContestOverride();
        Assert.Equal(["primary:null", "role:null", "local:null:null"], RoleContestTrace.Hooks);
        Assert.Equal(1, RoleContestTrace.PrimaryBindings);
        Assert.Equal(1, RoleContestTrace.RoleBindings);

        IContestRole asRole = primary;
        Assert.Equal(102, primary.Evaluate(1));
        Assert.Equal(103, asRole.Evaluate(1));
        Assert.Equal(2, primary.PrimaryCalls());
        Assert.Equal(1, RoleContestTrace.RoleBindings);
        Assert.Equal(202, primary.Evaluate("x"));
        Assert.Equal(404, primary.Evaluate(2L));
        Assert.Equal(2, primary.RoleCalls());
        Assert.Equal(2, RoleContestTrace.RoleBindings);
        Assert.Equal(202, ((IContestPrimary)reversed).Evaluate(1));
        Assert.Equal(0, reversed.PrimaryCalls());
        Assert.Equal(301, ((IContestRole)local).Evaluate(1));
        Assert.Equal(302, ((IContestPrimary)local).Evaluate(2));
        Assert.Equal(303, local.Evaluate(3));
        Assert.Equal(202, local.Evaluate("x"));

        primary.ReplaceBehavior(new Replacement(primary));
        Assert.Equal(901, asRole.Evaluate(1));
        Assert.Equal(902, ((IContestPrimary)primary).Evaluate(2));
        Assert.Equal(801, primary.Evaluate("x"));
        Assert.Equal(702, primary.Evaluate(2L));

        var graph = new ContestArchive { Selected = primary };
        graph.Items.AddRange([primary, reversed, local]);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR_HIERARCHY");
        foreach (bool references in new[] { true, false })
        {
            string suffix = references ? "-role-contest-reference.json" : "-role-contest-by-value.json";
            string wire = references ? ModelSnapshot.Write(graph) : ModelSnapshot.WriteWithoutReferences(graph, global::Vmf.Runtime.ReferenceMode.Omit);
            if (exchange is not null)
            {
                string java = File.ReadAllText(Path.Combine(exchange, "java" + suffix));
                Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(wire)));
                File.WriteAllText(Path.Combine(exchange, "csharp" + suffix), wire);
                wire = java;
            }
            var restored = references ? ModelSnapshot.Read<ContestArchive>(wire) : ModelSnapshot.ReadWithoutReferences<ContestArchive>(wire);
            var copy = Assert.IsType<ContestPrimaryWins>(restored.Items[0]);
            var reverse = Assert.IsType<ContestRoleWins>(restored.Items[1]);
            var overridden = Assert.IsType<ContestOverride>(restored.Items[2]);
            Assert.Equal(102, copy.Evaluate(1));
            Assert.Equal(202, reverse.Evaluate(1));
            Assert.Equal(301, overridden.Evaluate(1));
            Assert.Equal("main", copy.Title);
            Assert.Equal("side", copy.Label);
            if (references) Assert.Same(copy, restored.Selected);
            else Assert.Null(restored.Selected);
        }
    }

    private sealed class Replacement(ContestPrimaryWins expected) : ContestPrimaryWins.Behavior
    {
        public int Evaluate(ContestPrimaryWins caller, int value)
        {
            Assert.Same(expected, caller);
            return 900 + value;
        }

        public int Evaluate(IContestRole caller, long value) => 700 + (int)value;
        public int Evaluate(IContestRole caller, string? value) => 800 + value!.Length;
        public int PrimaryCalls(IContestPrimary caller) => -1;
        public int RoleCalls(IContestRole caller) => -2;
    }
}
