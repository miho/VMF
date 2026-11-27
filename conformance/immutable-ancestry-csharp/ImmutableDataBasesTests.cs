using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Immutableancestry;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ImmutableDataBasesTests
{
    [Fact]
    public void SecondaryContractSharesTheConcreteImmutableValueAndFrozenReferences()
    {
        var value = CreateJoinedValue();
        PrimaryDataValue primary = value;
        ISecondaryDataValue secondary = value;
        Assert.Same(primary, secondary);
        Assert.Same(value, primary.AsReadOnly());
        Assert.Same(value, secondary.AsReadOnly());
        Assert.Equal(4, primary.Rank);
        Assert.Equal(13L, secondary.Weight);
        Assert.Equal("shared", secondary.Title);
        var reflection = ModelSnapshot.Reflection.Reflect(value);
        Assert.Equal(4, reflection.Properties.Count);
        var weight = reflection.FindProperty("weight")!;
        Assert.Equal(13L, Assert.IsType<long>(weight.DefaultValue));
        Assert.False(weight.IsSet);

        var other = CreateJoinedValue();
        var first = FrozenJoinedSelection.NewBuilder().WithSelected(value).WithCandidates([value, null]).Build();
        var second = FrozenJoinedSelection.NewBuilder().WithSelected(other).WithCandidates([other, null]).Build();
        Assert.NotSame(first.Selected, second.Selected);
        Assert.True(first.ContentEquals(second));
        Assert.Equal(first.ContentHashCode(), second.ContentHashCode());
    }

    [Fact]
    public void BothParentDeclarationsExchangeTheComposedSubtype()
    {
        var value = CreateJoinedValue();
        var envelope = new JoinedDataEnvelope { Primary = value, Secondary = value };
        envelope.Selections.AddRange([value, null, value]);
        var projected = envelope.AsReadOnly();
        Assert.Same(value, projected.Primary);
        Assert.Same(value, projected.Secondary);
        Assert.Same(value, projected.Selections[0]);

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_IMMUTABLE_ANCESTRY");
        foreach (bool references in new[] { true, false })
        {
            string suffix = references ? "-joined-reference.json" : "-joined-by-value.json";
            string wire = references ? ModelSnapshot.Write(envelope) : ModelSnapshot.WriteWithoutReferences(envelope);
            if (exchange is not null)
            {
                var java = File.ReadAllText(Path.Combine(exchange, "java" + suffix));
                Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(wire)));
                File.WriteAllText(Path.Combine(exchange, "csharp" + suffix), wire);
                wire = java;
            }

            var restored = references ? ModelSnapshot.Read<JoinedDataEnvelope>(wire)
                : ModelSnapshot.ReadWithoutReferences<JoinedDataEnvelope>(wire);
            var primary = Assert.IsType<JoinedDataValue>(restored.Primary);
            var secondary = Assert.IsType<JoinedDataValue>(restored.Secondary);
            var first = Assert.IsType<JoinedDataValue>(restored.Selections[0]);
            Assert.Null(restored.Selections[1]);
            var last = Assert.IsType<JoinedDataValue>(restored.Selections[2]);
            Assert.Equal(references, ReferenceEquals(primary, secondary));
            Assert.Equal(references, ReferenceEquals(first, last));
            Assert.Equal(4, primary.Rank);
            Assert.Equal(13L, secondary.Weight);
            Assert.Equal("shared", secondary.Title);
        }
    }

    [Fact]
    public void ScalarRedeclarationsUseTheConcreteContractThroughGetterOnlyTraits()
    {
        var baseline = ModelSnapshot.ReadPlain<DescribedValue>("{}");
        IReadOnlyWithDisplayLabel label = baseline;
        IReadOnlyWithTooltipText tooltip = baseline;
        Assert.Equal("item", label.Label);
        Assert.Equal("", tooltip.Tooltip);
        Assert.Same(baseline, baseline.AsReadOnly());
        Assert.Equal(baseline, DescribedValue.NewBuilder().WithTooltip("other").Build());
        Assert.Equal(2, ModelSnapshot.Reflection.Reflect(baseline).Properties.Count);

        string wire = ModelSnapshot.WritePlain(DescribedValue.NewBuilder().WithLabel(null).WithTooltip("shown").Build());
        var fields = JsonNode.Parse(wire)!.AsObject();
        Assert.True(fields.ContainsKey("display_label"));
        Assert.Null(fields["display_label"]);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_IMMUTABLE_ANCESTRY") is { } exchange)
        {
            File.WriteAllText(Path.Combine(exchange, "csharp-redeclared-traits.json"), wire);
            wire = File.ReadAllText(Path.Combine(exchange, "java-redeclared-traits.json"));
            Assert.True(JsonNode.DeepEquals(fields, JsonNode.Parse(wire)));
        }
        var restored = ModelSnapshot.ReadPlain<DescribedValue>(wire);
        Assert.Null(restored.Label);
        Assert.Equal("shown", restored.Tooltip);
    }

    private static JoinedDataValue CreateJoinedValue() =>
        JoinedDataValue.NewBuilder().WithRank(4).WithName("joined").Build();
}
