using Eu.Mihosoft.Vmftest.Setteridentity;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class StockTraversalTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void OwningTraversalYieldsRepeatedSlotsAndExpandsTheirSharedChildrenOnce(bool readOnly)
    {
        var left = new NumberExpression { Value = 1 };
        var right = new NumberExpression { Value = 2 };
        var branch = new BinaryExpression { Operator = "*", Left = left, Right = right };
        var tail = new NumberExpression { Value = 4 };
        var root = new BinaryExpression();
        root.Arguments.AddRange([branch, null, new NumberExpression { Value = 3 }, tail]);
        root.Arguments[2] = branch;

        object input = readOnly ? root.AsReadOnly() : root;
        object child = readOnly ? branch.AsReadOnly() : branch;
        object first = readOnly ? left.AsReadOnly() : left;
        object second = readOnly ? right.AsReadOnly() : right;
        object last = readOnly ? tail.AsReadOnly() : tail;

        Check(ModelTraversalStrategy.UniqueNode, input, child, first, second, last);
        Check(ModelTraversalStrategy.UniqueProperty, input, child, first, second, child, last);
        Check(ModelTraversalStrategy.ContainmentTree, input, child, first, second, child, last);

        void Check(ModelTraversalStrategy strategy, params object[] expected)
        {
            var visited = ModelSnapshot.Reflection.Traverse(input, strategy).ToArray();
            Assert.Equal(expected.Length, visited.Length);
            for (int index = 0; index < expected.Length; index++)
                Assert.Same(expected[index], visited[index]);
        }
    }
}
