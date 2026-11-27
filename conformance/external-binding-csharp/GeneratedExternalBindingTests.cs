using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Externalbinding;
using Eu.Mihosoft.Vmf.Conformance.Externalbinding.Host;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class GeneratedExternalBindingTests
{
    private const string LocationText = "https://Example.invalid:443/a/%2f/../b?x=%e2%82%ac#first";
    private const string RelativeText = "../catalog%2F?q=false#relative";
    private const string NewLocationText = "https://Example.invalid:443/a/%2f/../b?x=%e2%82%ac#second";
    private const string NewLink = "urn:sample:item";
    private static readonly Distance Measurement = new(9007199254740993L, "true", false);
    private static readonly Distance NewMeasurement = new(7L, "note", true);
    private const string HostType = "warehouse-distance/v1";
    private const string HostId = "repeated-host-id";

    [Fact]
    public void NativeGeneratedSnapshotsExchangeUriAndStructuredValuesWithJava()
    {
        var root = Fixture();
        var formats = new (string Format, Func<string> WritePlain, Func<string, Endpoint> ReadPlain,
            Func<string> WriteReferences, Func<string, Endpoint> ReadReferences,
            Func<string> WriteTypedValues, Func<string, Endpoint> ReadTypedValues)[]
        {
            ("json", () => ModelSnapshot.WritePlain(root.AsReadOnly()), ModelSnapshot.ReadPlain<Endpoint>,
                () => ModelSnapshot.Write(root), ModelSnapshot.Read<Endpoint>,
                () => ModelSnapshot.WriteWithoutReferences(root), ModelSnapshot.ReadWithoutReferences<Endpoint>),
            ("yaml", () => ModelSnapshot.WritePlainYaml(root.AsReadOnly()), ModelSnapshot.ReadPlainYaml<Endpoint>,
                () => ModelSnapshot.WriteYaml(root), ModelSnapshot.ReadYaml<Endpoint>,
                () => ModelSnapshot.WriteYamlWithoutReferences(root), ModelSnapshot.ReadYamlWithoutReferences<Endpoint>),
            ("xml", () => ModelSnapshot.WritePlainXml(root.AsReadOnly()), ModelSnapshot.ReadPlainXml<Endpoint>,
                () => ModelSnapshot.WriteXml(root), ModelSnapshot.ReadXml<Endpoint>,
                () => ModelSnapshot.WriteXmlWithoutReferences(root), ModelSnapshot.ReadXmlWithoutReferences<Endpoint>),
        };
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE");
        foreach (var (format, writePlain, readPlain, writeReferences, readReferences, writeTyped, readTyped) in formats)
        {
            string plain = writePlain();
            string referenceAware = writeReferences();
            Verify(readPlain(plain));
            Verify(readReferences(referenceAware));
            Verify(readTyped(writeTyped()));
            if (exchange is null) continue;
            Verify(readPlain(File.ReadAllText(Path.Combine(exchange, $"java-external-false.{format}"))));
            Verify(readReferences(File.ReadAllText(Path.Combine(exchange, $"java-external-true.{format}"))));
            File.WriteAllText(Path.Combine(exchange, $"csharp-external-false.{format}"), plain);
            File.WriteAllText(Path.Combine(exchange, $"csharp-external-true.{format}"), referenceAware);
        }

        using var schema = JsonDocument.Parse(ModelSnapshot.GenerateJsonSchema<Endpoint>());
        var properties = schema.RootElement.GetProperty("properties");
        Assert.Equal("uri-reference", properties.GetProperty("location").GetProperty("anyOf")[0].GetProperty("format").GetString());
        var distance = properties.GetProperty("measurement").GetProperty("anyOf")[0].GetProperty("properties");
        Assert.Equal("integer", distance.GetProperty("millimetres").GetProperty("type").GetString());
        Assert.Equal("boolean", distance.GetProperty("estimated").GetProperty("type").GetString());
    }

    [Theory]
    [InlineData("json", false)]
    [InlineData("json", true)]
    [InlineData("yaml", false)]
    [InlineData("yaml", true)]
    public void DeclaredExternalObjectMetadataDoesNotSelectAModelType(string format, bool references)
    {
        var codecs = ExternalSnapshotCodecs.Empty.WithImmutable<Distance>(
            "eu.mihosoft.vmf.conformance.externalbinding.host.Distance",
            value => JsonSerializer.SerializeToElement(new JsonObject
            {
                ["@vmf-type"] = HostType,
                ["@vmf-id"] = HostId,
                ["millimetres"] = value.Millimetres,
                ["note"] = value.Note,
                ["estimated"] = value.Estimated
            }),
            node =>
            {
                Assert.Equal(HostType, node.GetProperty("@vmf-type").GetString());
                Assert.Equal(HostId, node.GetProperty("@vmf-id").GetString());
                return new Distance(node.GetProperty("millimetres").GetInt64(),
                    node.GetProperty("note").GetString()!, node.GetProperty("estimated").GetBoolean());
            },
            JsonSerializer.SerializeToElement(new { type = "object" }));
        var registry = ModelSnapshot.Registry.WithExternalValueCodecs(codecs);
        var model = Endpoint.NewBuilder().WithId("metadata").WithMeasurement(Measurement)
            .WithHistory([Measurement, null, Measurement]).Build();
        (Func<Endpoint, string> Write, Func<string, Endpoint> Read) codec = (format, references) switch
        {
            ("json", false) => (value => SnapshotJson.WritePlain(value, registry),
                wire => SnapshotJson.ReadPlain<Endpoint>(wire, registry)),
            ("json", true) => (value => SnapshotJson.Write(value, registry),
                wire => SnapshotJson.Read<Endpoint>(wire, registry)),
            ("yaml", false) => (value => SnapshotYaml.WritePlain(value, registry),
                wire => SnapshotYaml.ReadPlain<Endpoint>(wire, registry)),
            _ => (value => SnapshotYaml.Write(value, registry), wire => SnapshotYaml.Read<Endpoint>(wire, registry))
        };
        string native = codec.Write(model);
        Verify(codec.Read(native));
        string invalid = format == "json" ? $$"""{"@vmf-type":"{{HostType}}"}""" : $"'@vmf-type': {HostType}";
        if (format == "json")
            Assert.Throws<JsonException>(() => codec.Read(invalid));
        else
            Assert.IsType<JsonException>(Assert.Throws<YamlDotNet.Core.YamlException>(() => codec.Read(invalid)).InnerException);

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE");
        if (exchange is not null)
        {
            string file = $"external-metadata-{references.ToString().ToLowerInvariant()}.{format}";
            Verify(codec.Read(File.ReadAllText(Path.Combine(exchange, "java-" + file))));
            File.WriteAllText(Path.Combine(exchange, "csharp-" + file), native);
        }

        static void Verify(Endpoint restored)
        {
            Assert.Equal("metadata", restored.Id);
            Assert.Equal(Measurement, restored.Measurement);
            Assert.Equal(new Distance?[] { Measurement, null, Measurement }, restored.History.ToArray());
        }
    }

    [Fact]
    public void ClearingUuidAndUriDefaultsSurvivesSnapshotsAcrossFormats()
    {
        var cleared = new PresetEndpoint();
        Assert.NotNull(cleared.Location);
        Assert.NotNull(cleared.Key);
        cleared.Location = null;
        cleared.Key = null;

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE");
        foreach (string format in new[] { "json", "xml", "yaml" })
        {
            string omitted = format == "xml" ? "<eu.mihosoft.vmf.conformance.externalbinding.PresetEndpoint/>" : "{}";
            foreach (bool references in new[] { false, true })
            {
                var (write, read) = SnapshotCodec<PresetEndpoint>(format, references);
                string document = write(cleared);
                CheckCleared(read(document));
                var defaults = read(omitted);
                Assert.Equal("https://example.invalid/default", defaults.Location?.Text);
                Assert.Equal(Guid.Parse("980e13c6-84a0-49d6-821d-737269635aef"), defaults.Key);
                if (exchange is null) continue;
                string file = $"cleared-host-defaults-{references.ToString().ToLowerInvariant()}.{format}";
                CheckCleared(read(File.ReadAllText(Path.Combine(exchange, "java-" + file))));
                File.WriteAllText(Path.Combine(exchange, "csharp-" + file), document);
            }
        }

        static void CheckCleared(PresetEndpoint model)
        {
            Assert.Null(model.Location);
            Assert.Null(model.Key);
        }
    }

    [Fact]
    public void GeneratedModelsRecordAndReplayCanonicalExternalWireValuesAcrossFormats()
    {
        var binding = EndpointJournalBinding.Instance;
        var checkpoint = Fixture();
        var work = ModelSnapshot.Read<Endpoint>(ModelSnapshot.Write(checkpoint));
        var journal = JournalRecorder<Endpoint>.RecordFrame(work, ModelSnapshot.Registry, binding, "start", 0, model =>
        {
            model.Location = new LexicalUri(NewLocationText);
            model.Links.Add(new LexicalUri(NewLink));
            model.Measurement = NewMeasurement;
        });
        var formats = new (string Format, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
        };
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE");
        foreach (var (format, write, read) in formats)
        {
            string wire = write(journal);
            VerifyJournal(checkpoint, binding, read(wire));
            if (exchange is null) continue;
            var fromJava = read(File.ReadAllText(Path.Combine(exchange, $"java-journal.{format}")));
            VerifyJournal(checkpoint, binding, fromJava);
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(journal)), JsonNode.Parse(JournalJson.Write(fromJava))));
            File.WriteAllText(Path.Combine(exchange, $"csharp-journal.{format}"), wire);
        }
        Verify(checkpoint);
    }

    [Fact]
    public void MutableHostValuesStayLiveThroughReadOnlyViewsAndExchangeByValue()
    {
        var text = new StringBuilder("start");
        var model = TextBuffers.NewBuilder().WithTitle(text).WithLines([text, null]).Build();
        var view = model.AsReadOnly();
        Assert.Same(text, view.Title);
        Assert.Same(text, view.Lines[0]);
        text.Append(" & end");
        Assert.Equal("start & end", view.Title?.ToString());

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE");
        foreach (string format in new[] { "json", "yaml", "xml" })
        {
            foreach (bool references in new[] { false, true })
            {
                var codec = SnapshotCodec<TextBuffers>(format, references);
                string document = codec.Write(model);
                Check(codec.Read(document));
                if (exchange is null) continue;
                string file = $"text-buffers-{references.ToString().ToLowerInvariant()}.{format}";
                string java = File.ReadAllText(Path.Combine(exchange, "java-" + file));
                Check(codec.Read(java));
                if (format == "json")
                    Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(document)));
                File.WriteAllText(Path.Combine(exchange, "csharp-" + file), document);
            }
        }

        string captured = ModelSnapshot.WritePlain(model);
        text.Append(" later");
        Check(ModelSnapshot.ReadPlain<TextBuffers>(captured));

        static void Check(TextBuffers restored)
        {
            Assert.Equal("start & end", restored.Title?.ToString());
            Assert.Equal(["start & end", null], restored.Lines.Select(line => line?.ToString()));
        }
    }

    internal static (Func<T, string> Write, Func<string, T> Read) SnapshotCodec<T>(string format, bool references) where T : class =>
        (format, references) switch
        {
            ("json", false) => (ModelSnapshot.WritePlain<T>, ModelSnapshot.ReadPlain<T>),
            ("json", true) => (ModelSnapshot.Write, ModelSnapshot.Read<T>),
            ("yaml", false) => (ModelSnapshot.WritePlainYaml<T>, ModelSnapshot.ReadPlainYaml<T>),
            ("yaml", true) => (ModelSnapshot.WriteYaml, ModelSnapshot.ReadYaml<T>),
            ("xml", false) => (ModelSnapshot.WritePlainXml<T>, ModelSnapshot.ReadPlainXml<T>),
            ("xml", true) => (ModelSnapshot.WriteXml, ModelSnapshot.ReadXml<T>),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };

    private static Endpoint Fixture()
    {
        var model = new Endpoint { Id = "root", Location = new LexicalUri(LocationText), Measurement = Measurement };
        model.Links.AddRange([new LexicalUri(RelativeText), null, new LexicalUri("")]);
        model.History.AddRange([Measurement, null, Measurement]);
        return model;
    }

    private static void Verify(Endpoint model)
    {
        Assert.Equal("root", model.Id);
        Assert.Equal(LocationText, model.Location?.Text);
        Assert.Equal([RelativeText, null, ""], model.Links.Select(uri => uri?.Text));
        Assert.Equal(Measurement, model.Measurement);
        Assert.Equal(new Distance?[] { Measurement, null, Measurement }, model.History.ToArray());
    }

    private static void VerifyJournal(Endpoint checkpoint, JournalBinding binding, ChangeJournal journal)
    {
        Assert.Equal(1, journal.WireVersion);
        var operations = Assert.Single(journal.Transactions).Operations;
        Assert.Equal(3, operations.Count);
        var location = Assert.IsType<JournalSet>(operations[0]);
        Assert.Equal("probe.uri/v1", location.After.CodecId);
        Assert.Equal(NewLocationText, location.After.Text);
        var distance = Assert.IsType<JournalSet>(operations[2]);
        Assert.Equal("probe.distance/v1", distance.Before.CodecId);
        Assert.Equal("9007199254740993|true|false", distance.Before.Text);
        var result = JournalReplayer.Replay(checkpoint, binding, "start", journal, ModelSnapshot.Registry);
        Assert.Equal(NewLocationText, result.Location?.Text);
        Assert.Equal(NewLink, result.Links[3]?.Text);
        Assert.Equal(NewMeasurement, result.Measurement);
    }
}
