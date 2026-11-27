using System.Text.Json;
using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Externalbinding;
using Eu.Mihosoft.Vmf.Conformance.Externalbinding.Host;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class EnumBindingTests
{
    private const string ExternalType = "eu.mihosoft.vmf.conformance.externalbinding.host.DispatchMode";
    private static readonly JournalBinding Binding = new("enum-profile", 1, _ => "profile", null,
        JournalValueCodecs.Standard.WithImmutable<DispatchMode>("probe.dispatch-mode/v1",
            mode => Enum.GetName(mode) ?? throw new ArgumentException(nameof(mode)), Enum.Parse<DispatchMode>));

    [Fact]
    public void EnumSchemaAndNativeReflectionKeepTheNamedValueContract()
    {
        var model = ModelSnapshot.ReadPlain<ModeProfile>("{}");
        Assert.Equal(DispatchMode.READY, model.Preferred);
        var property = ModelSnapshot.Reflection.Reflect(model).FindProperty("preferred")!;
        Assert.Equal(DispatchMode.READY, property.DefaultValue);

        var fields = JsonNode.Parse(ModelSnapshot.GenerateJsonSchema<ModeProfile>())!["properties"]!;
        var shape = fields["preferred"]!["anyOf"]![0]!;
        Assert.Equal("string", shape["type"]!.GetValue<string>());
        Assert.Equal(["READY", "UNDER_REVIEW", "ARCHIVED"], shape["enum"]!.AsArray().Select(value => value!.GetValue<string>()));
        Assert.Throws<JsonException>(() => ModelSnapshot.ReadPlain<ModeProfile>("""{"preferred":"under_review"}"""));
        Assert.Throws<JsonException>(() => ModelSnapshot.ReadPlain<ModeProfile>("""{"preferred":19}"""));
        Assert.Throws<JsonException>(() => ModelSnapshot.WritePlain(new ModeProfile { Preferred = (DispatchMode)9 }));
        Assert.Throws<ArgumentException>(() => ExternalSnapshotCodecs.Empty.WithEnum<AliasedMode>(ExternalType));
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void ExchangesNamedEnumSnapshotsAndJournalsWithJava(string format)
    {
        var checkpoint = Fixture();
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE");
        foreach (bool references in new[] { false, true })
        {
            var codec = GeneratedExternalBindingTests.SnapshotCodec<ModeProfile>(format, references);
            string document = codec.Write(checkpoint);
            Verify(codec.Read(document));
            Exchange(exchange, $"mode-{references.ToString().ToLowerInvariant()}.{format}", document, java => Verify(codec.Read(java)));
        }

        var journal = JournalRecorder<ModeProfile>.RecordFrame(Fixture(), ModelSnapshot.Registry, Binding, "initial", 0, model =>
        {
            model.Preferred = DispatchMode.ARCHIVED;
            model.History.Add(DispatchMode.UNDER_REVIEW);
        });
        (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) storage = format switch
        {
            "json" => (JournalJson.Write, JournalJson.Read),
            "yaml" => (JournalYaml.Write, JournalYaml.Read),
            "xml" => (JournalXml.Write, JournalXml.Read),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
        string wire = storage.Write(journal);
        VerifyJournal(wire);
        Exchange(exchange, $"mode-journal.{format}", wire, VerifyJournal);
        Verify(checkpoint);

        void VerifyJournal(string document)
        {
            var read = storage.Read(document);
            Assert.Equal(1, read.WireVersion);
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(journal)), JsonNode.Parse(JournalJson.Write(read))));
            var restored = JournalReplayer.Replay(checkpoint, Binding, "initial", read, ModelSnapshot.Registry);
            Assert.Equal(DispatchMode.ARCHIVED, restored.Preferred);
            Assert.Equal(DispatchMode.UNDER_REVIEW, restored.History[3]);
        }
    }

    private static void Exchange(string? directory, string name, string outbound, Action<string> verify)
    {
        if (directory is null) return;
        verify(File.ReadAllText(Path.Combine(directory, "java-" + name)));
        File.WriteAllText(Path.Combine(directory, "csharp-" + name), outbound);
    }

    private static ModeProfile Fixture() => ModeProfile.NewBuilder().WithPreferred(null)
        .WithHistory([DispatchMode.UNDER_REVIEW, null, DispatchMode.READY]).Build();

    private static void Verify(ModeProfile typed)
    {
        Assert.Null(typed.Preferred);
        Assert.Equal(new DispatchMode?[] { DispatchMode.UNDER_REVIEW, null, DispatchMode.READY }, typed.History.ToArray());
    }

    private enum AliasedMode { First = 11, Second = 11 }
}
