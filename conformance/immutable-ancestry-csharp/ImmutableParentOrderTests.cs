using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Immutableancestry;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ImmutableParentOrderTests
{
    [Fact]
    public void LeadingGetterTraitStillUsesTheNativeImmutableBaseAndOriginalModelOrder()
    {
        OrderedParentBindings.Created = 0;
        OrderedParentBindings.Callers.Clear();
        var early = TraitFirstValue.NewBuilder().ApplyFrom(new ValueOrigin("intake"))
            .WithCaption("early").WithQuantity(2).Build();
        var control = BaseFirstValue.NewBuilder().WithSource("storage").WithCaption("control").WithQuantity(3).Build();
        Assert.Equal(typeof(ValueOrigin), typeof(TraitFirstValue).BaseType);
        Assert.Equal(typeof(ValueOrigin), typeof(BaseFirstValue).BaseType);

        var earlyType = ModelSnapshot.TypeCatalog.Find(typeof(TraitFirstValue))!;
        var controlType = ModelSnapshot.TypeCatalog.Find(typeof(BaseFirstValue))!;
        Assert.Collection(earlyType.DirectSupertypes,
            parent => Assert.EndsWith(".LeadingCaption", parent.Name, StringComparison.Ordinal),
            parent => Assert.EndsWith(".ValueOrigin", parent.Name, StringComparison.Ordinal));
        Assert.Collection(controlType.DirectSupertypes,
            parent => Assert.EndsWith(".ValueOrigin", parent.Name, StringComparison.Ordinal),
            parent => Assert.EndsWith(".LeadingCaption", parent.Name, StringComparison.Ordinal));

        var graph = new OrderedParentsEnvelope
        {
            EarlyBase = early, EarlyTrait = early,
            ControlBase = control, ControlTrait = control,
        };
        CheckState(graph);
        Assert.Equal(0, OrderedParentBindings.Created);
        IValueOriginCalls earlyCalls = early;
        IValueOriginCalls controlCalls = control;
        Assert.Equal("intake/early", earlyCalls.Describe());
        Assert.Equal(1, ((ITraitFirstValueCalls)graph.AsReadOnly().EarlyTrait!).Calls());
        Assert.Equal("storage/control", controlCalls.Describe());
        Assert.Equal(1, controlCalls.Calls());
        Assert.Collection(OrderedParentBindings.Callers, actual => Assert.Same(early, actual),
            actual => Assert.Same(control, actual));
        Assert.Equal(2, OrderedParentBindings.Created);
        string wire = ModelSnapshot.Write(graph.AsReadOnly());
        var document = JsonNode.Parse(wire)!;
        Assert.Equal("early", document["earlyBase"]!["display_caption"]!.GetValue<string>());
        Assert.Equal("control", document["controlBase"]!["display_caption"]!.GetValue<string>());
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_IMMUTABLE_ANCESTRY") is { } directory)
        {
            File.WriteAllText(Path.Combine(directory, "csharp-ordered-parents-reference.json"), wire);
            wire = File.ReadAllText(Path.Combine(directory, "java-ordered-parents-reference.json"));
            Assert.True(JsonNode.DeepEquals(document, JsonNode.Parse(wire)));
        }
        var copy = ModelSnapshot.Read<OrderedParentsEnvelope>(wire);
        CheckState(copy);
        Assert.Equal(2, OrderedParentBindings.Created);
        Assert.Equal(0, ((IValueOriginCalls)copy.AsReadOnly().EarlyTrait!).Calls());
        Assert.Equal(3, OrderedParentBindings.Created);
    }

    private static void CheckState(OrderedParentsEnvelope graph)
    {
        var early = Assert.IsType<TraitFirstValue>(graph.EarlyBase);
        var control = Assert.IsType<BaseFirstValue>(graph.ControlBase);
        var readOnly = graph.AsReadOnly();
        Assert.Same(early, Assert.IsAssignableFrom<IReadOnlyLeadingCaption>(graph.EarlyTrait));
        Assert.Same(early, readOnly.EarlyTrait);
        Assert.Same(early, readOnly.EarlyBase);
        Assert.Same(early, early.AsReadOnly());
        Assert.Same(early, ((ValueOrigin)early).AsReadOnly());
        Assert.Same(control, Assert.IsAssignableFrom<IReadOnlyLeadingCaption>(graph.ControlTrait));
        Assert.Same(control, readOnly.ControlTrait);
        Assert.Same(control, readOnly.ControlBase);
        Assert.Same(control, control.AsReadOnly());
        Assert.Same(control, ((ValueOrigin)control).AsReadOnly());
        Assert.Equal(("early", "intake", 2), (early.Caption, early.Source, early.Quantity));
        Assert.Equal(("control", "storage", 3), (control.Caption, control.Source, control.Quantity));
        CheckFields(early, ["caption", "source", "quantity"]);
        CheckFields(control, ["source", "caption", "quantity"]);
    }

    private static void CheckFields(ValueOrigin value, string[] order)
    {
        var reflection = ModelSnapshot.Reflection.Reflect(value);
        Assert.True(reflection.IsImmutable);
        Assert.Equal(order, reflection.Properties.Select(property => property.Name));
        Assert.All(reflection.Properties, property => Assert.False(property.CanWrite));
        Assert.Equal("display_caption", reflection.FindProperty("caption")!.WireName);
    }
}
