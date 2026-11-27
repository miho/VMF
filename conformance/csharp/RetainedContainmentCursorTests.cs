using Eu.Mihosoft.Vmftest.Setteridentity;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class RetainedContainmentCursorTests
{
    [Fact]
    public void RetainedSubtreeFinishesBeforeTheLaterAliasAndSibling()
    {
        var (root, branch, sibling) = Graph();
        using var cursor = AtFirstSlot(root, branch);
        var replacement = new BinaryExpression();
        cursor.ReplaceCurrent(replacement);

        Assert.Same(branch, cursor.Current);
        Assert.Same(replacement, root.Arguments[0]);
        Assert.Same(branch, root.Arguments[1]);
        Assert.Same(root, branch.Parent);
        Assert.True(cursor.TryPeek(out var pending));
        Assert.Same(branch.Left, pending);
        Move(cursor, branch.Left!);
        Move(cursor, branch.Right!);
        Move(cursor, branch);
        Move(cursor, sibling);
        Assert.False(cursor.MoveNext());
    }

    [Fact]
    public void MoreEditsKeepTheSelectedSlotAndShiftTheRetainedSlot()
    {
        var (root, branch, sibling) = Graph();
        using var cursor = AtFirstSlot(root, branch);
        cursor.ReplaceCurrent(new BinaryExpression());
        Assert.True(cursor.TryPeek(out var pending));
        Assert.Same(branch.Left, pending);

        var replacement = new BinaryExpression();
        cursor.ReplaceCurrent(replacement);
        Assert.Same(branch, cursor.Current);
        Assert.Same(replacement, root.Arguments[0]);
        Assert.Same(branch, root.Arguments[1]);

        var inserted = new NumberExpression { Value = 6 };
        cursor.InsertAfterCurrent(inserted);
        Assert.Same(replacement, root.Arguments[0]);
        Assert.Same(inserted, root.Arguments[1]);
        Assert.Same(branch, root.Arguments[2]);
        Assert.True(cursor.TryPeek(out pending));
        Assert.Same(branch.Left, pending);
        Move(cursor, branch.Left!);
        Move(cursor, branch.Right!);
        Move(cursor, branch);
        Move(cursor, sibling);
        Assert.False(cursor.MoveNext());
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void RemovingOrReparentingAReboundAliasMakesItsDescendantSelectionStale(bool reparent)
    {
        var (root, branch, _) = Graph(twoRetainedSlots: true);
        var leaf = branch.Left!;
        using var cursor = AtFirstSlot(root, branch);
        cursor.ReplaceCurrent(new BinaryExpression());
        Move(cursor, leaf);
        Assert.True(cursor.CanReplace);

        if (reparent)
        {
            var destination = new BinaryExpression();
            destination.Arguments.Add(branch);
            Assert.Same(destination, branch.Parent);
            Assert.DoesNotContain(root.Arguments, child => ReferenceEquals(child, branch));
        }
        else
        {
            root.Arguments.RemoveAt(1);
            Assert.Same(branch, root.Arguments[1]);
            Assert.Same(root, branch.Parent);
        }

        Assert.Same(leaf, cursor.Current);
        Assert.False(cursor.CanReplace);
        Assert.Throws<InvalidOperationException>(() => cursor.ReplaceCurrent(null));
        Assert.Throws<InvalidOperationException>(() => cursor.TryPeek(out _));
        Assert.Throws<InvalidOperationException>(() => cursor.MoveNext());
        Assert.Same(leaf, branch.Left);
    }

    [Fact]
    public void ReattachingAnAncestorInvalidatesADescendantOfTheReboundAlias()
    {
        var (owner, branch, _) = Graph();
        var root = new BinaryExpression { Left = owner };
        using var cursor = ModelSnapshot.Reflection.OpenContainmentCursor(root);
        Move(cursor, root);
        Move(cursor, owner);
        Move(cursor, branch);
        cursor.ReplaceCurrent(new BinaryExpression());
        var leaf = branch.Left!;
        Move(cursor, leaf);
        Assert.True(cursor.CanReplace);

        root.Left = null;
        root.Left = owner;
        Assert.Same(branch, owner.Arguments[1]);
        Assert.False(cursor.CanReplace);
        Assert.Throws<InvalidOperationException>(() => cursor.ReplaceCurrent(null));
        Assert.Same(leaf, branch.Left);
    }

    private static (BinaryExpression Root, BinaryExpression Branch, NumberExpression Sibling) Graph(bool twoRetainedSlots = false)
    {
        var branch = new BinaryExpression
        {
            Operator = "*",
            Left = new NumberExpression { Value = 1 },
            Right = new NumberExpression { Value = 2 },
        };
        var sibling = new NumberExpression { Value = 5 };
        var root = new BinaryExpression();
        root.Arguments.AddRange([branch, new NumberExpression(), sibling]);
        root.Arguments[1] = branch;
        if (twoRetainedSlots)
        {
            root.Arguments.Insert(2, new NumberExpression());
            root.Arguments[2] = branch;
        }
        return (root, branch, sibling);
    }

    private static ModelContainmentCursor AtFirstSlot(BinaryExpression root, BinaryExpression branch)
    {
        var cursor = ModelSnapshot.Reflection.OpenContainmentCursor(root);
        Move(cursor, root);
        Move(cursor, branch);
        Assert.True(cursor.TryPeek(out var pending));
        Assert.Same(branch.Left, pending);
        return cursor;
    }

    private static void Move(ModelContainmentCursor cursor, object expected)
    {
        Assert.True(cursor.MoveNext());
        Assert.Same(expected, cursor.Current);
    }
}
