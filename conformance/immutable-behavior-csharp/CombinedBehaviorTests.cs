using Eu.Mihosoft.Vmf.Conformance.Immutablebehavior;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class CombinedBehaviorTests
{
    [Fact]
    public void ImmutableProvidersFromBothParentsShareTheSelectedCommonCall()
    {
        var value = new CombinedBehaviorValue(left: 3, right: 7);
        ILeftBehaviorValueCalls left = value;
        IRightBehaviorValueCalls right = value;
        ICombinedBehaviorValueCalls combined = value;

        Assert.Equal(104, left.LeftCall());
        Assert.Equal(105, combined.Preferred());
        Assert.Equal(208, right.RightCall());
        Assert.Equal(106, right.Preferred());
        Assert.Equal(107, left.Preferred());

        var reversed = new ReversedBehaviorValue(left: 3, right: 7);
        IReversedBehaviorValueCalls reversedCalls = reversed;
        Assert.Equal(104, reversedCalls.LeftCall());
        Assert.Equal(208, reversedCalls.Preferred());
        Assert.Equal(209, reversedCalls.RightCall());
        Assert.Equal(210, ((ILeftBehaviorValueCalls)reversed).Preferred());
        Assert.Equal(211, ((IRightBehaviorValueCalls)reversed).Preferred());

        var restored = ModelSnapshot.ReadPlain<CombinedBehaviorValue>(ModelSnapshot.WritePlain(value));
        Assert.Equal((3, 7), (restored.Left, restored.Right));
        Assert.Equal(104, ((IRightBehaviorValueCalls)restored).Preferred());
        Assert.Equal(208, ((ICombinedBehaviorValueCalls)restored).RightCall());
    }
}
