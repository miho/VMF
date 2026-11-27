using Eu.Mihosoft.Vmftest.Collectioncontract;
using Vmf.Runtime;
using Xunit;
using Ordinary = Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Stock = Eu.Mihosoft.Vmftest.Setteridentity;
using Values = Eu.Mihosoft.Vmf.Conformance.Values;

namespace Vmf.CSharp.Conformance;

public sealed class ContainmentCursorTests
{
    [Fact]
    public void StockLookaheadAcrossNullKeepsTheLastReturnedSlotEditable()
    {
        var catalog = new Catalog();
        var first = Item("first");
        var last = Item("last");
        catalog.Items.AddRange([first, null, last]);

        using var cursor = ModelSnapshot.Reflection.OpenContainmentCursor(catalog);
        Assert.Throws<InvalidOperationException>(() => cursor.Current);
        Assert.Throws<InvalidOperationException>(() => cursor.ReplaceCurrent(first));
        Assert.True(cursor.TryPeek(out var root));
        Assert.Same(catalog, root);
        Assert.Throws<InvalidOperationException>(() => cursor.Current);
        Move(cursor, catalog);
        Assert.False(cursor.CanReplace);
        Assert.False(cursor.CanInsertAfter);
        Assert.Throws<InvalidOperationException>(() => cursor.InsertAfterCurrent(first));
        Move(cursor, first);
        Assert.True(cursor.CanReplace);
        Assert.True(cursor.CanInsertAfter);
        Assert.True(cursor.TryPeek(out var pending));
        Assert.Same(last, pending);
        Assert.True(cursor.TryPeek(out pending));
        Assert.Same(last, pending);

        var changes = new List<ModelCollectionChange>();
        using var watch = catalog.VmfChanges.AddPropertyListener("items", edit =>
            changes.Add(Assert.IsAssignableFrom<ModelCollectionChange>(edit)));
        var replacement = Item("replacement");
        cursor.ReplaceCurrent(replacement);
        Assert.Same(first, cursor.Current);
        Assert.Same(replacement, catalog.Items[0]);
        Assert.Null(first.Catalog);
        Assert.Same(catalog, replacement.Catalog);
        Assert.Same(first, Assert.Single(changes[0].Removed).Value);
        var addition = Assert.Single(changes[0].Added);
        Assert.Same(replacement, addition.Value);
        Assert.Equal(0, addition.Index);

        var inserted = Item("inserted");
        cursor.InsertAfterCurrent(inserted);
        Assert.Equal(new Item?[] { replacement, inserted, null, last }, catalog.Items);
        Assert.Same(catalog, inserted.Catalog);
        Assert.Same(inserted, Assert.Single(changes[1].Added).Value);
        Assert.Equal(1, Assert.Single(changes[1].Added).Index);
        Move(cursor, last);
        Assert.False(cursor.TryPeek(out _));
        Assert.True(cursor.CanReplace);
        cursor.ReplaceCurrent(null);
        Assert.Null(last.Catalog);
        Assert.Null(catalog.Items[3]);
        Assert.False(cursor.MoveNext());
        Assert.Throws<InvalidOperationException>(() => cursor.Current);
        Assert.False(cursor.CanReplace);
    }

    [Fact]
    public void PreservesTheGraphOnInvalidChildrenAndUsesManagedReparenting()
    {
        var catalog = new Catalog();
        var first = Item("first");
        var last = Item("last");
        catalog.Items.AddRange([first, last]);
        using var cursor = ModelSnapshot.Reflection.OpenContainmentCursor(catalog);
        Move(cursor, catalog);
        Move(cursor, first);
        Assert.True(cursor.TryPeek(out var pending));
        Assert.Same(last, pending);
        Assert.Throws<ArgumentException>(() => cursor.InsertAfterCurrent(last));
        Assert.Throws<ArgumentException>(() => cursor.ReplaceCurrent(new Catalog()));
        Assert.Throws<ArgumentException>(() => cursor.ReplaceCurrent(first.AsReadOnly()));
        Assert.Equal(new[] { first, last }, catalog.Items);

        var other = new Catalog();
        var recovered = Item("other");
        other.Items.Add(recovered);
        cursor.InsertAfterCurrent(recovered);
        Assert.Empty(other.Items);
        Assert.Same(catalog, recovered.Catalog);
        Assert.Equal(new[] { first, recovered, last }, catalog.Items);
        Move(cursor, last);
    }

    [Fact]
    public void DetectsAChangedDuplicateSlotEvenWhenTheSameIdentityRemainsAtTheIndex()
    {
        var catalog = new Catalog();
        var first = Item("first");
        var tail = Item("tail");
        catalog.Items.AddRange([first, Item("placeholder"), tail]);
        catalog.Items[1] = first;
        using var cursor = ModelSnapshot.Reflection.OpenContainmentCursor(catalog);
        Move(cursor, catalog);
        Move(cursor, first);
        Assert.True(cursor.TryPeek(out var alias));
        Assert.Same(first, alias);

        catalog.Items.RemoveAt(0);
        Assert.Same(first, catalog.Items[0]);
        Assert.False(cursor.CanReplace);
        Assert.Throws<InvalidOperationException>(() => cursor.ReplaceCurrent(Item("untouched")));
        Assert.Throws<InvalidOperationException>(() => cursor.TryPeek(out _));
        Assert.Throws<InvalidOperationException>(() => cursor.MoveNext());
        Assert.Equal(new[] { first, tail }, catalog.Items);
    }

    [Fact]
    public void DetectsAnExternallyReplacedPendingEdgeAndSingularAba()
    {
        var left = new Stock.NumberExpression { Value = 1 };
        var right = new Stock.NumberExpression { Value = 2 };
        var root = new Stock.BinaryExpression { Left = left, Right = right };
        using var cursor = Stock.ModelSnapshot.Reflection.OpenContainmentCursor(root);
        Move(cursor, root);
        Move(cursor, left);
        Assert.True(cursor.TryPeek(out var pending));
        Assert.Same(right, pending);
        root.Right = new Stock.NumberExpression();
        Assert.False(cursor.CanReplace);
        Assert.Throws<InvalidOperationException>(() => cursor.ReplaceCurrent(null));
        Assert.Same(left, root.Left);
        Assert.Throws<InvalidOperationException>(() => cursor.MoveNext());

        using var reopened = Stock.ModelSnapshot.Reflection.OpenContainmentCursor(root);
        Move(reopened, root);
        Move(reopened, left);
        root.Left = null;
        root.Left = left;
        Assert.False(reopened.CanReplace);
        Assert.Throws<InvalidOperationException>(() => reopened.ReplaceCurrent(null));
        Assert.Same(left, root.Left);
    }

    [Fact]
    public void ReplacingANonleafPrunesItsPendingSubtreeWithoutEnteringTheReplacement()
    {
        var oldLeft = new Stock.NumberExpression { Value = 1 };
        var old = new Stock.BinaryExpression { Left = oldLeft, Right = new Stock.NumberExpression { Value = 2 } };
        var replacement = new Stock.BinaryExpression
        {
            Left = new Stock.NumberExpression { Value = 3 }, Right = new Stock.NumberExpression { Value = 4 },
        };
        var tail = new Stock.NumberExpression { Value = 5 };
        var root = new Stock.BinaryExpression();
        root.Arguments.AddRange([old, tail]);

        using var cursor = Stock.ModelSnapshot.Reflection.OpenContainmentCursor(root);
        Move(cursor, root);
        Move(cursor, old);
        Assert.True(cursor.TryPeek(out var priorPending));
        Assert.Same(oldLeft, priorPending);
        cursor.ReplaceCurrent(replacement);
        Assert.Same(old, cursor.Current);
        Assert.Same(replacement, root.Arguments[0]);
        Assert.Null(old.Parent);
        Assert.Same(oldLeft, old.Left);
        Assert.Same(root, replacement.Parent);
        Assert.True(cursor.TryPeek(out var next));
        Assert.Same(tail, next);
        Move(cursor, tail);
        Assert.False(cursor.MoveNext());
    }

    [Fact]
    public void SingularAndOrderedEdgesUseTheirOwnManagedMutationRules()
    {
        var inset = new Values.ArchiveNode();
        var root = new Values.ArchiveNode { Inset = inset };
        using var singular = Values.ModelSnapshot.Reflection.OpenContainmentCursor(root);
        Move(singular, root);
        Move(singular, inset);
        Assert.True(singular.CanReplace);
        Assert.False(singular.CanInsertAfter);
        Assert.Throws<InvalidOperationException>(() => singular.InsertAfterCurrent(new Values.ArchiveNode()));
        singular.ReplaceCurrent(null);
        Assert.Null(root.Inset);
        Assert.Null(inset.InsetOf);
        var next = new Values.ArchiveNode();
        singular.ReplaceCurrent(next);
        Assert.Same(root, next.InsetOf);
        Assert.False(singular.MoveNext());

        var first = new Values.ArchiveNode();
        var last = new Values.ArchiveNode();
        root.Children.AddRange([first, last]);
        using var ordered = Values.ModelSnapshot.Reflection.OpenContainmentCursor(root);
        Move(ordered, root);
        Move(ordered, first);
        Assert.Throws<ArgumentNullException>(() => ordered.ReplaceCurrent(null));
        Assert.Throws<ArgumentNullException>(() => ordered.InsertAfterCurrent(null));
        Assert.Throws<ArgumentException>(() => ordered.ReplaceCurrent(last));
        Assert.Equal(new[] { first, last }, root.Children);
        var added = new Values.ArchiveNode();
        ordered.InsertAfterCurrent(added);
        Assert.Equal(new[] { first, added, last }, root.Children);
        Move(ordered, last);
    }

    [Fact]
    public void UnorderedEdgesTraverseWithoutPositionalEdits()
    {
        var book = new Ordinary.Volume { Name = "book" };
        var shelf = new Ordinary.Shelf();
        shelf.Volumes.Add(book);
        using var cursor = Ordinary.ModelSnapshot.Reflection.OpenContainmentCursor(shelf);
        Move(cursor, shelf);
        Move(cursor, book);
        Assert.False(cursor.CanReplace);
        Assert.False(cursor.CanInsertAfter);
        Assert.Throws<InvalidOperationException>(() => cursor.ReplaceCurrent(new Ordinary.Volume()));
        Assert.Throws<InvalidOperationException>(() => cursor.InsertAfterCurrent(new Ordinary.Volume()));
        Assert.Same(book, Assert.Single(shelf.Volumes));
        shelf.Volumes.Remove(book);
        Assert.Throws<InvalidOperationException>(() => cursor.MoveNext());
    }

    [Fact]
    public void ChangingAnAncestorInvalidatesTheSelectedDescendant()
    {
        var leaf = new Values.ArchiveNode();
        var branch = new Values.ArchiveNode { Inset = leaf };
        var root = new Values.ArchiveNode { Inset = branch };
        using var cursor = Values.ModelSnapshot.Reflection.OpenContainmentCursor(root);
        Move(cursor, root);
        Move(cursor, branch);
        Move(cursor, leaf);
        root.Inset = null;
        root.Inset = branch;

        Assert.False(cursor.CanReplace);
        Assert.Throws<InvalidOperationException>(() => cursor.ReplaceCurrent(null));
        Assert.Same(leaf, branch.Inset);
    }

    [Fact]
    public void AnObserverChangingTheActiveCollectionMakesTheCursorStale()
    {
        var catalog = new Catalog();
        var first = Item("first");
        var last = Item("last");
        catalog.Items.AddRange([first, last]);
        using var cursor = ModelSnapshot.Reflection.OpenContainmentCursor(catalog);
        Move(cursor, catalog);
        Move(cursor, first);
        var concurrentAdd = Item("observer addition");
        using var watch = catalog.VmfChanges.AddPropertyListener("items", _ =>
        {
            if (!catalog.Items.Contains(concurrentAdd)) catalog.Items.Add(concurrentAdd);
        });
        var replacement = Item("replacement");

        Assert.Throws<InvalidOperationException>(() => cursor.ReplaceCurrent(replacement));
        Assert.Equal(new[] { replacement, last, concurrentAdd }, catalog.Items);
        Assert.False(cursor.CanInsertAfter);
        Assert.Throws<InvalidOperationException>(() => cursor.MoveNext());
    }

    [Fact]
    public void ReadOnlyAndImmutableRootsDoNotExposeEditableEdges()
    {
        var catalog = new Catalog();
        Assert.Throws<ArgumentException>(() => ModelSnapshot.Reflection.OpenContainmentCursor(catalog.AsReadOnly()));
        Assert.Throws<ArgumentException>(() =>
            Values.ModelSnapshot.Reflection.OpenContainmentCursor(new Values.ValueLabel("immutable")));
        Assert.Throws<ArgumentNullException>(() => ModelSnapshot.Reflection.OpenContainmentCursor(null!));

        var cursor = ModelSnapshot.Reflection.OpenContainmentCursor(catalog);
        cursor.Dispose();
        cursor.Dispose();
        Assert.False(cursor.CanReplace);
        Assert.Throws<ObjectDisposedException>(() => cursor.MoveNext());
        Assert.Throws<ObjectDisposedException>(() => cursor.TryPeek(out _));
        Assert.Throws<ObjectDisposedException>(() => cursor.ReplaceCurrent(Item("child")));
        Assert.Throws<ObjectDisposedException>(() => cursor.Current);
    }

    private static Item Item(string name) => new() { Name = name };

    private static void Move(ModelContainmentCursor cursor, object expected)
    {
        Assert.True(cursor.MoveNext());
        Assert.Same(expected, cursor.Current);
    }
}
