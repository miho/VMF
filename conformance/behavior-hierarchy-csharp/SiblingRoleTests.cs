using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class SiblingRoleTests
{
    [Fact]
    public void LinearSiblingsShareTheirLocalProviderAndRetainTheBaseSlots()
    {
        SiblingRoleState.Hooks.Clear();
        SiblingRoleState.Callers.Clear();
        SiblingRoleState.RootCreated = SiblingRoleState.SidesCreated = SiblingRoleState.OverrideCreated = 0;
        var plain = new SiblingPlain();
        var reversed = new SiblingReversed();
        var first = new SiblingFirstPath();
        var overrideFirst = new SiblingSecondPath();
        var local = new SiblingLocal();
        Assert.Equal(["left:0", "right:0", "left:0", "override:0", "local:0"], SiblingRoleState.Hooks);
        Assert.Collection(SiblingRoleState.Callers, caller => Assert.Same(plain, caller),
            caller => Assert.Same(reversed, caller), caller => Assert.Same(first, caller),
            caller => Assert.Same(overrideFirst, caller), caller => Assert.Same(local, caller));
        Assert.Equal((0, 3, 1), (SiblingRoleState.RootCreated, SiblingRoleState.SidesCreated, SiblingRoleState.OverrideCreated));

        Assert.Equal(1, plain.Left());
        Assert.Equal(2, plain.Right());
        Assert.Equal(1, reversed.Right());
        Assert.Equal(2, reversed.Left());
        Assert.Equal(113, ((ISiblingRight)plain).Shared(10));
        Assert.Equal(114, ((ISiblingLeft)plain).Shared(10));
        Assert.Equal(3, SiblingRoleState.SidesCreated);
        Assert.Equal(102, ((ISiblingLeft)plain).Choose(1));
        Assert.Equal(103, ((ISiblingRight)plain).Choose(1));
        Assert.Equal(2, ((ISiblingRoot)plain).RootCalls());
        Assert.Equal(1, SiblingRoleState.RootCreated);

        Assert.Equal(102, first.Choose(1));
        Assert.Equal(202, overrideFirst.Choose(1));
        Assert.Equal(302, local.Choose(1));
        Assert.Equal(203, ((ISiblingRoot)overrideFirst).Choose(1));
        Assert.Equal(204, ((ISiblingLeft)overrideFirst).Choose(1));
        Assert.Equal(3, overrideFirst.OverrideCalls());
        Assert.Equal(0, overrideFirst.RootCalls());
        Assert.Equal(11, local.Value);

        plain.ReplaceBehavior(new Replacement(plain));
        Assert.Equal(810, ((ISiblingLeft)plain).Shared(10));
        Assert.Equal(810, ((ISiblingRight)plain).Shared(10));
        Assert.Equal(901, ((ISiblingRoot)plain).Choose(1));
        Assert.Equal(81, plain.Left());
        Assert.Equal(82, plain.Right());
        Assert.Equal(3, reversed.Left());

        var archive = new SiblingRoleArchive { Selected = plain };
        archive.Items.AddRange([plain, reversed, first, overrideFirst, local]);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR_HIERARCHY");
        foreach (bool references in new[] { false, true })
        {
            string suffix = $"-sibling-roles-{(references ? "true" : "false")}.json";
            string wire = references ? ModelSnapshot.Write(archive) : ModelSnapshot.WriteWithoutReferences(archive, global::Vmf.Runtime.ReferenceMode.Omit);
            if (exchange is not null)
            {
                string java = File.ReadAllText(Path.Combine(exchange, "java" + suffix));
                Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(wire)));
                File.WriteAllText(Path.Combine(exchange, "csharp" + suffix), wire);
                wire = java;
            }
            var restored = references ? ModelSnapshot.Read<SiblingRoleArchive>(wire)
                : ModelSnapshot.ReadWithoutReferences<SiblingRoleArchive>(wire);
            var fresh = Assert.IsType<SiblingPlain>(restored.Items[0]);
            Assert.Equal(102, fresh.Choose(1));
            Assert.Equal(1, fresh.Left());
            Assert.Equal(2, fresh.Right());
            Assert.Equal(202, Assert.IsType<SiblingSecondPath>(restored.Items[3]).Choose(1));
            Assert.Equal(302, Assert.IsType<SiblingLocal>(restored.Items[4]).Choose(1));
            if (references) Assert.Same(fresh, restored.Selected);
            else Assert.Null(restored.Selected);
        }
    }

    private sealed class Replacement(SiblingPlain actual) : SiblingPlain.Behavior
    {
        public int Choose(SiblingPlain caller, int value) => Result(caller, 900 + value);
        public int Shared(SiblingPlain caller, int value) => Result(caller, 800 + value);
        public int RootCalls(SiblingPlain caller) => Result(caller, -1);
        public int Left(ISiblingLeft caller) => Result(caller, 81);
        public int Right(ISiblingRight caller) => Result(caller, 82);

        private int Result(object caller, int value)
        {
            Assert.Same(actual, caller);
            return value;
        }
    }
}
