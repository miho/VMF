using Eu.Mihosoft.Vmf.Conformance.Immutablebehavior;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ImmutableCovariantTests
{
    [Fact]
    public void CovariantImmutableCallsPreserveBothInterfacesAndOneProviderPerValue()
    {
        ImmutableCovariantBindings.BaseCreated = ImmutableCovariantBindings.ChildCreated
            = ImmutableCovariantBindings.GrandchildCreated = 0;
        var wide = new ValueResult("wide");
        var narrow = new NarrowValueResult("narrow", 9);
        var root = new BaseValue(wide);
        var child = new ChildValue(narrowStored: narrow, stored: wide);
        var grandchild = new GrandchildValue(narrowStored: narrow, stored: wide);
        Assert.Equal((0, 0, 0), (ImmutableCovariantBindings.BaseCreated, ImmutableCovariantBindings.ChildCreated,
            ImmutableCovariantBindings.GrandchildCreated));

        Assert.Same(wide, ((IBaseValueCalls)root).Evaluate());
        Assert.Same(narrow, ((IBaseValueCalls)child).Evaluate());
        Assert.Same(narrow, ((IChildValueCalls)child).Evaluate());
        Assert.Same(narrow, ((IBaseValueCalls)child.AsReadOnly()).Evaluate());

        Assert.Same(narrow, ((IBaseValueCalls)grandchild).Evaluate());
        Assert.Same(narrow, ((IChildValueCalls)grandchild).Evaluate());
        Assert.Same(narrow, ((IGrandchildValueCalls)grandchild).Evaluate());
        Assert.Equal((1, 1, 1), (ImmutableCovariantBindings.BaseCreated, ImmutableCovariantBindings.ChildCreated,
            ImmutableCovariantBindings.GrandchildCreated));

        Assert.Null(typeof(ChildValue).GetMethod(nameof(IChildValueCalls.Evaluate)));
        Assert.Equal(typeof(ValueResult), typeof(IBaseValueCalls).GetMethod(nameof(IBaseValueCalls.Evaluate))?.ReturnType);
        Assert.Equal(typeof(NarrowValueResult), typeof(IChildValueCalls).GetMethod(nameof(IChildValueCalls.Evaluate))?.ReturnType);
        var empty = new ChildValue();
        Assert.Null(((IChildValueCalls)empty).Evaluate());
        Assert.Null(((IBaseValueCalls)empty).Evaluate());
    }
}
