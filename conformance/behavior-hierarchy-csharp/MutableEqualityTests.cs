using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class MutableEqualityTests
{
    [Fact]
    public void DelegatedObjectEqualityIsLiveAndLeavesIdentityMembershipAndStructuralContentIntact()
    {
        EqualityItem.BindingCount = 0;
        var lower = new EqualityItem { Code = "ab" };
        var upper = new EqualityItem { Code = "AB" };
        var same = new EqualityItem { Code = "ab" };
        var view = lower.AsReadOnly();
        var basket = new EqualityBasket();
        Assert.True(basket.Items.Add(lower));
        Assert.True(basket.Items.Add(upper));
        Assert.True(basket.Selected.Add(lower));
        Assert.True(basket.Selected.Add(upper));
        Assert.False(basket.Selected.Add(lower));
        Assert.False(basket.Selected.Contains(same));
        Assert.Equal(0, EqualityItem.BindingCount);

        Assert.True(((object)lower).Equals(upper));
        Assert.True(view.Equals(upper));
        Assert.True(upper.Equals(view));
        Assert.Equal(3105, lower.GetHashCode());
        Assert.Equal(view.GetHashCode(), upper.GetHashCode());
        Assert.False(lower.Equals(null));
        Assert.Equal(2, EqualityItem.BindingCount);
        Assert.False(lower.ContentEquals(upper));
        Assert.True(lower.ContentEquals(same));
        Assert.Equal(lower.ContentHashCode(), same.ContentHashCode());
        Assert.True(basket.AsReadOnly().Selected.Contains(view));
        Assert.False(basket.AsReadOnly().Selected.Contains(same.AsReadOnly()));
        Assert.Equal(2, EqualityItem.BindingCount);

        lower.Code = "changed";
        Assert.False(view.Equals(upper));
        lower.Code = "ab";
        string before = ModelSnapshot.Write(basket.AsReadOnly());
        var replacement = new Replacement();
        lower.ReplaceBehavior(replacement);
        Assert.False(view.Equals(upper));
        Assert.Same(lower, replacement.Caller);
        Assert.True(view.Equals(same));
        Assert.Equal(17, view.GetHashCode());
        Assert.Equal(3105, upper.GetHashCode());
        Assert.True(lower.ContentEquals(same));
        Assert.Equal(2, EqualityItem.BindingCount);
        Assert.Equal(before, ModelSnapshot.Write(basket.AsReadOnly()));

        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR_HIERARCHY") is { } exchange)
        {
            string java = File.ReadAllText(Path.Combine(exchange, "java-delegated-equality.json"));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(before)));
            VerifyRestored(ModelSnapshot.Read<EqualityBasket>(java));
            File.WriteAllText(Path.Combine(exchange, "csharp-delegated-equality.json"), before);
        }
        VerifyRestored(ModelSnapshot.Read<EqualityBasket>(before));
    }

    private static void VerifyRestored(EqualityBasket basket)
    {
        int previousBindings = EqualityItem.BindingCount;
        var lower = basket.Items[0];
        var upper = basket.Items[1];
        Assert.NotSame(lower, upper);
        Assert.Equal(2, basket.Selected.Count);
        Assert.True(basket.Selected.Contains(lower));
        Assert.True(basket.Selected.Contains(upper));
        Assert.Null(lower.BehaviorOverride);
        Assert.Equal(previousBindings, EqualityItem.BindingCount);
        Assert.True(lower.AsReadOnly().Equals(upper));
        Assert.Equal(lower.GetHashCode(), upper.GetHashCode());
        Assert.Equal(previousBindings + 2, EqualityItem.BindingCount);
    }

    private sealed class Replacement : EqualityItem.Behavior
    {
        public EqualityItem? Caller { get; private set; }

        public bool Equals(EqualityItem caller, object? other)
        {
            Caller = caller;
            return caller.Code == EqualityItem.CodeOf(other);
        }

        public int GetHashCode(EqualityItem caller) => 17;
    }
}
