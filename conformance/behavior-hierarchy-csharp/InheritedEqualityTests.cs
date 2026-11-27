using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class InheritedEqualityTests
{
    [Fact]
    public void EqualityInheritsOneProviderAndUsesTheDerivedReplacementThroughExistingViews()
    {
        InheritedEqualityBase.BindingCount = 0;
        InheritedEqualityBase.Callers.Clear();
        var lower = new InheritedEqualityItem { Code = 107, Rank = 9 };
        var upper = new InheritedEqualityItem { Code = 207, Rank = 9 };
        var same = new InheritedEqualityItem { Code = 107, Rank = 9 };
        InheritedEqualityBase model = lower;
        var view = lower.AsReadOnly();
        var basket = new InheritedEqualityBasket();
        Assert.True(basket.Items.Add(lower));
        Assert.True(basket.Items.Add(upper));
        Assert.True(basket.Selected.Add(lower));
        Assert.True(basket.Selected.Add(upper));
        Assert.False(basket.Selected.Add(lower));
        Assert.False(basket.Selected.Contains(same));
        Assert.Equal(0, InheritedEqualityBase.BindingCount);

        Assert.True(((object)model).Equals(upper));
        Assert.True(view.Equals(upper));
        Assert.True(upper.Equals(view));
        Assert.Equal(7, view.GetHashCode());
        Assert.Equal(7, upper.GetHashCode());
        Assert.Equal(7, model.GetHashCode());
        Assert.Equal(2, lower.Comparisons());
        Assert.Equal(1, upper.Comparisons());
        Assert.Equal(2, InheritedEqualityBase.BindingCount);
        Assert.Same(lower, InheritedEqualityBase.Callers[0]);
        Assert.Same(upper, InheritedEqualityBase.Callers[1]);
        Assert.True(model.ContentEquals(same));
        Assert.False(model.ContentEquals(upper));
        Assert.Equal(model.ContentHashCode(), same.ContentHashCode());
        Assert.True(basket.AsReadOnly().Selected.Contains(view));
        Assert.False(basket.AsReadOnly().Selected.Contains(same.AsReadOnly()));

        lower.Code = 108;
        Assert.False(view.Equals(upper));
        lower.Code = 107;
        string before = ModelSnapshot.Write(basket.AsReadOnly());
        var replacement = new Replacement();
        lower.ReplaceBehavior(replacement);
        Assert.False(model.Equals(upper));
        Assert.False(view.Equals(upper));
        Assert.Same(lower, replacement.Caller);
        Assert.Equal(17, view.GetHashCode());
        Assert.Equal(42, lower.Comparisons());
        Assert.Equal(7, upper.GetHashCode());
        Assert.Equal(2, InheritedEqualityBase.BindingCount);
        Assert.Equal(before, ModelSnapshot.Write(basket.AsReadOnly()));

        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR_HIERARCHY") is { } exchange)
        {
            string java = File.ReadAllText(Path.Combine(exchange, "java-inherited-equality.json"));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(before)));
            VerifyRestored(ModelSnapshot.Read<InheritedEqualityBasket>(java));
            File.WriteAllText(Path.Combine(exchange, "csharp-inherited-equality.json"), before);
        }
        VerifyRestored(ModelSnapshot.Read<InheritedEqualityBasket>(before));
    }

    private static void VerifyRestored(InheritedEqualityBasket basket)
    {
        int previousBindings = InheritedEqualityBase.BindingCount;
        var lower = basket.Items[0];
        var upper = basket.Items[1];
        Assert.NotSame(lower, upper);
        Assert.Equal(9, lower.Rank);
        Assert.Equal(9, upper.Rank);
        Assert.Equal(2, basket.Selected.Count);
        Assert.True(basket.Selected.Contains(lower));
        Assert.True(basket.Selected.Contains(upper));
        Assert.Null(lower.BehaviorOverride);
        Assert.Equal(previousBindings, InheritedEqualityBase.BindingCount);
        Assert.True(lower.AsReadOnly().Equals(upper));
        Assert.Equal(lower.GetHashCode(), upper.GetHashCode());
        Assert.Equal(previousBindings + 2, InheritedEqualityBase.BindingCount);
        Assert.Same(lower, InheritedEqualityBase.Callers[previousBindings]);
    }

    private sealed class Replacement : InheritedEqualityItem.Behavior
    {
        public InheritedEqualityBase? Caller { get; private set; }

        public bool Equals(InheritedEqualityBase caller, object? other)
        {
            Caller = caller;
            return caller.Code == InheritedEqualityBase.CodeOf(other);
        }

        public int GetHashCode(InheritedEqualityBase caller) => 17;

        public int Comparisons(InheritedEqualityBase caller) => 42;
    }
}
