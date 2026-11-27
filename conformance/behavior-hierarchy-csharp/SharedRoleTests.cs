using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class SharedRoleTests
{
    [Fact]
    public void SharedJavaClassUsesOneNativeBindingPerActualModel()
    {
        ModelBehaviorBindings.SharedHooks.Clear();
        ModelBehaviorBindings.SharedHookOwners.Clear();
        ModelBehaviorBindings.SharedBindingsCreated = 0;
        var standalone = new SharedPrimary();
        var joined = new SharedJoined();
        var reversed = new SharedReversed();
        var local = new SharedLocal();
        Assert.Equal(["role:null", "role:null", "local:0:null"], ModelBehaviorBindings.SharedHooks);
        Assert.Equal(3, ModelBehaviorBindings.SharedBindingsCreated);
        Assert.Same(joined, ModelBehaviorBindings.SharedHookOwners[0]);
        Assert.Same(reversed, ModelBehaviorBindings.SharedHookOwners[1]);
        Assert.Same(local, ModelBehaviorBindings.SharedHookOwners[2]);

        ISharedPrimary primary = joined;
        ISharedRole role = joined;
        Assert.Equal(1, primary.One());
        Assert.Equal(2, role.Two());
        Assert.Equal(13, primary.Evaluate(10));
        Assert.Equal(5, role.Evaluate("x"));
        Assert.Equal(15, role.Evaluate(10));
        Assert.Equal(3, ModelBehaviorBindings.SharedBindingsCreated);
        Assert.Equal(1, reversed.Two());
        Assert.Equal(2, ((ISharedPrimary)reversed).One());
        Assert.Equal(13, reversed.Evaluate(10));
        Assert.Equal(11, ((ISharedRole)local).Evaluate(10));
        Assert.Equal(2, local.Two());
        Assert.Equal(3, local.One());
        Assert.Equal(6, local.Evaluate("ok"));
        Assert.Equal(1, standalone.One());
        Assert.Equal(2, standalone.Evaluate(0));
        Assert.Equal(4, ModelBehaviorBindings.SharedBindingsCreated);

        var graph = new SharedArchive { Selected = joined };
        graph.Items.AddRange([joined, reversed, local]);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR_HIERARCHY");
        foreach (bool references in new[] { true, false })
        {
            string suffix = references ? "-shared-behavior-reference.json" : "-shared-behavior-by-value.json";
            string wire = references ? ModelSnapshot.Write(graph) : ModelSnapshot.WriteWithoutReferences(graph, global::Vmf.Runtime.ReferenceMode.Omit);
            if (exchange is not null)
            {
                var java = File.ReadAllText(Path.Combine(exchange, "java" + suffix));
                Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(wire)));
                File.WriteAllText(Path.Combine(exchange, "csharp" + suffix), wire);
                wire = java;
            }
            var restored = references ? ModelSnapshot.Read<SharedArchive>(wire) : ModelSnapshot.ReadWithoutReferences<SharedArchive>(wire);
            var copy = Assert.IsType<SharedJoined>(restored.Items[0]);
            var reverseCopy = Assert.IsType<SharedReversed>(restored.Items[1]);
            var localCopy = Assert.IsType<SharedLocal>(restored.Items[2]);
            Assert.Equal(1, copy.One());
            Assert.Equal(2, copy.Two());
            Assert.Equal(1, reverseCopy.Two());
            Assert.Equal(11, localCopy.Evaluate(10));
            Assert.Equal(2, localCopy.One());
            if (references) Assert.Same(copy, restored.Selected);
            else Assert.Null(restored.Selected);
        }
    }
}
