using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class CovariantRoleTests
{
    [Fact]
    public void InheritedNarrowResultServesBothTypedRoleContracts()
    {
        ModelBehaviorBindings.CovariantSharedCreated = 0;
        var ordered = new NarrowFirst { Stored = new RankedResult { Name = "ordered", Rank = 2 } };
        Assert.Equal(typeof(Result), typeof(IWideRole).GetMethod(nameof(IWideRole.Choose))?.ReturnType);
        Assert.Equal(typeof(RankedResult), typeof(INarrowRole).GetMethod(nameof(INarrowRole.Choose))?.ReturnType);
        Assert.Same(ordered.Stored, ordered.Choose());
        Assert.Same(ordered.Stored, ((IWideRole)ordered).Choose());
        Assert.Equal(2, ((INarrowRole)ordered).Choose()?.Rank);

        var competing = new SecondaryNarrow { Stored = new RankedResult { Name = "secondary", Rank = 4 } };
        Assert.Same(competing.Stored, competing.Choose());
        Assert.Same(competing.Stored, ((IWideRole)competing).Choose());

        var shared = new SharedNarrowFirst { Stored = new RankedResult { Name = "shared", Rank = 5 } };
        Assert.Same(shared.Stored, ((ISharedWide)shared).Sample());
        Assert.Same(shared.Stored, shared.Sample());
        Assert.Equal(1, ModelBehaviorBindings.CovariantSharedCreated);

        var replacement = new RankedResult { Name = "replacement", Rank = 9 };
        ordered.ReplaceBehavior(new Replacement(ordered, replacement));
        Assert.Same(replacement, ((IWideRole)ordered).Choose());
        Assert.Same(replacement, ((INarrowRole)ordered).Choose());
        var empty = new SecondaryNarrow();
        Assert.Null(empty.Choose());
        Assert.Null(((IWideRole)empty).Choose());

        var archive = new CovariantRoleArchive { Selected = ordered };
        archive.Items.AddRange([ordered, competing, shared]);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR_HIERARCHY");
        foreach (bool references in new[] { false, true })
        {
            string suffix = $"-covariant-roles-{(references ? "true" : "false")}.json";
            string wire = references ? ModelSnapshot.Write(archive) : ModelSnapshot.WriteWithoutReferences(archive);
            if (exchange is not null)
            {
                string java = File.ReadAllText(Path.Combine(exchange, "java" + suffix));
                Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(wire)));
                File.WriteAllText(Path.Combine(exchange, "csharp" + suffix), wire);
                wire = java;
            }
            var restored = references ? ModelSnapshot.Read<CovariantRoleArchive>(wire)
                : ModelSnapshot.ReadWithoutReferences<CovariantRoleArchive>(wire);
            var fresh = Assert.IsType<NarrowFirst>(restored.Items[0]);
            Assert.Same(fresh.Stored, ((IWideRole)fresh).Choose());
            Assert.Equal(2, fresh.Choose()?.Rank);
            Assert.Equal(4, Assert.IsType<SecondaryNarrow>(restored.Items[1]).Choose()?.Rank);
            Assert.Equal(5, Assert.IsType<SharedNarrowFirst>(restored.Items[2]).Sample()?.Rank);
            if (references) Assert.Same(fresh, restored.Selected);
            else Assert.Null(restored.Selected);
        }
    }

    private sealed class Replacement(NarrowFirst expected, RankedResult value) : NarrowFirst.Behavior
    {
        public RankedResult Choose(NarrowFirst caller)
        {
            Assert.Same(expected, caller);
            return value;
        }
    }
}
