using System.Collections;
using Builders = Eu.Mihosoft.Vmftest.Builders;
using Ends = Eu.Mihosoft.Vmftest.Referenceends;
using Equality = Eu.Mihosoft.Vmftest.Equals;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class StockReferenceBuilderTests
{
    [Fact]
    public void EvaluatesEachStagedNestedBuilderOnlyDuringBuild()
    {
        var child = Builders.Child.NewBuilder().WithValue(1);
        var single = Builders.Child2.NewBuilder().WithValue(2);
        var counted = new CountingBuilder<Builders.Child>(child);
        var inputs = new List<IModelBuilder<Builders.Child>?> { counted, null, counted };
        var parent = Builders.AClass.NewBuilder().WithChildrenBuilders(inputs).WithChildBuilder(single);
        inputs.Clear();

        var existing = new Builders.Child();
        var target = Builders.AClass.NewBuilder().WithChildren([existing]).WithChild(new Builders.Child2()).Build();
        parent.ApplyTo(target);
        Assert.Null(target.Child);
        Assert.Same(existing, Assert.Single(target.Children));
        parent.AppendCollections(false).ApplyTo(target);
        Assert.Empty(target.Children);
        Assert.Equal(0, counted.Calls);

        child.WithValue(7);
        single.WithValue(8);
        var first = parent.Build();
        child.WithValue(9);
        single.WithValue(10);
        var second = parent.Build();

        Assert.Equal(4, counted.Calls);
        Assert.Equal(8, first.Child!.Value);
        Assert.Equal(10, second.Child!.Value);
        Assert.NotSame(first.Child, second.Child);
        Assert.Collection(first.Children, item => Assert.Equal(7, item!.Value), Assert.Null, item => Assert.Equal(7, item!.Value));
        Assert.Collection(second.Children, item => Assert.Equal(9, item!.Value), Assert.Null, item => Assert.Equal(9, item!.Value));
        Assert.NotSame(first.Children[0], first.Children[2]);
        Assert.NotSame(first.Children[0], second.Children[0]);

        var direct = parent.WithChildren([existing]).WithChild(first.Child).Build();
        Assert.Same(existing, Assert.Single(direct.Children));
        Assert.Same(first.Child, direct.Child);
        Assert.Null(parent.WithChildBuilder(null).Build().Child);
        Assert.Equal(4, counted.Calls);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void ImportReplacesThePendingNestedSource(bool append)
    {
        var child = Builders.Child2.NewBuilder().WithValue(1).Build();
        var leaf = Builders.Child.NewBuilder().WithValue(2).Build();
        var source = Builders.AClass.NewBuilder().WithChild(child).WithChildren([leaf, null, leaf]).Build();

        var lazyChild = Builders.Child2.NewBuilder().WithValue(11);
        var lazyLeaf = new CountingBuilder<Builders.Child>(Builders.Child.NewBuilder().WithValue(12));
        var parent = Builders.AClass.NewBuilder().AppendCollections(append)
            .WithChildBuilder(lazyChild).WithChildrenBuilders([lazyLeaf]).ApplyFrom(source);

        var target = new Builders.AClass();
        parent.ApplyTo(target);
        foreach (var imported in new[] { parent.Build(), target })
        {
            Assert.Same(child, imported.Child);
            Assert.Collection(imported.Children, item => Assert.Same(leaf, item), Assert.Null, item => Assert.Same(leaf, item));
        }
        Assert.Equal(0, lazyLeaf.Calls);

        parent.WithChildBuilder(lazyChild).WithChildrenBuilders([lazyLeaf]);
        lazyChild.WithValue(13);
        var rebuilt = parent.Build();
        Assert.Equal(13, rebuilt.Child!.Value);
        Assert.Equal(12, Assert.Single(rebuilt.Children)!.Value);
        Assert.Equal(1, lazyLeaf.Calls);

        var cleared = parent.ApplyFrom(new Builders.AClass()).Build();
        Assert.Null(cleared.Child);
        Assert.Empty(cleared.Children);
        Assert.Equal(1, lazyLeaf.Calls);
    }

    private sealed class CountingBuilder<T>(IModelBuilder<T> source) : IModelBuilder<T>
    {
        public int Calls { get; private set; }

        public T Build()
        {
            Calls++;
            return source.Build();
        }
    }

    [Fact]
    public void BoundedPositionalReferencesKeepTheirListAndSetSemantics()
    {
        var a = new Ends.Station { Name = "a" };
        var b = new Ends.Station { Name = "b" };
        var c = new Ends.Station { Name = "c" };
        var kept = new Ends.Station { Name = "kept" };
        var source = Ends.BoundedPicks.NewBuilder().WithTrail([a, null, b, a]).WithChoices([a, b, c]).Build();
        var builder = Ends.BoundedPicks.NewBuilder().ApplyFrom(source);
        var target = Ends.BoundedPicks.NewBuilder().WithTrail([kept]).WithChoices([kept]).Build();
        var trail = target.Trail;
        var choices = target.Choices;

        builder.ApplyTo(target);
        Assert.Same(trail, target.Trail);
        Assert.Same(choices, target.Choices);
        Assert.Collection(trail, value => Assert.Same(kept, value), value => Assert.Same(a, value), Assert.Null,
            value => Assert.Same(b, value), value => Assert.Same(a, value));
        Assert.Collection(choices, value => Assert.Same(kept, value), value => Assert.Same(a, value),
            value => Assert.Same(b, value), value => Assert.Same(c, value));
        source.Trail.Clear();
        source.Choices.Clear();

        builder.AppendCollections(false).ApplyTo(target);
        Assert.Collection(trail, value => Assert.Same(a, value), Assert.Null,
            value => Assert.Same(b, value), value => Assert.Same(a, value));
        Assert.Collection(choices, value => Assert.Same(a, value), value => Assert.Same(b, value),
            value => Assert.Same(c, value));
        Assert.Equal(new[] { "/choices", "/trail" }, target.Validate().Select(issue => issue.ModelPath));
    }

    [Fact]
    public void BoundedOrderedBuilderRetainsExistingSlotsAndAppliesOwningTransfers()
    {
        var a = new Ends.Station { Name = "a" };
        var b = new Ends.Station { Name = "b" };
        var c = new Ends.Station { Name = "c" };
        var source = Ends.Workshop.NewBuilder().WithStations([a, b]).WithFavorites([a]).Build();
        var target = Ends.Workshop.NewBuilder().WithStations([c]).Build();
        var staged = Ends.Workshop.NewBuilder().ApplyFrom(source);
        Assert.Same(source, a.Workshop);
        Assert.Same(source, b.Workshop);

        var stations = target.Stations;
        staged.ApplyTo(target);
        Assert.Same(stations, target.Stations);
        Assert.Collection(stations, station => Assert.Same(c, station), station => Assert.Same(a, station),
            station => Assert.Same(b, station));
        Assert.Same(target, a.Workshop);
        Assert.Same(target, b.Workshop);
        Assert.Empty(source.Stations);
        Assert.Same(a, Assert.Single(source.Favorites));
        Assert.Same(a, Assert.Single(target.Favorites));
        Assert.Equal("/stations", Assert.Single(target.Validate()).ModelPath);

        Ends.Workshop.NewBuilder().WithStations([b, a]).AppendCollections(false).ApplyTo(target);
        Assert.Collection(stations, station => Assert.Same(b, station), station => Assert.Same(a, station));
        Assert.Null(c.Workshop);
        Assert.Empty(target.Validate());
    }

    [Fact]
    public void CopiesAnOrdinaryListAndSingularReferenceWithoutChangingAliases()
    {
        var leaf = new Builders.Child();
        var single = new Builders.Child2();
        var source = Builders.AClass.NewBuilder()
            .WithName("source").WithChildren([leaf, null, leaf]).WithChild(single).Build();
        var builder = Builders.AClass.NewBuilder().AppendCollections(false).ApplyFrom(source);
        source.Children.Clear();
        source.Child = null;

        var target = Builders.AClass.NewBuilder().WithChildren([new Builders.Child()]).Build();
        var list = target.Children;
        builder.ApplyTo(target);
        Assert.Same(list, target.Children);
        Assert.Equal("source", target.Name);
        Assert.Collection(list, item => Assert.Same(leaf, item), Assert.Null, item => Assert.Same(leaf, item));
        Assert.Same(single, target.Child);

        builder.AppendCollections(true).ApplyTo(target);
        Assert.Equal(6, list.Count);
        Assert.Same(leaf, list[3]);
        Assert.Null(list[4]);
        Assert.Same(leaf, list[5]);
        Assert.Empty(source.Children);
    }

    [Fact]
    public void CopiesAStockSingularReferenceAndTheInheritedName()
    {
        var reference = Equality.AReference.NewBuilder().WithName("shared").Build();
        var source = Equality.EqualsTestModelAllEq.NewBuilder()
            .WithName("source").WithValue(3).WithReference(reference).Build();
        var target = new Equality.EqualsTestModelAllEq();

        Equality.EqualsTestModelAllEq.NewBuilder().ApplyFrom(source).ApplyTo(target);

        Assert.Same(reference, target.Reference);
        Assert.Equal(source, target);
    }

    [Fact]
    public void UsesTheInheritedLiteralDefaultUntilAnAncestorIsImported()
    {
        var target = Builders.InheritedDefaultChild.NewBuilder().WithValue(99).Build();
        var builder = Builders.InheritedDefaultChild.NewBuilder();
        builder.ApplyTo(target);
        Assert.Equal(123, target.Value);

        var source = Builders.DefaultChild.NewBuilder().WithValue(57).Build();
        builder.ApplyFrom(source).ApplyTo(target);
        Assert.Equal(57, target.Value);
        Assert.Equal(57, builder.Build().Value);
    }

    [Fact]
    public void ReplacesReferenceSetMembershipWithoutTransferringOwnershipOrDefaults()
    {
        var shared = new Ends.Station();
        var owner = Ends.Workshop.NewBuilder().WithStations([shared]).Build();
        var source = Ends.DefaultNetwork.NewBuilder().WithNearby([shared]).Build();
        var builder = Ends.DefaultNetwork.NewBuilder();
        var seed = Assert.Single(builder.Build().Nearby);
        builder.ApplyFrom(source);

        var target = new Ends.DefaultNetwork();
        var members = target.Nearby;
        var targetSeed = Assert.Single(members);
        members.Add(shared);
        var changes = new List<ModelChange>();
        using (target.VmfChanges.AddPropertyListener("nearby", changes.Add))
            builder.AppendCollections(false).ApplyTo(target);

        Assert.Same(members, target.Nearby);
        Assert.Equal(2, members.Count);
        Assert.True(members.Contains(seed));
        Assert.True(members.Contains(shared));
        Assert.Same(owner, shared.Workshop);
        Assert.Same(shared, Assert.Single(source.Nearby));
        Assert.Collection(changes,
            change =>
            {
                var edit = Assert.IsType<ModelCollectionChange>(change);
                Assert.Equal(ModelChangeKind.ReferenceSet, edit.Kind);
                Assert.Same(targetSeed, Assert.Single(edit.Removed).Value);
                Assert.Empty(edit.Added);
            },
            change =>
            {
                var edit = Assert.IsType<ModelCollectionChange>(change);
                Assert.Equal(ModelChangeKind.ReferenceSet, edit.Kind);
                Assert.Same(seed, Assert.Single(edit.Added).Value);
                Assert.Empty(edit.Removed);
            });

        var field = Ends.ModelSnapshot.Reflection.Reflect(target).FindProperty("nearby")!;
        Assert.True(field.IsSet);
        Assert.Same(targetSeed, Assert.Single(((IEnumerable)field.DefaultValue!).Cast<object>()));
        field.Reset();
        Assert.Same(targetSeed, Assert.Single(members));
        Assert.False(field.IsSet);

        Assert.Throws<ArgumentException>(() => builder.WithNearby([shared, shared]));
        Assert.True(builder.Build().Nearby.Contains(seed));
        Assert.Same(owner, shared.Workshop);
    }
}
