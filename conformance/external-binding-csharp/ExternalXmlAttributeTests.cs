using System.Text.Json;
using System.Xml;
using System.Xml.Linq;
using Eu.Mihosoft.Vmf.Conformance.Externalbinding;
using Eu.Mihosoft.Vmf.Conformance.Externalbinding.Host;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ExternalXmlAttributeTests
{
    private const string HostId = "opaque-host-id";
    private static readonly Distance Measurement = new(9007199254740993L, " note & finish ", true);
    private static readonly XName Nil = XName.Get("nil", "http://www.w3.org/2001/XMLSchema-instance");
    private static readonly SnapshotRegistry Attributes = ModelSnapshot.Registry.WithExternalValueCodecs(
        ExternalSnapshotCodecs.Empty.WithImmutable<Distance>("eu.mihosoft.vmf.conformance.externalbinding.host.Distance",
            static value => JsonSerializer.SerializeToElement(new
            {
                millimetres = value.Millimetres, estimated = value.Estimated, note = value.Note,
            }),
            static value => new(value.GetProperty("millimetres").GetInt64(), value.GetProperty("note").GetString()!,
                value.GetProperty("estimated").GetBoolean()),
            JsonSerializer.SerializeToElement(new { type = "object" }),
            new ExternalSnapshotXmlCodec(
                static (xml, value) =>
                {
                    xml.WriteAttributeString("millimetres", XmlConvert.ToString(value.GetProperty("millimetres").GetInt64()));
                    xml.WriteAttributeString("estimated", XmlConvert.ToString(value.GetProperty("estimated").GetBoolean()));
                    xml.WriteAttributeString("vmf-id", HostId);
                    xml.WriteElementString("note", value.GetProperty("note").GetString());
                },
                static element =>
                {
                    if ((string?)element.Attribute("vmf-id") != HostId) throw new XmlException("Missing opaque host attribute.");
                    return JsonSerializer.SerializeToElement(new
                    {
                        millimetres = XmlConvert.ToInt64(element.Attribute("millimetres")!.Value),
                        estimated = XmlConvert.ToBoolean(element.Attribute("estimated")!.Value),
                        note = element.Element("note")!.Value,
                    });
                }, allowElementAttributes: true)));

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void HostCodecsExchangeAttributesOnExternalValuesAndListItems(bool references)
    {
        var model = Fixture();
        var xml = references ? SnapshotXml.Write(model, Attributes) : SnapshotXml.WriteWithoutReferences(model, Attributes);
        Verify(Read(xml));
        Verify(SnapshotJson.Read<Endpoint>(SnapshotJson.Write(model, Attributes), Attributes));
        Verify(SnapshotYaml.Read<Endpoint>(SnapshotYaml.Write(model, Attributes), Attributes));

        var root = XElement.Parse(xml);
        Assert.Equal(HostId, (string?)root.Element("measurement")?.Attribute("vmf-id"));
        Assert.Equal(HostId, (string?)root.Element("history")?.Elements("item").First().Attribute("vmf-id"));
        Assert.Equal("true", (string?)root.Element("history")?.Elements("item").ElementAt(1).Attribute(Nil));
        Assert.Null(root.Element("vmf-id"));

        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE") is { } exchange)
        {
            string filename = $"external-attributes-{references.ToString().ToLowerInvariant()}.xml";
            Verify(Read(File.ReadAllText(Path.Combine(exchange, "java-" + filename))));
            File.WriteAllText(Path.Combine(exchange, "csharp-" + filename), xml);
        }

        Endpoint Read(string document) => references
            ? SnapshotXml.Read<Endpoint>(document, Attributes)
            : SnapshotXml.ReadWithoutReferences<Endpoint>(document, Attributes);
    }

    [Fact]
    public void ModelElementsAndNonOptedCodecsStillRejectAttributes()
    {
        var xml = SnapshotXml.Write(Fixture(), Attributes);
        Assert.Contains("Unexpected attribute", Assert.Throws<XmlException>(() => ModelSnapshot.ReadXml<Endpoint>(xml)).Message);
        var listOnly = XElement.Parse(xml);
        listOnly.Element("measurement")!.Remove();
        Assert.Contains("Unexpected attribute", Assert.Throws<XmlException>(() =>
            ModelSnapshot.ReadXml<Endpoint>(listOnly.ToString())).Message);

        foreach (var name in new[] { "id", "history" })
        {
            var invalid = XElement.Parse(xml);
            invalid.Element(name)!.SetAttributeValue("host", "unexpected");
            Reject(invalid);
        }
        var nil = XElement.Parse(xml);
        nil.Element("history")!.Elements("item").Single(item => (string?)item.Attribute(Nil) == "true")
            .SetAttributeValue("host", "unexpected");
        Reject(nil);

        static void Reject(XElement invalid) => Assert.Contains("Unexpected attribute", Assert.Throws<XmlException>(() =>
            SnapshotXml.Read<Endpoint>(invalid.ToString(), Attributes)).Message);
    }

    private static Endpoint Fixture() => Endpoint.NewBuilder().WithId("attribute-sample")
        .WithMeasurement(Measurement).WithHistory([Measurement, null, Measurement]).Build();

    private static void Verify(Endpoint restored)
    {
        Assert.Equal("attribute-sample", restored.Id);
        Assert.Equal(Measurement, restored.Measurement);
        Assert.Equal(new Distance?[] { Measurement, null, Measurement }, restored.History.ToArray());
    }
}
