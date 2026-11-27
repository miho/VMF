using Eu.Mihosoft.Vmftest.Cross_ref;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class StockReciprocalBuilderTests
{
    [Fact]
    public void ScalarImportStagesTheReferenceAndApplyUsesTheExistingTakeover()
    {
        var source = new EntityOneA();
        var target = new EntityOneA();
        var selected = new EntityTwoA();
        var displaced = new EntityTwoA();
        source.Ref = selected;
        target.Ref = displaced;
        var trace = new List<string>();
        using var previous = source.VmfChanges.AddPropertyListener("ref", _ => trace.Add("previous owner"));
        using var oldInverse = displaced.VmfChanges.AddPropertyListener("ref", _ => trace.Add("old inverse"));
        using var destination = target.VmfChanges.AddPropertyListener("ref", _ => trace.Add("destination"));
        using var newInverse = selected.VmfChanges.AddPropertyListener("ref", _ => trace.Add("new inverse"));

        var staged = EntityOneA.NewBuilder().ApplyFrom(source);
        Assert.Same(source, selected.Ref);
        Assert.Empty(trace);
        staged.ApplyTo(source);
        Assert.Empty(trace);
        staged.ApplyTo(target);
        Assert.Equal(["previous owner", "old inverse", "destination", "new inverse"], trace);
        Assert.Null(source.Ref);
        Assert.Null(displaced.Ref);
        Assert.Same(selected, target.Ref);
        Assert.Same(target, selected.Ref);

        var built = staged.Build();
        Assert.Null(target.Ref);
        Assert.Same(selected, built.Ref);
        Assert.Same(built, selected.Ref);
        EntityOneA.NewBuilder().WithRefBuilder(EntityTwoA.NewBuilder()).ApplyTo(built);
        Assert.Null(built.Ref);
        Assert.Null(selected.Ref);
    }

    [Fact]
    public void InterfaceDeclaredEndsCanBeImportedFromTheirAbstractRoles()
    {
        Selector source = new Panel { Name = "source" };
        var target = new Panel { Name = "target" };
        var selected = new Button { Name = "selected" };
        source.Selected = selected;
        Panel.NewBuilder().ApplyFrom(source).ApplyTo(target);
        Assert.Null(source.Selected);
        Assert.Same(selected, target.Selected);
        Assert.Same(target, selected.SelectedBy);
        Assert.Null(target.Name);

        Selectable previouslySelected = selected;
        var replacement = new Button();
        Button.NewBuilder().ApplyFrom(previouslySelected).ApplyTo(replacement);
        Assert.Null(selected.SelectedBy);
        Assert.Same(target, replacement.SelectedBy);
        Assert.Same(replacement, target.Selected);
    }

    [Fact]
    public void InheritedAndSelfOppositeScalarSlotsKeepTheirIdentity()
    {
        var owner = new EntityOneA();
        var selected = new EntityTwoA();
        owner.Ref = selected;
        var derived = SpecializedEntityOneA.NewBuilder().WithLabel("derived").ApplyFrom(owner).Build();
        Assert.Null(owner.Ref);
        Assert.Same(derived, selected.Ref);
        Assert.Same(selected, derived.Ref);
        Assert.Equal("derived", derived.Label);

        var self = new Partner();
        var destination = new Partner();
        self.PartnerReference = self;
        Partner.NewBuilder().ApplyFrom(self).ApplyTo(destination);
        Assert.Same(self, destination.PartnerReference);
        Assert.Same(destination, self.PartnerReference);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void OrdinaryReciprocalListImportsRespectModesAndKeepTheirObserverOrder(bool append)
    {
        var one = new EntityOneB { Id = "one" };
        var two = new EntityOneB { Id = "two" };
        var existing = new EntityOneB { Id = "existing" };
        var source = EntityTwoB.NewBuilder().WithRefs([one, null, two, one]).Build();
        var target = EntityTwoB.NewBuilder().WithRefs([existing]).Build();
        var sourceRefs = source.Refs;
        var targetRefs = target.Refs;
        Assert.Equal([null, two, one], sourceRefs);
        var trace = new List<string>();
        using var priorOwner = source.VmfChanges.AddPropertyListener("refs", _ => trace.Add("previous"));
        using var destination = target.VmfChanges.AddPropertyListener("refs", _ => trace.Add("destination"));
        using var firstInverse = one.VmfChanges.AddPropertyListener("ref", _ => trace.Add("one"));
        using var secondInverse = two.VmfChanges.AddPropertyListener("ref", _ => trace.Add("two"));
        using var priorInverse = existing.VmfChanges.AddPropertyListener("ref", _ => trace.Add("existing"));

        var staged = EntityTwoB.NewBuilder().AppendCollections(append).ApplyFrom(source);
        Assert.Empty(trace);
        Assert.Same(source, one.Ref);
        Assert.Same(source, two.Ref);
        Assert.Same(target, existing.Ref);
        staged.ApplyTo(target);
        Assert.Same(sourceRefs, source.Refs);
        Assert.Same(targetRefs, target.Refs);
        Assert.Equal([null], sourceRefs);
        Assert.Equal(append ? [existing, null, two, one] : [null, two, one], targetRefs);
        Assert.Same(target, one.Ref);
        Assert.Same(target, two.Ref);
        Assert.Same(append ? target : null, existing.Ref);
        Assert.Equal(append ? ["previous", "two", "previous", "one", "destination"]
            : ["existing", "destination", "previous", "two", "previous", "one", "destination"], trace);

        trace.Clear();
        var fromTarget = EntityTwoB.NewBuilder().AppendCollections(append).ApplyFrom(target);
        Assert.Empty(trace);
        fromTarget.ApplyTo(target);
        Assert.Equal(append ? [null, existing, null, two, one] : [null, two, one], targetRefs);
        Assert.Equal(append ? ["destination", "destination", "destination", "destination"]
            : ["two", "one", "destination", "two", "one", "destination"], trace);
        Assert.Same(target, one.Ref);
        Assert.Same(target, two.Ref);
    }

    [Fact]
    public void BuildingAnImportedReciprocalListTransfersExactlyTheExistingMembers()
    {
        var first = new EntityOneB { Id = "first" };
        var second = new EntityOneB { Id = "second" };
        var source = EntityTwoB.NewBuilder().WithRefs([first, null, second, first]).Build();
        var staged = EntityTwoB.NewBuilder().ApplyFrom(source);
        Assert.Same(source, first.Ref);
        Assert.Same(source, second.Ref);
        var built = staged.Build();
        Assert.Equal([null], source.Refs);
        Assert.Equal([null, second, first], built.Refs);
        Assert.Same(built, first.Ref);
        Assert.Same(built, second.Ref);
    }

    [Fact]
    public void SingularListInverseImportKeepsTheSourceAndMovesOnlyTheTargetMember()
    {
        var source = new EntityOneB { Id = "source" };
        var target = new EntityOneB { Id = "target" };
        var sibling = new EntityOneB { Id = "sibling" };
        var kept = new EntityOneB { Id = "kept" };
        var parent = EntityTwoB.NewBuilder().WithRefs([source, sibling]).Build();
        var previous = EntityTwoB.NewBuilder().WithRefs([target, kept]).Build();
        var trace = new List<string>();
        using var priorOwner = previous.VmfChanges.AddPropertyListener("refs", _ => trace.Add("old list"));
        using var destination = parent.VmfChanges.AddPropertyListener("refs", _ => trace.Add("new list"));
        using var assignment = target.VmfChanges.AddPropertyListener("ref", change =>
            trace.Add(change.NewValue is null ? "clear" : "assign"));

        var staged = EntityOneB.NewBuilder().ApplyFrom(source);
        Assert.Empty(trace);
        Assert.Same(previous, target.Ref);
        staged.ApplyTo(source);
        Assert.Empty(trace);
        staged.ApplyTo(target);
        Assert.Equal(["clear", "old list", "assign", "new list"], trace);
        Assert.Equal([kept], previous.Refs);
        Assert.Equal([source, sibling, target], parent.Refs);
        Assert.Same(parent, source.Ref);
        Assert.Same(parent, target.Ref);
        Assert.Equal("source", target.Id);

        trace.Clear();
        var built = staged.Build();
        Assert.Equal(["new list"], trace);
        Assert.Equal([source, sibling, target, built], parent.Refs);
        Assert.Same(parent, built.Ref);
    }
}
