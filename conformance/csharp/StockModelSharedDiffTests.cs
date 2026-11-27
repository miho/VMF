using Xunit;
using Collections = Eu.Mihosoft.Vmf.Conformance.Collectiondefaults;
using Relationships = Eu.Mihosoft.Vmf.Conformance.Collectionredeclarations;
using Core = Eu.Mihosoft.Vmftest.Staticreflection;
using Values = Eu.Mihosoft.Vmf.Conformance.Values;

namespace Vmf.CSharp.Conformance;

[Collection(Collections.CollectionDefaultBindings.TestCollection)]
public sealed class StockModelSharedDiffTests
{
    [Fact]
    public void DiffsSharedStockPropertiesAndPreservesAnImportedAliasAndCycle()
    {
        var source = new Core.TypeC { Name = "retained", PropA1 = 17, PropB1 = 2.5 };
        var incoming = new Core.TypeC { Name = "imported", PropA2 = "reference" };
        incoming.Prop = incoming;
        var desired = new Core.TypeB { PropB1 = 25, Prop = incoming, PropB2 = incoming };
        var reflection = Core.ModelSnapshot.Reflection;

        var patch = reflection.DiffSharedProperties(source, desired.AsReadOnly());
        Assert.Equal(new[] { "prop", "propB1", "propB2" }, patch.Changes.Select(edit => edit.PropertyName).Order());
        Assert.Same(source, patch.Source);
        Assert.Null(source.Prop);
        Assert.Throws<ArgumentException>(() => patch.Apply(reflection.DeepCopy(source)));
        patch.Apply(source);
        Assert.Equal("retained", source.Name);
        Assert.Equal(17, source.PropA1);
        Assert.Equal(25, source.PropB1);
        var copied = Assert.IsType<Core.TypeC>(source.PropB2);
        Assert.NotSame(incoming, copied);
        Assert.Same(copied, source.Prop);
        Assert.Same(copied, copied.Prop);
        Assert.Equal("imported", copied.Name);
        Assert.Equal("reference", copied.PropA2);

        desired.PropB2 = null;
        var clearing = reflection.DiffSharedProperties(source, desired);
        Assert.Equal("propB2", Assert.Single(clearing.Changes).PropertyName);
        clearing.Apply(source);
        Assert.Null(source.PropB2);
        Assert.Same(copied, source.Prop);
        clearing.Undo(source);
        Assert.Same(copied, source.PropB2);
        patch.Undo(source);
        Assert.Equal(2.5, source.PropB1);
        Assert.Null(source.Prop);
        Assert.Null(source.PropB2);
        Assert.Same(incoming, desired.Prop);
        Assert.Same(incoming, incoming.Prop);
    }

    [Fact]
    public void DiffsSharedSingularReferenceContentIntoAnExistingMutableTarget()
    {
        var existing = new Core.TypeA { PropA2 = "before" };
        var incoming = new Core.TypeA { PropA2 = "after" };
        var source = new Core.TypeC { PropB2 = existing };
        var desired = new Core.TypeB { PropB2 = incoming };
        var patch = Core.ModelSnapshot.Reflection.DiffSharedProperties(source, desired);
        var change = Assert.Single(patch.Changes);
        Assert.Same(existing, change.Object);
        Assert.Equal("propA2", change.PropertyName);
        patch.Apply(source);
        Assert.Same(existing, source.PropB2);
        Assert.Equal("after", existing.PropA2);
        patch.Undo(source);
        Assert.Equal("before", existing.PropA2);
    }

    [Fact]
    public void DiffsFrozenCollectionsIntoMutableLiveStorageAndUndoesPresentNull()
    {
        var source = new Collections.ManagedSamples();
        var numbers = source.Numbers;
        var options = source.Options;
        numbers.Clear();
        numbers.AddRange([3, 3]);
        options.Clear();
        options["before"] = null;
        var desired = new Collections.FrozenSamples(numbers: [8], labels: ["frozen"], codes: [9L],
            options: new Dictionary<string, int?> { ["after"] = 11 });

        var patch = Collections.ModelSnapshot.Reflection.DiffSharedProperties(source, desired);
        Assert.Equal(new[] { "codes", "labels", "numbers", "options" },
            patch.Changes.Select(edit => edit.PropertyName).Order());
        patch.Apply(source);
        Assert.Same(numbers, source.Numbers);
        Assert.Same(options, source.Options);
        Assert.Equal(new[] { 8 }, numbers);
        Assert.Equal(new[] { "frozen" }, source.Labels);
        Assert.Equal(9L, Assert.Single(source.Codes));
        Assert.Equal(11, options["after"]);

        patch.Undo(source);
        Assert.Equal(new[] { 3, 3 }, numbers);
        Assert.True(options.ContainsKey("before"));
        Assert.Null(options["before"]);
        Assert.False(options.ContainsKey("after"));
    }

    [Fact]
    public void RejectsAnUnassignableRootBackReferenceBeforeProducingAPatch()
    {
        var source = new Core.TypeB { PropB1 = 2.5 };
        var desired = new Core.TypeC { PropB1 = 25 };
        desired.Prop = desired;
        var failure = Assert.Throws<ArgumentException>(() => Core.ModelSnapshot.Reflection.DiffSharedProperties(source, desired));
        Assert.Contains("'prop'", failure.Message);
        Assert.Equal(2.5, source.PropB1);
        Assert.Null(source.Prop);
    }

    [Theory]
    [InlineData(true, false)]
    [InlineData(false, true)]
    [InlineData(true, true)]
    public void DiffsSharedOwningAndReciprocalFieldsWithTheirOriginalInverses(bool owning, bool reciprocal)
    {
        var source = new Relationships.CollectionBasis();
        var desired = new Relationships.CollectionVariant();
        var oldFirst = new Relationships.CollectionItem { Name = "a" };
        var oldSecond = new Relationships.CollectionItem { Name = "b" };
        var desiredFirst = new Relationships.CollectionItem { Name = "b" };
        var desiredSecond = new Relationships.CollectionItem { Name = "c" };
        if (owning)
        {
            source.Owned.AddRange([oldFirst, oldSecond]);
            desired.Owned.AddRange([desiredFirst, desiredSecond]);
        }
        if (reciprocal)
        {
            source.Featured.Add(oldFirst);
            desired.Featured.Add(desiredSecond);
        }
        source.Trail.Add(oldSecond);
        desired.Trail.Add(desiredFirst);

        var observed = 0;
        using var sourceTrace = source.VmfChanges.AddRecursiveListener(_ => observed++);
        using var desiredTrace = desired.VmfChanges.AddRecursiveListener(_ => observed++);
        var patch = Relationships.ModelSnapshot.Reflection.DiffSharedProperties(source, desired.AsReadOnly());
        Assert.Equal(0, observed);
        var names = new List<string> { "trail" };
        if (owning) names.Add("owned");
        if (reciprocal) names.Add("featured");
        Assert.Equal(names.Order(), patch.Changes.Select(change => change.PropertyName).Order());

        patch.Apply(source);
        var imported = Assert.Single(source.Trail);
        Assert.NotSame(desiredFirst, imported);
        if (owning)
        {
            Assert.Equal(new[] { "b", "c" }, source.Owned.Select(item => item.Name));
            Assert.NotSame(oldSecond, imported);
            Assert.Same(imported, source.Owned[0]);
            Assert.Same(source, imported!.Basis);
            Assert.Null(oldFirst.Basis);
            Assert.Null(oldSecond.Basis);
            Assert.Same(desired, desiredFirst.Basis);
        }
        if (reciprocal)
        {
            var selected = Assert.Single(source.Featured);
            Assert.NotSame(desiredSecond, selected);
            Assert.Same(source, selected.SelectedBy);
            Assert.Same(desired, desiredSecond.SelectedBy);
            Assert.Null(oldFirst.SelectedBy);
            if (owning) Assert.Same(source.Owned[1], selected);
        }

        patch.Undo(source);
        Assert.Same(oldSecond, Assert.Single(source.Trail));
        if (owning)
        {
            Assert.Equal(new[] { oldFirst, oldSecond }, source.Owned);
            Assert.Same(source, oldFirst.Basis);
            Assert.Same(desired, desiredSecond.Basis);
        }
        if (reciprocal)
        {
            Assert.Same(oldFirst, Assert.Single(source.Featured));
            Assert.Same(source, oldFirst.SelectedBy);
            Assert.Same(desired, desiredSecond.SelectedBy);
        }
    }

    [Fact]
    public void RejectsIncompatibleCommonFieldKinds()
    {
        var source = new Values.DefaultSettings();
        var desired = new Values.ScalarBatch();
        desired.Entries.Add(new());
        var failure = Assert.Throws<ArgumentException>(() => Values.ModelSnapshot.Reflection.DiffSharedProperties(source, desired));
        Assert.Contains("'entries'", failure.Message);
        Assert.Empty(source.Entries);
        Assert.Single(desired.Entries);
    }
}
