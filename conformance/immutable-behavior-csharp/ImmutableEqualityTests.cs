using Eu.Mihosoft.Vmf.Conformance.Immutablebehavior;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ImmutableEqualityTests
{
    [Fact]
    public void ImmutableObjectEqualityAndOrdinaryCallsUseOneLazyProvider()
    {
        ImmutableEqualityValue.BindingCount = 0;
        var lower = new ImmutableEqualityValue(107);
        var upper = new ImmutableEqualityValue(207);
        var same = new ImmutableEqualityValue(107);
        Assert.Same(lower, lower.AsReadOnly());
        Assert.Same(lower, ModelSnapshot.Reflection.DeepCopy(lower));
        Assert.Equal(0, ImmutableEqualityValue.BindingCount);
        Assert.True(((object)lower.AsReadOnly()).Equals(upper));
        Assert.Equal(7, lower.GetHashCode());
        Assert.Equal(7, upper.GetHashCode());
        Assert.Equal(1, ((IImmutableEqualityValueCalls)lower).Comparisons());
        Assert.Equal(0, ((IImmutableEqualityValueCalls)upper).Comparisons());
        Assert.Equal(2, ImmutableEqualityValue.BindingCount);
        Assert.True(lower.ContentEquals(same));
        Assert.False(lower.ContentEquals(upper));
        Assert.Equal(lower.ContentHashCode(), same.ContentHashCode());
        Assert.Null(typeof(IImmutableEqualityValueCalls).GetMethod(nameof(GetHashCode)));
        Assert.Null(typeof(ImmutableEqualityValue).GetMethod("ReplaceBehavior"));

        VerifySnapshot(references: false);
        VerifySnapshot(references: true);
    }

    private static void VerifySnapshot(bool references)
    {
        var original = new ImmutableEqualityValue(107);
        int previousBindings = ImmutableEqualityValue.BindingCount;
        string Write(ImmutableEqualityValue value) => references
            ? ModelSnapshot.Write(value)
            : ModelSnapshot.WriteWithoutReferences(value);
        string snapshot = Write(original.AsReadOnly());
        Assert.Equal("{\"code\":107}", snapshot);
        Assert.Equal(previousBindings, ImmutableEqualityValue.BindingCount);
        Assert.Equal(7, original.GetHashCode());
        Assert.Equal(snapshot, Write(original));

        var copy = ModelSnapshot.Read<ImmutableEqualityValue>(snapshot);
        Assert.NotSame(original, copy);
        Assert.Equal(previousBindings + 1, ImmutableEqualityValue.BindingCount);
        Assert.Equal(7, copy.GetHashCode());
        Assert.Equal(previousBindings + 2, ImmutableEqualityValue.BindingCount);
    }
}
