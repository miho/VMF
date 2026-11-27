using Eu.Mihosoft.Vmftest.Collectioncontract;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class StockCollectionContractTests
{
    [Fact]
    public void BoundedOrdinaryBuilderTransfersOwningSlotsAndValidatesOnlyActualMembers()
    {
        var first = new Item { Name = "first" };
        var next = new Item { Name = "next" };
        var source = Catalog.NewBuilder().WithItems([first, null, next]).Build();
        var staged = Catalog.NewBuilder().ApplyFrom(source);
        Assert.Same(source, first.Catalog);
        Assert.Same(source, next.Catalog);
        Assert.Equal(3, source.Items.Count);

        var target = new Catalog();
        var items = target.Items;
        staged.AppendCollections(false).ApplyTo(target);
        Assert.Same(items, target.Items);
        Assert.Collection(items, item => Assert.Same(first, item), Assert.Null, item => Assert.Same(next, item));
        Assert.Same(target, first.Catalog);
        Assert.Same(target, next.Catalog);
        Assert.Null(Assert.Single(source.Items));
        Assert.Empty(target.Validate());

        Catalog.NewBuilder().WithItems([new Item { Name = "extra" }]).ApplyTo(target);
        Assert.Equal(4, items.Count);
        Assert.Equal("/items", Assert.Single(target.Validate()).ModelPath);
    }

    [Fact]
    public void RemovingAnEqualChildNotifiesAndDetachesTheFirstMatchingSlot()
    {
        var catalog = new Catalog();
        var first = new Item { Name = "same" };
        var second = new Item { Name = "same" };
        var detachedEqual = new Item { Name = "same" };
        Assert.Equal(first, second);
        Assert.NotSame(first, second);
        catalog.Items.AddRange([first, second]);
        bool containsDetachedEqual = catalog.Items.Contains(detachedEqual);
        Assert.True(containsDetachedEqual);
        Assert.Equal(0, ((IList<Item?>)catalog.Items).IndexOf(detachedEqual));

        var observed = new List<ModelListChange<Item?>>();
        using var listener = catalog.Items.AddListener(observed.Add);
        catalog.VmfChanges.Start();
        Assert.True(catalog.Items.Remove(second));
        catalog.VmfChanges.Stop();

        Assert.Null(first.Catalog);
        Assert.Same(second, Assert.Single(catalog.Items));
        Assert.Same(catalog, second.Catalog);
        var removal = Assert.Single(Assert.Single(observed).Removed);
        Assert.Equal(0, removal.Index);
        Assert.Same(first, removal.Value);
        var change = Assert.IsType<ModelCollectionChange>(Assert.Single(catalog.VmfChanges.History));
        Assert.Same(first, Assert.Single(change.Removed).Value);
        Assert.True(change.Undo());
        Assert.Same(first, catalog.Items[0]);
        Assert.Same(second, catalog.Items[1]);
        Assert.Same(catalog, first.Catalog);
    }

    [Fact]
    public void OwnershipAndReciprocalTransferSelectTheExactInstanceAmongEqualValues()
    {
        var original = new Catalog();
        var destination = new Catalog();
        var first = new Item { Name = "same" };
        var second = new Item { Name = "same" };
        Assert.Equal(first, second);
        original.Items.AddRange([first, second]);
        destination.Items.Add(second);
        Assert.Same(first, Assert.Single(original.Items));
        Assert.Same(original, first.Catalog);
        Assert.Same(second, Assert.Single(destination.Items));
        Assert.Same(destination, second.Catalog);

        var a = new Member { Name = "same" };
        var b = new Member { Name = "same" };
        var team = new Team();
        a.Teams.Add(team);
        b.Teams.Add(team);
        Assert.Equal(a, b);
        Assert.True(b.Teams.Remove(team));
        Assert.Same(a, Assert.Single(team.Members));
    }
}
