using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class LocalBindingTests
{
    [Fact]
    public void AComposedOwnerBindsOneDefaultPerSourceClassAndOneReplacementForAllMethods()
    {
        LocalBindingState.Hooks.Clear();
        LocalBindingState.HookCallers.Clear();
        LocalBindingState.ColdCreated = LocalBindingState.HotCreated = LocalBindingState.SharedCreated = 0;
        var first = new LocalComposite();
        var child = new LocalCompositeChild();
        Assert.Equal(["cold:0:0:0", "cold:0:0:0"], LocalBindingState.Hooks);
        Assert.Collection(LocalBindingState.HookCallers, caller => Assert.Same(first, caller),
            caller => Assert.Same(child, caller));
        Assert.Equal((2, 0, 0), (LocalBindingState.ColdCreated, LocalBindingState.HotCreated, LocalBindingState.SharedCreated));
        Assert.Equal((11, 22, 33), (first.LeftValue, first.RightValue, first.LocalValue));

        foreach (var owner in new[] { first, child })
        {
            Assert.Equal(101, owner.Cool());
            Assert.Equal(1, owner.Heat());
            Assert.Equal(2, owner.Warm());
            Assert.Equal(1, owner.Left());
            Assert.Equal(2, owner.Joined());
            Assert.Equal(3, ((ILocalRight)owner).Right());
        }
        Assert.Equal((2, 2, 2), (LocalBindingState.ColdCreated, LocalBindingState.HotCreated, LocalBindingState.SharedCreated));

        first.ReplaceBehavior(new Replacement(first));
        Assert.Equal(201, first.Cool());
        Assert.Equal(202, first.Heat());
        Assert.Equal(203, first.Warm());
        Assert.Equal(204, first.Joined());
        Assert.Equal(205, first.Left());
        Assert.Equal(206, ((ILocalRight)first).Right());
        Assert.Equal(3, child.Warm());
        Assert.Equal(4, child.Right());
        Assert.Equal(102, child.Cool());

        var untouched = new LocalComposite();
        untouched.ReplaceBehavior(new Replacement(untouched));
        Assert.Equal(202, untouched.Heat());
        Assert.Equal(204, untouched.Joined());
        Assert.Equal((3, 2, 2), (LocalBindingState.ColdCreated, LocalBindingState.HotCreated, LocalBindingState.SharedCreated));

        var graph = new LocalBundle { Selected = child };
        graph.Items.AddRange([first, child]);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR_HIERARCHY");
        foreach (bool references in new[] { false, true })
        {
            string suffix = $"-local-delegate-bindings-{(references ? "true" : "false")}.json";
            string wire = references ? ModelSnapshot.Write(graph) : ModelSnapshot.WriteWithoutReferences(graph, global::Vmf.Runtime.ReferenceMode.Omit);
            if (exchange is not null)
            {
                string java = File.ReadAllText(Path.Combine(exchange, "java" + suffix));
                Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(wire)));
                File.WriteAllText(Path.Combine(exchange, "csharp" + suffix), wire);
                wire = java;
            }
            var restored = references ? ModelSnapshot.Read<LocalBundle>(wire) : ModelSnapshot.ReadWithoutReferences<LocalBundle>(wire);
            var value = Assert.IsType<LocalComposite>(restored.Items[0]);
            var derived = Assert.IsType<LocalCompositeChild>(restored.Items[1]);
            Assert.Null(value.BehaviorOverride);
            Assert.Equal(101, value.Cool());
            Assert.Equal(1, value.Warm());
            Assert.Equal(1, derived.Warm());
            Assert.Equal(1, derived.Right());
            Assert.Equal(2, derived.Left());
            if (references) Assert.Same(derived, restored.Selected);
            else Assert.Null(restored.Selected);
        }
    }

    private sealed class Replacement(LocalComposite owner) : LocalComposite.Behavior
    {
        public int Cool(LocalComposite caller) => Result(caller, 201);
        public int Heat(LocalComposite caller) => Result(caller, 202);
        public int Warm(LocalComposite caller) => Result(caller, 203);
        public int Joined(LocalComposite caller) => Result(caller, 204);
        public int Left(LocalLeft caller) => Result(caller, 205);
        public int Right(ILocalRight caller) => Result(caller, 206);

        private int Result(object caller, int result)
        {
            Assert.Same(owner, caller);
            return result;
        }
    }
}
