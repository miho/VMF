using System.Text.Json;
using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.External;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ExternalValueJournalTests
{
    private const string Model = "conformance.external-journal";
    private const string Checkpoint = "external-checkpoint";
    private const string UuidCodec = JournalValueCodecs.UuidCodecId;
    private static readonly Guid First = Guid.Parse("980e13c6-84a0-49d6-821d-737269635aef");
    private static readonly Guid Second = Guid.Parse("b0e36ead-e4e3-4de7-bca4-b2ba2a7c6035");
    private static readonly Guid Third = Guid.Parse("7bfefa79-00d2-44b6-823a-b7c29e8d2246");
    private static readonly JournalBinding Binding = new(Model, 1, Id);
    private static readonly (string Extension, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[] Formats =
        [("json", JournalJson.Write, JournalJson.Read), ("xml", JournalXml.Write, JournalXml.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read)];

    [Fact]
    public void ReplaysUuidPropertiesListsAndCreationsAcrossBothRuntimes()
    {
        var start = Start();
        string checkpoint = ModelSnapshot.Write(start);
        var work = ModelSnapshot.Read<ExternalValueEntry>(checkpoint);
        ChangeJournal journal;
        using (var recorder = JournalRecorder<ExternalValueEntry>.Start(work, ModelSnapshot.Registry, Binding, Checkpoint, 40))
        {
            work.SourceId = Second;
            work.RelatedIds[1] = Third;
            Assert.Equal(41, recorder.PublishFrame()!.Sequence);
            var child = new ExternalValueEntry { Id = "child", SourceId = Third };
            child.RelatedIds.AddRange([First, null, Third]);
            work.Children.Add(child);
            journal = recorder.Finish();
        }

        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_JOURNAL");
        foreach (var (format, write, read) in Formats)
        {
            string document = write(journal);
            Verify(ModelSnapshot.Read<ExternalValueEntry>(checkpoint), read(document));
            if (interchange is null) continue;
            var fromJava = read(File.ReadAllText(Path.Combine(interchange, $"java-journal.{format}")));
            var javaCheckpoint = ModelSnapshot.Read<ExternalValueEntry>(
                File.ReadAllText(Path.Combine(interchange, "java-checkpoint.json")));
            Verify(javaCheckpoint, fromJava);
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(journal)), JsonNode.Parse(JournalJson.Write(fromJava))));
            File.WriteAllText(Path.Combine(interchange, $"csharp-journal.{format}"), document);
        }
        if (interchange is not null) File.WriteAllText(Path.Combine(interchange, "csharp-checkpoint.json"), checkpoint);
    }

    [Fact]
    public void BoundedExternalListsUseValidationOnlyAndPreserveOverfullValuesAcrossFormats()
    {
        const string idsCheckpoint = "ids-start";
        var binding = new JournalBinding("conformance.bounded-external-ids", 1, _ => "root");
        var start = new BoundedExternalIds();
        Assert.Equal("/ids", Assert.Single(start.Validate()).ModelPath);
        start.Ids.Add(null);
        Assert.Empty(start.Validate());
        string checkpoint = ModelSnapshot.WritePlain(start);
        var journal = JournalRecorder<BoundedExternalIds>.RecordFrame(start, ModelSnapshot.Registry, binding, idsCheckpoint, 0,
            model => model.Ids.AddRange([First, First]));
        Verify(start);
        Assert.Equal(1, journal.WireVersion);
        string document = JournalJson.Write(journal);
        Verify(JournalReplayer.Replay(ModelSnapshot.ReadPlain<BoundedExternalIds>(checkpoint), binding, idsCheckpoint,
            JournalJson.Read(document), ModelSnapshot.Registry));
        var schema = JsonNode.Parse(ModelSnapshot.GenerateJsonSchema<BoundedExternalIds>(SnapshotSchemaProfile.Plain))!;
        Assert.Equal(1, schema["properties"]!["ids"]!["minItems"]!.GetValue<int>());
        Assert.Equal(2, schema["properties"]!["ids"]!["maxItems"]!.GetValue<int>());

        (string Extension, Func<BoundedExternalIds, string> Write, Func<string, BoundedExternalIds> Read)[] codecs =
        [
            ("json", ModelSnapshot.WritePlain, ModelSnapshot.ReadPlain<BoundedExternalIds>),
            ("xml", value => ModelSnapshot.WritePlainXml(value), ModelSnapshot.ReadPlainXml<BoundedExternalIds>),
            ("yaml", ModelSnapshot.WritePlainYaml, ModelSnapshot.ReadPlainYaml<BoundedExternalIds>)
        ];
        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_JOURNAL");
        foreach (var (format, write, read) in codecs)
        {
            var snapshot = write(start);
            Verify(read(snapshot));
            if (interchange is null) continue;
            Verify(read(File.ReadAllText(Path.Combine(interchange, $"java-bounded-ids.{format}"))));
            File.WriteAllText(Path.Combine(interchange, $"csharp-bounded-ids.{format}"), snapshot);
        }
        if (interchange is not null)
        {
            var javaCheckpoint = ModelSnapshot.ReadPlain<BoundedExternalIds>(
                File.ReadAllText(Path.Combine(interchange, "java-bounded-checkpoint.json")));
            var javaJournal = JournalJson.Read(File.ReadAllText(Path.Combine(interchange, "java-bounded-journal.json")));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(document), JsonNode.Parse(JournalJson.Write(javaJournal))));
            Verify(JournalReplayer.Replay(javaCheckpoint, binding, idsCheckpoint, javaJournal, ModelSnapshot.Registry));
            Assert.Null(Assert.Single(javaCheckpoint.Ids));
            File.WriteAllText(Path.Combine(interchange, "csharp-bounded-checkpoint.json"), checkpoint);
            File.WriteAllText(Path.Combine(interchange, "csharp-bounded-journal.json"), document);
        }

        static void Verify(BoundedExternalIds current)
        {
            Assert.Equal(new Guid?[] { null, First, First }, current.Ids);
            Assert.Equal("/ids", Assert.Single(current.Validate()).ModelPath);
        }
    }

    [Fact]
    public void RejectsUnknownAndNoncanonicalEntriesOnlyWhenReplayed()
    {
        var start = Start();
        var invalid = new[]
        {
            JournalValue.External("example.unknown/v1", Second.ToString("D")),
            JournalValue.External(UuidCodec, Second.ToString("D").ToUpperInvariant()),
            JournalValue.External(UuidCodec, "1-1-1-1-1"),
        };
        foreach (var after in invalid)
        {
            foreach (var (_, write, read) in Formats)
            {
                var journal = read(write(Replace(Uuid(First), after)));
                var operation = Assert.IsType<JournalSet>(Assert.Single(Assert.Single(journal.Transactions).Operations));
                Assert.Equal(after.CodecId, operation.After.CodecId);
                Assert.Equal(after.Text, operation.After.Text);
                Assert.Throws<InvalidOperationException>(() =>
                    JournalReplayer.Replay(start, Binding, Checkpoint, journal, ModelSnapshot.Registry));
            }
        }
        Assert.Equal(First, start.SourceId);
        Assert.Empty(start.Children);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void BuiltInUuidClosesAnUnboundedGenericScalarAndList(bool references)
    {
        var source = GenericExternalIds.NewBuilder().WithLatest(First).WithSamples([Second, null, First, First]).Build();
        ExternalSamples<Guid?> typed = source;
        IReadOnlyExternalSamples<Guid?> view = typed.AsReadOnly();
        typed.Latest = null;
        Assert.Null(view.Latest);
        Assert.Null(view.Samples[1]);
        typed.Latest = First;

        var metadata = ModelSnapshot.TypeCatalog.Find(typeof(GenericExternalIds))!;
        var argument = Assert.Single(Assert.Single(metadata.DirectSupertypes).Arguments);
        Assert.Equal(ModelValueKind.External, argument.Kind);
        Assert.Equal("java.util.UUID", argument.Name);
        Assert.Equal("/samples", Assert.Single(new GenericExternalIds().Validate()).ModelPath);
        var fields = JsonNode.Parse(ModelSnapshot.GenerateJsonSchema<GenericExternalIds>(
            references ? SnapshotSchemaProfile.References : SnapshotSchemaProfile.Plain))!["properties"]!;
        Assert.Equal(1, fields["samples"]!["minItems"]!.GetValue<int>());
        var items = fields["samples"]!["items"]!;
        Assert.Equal("uuid", items["format"]!.GetValue<string>());
        Assert.Equal("null", items["type"]![1]!.GetValue<string>());

        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_JOURNAL");
        var suffix = references ? "true" : "false";
        Check("json", references ? ModelSnapshot.Write(source) : ModelSnapshot.WritePlain(source),
            references ? ModelSnapshot.Read<GenericExternalIds> : ModelSnapshot.ReadPlain<GenericExternalIds>);
        Check("xml", references ? ModelSnapshot.WriteXml(source) : ModelSnapshot.WritePlainXml(source),
            references ? ModelSnapshot.ReadXml<GenericExternalIds> : ModelSnapshot.ReadPlainXml<GenericExternalIds>);
        Check("yaml", references ? ModelSnapshot.WriteYaml(source) : ModelSnapshot.WritePlainYaml(source),
            references ? ModelSnapshot.ReadYaml<GenericExternalIds> : ModelSnapshot.ReadPlainYaml<GenericExternalIds>);

        void Check(string extension, string snapshot, Func<string, GenericExternalIds> read)
        {
            VerifyGeneric(read(snapshot));
            if (interchange is null) return;
            VerifyGeneric(read(File.ReadAllText(Path.Combine(interchange, $"java-generic-ids-{suffix}.{extension}"))));
            File.WriteAllText(Path.Combine(interchange, $"csharp-generic-ids-{suffix}.{extension}"), snapshot);
        }

        static void VerifyGeneric(GenericExternalIds model)
        {
            Assert.Equal(First, model.Latest);
            Assert.Equal(new Guid?[] { Second, null, First, First }, model.Samples);
            Assert.Empty(model.Validate());
        }
    }

    [Fact]
    public void UsesTheBindingSpecificRegistryAndValidatesExistingCheckpointValues()
    {
        const string customId = "example.uuid/v1";
        var custom = JournalValueCodecs.Empty.WithImmutable<Guid>(customId,
            value => value.ToString("N"), text => Guid.ParseExact(text, "N"));
        var binding = new JournalBinding(Model, 1, Id, null, custom);
        var start = Start();
        var work = Start();
        var journal = JournalRecorder<ExternalValueEntry>.RecordFrame(work, ModelSnapshot.Registry, binding,
            Checkpoint, 40, root => root.SourceId = Second);
        var operation = Assert.IsType<JournalSet>(Assert.Single(Assert.Single(journal.Transactions).Operations));
        Assert.Equal(customId, operation.After.CodecId);
        Assert.Equal(Second.ToString("N"), operation.After.Text);
        Assert.Equal(Second, JournalReplayer.Replay(start, binding, Checkpoint, journal, ModelSnapshot.Registry).SourceId);
        Assert.Throws<InvalidOperationException>(() =>
            JournalReplayer.Replay(start, Binding, Checkpoint, journal, ModelSnapshot.Registry));
        Assert.Throws<ArgumentException>(() => custom.WithImmutable<Guid>("second", value => value.ToString(), Guid.Parse));

        var absent = new JournalBinding(Model, 1, Id, null, JournalValueCodecs.Empty);
        var empty = new ChangeJournal(Model, 1, "root", Checkpoint, 40, []);
        Assert.Throws<ArgumentException>(() => JournalRecorder<ExternalValueEntry>.Start(Start(), ModelSnapshot.Registry, absent,
            Checkpoint, 40));
        Assert.Throws<ArgumentException>(() =>
            JournalReplayer.Replay(start, absent, Checkpoint, empty, ModelSnapshot.Registry));
    }

    [Fact]
    public void RejectsMissingCodecIdsAndExternalManagedSetOrMapMembers()
    {
        var journal = Replace(Uuid(First), Uuid(Second));
        var wire = JsonNode.Parse(JournalJson.Write(journal))!;
        Assert.Equal(1, JournalJson.Read(wire.ToJsonString()).WireVersion);

        wire["transactions"]![0]!["operations"]![0]!["after"]![0]!.AsObject().Remove("codec");
        Assert.Throws<JsonException>(() => JournalJson.Read(wire.ToJsonString()));

        Assert.Throws<ArgumentException>(() => new JournalMembershipEdit("root", "ids", references: false, [], [Uuid(Second)]));
        Assert.Throws<ArgumentException>(() => new JournalMapEdit("root", "ids", typedKeys: true, [],
            [new JournalMapEntry(Uuid(First), Uuid(Second))]));
    }

    private static ExternalValueEntry Start()
    {
        var root = new ExternalValueEntry { Id = "root", SourceId = First };
        root.RelatedIds.AddRange([Second, null, First]);
        return root;
    }

    private static string Id(object model) =>
        ((ExternalValueEntry)model).Id ?? throw new ArgumentException("External journal object needs an ID.");

    private static ChangeJournal Replace(JournalValue before, JournalValue after) =>
        new(Model, 1, "root", Checkpoint, 40, [new JournalFrame(41, [new JournalSet("root", "sourceId", before, after)])]);

    private static JournalValue Uuid(Guid value) => JournalValue.External(UuidCodec, value.ToString("D"));

    private static void Verify(ExternalValueEntry start, ChangeJournal journal)
    {
        Assert.Equal(1, journal.WireVersion);
        Assert.Equal(2, journal.Transactions.Count);
        var first = Assert.IsType<JournalSet>(journal.Transactions[0].Operations[0]);
        Assert.Equal(UuidCodec, first.Before.CodecId);
        Assert.Equal(First.ToString("D"), first.Before.Text);
        Assert.Equal(UuidCodec, first.After.CodecId);
        Assert.Equal(Second.ToString("D"), first.After.Text);
        var creation = Assert.Single(Assert.Single(journal.Transactions[1].Operations).Created);
        Assert.Equal("external-entry", creation.Type);
        var sourceId = Assert.Single(creation.Properties["sourceId"]);
        Assert.Equal(UuidCodec, sourceId.CodecId);
        Assert.Equal(Third.ToString("D"), sourceId.Text);

        var replayed = JournalReplayer.Replay(start, Binding, Checkpoint, journal, ModelSnapshot.Registry);
        Assert.Equal(Second, replayed.SourceId);
        Assert.Equal(new Guid?[] { Second, Third, First }, replayed.RelatedIds);
        var child = Assert.Single(replayed.Children);
        Assert.Equal("child", child.Id);
        Assert.Equal(Third, child.SourceId);
        Assert.Equal(new Guid?[] { First, null, Third }, child.RelatedIds);
        Assert.Equal(First, start.SourceId);
        Assert.Equal(new Guid?[] { Second, null, First }, start.RelatedIds);
        Assert.Empty(start.Children);
    }
}
