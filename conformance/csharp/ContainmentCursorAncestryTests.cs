using Eu.Mihosoft.Vmftest.Setteridentity;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ContainmentCursorAncestryTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void RestoringASingularSlotInvalidatesItsSelection(bool clearFirst)
    {
        var selected = new NumberExpression { Value = 1 };
        var root = new BinaryExpression { Left = selected };
        using var cursor = ModelSnapshot.Reflection.OpenContainmentCursor(root);
        Assert.True(cursor.MoveNext());
        Assert.True(cursor.MoveNext());
        Assert.Same(selected, cursor.Current);
        if (clearFirst) cursor.ReplaceCurrent(null);
        var expected = root.Left;

        var transientChild = new NumberExpression { Value = 2 };
        root.Left = transientChild;
        root.Left = expected;

        Assert.False(cursor.CanReplace);
        var attempted = new NumberExpression { Value = 3 };
        Assert.Throws<InvalidOperationException>(() => cursor.ReplaceCurrent(attempted));
        Assert.Same(expected, root.Left);
        Assert.Null(transientChild.Parent);
        Assert.Null(attempted.Parent);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void DetachingASingularOrListAncestorInvalidatesEdits(bool list)
    {
        var first = new NumberExpression { Value = 1 };
        var second = new NumberExpression { Value = 2 };
        var branch = new BinaryExpression();
        branch.Arguments.AddRange([first, second]);
        var root = new BinaryExpression();
        if (list) root.Arguments.Add(branch);
        else root.Left = branch;

        using var cursor = ModelSnapshot.Reflection.OpenContainmentCursor(root);
        Assert.True(cursor.MoveNext());
        Assert.True(cursor.MoveNext());
        Assert.True(cursor.MoveNext());
        Assert.Same(first, cursor.Current);
        Assert.True(cursor.TryPeek(out var pending));
        Assert.Same(second, pending);

        if (list) root.Arguments.Clear();
        else root.Left = null;
        var attempted = new NumberExpression { Value = 3 };
        Assert.False(cursor.CanReplace);
        Assert.False(cursor.CanInsertAfter);
        Assert.Throws<InvalidOperationException>(() => cursor.ReplaceCurrent(attempted));
        Assert.Throws<InvalidOperationException>(() => cursor.InsertAfterCurrent(attempted));
        Assert.Throws<InvalidOperationException>(() => cursor.TryPeek(out _));
        Assert.Same(first, branch.Arguments[0]);
        Assert.Same(second, branch.Arguments[1]);
        Assert.Null(attempted.Parent);
    }
}
