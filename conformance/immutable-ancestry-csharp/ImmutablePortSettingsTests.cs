using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Immutableancestry;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ImmutablePortSettingsTests
{
    [Fact]
    public void BoundDefaultsUseConcreteStorageThroughBothGetterOnlyTraits()
    {
        var defaults = PortSettings.NewBuilder().Build();
        IReadOnlyWithDisplayLabel label = defaults;
        IReadOnlyWithTooltipText tooltip = defaults;
        Assert.Equal("115200", label.Label);
        Assert.Equal("", tooltip.Tooltip);
        Assert.Equal(PortMode.NORMAL, defaults.Mode);
        Assert.Same(defaults, defaults.AsReadOnly());
        var omitted = ModelSnapshot.ReadPlain<PortSettings>("{}");
        Assert.Equal("115200", omitted.Label);
        Assert.Equal(PortMode.NORMAL, omitted.Mode);

        var reflected = ModelSnapshot.Reflection.Reflect(defaults);
        Assert.Equal(3, reflected.Properties.Count);
        Assert.Equal(PortMode.NORMAL, reflected.FindProperty("mode")!.DefaultValue);
        Assert.Equal("115200", reflected.FindProperty("label")!.DefaultValue);
        Assert.True(ModelSnapshot.TypeCatalog.Find(typeof(PortSettings))!.FindProperty("mode")!.HasExecutableDefault);
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void ExplicitOverridesExchangeInTheExistingPlainSnapshotFormats(string format)
    {
        var value = PortSettings.NewBuilder().WithLabel(null).WithMode(PortMode.DIAGNOSTIC).Build();
        (string Document, Func<string, PortSettings> Read) snapshot = format switch
        {
            "yaml" => (ModelSnapshot.WritePlainYaml(value), ModelSnapshot.ReadPlainYaml<PortSettings>),
            "xml" => (ModelSnapshot.WritePlainXml(value), ModelSnapshot.ReadPlainXml<PortSettings>),
            _ => (ModelSnapshot.WritePlain(value), ModelSnapshot.ReadPlain<PortSettings>),
        };
        string wire = snapshot.Document;
        if (format == "json")
        {
            var fields = JsonNode.Parse(wire)!.AsObject();
            Assert.True(fields.ContainsKey("port_label"));
            Assert.Null(fields["port_label"]);
            Assert.Equal("DIAGNOSTIC", (string?)fields["mode"]);
        }
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_IMMUTABLE_ANCESTRY") is { } exchange)
        {
            File.WriteAllText(Path.Combine(exchange, $"csharp-immutable-port.{format}"), wire);
            wire = File.ReadAllText(Path.Combine(exchange, $"java-immutable-port.{format}"));
        }
        var restored = snapshot.Read(wire);
        Assert.Null(restored.Label);
        Assert.Equal(PortMode.DIAGNOSTIC, restored.Mode);
    }
}
