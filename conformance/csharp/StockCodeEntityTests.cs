using Eu.Mihosoft.Vmftest.Parentcontainment01;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class StockCodeEntityTests
{
    [Fact]
    public void AbstractBehaviorReturnPreservesTheActualNode()
    {
        var root = new OperatorExpression();
        var left = NumberExpression.NewBuilder().WithValue(3.2).Build();
        var right = new OperatorExpression();
        var deep = new NumberExpression { Value = 5.6 };
        Assert.Null(left.Parent);
        Assert.Null(right.Parent);
        root.Left = left;
        root.Right = right;
        right.Right = deep;

        CodeEntity child = deep;
        Assert.Same(root, root.Root());
        Assert.Same(root, left.Root());
        Assert.Same(root, right.Root());
        Assert.Same(root, child.Root());
        Assert.Same(root, right.Parent);
        Assert.Same(right, deep.Parent);

        string wire = ModelSnapshot.WriteWithoutReferences(left, global::Vmf.Runtime.ReferenceMode.Omit);
        var restored = ModelSnapshot.ReadWithoutReferences<NumberExpression>(wire);
        Assert.Equal(3.2, restored.Value);
        Assert.Null(restored.Parent);
        Assert.Same(restored, restored.Root());
    }
}
