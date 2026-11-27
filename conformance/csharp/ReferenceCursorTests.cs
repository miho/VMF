using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ReferenceCursorTests
{
    [Fact]
    public void GraphCursorDefaultsToUniqueNodesAndAllowsExplicitAliasSelection()
    {
        var alias = new ArchiveNode();
        var root = new ArchiveNode { Anchor = alias };
        root.Trail.Add(alias);
        var reflection = ModelSnapshot.Reflection;

        using var defaultCursor = reflection.OpenGraphCursor(root);
        Move(defaultCursor, root);
        Move(defaultCursor, alias);
        Assert.False(defaultCursor.TryPeek(out _));

        using var propertyCursor = reflection.OpenGraphCursor(root, ModelTraversalStrategy.UniqueProperty);
        Move(propertyCursor, root);
        Move(propertyCursor, alias);
        Move(propertyCursor, alias);
        Assert.False(propertyCursor.TryPeek(out _));

        Assert.Equal(new[] { root, alias, alias }, reflection.Traverse(root));
        using var containmentCursor = reflection.OpenContainmentCursor(root);
        Move(containmentCursor, root);
        Assert.False(containmentCursor.TryPeek(out _));
    }

    [Theory]
    [InlineData(ModelTraversalStrategy.UniqueProperty)]
    [InlineData(ModelTraversalStrategy.UniqueNode)]
    public void SelectsExactReferenceSlotsAndPreservesTheOriginalBranchesPendingChildren(ModelTraversalStrategy strategy)
    {
        var descendant = new ArchiveNode();
        var original = new ArchiveNode { Anchor = descendant };
        var root = new ArchiveNode { Anchor = original };
        var tail = new ArchiveNode();
        var replacement = new ArchiveNode();
        var inserted = new ArchiveNode();
        root.Trail.AddRange([original, null, tail]);

        using var cursor = ModelSnapshot.Reflection.OpenGraphCursor(root, strategy);
        Move(cursor, root);
        Move(cursor, original);
        Assert.True(cursor.CanReplace);
        Assert.False(cursor.CanInsertAfter);
        Assert.True(cursor.TryPeek(out var next));
        Assert.Same(descendant, next);
        cursor.ReplaceCurrent(replacement);
        Assert.Same(replacement, root.Anchor);
        Assert.Same(original, root.Trail[0]);
        Assert.Same(original, cursor.Current);
        Move(cursor, descendant);

        if (strategy == ModelTraversalStrategy.UniqueProperty)
        {
            Move(cursor, original);
            Assert.True(cursor.CanInsertAfter);
            cursor.ReplaceCurrent(tail);
            cursor.InsertAfterCurrent(inserted);
            cursor.InsertAfterCurrent(replacement);
            Assert.Equal(new ArchiveNode?[] { tail, inserted, replacement, null, tail }, root.Trail);
            Assert.False(cursor.CanReplace);
            Assert.Throws<InvalidOperationException>(() => cursor.ReplaceCurrent(original));
        }

        Move(cursor, tail);
        cursor.ReplaceCurrent(null);
        Assert.Null(root.Trail[^1]);
        Assert.False(cursor.MoveNext());
    }

    [Fact]
    public void DetectsStructuralListChangesEvenIfItsSlotIdentityReturnsAndRequiresAWritableRoot()
    {
        var selected = new ArchiveNode();
        var root = new ArchiveNode();
        root.Trail.Add(selected);
        Assert.Throws<ArgumentException>(() => ModelSnapshot.Reflection.OpenGraphCursor(root.AsReadOnly()));
        Assert.Throws<ArgumentOutOfRangeException>(() =>
            ModelSnapshot.Reflection.OpenGraphCursor(root, (ModelTraversalStrategy)99));

        using var cursor = ModelSnapshot.Reflection.OpenGraphCursor(root);
        Move(cursor, root);
        Move(cursor, selected);
        root.Trail.RemoveAt(0);
        root.Trail.Add(selected);
        Assert.False(cursor.CanReplace);
        Assert.False(cursor.CanInsertAfter);
        Assert.Throws<InvalidOperationException>(() => cursor.ReplaceCurrent(null));
        Assert.Throws<InvalidOperationException>(() => cursor.MoveNext());
        Assert.Same(selected, root.Trail[0]);
    }

    [Theory]
    [InlineData(ModelTraversalStrategy.UniqueProperty)]
    [InlineData(ModelTraversalStrategy.UniqueNode)]
    public void ContinuesReadingAfterIndexedChangesAndCanEditAnEnteredDetachedBranch(ModelTraversalStrategy strategy)
    {
        var branch = new ArchiveNode();
        var root = new ArchiveNode { Anchor = branch };
        var selected = new ArchiveNode();
        var temporary = new ArchiveNode();
        var tail = new ArchiveNode();
        var updatedTail = new ArchiveNode();
        branch.Trail.AddRange([selected, tail]);

        using var cursor = ModelSnapshot.Reflection.OpenGraphCursor(root, strategy);
        Move(cursor, root);
        Move(cursor, branch);
        Move(cursor, selected);
        Assert.True(cursor.TryPeek(out var next));
        Assert.Same(tail, next);

        root.Anchor = null;
        branch.Trail[0] = temporary;
        Assert.False(cursor.CanReplace);
        Assert.False(cursor.CanInsertAfter);
        Assert.Throws<InvalidOperationException>(() => cursor.ReplaceCurrent(null));
        Assert.True(cursor.TryPeek(out next));
        Assert.Same(tail, next);

        branch.Trail[0] = selected;
        branch.Trail[1] = updatedTail;
        Assert.True(cursor.CanReplace);
        Assert.True(cursor.CanInsertAfter);
        Assert.True(cursor.TryPeek(out next));
        Assert.Same(updatedTail, next);
        cursor.ReplaceCurrent(null);
        Assert.Null(branch.Trail[0]);
        Move(cursor, updatedTail);
        Assert.False(cursor.TryPeek(out _));
    }

    [Fact]
    public void PublishesNativeListEventsAndKeepsItsPositionAfterCommittedListenerFailures()
    {
        var root = new ArchiveNode();
        var original = new ArchiveNode();
        var replacement = new ArchiveNode();
        root.Trail.Add(original);
        using var cursor = ModelSnapshot.Reflection.OpenGraphCursor(root);
        Move(cursor, root);
        Move(cursor, original);

        var events = new List<string>();
        var changes = ModelChanges.For(root);
        using var trace = changes.AddLocalListener(change => events.Add(change.PropertyName));
        cursor.ReplaceCurrent(original);
        var failure = new InvalidOperationException("Listener failed.");
        using (changes.AddLocalListener(_ => throw failure))
        {
            Assert.Same(failure, Assert.Throws<InvalidOperationException>(() => cursor.ReplaceCurrent(replacement)));
            Assert.Same(replacement, root.Trail[0]);
            Assert.True(cursor.CanInsertAfter);
            Assert.Same(failure, Assert.Throws<InvalidOperationException>(() => cursor.InsertAfterCurrent(original)));
        }
        cursor.InsertAfterCurrent(replacement);
        Assert.Equal(new[] { "trail", "trail", "trail", "trail" }, events);
        Assert.Equal(new[] { replacement, original, replacement }, root.Trail);
        Assert.False(cursor.MoveNext());
    }

    [Fact]
    public void EditsOrderedSetSlotsAndKeepsTheLastSelectionAfterTerminalLookahead()
    {
        var root = new ArchiveNode();
        var first = new ArchiveNode();
        var last = new ArchiveNode();
        var replacement = new ArchiveNode();
        var inserted = new ArchiveNode();
        var secondInsertion = new ArchiveNode();
        var lastReplacement = new ArchiveNode();
        root.Featured.AddRange([first, last]);
        using var cursor = ModelSnapshot.Reflection.OpenGraphCursor(root);
        Move(cursor, root);
        Move(cursor, first);
        Assert.True(cursor.CanInsertAfter);

        var events = new List<ModelChange>();
        using var trace = ModelChanges.For(root).AddLocalListener(events.Add);
        cursor.ReplaceCurrent(first);
        Assert.Throws<ArgumentException>(() => cursor.ReplaceCurrent(last));
        Assert.Throws<ArgumentException>(() => cursor.InsertAfterCurrent(last));
        Assert.Throws<ArgumentNullException>(() => cursor.ReplaceCurrent(null));
        Assert.Throws<ArgumentNullException>(() => cursor.InsertAfterCurrent(null));
        Assert.Empty(events);
        cursor.ReplaceCurrent(replacement);
        cursor.InsertAfterCurrent(inserted);
        cursor.InsertAfterCurrent(secondInsertion);
        Assert.Throws<InvalidOperationException>(() => cursor.ReplaceCurrent(first));
        Move(cursor, last);
        Assert.False(cursor.TryPeek(out _));
        Assert.False(cursor.TryPeek(out _));
        Assert.True(cursor.CanReplace);
        Assert.True(cursor.CanInsertAfter);
        cursor.ReplaceCurrent(lastReplacement);
        Assert.Equal(new[] { replacement, inserted, secondInsertion, lastReplacement }, root.Featured);
        Assert.Equal(new int?[] { 0, 1, 2, 3 }, events.Select(change =>
        {
            Assert.Equal("featured", change.PropertyName);
            Assert.Equal(ModelChangeKind.ReferenceSet, change.Kind);
            return Assert.Single(Assert.IsType<ModelCollectionChange>(change).Added).Index;
        }));
    }

    private static void Move(ModelGraphCursor cursor, ArchiveNode expected)
    {
        Assert.True(cursor.MoveNext());
        Assert.Same(expected, cursor.Current);
    }
}
