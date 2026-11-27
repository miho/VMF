using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class AbstractRoleBehaviorTests
{
    [Fact]
    public void InterfaceOnlyRootSharesItsBindingThroughDataTraitsAndBothSnapshotProfiles()
    {
        FlowChild.HookIds.Clear();
        FlowChild.BindingCount = 0;
        var block = new FlowBlock { Label = "body", ScopeName = "local" };
        var expression = new FlowAddition { Id = 7, Operator = "+" };
        var procedure = new FlowProcedure { Id = 9, ScopeName = "main" };
        Assert.Equal([0, 0, 0], FlowChild.HookIds);
        Assert.Equal(3, FlowChild.BindingCount);
        FlowStatement statement = block;
        IFlowScope scope = block;
        IFlowOperator operation = expression;
        Assert.Same(statement, scope);
        Assert.Same(expression, operation);
        Assert.Equal(11, scope.Id);
        scope.Id = 13;
        var ancestors = statement.ParentScopes()!;
        Assert.Equal(2, ancestors.Count);
        Assert.Same(scope, ancestors[0]);
        Assert.Null(ancestors[1]);
        Assert.Equal(1, scope.Calls());
        Assert.Equal(13, scope.OriginId());
        Assert.Equal(0, operation.Calls());
        Assert.Equal(7, operation.OriginId());
        Assert.Same(procedure, procedure.ParentScopes()![0]);
        Assert.Equal(1, procedure.Calls());
        Assert.Equal(3, FlowChild.BindingCount);
        Assert.Null(typeof(IReadOnlyFlowScope).GetMethod("ParentScopes"));
        block.ReplaceBehavior(new BlockReplacement());
        Assert.Equal(-1, statement.Calls());
        Assert.Equal(113, scope.OriginId());
        Assert.Same(block, statement.ParentScopes()![0]);
        Assert.Equal(1, procedure.Calls());

        var graph = new FlowProgram { Block = block, Expression = expression, Procedure = procedure, Lookup = block };
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR_HIERARCHY");
        foreach (bool references in new[] { true, false })
        {
            string suffix = references ? "-abstract-flow-role-reference.json" : "-abstract-flow-role-by-value.json";
            string wire = references ? ModelSnapshot.Write(graph) : ModelSnapshot.WriteWithoutReferences(graph);
            if (exchange is not null)
            {
                string java = File.ReadAllText(Path.Combine(exchange, "java" + suffix));
                Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(wire)));
                File.WriteAllText(Path.Combine(exchange, "csharp" + suffix), wire);
                wire = java;
            }

            var restored = references ? ModelSnapshot.Read<FlowProgram>(wire) : ModelSnapshot.ReadWithoutReferences<FlowProgram>(wire);
            var copy = Assert.IsType<FlowBlock>(restored.Block);
            Assert.Equal(13, copy.Id);
            Assert.Equal(0, copy.Calls());
            Assert.Same(copy, copy.ParentScopes()![0]);
            var copiedExpression = Assert.IsType<FlowAddition>(restored.Expression);
            Assert.Equal("+", copiedExpression.Operator);
            Assert.Equal(7, copiedExpression.OriginId());
            Assert.Equal(9, restored.Procedure!.OriginId());
            if (references) Assert.Same(copy, restored.Lookup);
            else Assert.Null(restored.Lookup);
        }
    }

    private sealed class BlockReplacement : FlowBlock.Behavior
    {
        public int Calls(IFlowChild caller) => -1;
        public int OriginId(IFlowChild caller) => ((IFlowScope)caller).Id + 100;
        public IList<IFlowScope?>? ParentScopes(IFlowChild caller) => new List<IFlowScope?> { (IFlowScope)caller };
    }
}
