using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Immutableancestry;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ImmutableAncestryTests
{
    [Fact]
    public void InheritedContractsDispatchAgainstTheConcreteRuntimeValue()
    {
        BindingTrace.Values.Clear();
        var baseValue = new BaseValue(3);
        var inherited = new InheritedValue(5, "inherited");
        var special = new SpecialValue(7, 4);
        var leaf = new LeafValue(8, 6, "leaf");
        Assert.Empty(BindingTrace.Values);
        Assert.Null(typeof(BaseValue).GetMethod("Score"));
        Assert.Null(typeof(SpecialValue).GetMethod("Adjust"));
        Assert.Null(typeof(LeafValue).GetMethod("ReplaceBehavior"));
        Assert.Same(leaf, leaf.AsReadOnly());

        var baseCalls = (IBaseValueCalls)baseValue;
        Assert.Equal(5, baseCalls.Score(2));
        Assert.Equal("base:3", baseCalls.Render("base:"));
        IInheritedValueCalls inheritedCalls = (IInheritedValueCalls)inherited;
        Assert.Equal(7, inheritedCalls.Score(2));
        Assert.Equal("inherited:5", inheritedCalls.Render("inherited:"));

        IBaseValueCalls specialAsBase = (IBaseValueCalls)special;
        Assert.Equal(36, specialAsBase.Score(2));
        Assert.Equal(12, ((ISpecialValueCalls)special).Adjust(3));
        Assert.Equal(["base:BaseValue", "base:InheritedValue", "special:SpecialValue"], BindingTrace.Values);
        Assert.Equal("special:7", specialAsBase.Render("special:"));

        ILeafValueCalls leafCalls = (ILeafValueCalls)leaf;
        ISpecialValueCalls leafAsSpecial = leafCalls;
        IBaseValueCalls leafAsBase = leafAsSpecial;
        Assert.Equal(60, leafAsBase.Score(2));
        Assert.Equal(18, leafAsSpecial.Adjust(3));
        Assert.Equal("leaf:8", leafCalls.Render("leaf:"));
        Assert.Equal(["base:BaseValue", "base:InheritedValue", "special:SpecialValue",
            "base:SpecialValue", "special:LeafValue", "base:LeafValue"], BindingTrace.Values);
    }

    [Fact]
    public void SnapshotSchemaAndWireKeepTheConcreteDataType()
    {
        BindingTrace.Values.Clear();
        var graph = new ValueEnvelope { Value = new LeafValue(8, 6, "leaf") };
        string wire = ModelSnapshot.WriteWithoutReferences(graph);
        const string Expected = """
            {"value":{"@vmf-type":"eu.mihosoft.vmf.conformance.immutableancestry.LeafValue","base":8,"factor":6,"label":"leaf"}}
            """;
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(Expected), JsonNode.Parse(wire)));
        Assert.Empty(BindingTrace.Values);
        var schema = JsonNode.Parse(ModelSnapshot.GenerateJsonSchema<ValueEnvelope>())!;
        Assert.NotNull(schema["definitions"]!["eu.mihosoft.vmf.conformance.immutableancestry.LeafValue"]?["properties"]);
        Assert.Empty(BindingTrace.Values);

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_IMMUTABLE_ANCESTRY");
        if (exchange is not null)
        {
            string java = File.ReadAllText(Path.Combine(exchange, "java-value.json"));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(wire)));
            File.WriteAllText(Path.Combine(exchange, "csharp-value.json"), wire);
            wire = java;
        }
        var data = ModelSnapshot.ReadWithoutReferences<ValueEnvelope>(wire);
        Assert.IsType<LeafValue>(data.Value);
        Assert.Empty(BindingTrace.Values);
        var calls = (IBaseValueCalls)data.Value;
        Assert.Equal(60, calls.Score(2));
        Assert.Equal(["special:LeafValue"], BindingTrace.Values);
    }
}
