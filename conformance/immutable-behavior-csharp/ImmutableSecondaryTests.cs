using Eu.Mihosoft.Vmf.Conformance.Immutablebehavior;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ImmutableSecondaryTests
{
    [Fact]
    public void SecondaryDataAndCallInterfacesKeepTheExactImmutableOwnerAndProvider()
    {
        ImmutableSecondaryBindings.ComposedCreated = ImmutableSecondaryBindings.SecondaryCreated
            = ImmutableSecondaryBindings.ChildCreated = 0;
        ImmutableSecondaryBindings.Calls.Clear();
        var composed = new ComposedValue(metric: 5, name: "composed");
        var secondary = new SecondaryBehavior(metric: 10);
        var child = new ComposedChild(metric: 15, name: "child");
        PrimaryData primary = composed;
        ISecondaryBehavior data = composed;
        ISecondaryBehaviorCalls inherited = composed;
        IComposedValueCalls calls = composed;

        Assert.Equal("composed", primary.Name);
        Assert.Equal(5, data.Metric);
        Assert.Same(composed, data.AsReadOnly());
        Assert.Same(composed, inherited);
        Assert.Equal((0, 0, 0), (ImmutableSecondaryBindings.ComposedCreated, ImmutableSecondaryBindings.SecondaryCreated,
            ImmutableSecondaryBindings.ChildCreated));

        Assert.Equal(6, inherited.Counter());
        Assert.Equal(7, calls.Counter());
        Assert.Equal(8, ((ISecondaryBehaviorCalls)data.AsReadOnly()).Counter());
        Assert.Equal(11, ((ISecondaryBehaviorCalls)secondary).Counter());
        Assert.Equal(16, ((IComposedChildCalls)child).Counter());
        Assert.Equal(17, ((ISecondaryBehaviorCalls)child).Counter());
        Assert.Equal((1, 1, 1), (ImmutableSecondaryBindings.ComposedCreated, ImmutableSecondaryBindings.SecondaryCreated,
            ImmutableSecondaryBindings.ChildCreated));

        var seen = ImmutableSecondaryBindings.Calls;
        Assert.Equal(6, seen.Count);
        Assert.All(seen.Take(3), call =>
        {
            Assert.Same(composed, call.Owner);
            Assert.Same(seen[0].Provider, call.Provider);
        });
        Assert.Same(secondary, seen[3].Owner);
        Assert.Same(child, seen[4].Owner);
        Assert.Same(child, seen[5].Owner);
        Assert.Same(seen[4].Provider, seen[5].Provider);
        Assert.NotSame(seen[0].Provider, seen[3].Provider);
        Assert.NotSame(seen[0].Provider, seen[4].Provider);
        Assert.Null(typeof(ISecondaryBehavior).GetMethod(nameof(ISecondaryBehaviorCalls.Counter)));
    }

    [Fact]
    public void ClassLevelSecondaryBindingStaysLazyAndPreservesImmutableGraphAliases()
    {
        HookedSecondaryBindings.Created = 0;
        HookedSecondaryBindings.Callers.Clear();
        var value = HookedComposed.NewBuilder().WithName("composed").WithScale(5).Build();
        IHookedSecondary data = value;
        var separate = new HookedSecondary(scale: 10);

        Assert.Equal(5, data.Scale);
        Assert.Same(value, data.AsReadOnly());
        Assert.Equal(0, HookedSecondaryBindings.Created);
        Assert.Equal(6, ((IHookedSecondaryCalls)data).Counter());
        Assert.Equal(7, ((IHookedComposedCalls)value).Counter());
        Assert.Equal(11, ((IHookedSecondaryCalls)separate).Counter());
        Assert.Equal(2, HookedSecondaryBindings.Created);
        Assert.Collection(HookedSecondaryBindings.Callers,
            caller => Assert.Same(value, caller),
            caller => Assert.Same(value, caller),
            caller => Assert.Same(separate, caller));

        var envelope = new HookedValueEnvelope { First = data, Second = value };
        var view = envelope.AsReadOnly();
        var references = ModelSnapshot.Read<HookedValueEnvelope>(ModelSnapshot.Write(view));
        Assert.Same(references.First, references.Second);
        Assert.Equal("composed", Assert.IsType<HookedComposed>(references.First).Name);

        var byValue = ModelSnapshot.ReadWithoutReferences<HookedValueEnvelope>(ModelSnapshot.WriteWithoutReferences(view));
        Assert.NotSame(byValue.First, byValue.Second);
        Assert.Equal("composed", Assert.IsType<HookedComposed>(byValue.First).Name);
        Assert.Equal(5, byValue.Second!.Scale);
        Assert.Equal(2, HookedSecondaryBindings.Created);
    }
}
