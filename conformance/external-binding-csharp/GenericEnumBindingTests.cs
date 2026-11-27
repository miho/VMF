using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Externalbinding;
using Eu.Mihosoft.Vmf.Conformance.Externalbinding.Host;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class GenericEnumBindingTests
{
    private const string JavaType = "eu.mihosoft.vmf.conformance.externalbinding.host.DispatchMode";
    private static readonly JournalBinding Binding = new("enum-generic", 1, value => ((GenericModeProfile)value).Id!, null,
        JournalValueCodecs.Standard.WithImmutable<DispatchMode>("probe.generic-mode/v1",
            mode => Enum.GetName(mode) ?? throw new ArgumentException(nameof(mode)), Enum.Parse<DispatchMode>));

    [Fact]
    public void NestedHostTypeKeepsCanonicalMetadataAndExchangesNamedValues()
    {
        var model = GenericThreadProfile.NewBuilder().WithLatest(ThreadPhase.RUNNABLE).WithSamples([ThreadPhase.NEW, null]).Build();
        ModeSamples<ThreadPhase?> typed = model;
        Assert.Equal(ThreadPhase.RUNNABLE, typed.AsReadOnly().Latest);
        var metadata = ModelSnapshot.TypeCatalog.Find(typeof(GenericThreadProfile))!;
        Assert.Equal("java.lang.Thread.State", Assert.Single(Assert.Single(metadata.DirectSupertypes).Arguments).Name);
        var fields = JsonNode.Parse(ModelSnapshot.GenerateJsonSchema<GenericThreadProfile>())!["properties"]!;
        Assert.Equal(6, fields["latest"]!["anyOf"]![0]!["enum"]!.AsArray().Count);
        Assert.Equal(6, fields["samples"]!["items"]!["anyOf"]![0]!["enum"]!.AsArray().Count);

        var binding = new JournalBinding("nested-external", 1, _ => "profile", null,
            JournalValueCodecs.Standard.WithImmutable<ThreadPhase>("probe.thread-state/v1",
                value => Enum.GetName(value) ?? throw new ArgumentException(nameof(value)), Enum.Parse<ThreadPhase>));
        string checkpoint = ModelSnapshot.WritePlain(model);
        var working = ModelSnapshot.ReadPlain<GenericThreadProfile>(checkpoint);
        var records = JournalRecorder<GenericThreadProfile>.RecordFrame(working, ModelSnapshot.Registry, binding, "base", 0, changed =>
        {
            changed.Latest = ThreadPhase.TERMINATED;
            changed.Samples.Add(ThreadPhase.BLOCKED);
        });
        string journal = JournalJson.Write(records);
        VerifyJournal(journal);
        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE");
        Exchange(directory, "nested-enum-snapshot.json", checkpoint, java =>
        {
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(checkpoint)));
            var restored = ModelSnapshot.ReadPlain<GenericThreadProfile>(java);
            Assert.Equal(ThreadPhase.RUNNABLE, restored.Latest);
            Assert.Equal(new ThreadPhase?[] { ThreadPhase.NEW, null }, restored.Samples.ToArray());
        });
        Exchange(directory, "nested-enum-journal.json", journal, VerifyJournal);

        void VerifyJournal(string document)
        {
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(document), JsonNode.Parse(journal)));
            var read = JournalJson.Read(document);
            Assert.Equal(1, read.WireVersion);
            var replayed = JournalReplayer.Replay(model, binding, "base", read, ModelSnapshot.Registry);
            Assert.Equal(ThreadPhase.TERMINATED, replayed.Latest);
            Assert.Equal(new ThreadPhase?[] { ThreadPhase.NEW, null, ThreadPhase.BLOCKED }, replayed.Samples.ToArray());
        }
    }

    [Fact]
    public void ClosedGenericRetainsNullableNativeTypesAndExternalMetadata()
    {
        var profile = Fixture();
        ModeSamples<DispatchMode?> typed = profile;
        IReadOnlyModeSamples<DispatchMode?> view = typed.AsReadOnly();
        Assert.Null(view.Latest);
        Assert.Null(view.Samples[1]);
        Assert.Equal(DispatchMode.UNDER_REVIEW, view.Samples[0]);
        typed.Latest = DispatchMode.READY;
        Assert.Equal(DispatchMode.READY, view.Latest);

        var reflected = ModelSnapshot.Reflection.Reflect(profile);
        Assert.Equal(typeof(DispatchMode?), reflected.FindProperty("latest")!.ValueType);
        Assert.Equal(typeof(DispatchMode?), reflected.FindProperty("samples")!.ValueType);
        var metadata = ModelSnapshot.TypeCatalog.Find(typeof(GenericModeProfile))!;
        var argument = Assert.Single(Assert.Single(metadata.DirectSupertypes).Arguments);
        Assert.Equal(ModelValueKind.External, argument.Kind);
        Assert.Equal(JavaType, argument.Name);
        Assert.Equal(JavaType, Assert.Single(metadata.Properties, property => property.Name == "samples").ValueType.Name);
    }

    [Fact]
    public void SecondaryGenericRolePreservesMappedExternalValues()
    {
        var source = ScheduledEndpoint.NewBuilder().WithId("scheduled").WithLatest(ThreadPhase.NEW)
            .WithSamples([ThreadPhase.NEW, null]).Build();
        IForwardModeSamples<ThreadPhase?> role = source;
        var view = role.AsReadOnly();
        Assert.Same(source.AsReadOnly(), view);
        role.Latest = ThreadPhase.WAITING;
        Assert.Equal(ThreadPhase.WAITING, view.Latest);
        Assert.Null(view.Samples[1]);
        Assert.Equal(ThreadPhase.WAITING, ModelSnapshot.Reflection.DeepCopy(source).Latest);
        role.Latest = ThreadPhase.NEW;
        var binding = new JournalBinding("secondary-enum", 1, value => ((ScheduledEndpoint)value).Id!, null,
            JournalValueCodecs.Standard.WithImmutable<ThreadPhase>("probe.thread-state/v1",
                state => Enum.GetName(state) ?? throw new ArgumentException(nameof(state)), Enum.Parse<ThreadPhase>));
        string saved = ModelSnapshot.WritePlain(source);
        var journal = JournalRecorder<ScheduledEndpoint>.RecordFrame(source, ModelSnapshot.Registry, binding,
            "base", 0, model =>
            {
                IForwardModeSamples<ThreadPhase?> edited = model;
                edited.Latest = ThreadPhase.WAITING;
                edited.Samples.Add(ThreadPhase.RUNNABLE);
            });
        string edits = JournalJson.Write(journal);
        Verify(saved, edits);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE") is { } directory)
        {
            Verify(File.ReadAllText(Path.Combine(directory, "java-secondary-enum-snapshot.json")),
                File.ReadAllText(Path.Combine(directory, "java-secondary-enum-journal.json")));
            File.WriteAllText(Path.Combine(directory, "csharp-secondary-enum-snapshot.json"), saved);
            File.WriteAllText(Path.Combine(directory, "csharp-secondary-enum-journal.json"), edits);
        }

        void Verify(string snapshot, string changes)
        {
            var original = ModelSnapshot.ReadPlain<ScheduledEndpoint>(snapshot);
            Assert.Equal("scheduled", original.Id);
            Assert.Equal(ThreadPhase.NEW, original.Latest);
            Assert.Equal(new ThreadPhase?[] { ThreadPhase.NEW, null }, original.Samples.ToArray());
            var decoded = JournalJson.Read(changes);
            Assert.Equal(1, decoded.WireVersion);
            var result = JournalReplayer.Replay(original, binding, "base", decoded, ModelSnapshot.Registry);
            Assert.Equal(ThreadPhase.WAITING, result.Latest);
            Assert.Equal(new ThreadPhase?[] { ThreadPhase.NEW, null, ThreadPhase.RUNNABLE }, result.Samples.ToArray());
            Assert.Equal(ThreadPhase.NEW, original.Latest);
        }
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void ExchangesClosedNullableEnumSnapshotsAndJournals(string format)
    {
        var baseline = Fixture();
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE");
        foreach (bool references in new[] { false, true })
        {
            var codec = GeneratedExternalBindingTests.SnapshotCodec<GenericModeProfile>(format, references);
            string filename = $"generic-mode-snapshot-{references.ToString().ToLowerInvariant()}.{format}";
            string document = codec.Write(baseline);
            VerifySnapshot(codec.Read(document));
            Exchange(exchange, filename, document, java =>
            {
                VerifySnapshot(codec.Read(java));
                if (format == "json")
                    Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(document)));
            });
        }

        var journal = JournalRecorder<GenericModeProfile>.RecordFrame(Fixture(), ModelSnapshot.Registry, Binding, "initial", 0, model =>
        {
            model.Latest = DispatchMode.ARCHIVED;
            model.Samples[1] = DispatchMode.UNDER_REVIEW;
            model.Samples.Add(null);
        });
        (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) storage = format switch
        {
            "json" => (JournalJson.Write, JournalJson.Read),
            "yaml" => (JournalYaml.Write, JournalYaml.Read),
            "xml" => (JournalXml.Write, JournalXml.Read),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
        string saved = storage.Write(journal);
        VerifyJournal(saved);
        Exchange(exchange, $"generic-mode-journal.{format}", saved, VerifyJournal);
        VerifySnapshot(baseline);

        void VerifyJournal(string document)
        {
            var read = storage.Read(document);
            Assert.Equal(1, read.WireVersion);
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(journal)), JsonNode.Parse(JournalJson.Write(read))));
            var replayed = JournalReplayer.Replay(baseline, Binding, "initial", read, ModelSnapshot.Registry);
            Assert.Equal(DispatchMode.ARCHIVED, replayed.Latest);
            Assert.Equal(new DispatchMode?[] { DispatchMode.UNDER_REVIEW, DispatchMode.UNDER_REVIEW, DispatchMode.READY, null },
                replayed.Samples.ToArray());
        }
    }

    private static void Exchange(string? directory, string name, string outbound, Action<string> verify)
    {
        if (directory is null) return;
        verify(File.ReadAllText(Path.Combine(directory, "java-" + name)));
        File.WriteAllText(Path.Combine(directory, "csharp-" + name), outbound);
    }

    private static GenericModeProfile Fixture() => GenericModeProfile.NewBuilder().WithId("series").WithLatest(null)
        .WithSamples([DispatchMode.UNDER_REVIEW, null, DispatchMode.READY]).Build();

    private static void VerifySnapshot(GenericModeProfile model)
    {
        Assert.Null(model.Latest);
        Assert.Equal(new DispatchMode?[] { DispatchMode.UNDER_REVIEW, null, DispatchMode.READY }, model.Samples.ToArray());
    }
}
