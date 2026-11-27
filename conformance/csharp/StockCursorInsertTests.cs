using Eu.Mihosoft.Vmftest.Collectioncontract;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class StockCursorInsertTests
{
    [Fact]
    public void CursorRejectsAnEarlierOwnedIdentityAndStillInsertsFreshAndTransferredChildren()
    {
        var catalog = new Catalog();
        var first = new Item { Name = "same" };
        var second = new Item { Name = "second" };
        var last = new Item { Name = "last" };
        catalog.Items.AddRange([first, second, null, last]);
        using var cursor = CursorAtSecond(catalog, first, second);
        var changes = new List<ModelChange>();
        using var listener = catalog.VmfChanges.AddPropertyListener("items", changes.Add);

        Assert.Throws<ArgumentException>(() => cursor.InsertAfterCurrent(first));
        Assert.Same(first, catalog.Items[0]);
        Assert.Same(second, catalog.Items[1]);
        Assert.Null(catalog.Items[2]);
        Assert.Same(last, catalog.Items[3]);
        Assert.Same(catalog, first.Catalog);
        Assert.Empty(changes);

        var fresh = new Item { Name = "same" };
        Assert.Equal(first, fresh);
        Assert.NotSame(first, fresh);
        cursor.InsertAfterCurrent(fresh);
        Assert.Same(fresh, catalog.Items[2]);
        Assert.Same(catalog, fresh.Catalog);
        Assert.True(cursor.MoveNext());
        Assert.Same(last, cursor.Current);
        Assert.False(cursor.MoveNext());

        var other = new Catalog();
        var transfer = new Item { Name = "transfer" };
        other.Items.Add(transfer);
        using var transferCursor = CursorAtSecond(catalog, first, second);
        transferCursor.InsertAfterCurrent(transfer);
        Assert.Empty(other.Items);
        Assert.Same(catalog, transfer.Catalog);
        Assert.Same(transfer, catalog.Items[2]);
        Assert.True(transferCursor.MoveNext());
        Assert.Same(fresh, transferCursor.Current);
        Assert.True(transferCursor.MoveNext());
        Assert.Same(last, transferCursor.Current);
        Assert.False(transferCursor.MoveNext());
    }

    private static ModelContainmentCursor CursorAtSecond(Catalog catalog, Item first, Item second)
    {
        var cursor = ModelSnapshot.Reflection.OpenContainmentCursor(catalog);
        Assert.True(cursor.MoveNext());
        Assert.Same(catalog, cursor.Current);
        Assert.True(cursor.MoveNext());
        Assert.Same(first, cursor.Current);
        Assert.True(cursor.MoveNext());
        Assert.Same(second, cursor.Current);
        Assert.True(cursor.TryPeek(out _));
        Assert.True(cursor.TryPeek(out _));
        Assert.True(cursor.CanInsertAfter);
        return cursor;
    }
}
