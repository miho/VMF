using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class MutableToStringTests
{
    [Fact]
    public void MutableObjectOverrideAndReadOnlyViewUseTheLiveModelBinding()
    {
        MutableCaption.BindingCount = NullCaption.BindingCount = 0;
        var plain = new PlainCaption { Label = "plain-data" };
        var unrelated = new UnrelatedCaption { Label = "unrelated-data" };
        var empty = new NullCaption { Label = "still-data" };
        var value = new MutableCaption { Label = "alpha" };
        var derived = new InheritedCaption { Label = "delta" };
        var view = value.AsReadOnly();
        var nullView = empty.AsReadOnly();
        Assert.Equal((0, 0), (MutableCaption.BindingCount, NullCaption.BindingCount));

        Assert.Equal(ModelSnapshot.Reflection.FormatSummary(plain), plain.ToString());
        Assert.Equal(plain.ToString(), plain.AsReadOnly().ToString());
        string unrelatedSummary = unrelated.ToString();
        Assert.Equal(ModelSnapshot.Reflection.FormatSummary(unrelated), unrelatedSummary);

        Assert.Null(((object)nullView).ToString());
        Assert.Null(((object)empty).ToString());
        Assert.Equal(1, NullCaption.BindingCount);
        Assert.Equal("alpha:1", ((object)view).ToString());
        Assert.Equal(1, value.Calls());
        Assert.Equal("alpha:2", ((PlainCaption)value).ToString());
        Assert.Equal(1, MutableCaption.BindingCount);
        Assert.Null(typeof(IReadOnlyMutableCaption).GetMethod(nameof(MutableCaption.Calls)));

        value.Label = "beta";
        Assert.Equal("beta", view.Label);
        Assert.Equal("beta:3", view.ToString());
        Assert.Equal("delta:1", ((MutableCaption)derived).ToString());
        Assert.Equal("delta:2", derived.AsReadOnly().ToString());
        Assert.Equal(2, MutableCaption.BindingCount);

        string before = ModelSnapshot.Write(view);
        var replacement = new CaptionReplacement();
        value.ReplaceBehavior(replacement);
        Assert.Null(value.ToString());
        Assert.Null(view.ToString());
        Assert.Same(value, replacement.Caller);
        Assert.Equal(42, value.Calls());
        Assert.Equal(2, derived.Calls());
        Assert.Equal(2, MutableCaption.BindingCount);
        Assert.Equal(before, ModelSnapshot.Write(view));

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR_HIERARCHY");
        foreach (bool references in new[] { false, true })
        {
            string suffix = references ? "-caption-reference.json" : "-caption-by-value.json";
            string wire = references ? ModelSnapshot.Write(view) : ModelSnapshot.WriteWithoutReferences(view);
            if (exchange is not null)
            {
                string java = File.ReadAllText(Path.Combine(exchange, "java" + suffix));
                Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(wire)));
                VerifyRestored(ModelSnapshot.Read<MutableCaption>(java));
                File.WriteAllText(Path.Combine(exchange, "csharp" + suffix), wire);
            }
            VerifyRestored(ModelSnapshot.Read<MutableCaption>(wire));
        }
    }

    private static void VerifyRestored(MutableCaption loaded)
    {
        int previousBindings = MutableCaption.BindingCount;
        Assert.Equal("beta", loaded.Label);
        Assert.Null(loaded.BehaviorOverride);
        Assert.Equal("beta:1", loaded.AsReadOnly().ToString());
        Assert.Equal(1, loaded.Calls());
        Assert.Equal(previousBindings + 1, MutableCaption.BindingCount);
    }

    private sealed class CaptionReplacement : MutableCaption.Behavior
    {
        public MutableCaption? Caller { get; private set; }

        public string? ToString(MutableCaption caller)
        {
            Caller = caller;
            return null;
        }

        public int Calls(MutableCaption caller) => 42;
    }
}
