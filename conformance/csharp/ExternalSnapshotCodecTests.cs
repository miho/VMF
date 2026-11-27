using System.Globalization;
using System.Text.Json;
using System.Xml;
using System.Xml.Linq;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ExternalSnapshotCodecTests
{
    private const string UriType = "java.net.URI";
    private const string DistanceType = "eu.scratch.host.Distance";
    private const string LocationText = "https://Example.invalid:443/a/%2f/../b?x=%e2%82%ac#first";
    private const string RelativeText = "../catalog%2F?q=false#relative";
    private static readonly Distance Measurement = new(9007199254740993L, "true", false);
    private static readonly SnapshotRegistry BuiltIns = new([
        new SnapshotType("eu.scratch.externals.Endpoint", typeof(Endpoint), static () => new Endpoint(), [
            new SnapshotProperty("children", SnapshotPropertyKind.ContainmentList, typeof(Endpoint),
                static model => ((Endpoint)model).Children,
                add: static (model, child) => ((Endpoint)model).Children.Add((Endpoint)child)),
            new SnapshotProperty("history", "history", SnapshotPropertyKind.ScalarList, typeof(Distance?),
                static model => ((Endpoint)model).History, externalType: DistanceType, allowNullElements: true,
                add: static (model, value) => ((Endpoint)model).History.Add((Distance?)value)),
            new SnapshotProperty("id", SnapshotPropertyKind.Scalar, typeof(string),
                static model => ((Endpoint)model).Id, set: static (model, value) => ((Endpoint)model).Id = (string?)value),
            new SnapshotProperty("links", "links", SnapshotPropertyKind.ScalarList, typeof(Uri),
                static model => ((Endpoint)model).Links, externalType: UriType, allowNullElements: true,
                add: static (model, value) => ((Endpoint)model).Links.Add((Uri?)value)),
            new SnapshotProperty("location", "location", SnapshotPropertyKind.Scalar, typeof(Uri),
                static model => ((Endpoint)model).Location, externalType: UriType,
                set: static (model, value) => ((Endpoint)model).Location = (Uri?)value),
            new SnapshotProperty("measurement", "measurement", SnapshotPropertyKind.Scalar, typeof(Distance?),
                static model => ((Endpoint)model).Measurement, externalType: DistanceType,
                set: static (model, value) => ((Endpoint)model).Measurement = (Distance?)value),
        ]),
    ]);
    private static readonly ExternalSnapshotXmlCodec StructuredXml = new(
        static (xml, value) =>
        {
            xml.WriteElementString("millimetres", XmlConvert.ToString(value.GetProperty("millimetres").GetInt64()));
            xml.WriteElementString("note", value.GetProperty("note").GetString());
            xml.WriteElementString("estimated", XmlConvert.ToString(value.GetProperty("estimated").GetBoolean()));
        },
        static xml => JsonSerializer.SerializeToElement(new
        {
            millimetres = XmlConvert.ToInt64(xml.Element("millimetres")!.Value),
            note = xml.Element("note")!.Value,
            estimated = XmlConvert.ToBoolean(xml.Element("estimated")!.Value),
        }));

    [Theory]
    [InlineData("json", "typed")]
    [InlineData("json", "references")]
    [InlineData("json", "plain")]
    [InlineData("yaml", "typed")]
    [InlineData("yaml", "references")]
    [InlineData("yaml", "plain")]
    [InlineData("xml", "typed")]
    [InlineData("xml", "references")]
    [InlineData("xml", "plain")]
    public void RoundTripsApplicationValuesWithNoAdditionalWireTokens(string format, string profile)
    {
        var registry = BuiltIns.WithExternalValueCodecs(Codecs(StructuredXml));
        string wire = Write(format, profile, Fixture(), registry);
        Assert.DoesNotContain(UriType, wire);
        Assert.DoesNotContain(DistanceType, wire);
        Assert.DoesNotContain("@vmf-id", wire);
        Verify(Read(format, profile, wire, registry));

        if (format == "json")
        {
            using var json = JsonDocument.Parse(wire);
            var distance = json.RootElement.GetProperty("measurement");
            Assert.Equal(Measurement.Millimetres, distance.GetProperty("millimetres").GetInt64());
            Assert.Equal("true", distance.GetProperty("note").GetString());
            Assert.False(distance.GetProperty("estimated").GetBoolean());
        }
        else if (format == "xml")
        {
            var xml = XElement.Parse(wire);
            Assert.Equal(LocationText, xml.Element("location")!.Value);
            Assert.Equal("9007199254740993", xml.Element("measurement")!.Element("millimetres")!.Value);
            var items = xml.Element("links")!.Elements("item").ToArray();
            Assert.Equal("true", items[1].Attribute(XName.Get("nil", "http://www.w3.org/2001/XMLSchema-instance"))!.Value);
            Assert.Empty(items[2].Value);
        }

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_BINDING");
        if (exchange is null || profile == "plain") return;
        string name = $"external-{(profile == "references" ? "true" : "false")}.{format}";
        Verify(Read(format, profile, File.ReadAllText(Path.Combine(exchange, name)), registry));
        File.WriteAllText(Path.Combine(exchange, $"csharp-{name}"), wire);
    }

    [Fact]
    public void XmlSnapshotDepthIncludesPhysicalElementsFromAnExternalCodec()
    {
        var xmlCodec = new ExternalSnapshotXmlCodec(
            static (writer, value) => writer.WriteRaw(value.GetProperty("note").GetString()!),
            static element => JsonSerializer.SerializeToElement(new { millimetres = 0L, note = element.Value, estimated = false }));
        var registry = BuiltIns.WithExternalValueCodecs(Codecs(xmlCodec));

        string Markup(int depth) => string.Concat(Enumerable.Repeat("<entry>", depth)) + "leaf"
            + string.Concat(Enumerable.Repeat("</entry>", depth));
        Endpoint Create(int depth) => new() { Measurement = new Distance(0, Markup(depth), false) };

        // The root and measurement add two physical elements beyond the codec's markup.
        string xml = SnapshotXml.Write(Create(998), registry);
        Assert.Equal("leaf", SnapshotXml.Read<Endpoint>(xml, registry).Measurement?.Note);
        var error = Assert.Throws<XmlException>(() => SnapshotXml.Write(Create(999), registry));
        Assert.Contains("maximum element depth", error.Message);
    }


    [Fact]
    public void UsesConfiguredWireSchemaAndPreservesBindingsAcrossCanonicalComposition()
    {
        var registry = BuiltIns.WithExternalValueCodecs(Codecs(StructuredXml));
        var combined = SnapshotRegistry.CombineCanonical(registry, registry);
        using var schema = JsonDocument.Parse(SnapshotSchema.Generate<Endpoint>(combined));
        var fields = schema.RootElement.GetProperty("properties");
        var location = fields.GetProperty("location").GetProperty("anyOf")[0];
        Assert.Equal("uri-reference", location.GetProperty("format").GetString());
        var distance = fields.GetProperty("measurement").GetProperty("anyOf")[0];
        Assert.Equal("object", distance.GetProperty("type").GetString());
        Assert.Equal("integer", distance.GetProperty("properties").GetProperty("millimetres").GetProperty("type").GetString());
        Assert.Equal("null", fields.GetProperty("history").GetProperty("items").GetProperty("anyOf")[1].GetProperty("type").GetString());
        Verify(SnapshotJson.Read<Endpoint>(SnapshotJson.Write(Fixture(), combined), combined));
        Assert.Throws<ArgumentException>(() => SnapshotRegistry.CombineCanonical(registry,
            BuiltIns.WithExternalValueCodecs(Codecs(StructuredXml))));
    }

    [Fact]
    public void ReportsMissingWrongAndInvalidCodecsWithoutAffectingOtherRegistries()
    {
        var linked = BuiltIns.WithExternalValueCodecs(Codecs(StructuredXml));
        string document = SnapshotJson.Write(Fixture(), linked);
        var missing = Assert.Throws<JsonException>(() => SnapshotJson.Read<Endpoint>(document, BuiltIns));
        Assert.Contains(DistanceType, missing.Message);
        Assert.Contains("/history/0", missing.Message);

        var wrong = BuiltIns.WithExternalValueCodecs(ExternalSnapshotCodecs.Empty.WithImmutable<string>(
            DistanceType, static text => JsonSerializer.SerializeToElement(text), static value => value.GetString()!, Element("""{"type":"string"}""")));
        Assert.Contains("System.String", Assert.Throws<JsonException>(() => SnapshotJson.Read<Endpoint>(document, wrong)).Message);
        Assert.Contains("/measurement", Assert.Throws<JsonException>(() => SnapshotJson.Read<Endpoint>(
            """{"measurement":{"millimetres":1,"note":true,"estimated":false}}""", linked)).Message);
        Assert.Contains("Duplicate field", Assert.Throws<JsonException>(() => SnapshotJson.Read<Endpoint>(
            """{"measurement":{"millimetres":1,"note":"first","note":"second","estimated":false}}""", linked)).Message);

        var missingXml = BuiltIns.WithExternalValueCodecs(Codecs(null));
        Verify(SnapshotYaml.Read<Endpoint>(SnapshotYaml.Write(Fixture(), missingXml), missingXml));
        Assert.Contains("No XML projection", Assert.Throws<XmlException>(() => SnapshotXml.Write(Fixture(), missingXml)).Message);
        Assert.Contains("No XML projection", Assert.Throws<XmlException>(() =>
            SnapshotXml.Read<Endpoint>(SnapshotXml.Write(Fixture(), linked), missingXml)).Message);
        Verify(SnapshotJson.Read<Endpoint>(document, linked));
    }

    [Fact]
    public void TreatsWholeExternalTreesAsDetachedValues()
    {
        var tree = new OpaqueValue(Element("""{"@vmf-id":"host-local","values":[true,"false",9007199254740993]}"""));
        var codec = ExternalSnapshotCodecs.Empty.WithImmutable<OpaqueValue>("opaque",
            static value => value.Document, static value => new OpaqueValue(value), Element("""{"type":"object"}"""));
        var registry = new SnapshotRegistry([
            new SnapshotType("Holder", typeof(Holder), static () => new Holder(), [
                new SnapshotProperty("value", "value", SnapshotPropertyKind.Scalar, typeof(OpaqueValue),
                    static model => ((Holder)model).Value, externalType: "opaque",
                    set: static (model, value) => ((Holder)model).Value = (OpaqueValue?)value),
            ]),
        ]).WithExternalValueCodecs(codec);
        var result = SnapshotJson.ReadPlain<Holder>(SnapshotJson.WritePlain(new Holder { Value = tree }, registry), registry);
        Assert.Equal("host-local", result.Value!.Document.GetProperty("@vmf-id").GetString());
        var values = result.Value.Document.GetProperty("values");
        Assert.True(values[0].GetBoolean());
        Assert.Equal("false", values[1].GetString());
        Assert.Equal(Measurement.Millimetres, values[2].GetInt64());

        var duplicate = new Holder { Value = new OpaqueValue(Element("""{"a":1,"a":2}""")) };
        Assert.Contains("/value", Assert.Throws<JsonException>(() => SnapshotJson.WritePlain(duplicate, registry)).Message);
        var invalid = new Holder { Value = new OpaqueValue(default) };
        Assert.Contains("/value", Assert.Throws<JsonException>(() => SnapshotJson.WritePlain(invalid, registry)).Message);
    }

    [Fact]
    public void UsesSeparateCanonicalTextCodecsForPortableJournals()
    {
        var registry = BuiltIns.WithExternalValueCodecs(Codecs(StructuredXml));
        var journals = JournalValueCodecs.Standard
            .WithImmutable<Uri>("probe.uri/v1", static uri => uri.OriginalString,
                static text => new Uri(text, UriKind.RelativeOrAbsolute))
            .WithImmutable<Distance>("probe.distance/v1",
                static value => value.Millimetres.ToString(CultureInfo.InvariantCulture) + "|" + value.Note + "|"
                    + (value.Estimated ? "true" : "false"),
                static text =>
                {
                    var parts = text.Split('|');
                    return new Distance(long.Parse(parts[0], CultureInfo.InvariantCulture), parts[1], bool.Parse(parts[2]));
                });
        var binding = new JournalBinding("external-probe", 1, static model => ((Endpoint)model).Id!, null, journals);
        const string changedLocation = "https://Example.invalid:443/changed/%2f/../b?x=%e2%82%ac#second";
        var work = Fixture();
        var journal = JournalRecorder<Endpoint>.RecordFrame(work, registry, binding, "start", 0, endpoint =>
        {
            endpoint.Location = new Uri(changedLocation);
            endpoint.Links.Add(new Uri("urn:sample:item"));
            endpoint.Measurement = new Distance(7L, "note", true);
        });
        Assert.Equal(1, journal.WireVersion);
        var operations = Assert.Single(journal.Transactions).Operations;
        var location = Assert.IsType<JournalSet>(operations[0]);
        Assert.Equal("probe.uri/v1", location.After.CodecId);
        Assert.Equal(changedLocation, location.After.Text);
        var distance = Assert.IsType<JournalSet>(operations[2]);
        Assert.Equal("probe.distance/v1", distance.Before.CodecId);
        Assert.Equal("9007199254740993|true|false", distance.Before.Text);
        var checkpoint = Fixture();
        foreach (var replay in new[]
        {
            JournalJson.Read(JournalJson.Write(journal)),
            JournalYaml.Read(JournalYaml.Write(journal)),
            JournalXml.Read(JournalXml.Write(journal)),
        })
        {
            var result = JournalReplayer.Replay(checkpoint, binding, "start", replay, registry);
            Assert.Equal(changedLocation, result.Location?.OriginalString);
            Assert.Equal("urn:sample:item", result.Links[3]?.OriginalString);
            Assert.Equal(new Distance(7L, "note", true), result.Measurement);
        }
        Verify(checkpoint);
    }

    private static ExternalSnapshotCodecs Codecs(ExternalSnapshotXmlCodec? structuredXml) => ExternalSnapshotCodecs.Empty
        .WithImmutable<Uri>(UriType,
            static value => JsonSerializer.SerializeToElement(value.OriginalString),
            static value => new Uri(value.GetString()!, UriKind.RelativeOrAbsolute),
            Element("""{"type":"string","format":"uri-reference"}"""), ExternalSnapshotXmlCodec.Text)
        .WithImmutable<Distance>(DistanceType,
            static value => JsonSerializer.SerializeToElement(new { millimetres = value.Millimetres, note = value.Note, estimated = value.Estimated }),
            static value => new Distance(value.GetProperty("millimetres").GetInt64(), value.GetProperty("note").GetString()!,
                value.GetProperty("estimated").GetBoolean()),
            Element("""
                {"type":"object","required":["millimetres","note","estimated"],"properties":{
                "millimetres":{"type":"integer"},"note":{"type":"string"},"estimated":{"type":"boolean"}}}
                """), structuredXml);

    private static JsonElement Element(string json)
    {
        using var document = JsonDocument.Parse(json);
        return document.RootElement.Clone();
    }

    private static Endpoint Fixture()
    {
        var endpoint = new Endpoint { Id = "root", Location = new Uri(LocationText), Measurement = Measurement };
        endpoint.Links.AddRange([new Uri(RelativeText, UriKind.Relative), null, new Uri("", UriKind.Relative)]);
        endpoint.History.AddRange([Measurement, null, Measurement]);
        return endpoint;
    }

    private static void Verify(Endpoint endpoint)
    {
        Assert.Equal("root", endpoint.Id);
        Assert.Equal(LocationText, endpoint.Location?.OriginalString);
        Assert.Equal([RelativeText, null, ""], endpoint.Links.Select(uri => uri?.OriginalString));
        Assert.Equal(new Distance?[] { Measurement, null, Measurement }, endpoint.History.ToArray());
        Assert.Equal(Measurement, endpoint.Measurement);
        Assert.Empty(endpoint.Children);
    }

    private static string Write(string format, string profile, Endpoint root, SnapshotRegistry registry) => (format, profile) switch
    {
        ("json", "typed") => SnapshotJson.WriteWithoutReferences(root, registry),
        ("json", "references") => SnapshotJson.Write(root, registry),
        ("json", _) => SnapshotJson.WritePlain(root, registry),
        ("yaml", "typed") => SnapshotYaml.WriteWithoutReferences(root, registry),
        ("yaml", "references") => SnapshotYaml.Write(root, registry),
        ("yaml", _) => SnapshotYaml.WritePlain(root, registry),
        ("xml", "typed") => SnapshotXml.WriteWithoutReferences(root, registry),
        ("xml", "references") => SnapshotXml.Write(root, registry),
        ("xml", _) => SnapshotXml.WritePlain(root, registry),
        _ => throw new ArgumentOutOfRangeException(nameof(format)),
    };

    private static Endpoint Read(string format, string profile, string wire, SnapshotRegistry registry) => (format, profile) switch
    {
        ("json", "typed") => SnapshotJson.ReadWithoutReferences<Endpoint>(wire, registry),
        ("json", "references") => SnapshotJson.Read<Endpoint>(wire, registry),
        ("json", _) => SnapshotJson.ReadPlain<Endpoint>(wire, registry),
        ("yaml", "typed") => SnapshotYaml.ReadWithoutReferences<Endpoint>(wire, registry),
        ("yaml", "references") => SnapshotYaml.Read<Endpoint>(wire, registry),
        ("yaml", _) => SnapshotYaml.ReadPlain<Endpoint>(wire, registry),
        ("xml", "typed") => SnapshotXml.ReadWithoutReferences<Endpoint>(wire, registry),
        ("xml", "references") => SnapshotXml.Read<Endpoint>(wire, registry),
        ("xml", _) => SnapshotXml.ReadPlain<Endpoint>(wire, registry),
        _ => throw new ArgumentOutOfRangeException(nameof(format)),
    };

    private sealed class Endpoint
    {
        private readonly ContainmentLink link = new();
        private Uri? location;
        private Distance? measurement;
        public Endpoint()
        {
            Children = new(this, link, static child => child.link);
            Links = new(allowNull: true, changeSource: new(this, link, "links"));
            History = new(allowNull: true, changeSource: new(this, link, "history"));
        }
        public ContainmentList<Endpoint, Endpoint> Children { get; }
        public string? Id { get; set; }
        public Uri? Location
        {
            get => location;
            set => ModelChanges.SetProperty(this, link, "location", ref location, value,
                static model => model.Location, static (model, item) => model.Location = item, isModelReference: false);
        }
        public Distance? Measurement
        {
            get => measurement;
            set => ModelChanges.SetProperty(this, link, "measurement", ref measurement, value,
                static model => model.Measurement, static (model, item) => model.Measurement = item, isModelReference: false);
        }
        public ScalarList<Uri?> Links { get; }
        public ScalarList<Distance?> History { get; }
    }

    private readonly record struct Distance(long Millimetres, string Note, bool Estimated);
    private sealed class Holder { public OpaqueValue? Value { get; set; } }
    private sealed record OpaqueValue(JsonElement Document);
}
