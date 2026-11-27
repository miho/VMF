using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Genericscalars;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class SecondaryScalarTests
{
    private const string CheckpointId = "initial";
    private static readonly JournalBinding Binding = new("secondary-generic", 1,
        model => ((ReadingDocument)model).Id!, null);

    [Fact]
    public void SecondaryGenericContractsShareTypedStorageAndLiveViews()
    {
        var document = Measurements();
        var labels = Labels();
        ISampleValues<int?> numeric = document;
        IForwardValues<string?> textual = labels;
        IReadOnlySampleValues<int?> numericView = numeric.AsReadOnly();
        IReadOnlyForwardValues<string?> textView = textual.AsReadOnly();

        numeric.Latest = 41;
        textual.History[0] = "live";
        Assert.Equal(41, numericView.Latest);
        Assert.Null(numericView.History[1]);
        Assert.Equal("live", textView.History[0]);
        Assert.Same(numeric.History, document.History);
        Assert.Same(textual.History, labels.History);

        ISampleValues<int?> primary = new PrimaryMeasurements();
        primary.Latest = 6;
        Assert.Equal(6, primary.AsReadOnly().Latest);
        IForwardValues<int?> direct = new DirectMeasurements();
        direct.Latest = 11;
        Assert.Equal(11, direct.AsReadOnly().Latest);

        var reflection = ModelSnapshot.Reflection.Reflect(document);
        Assert.Equal(typeof(int?), reflection.FindProperty("latest")?.ValueType);
        Assert.Equal(typeof(int?), reflection.FindProperty("history")?.ValueType);
        Assert.Equal(typeof(string), ModelSnapshot.Reflection.Reflect(labels).FindProperty("history")?.ValueType);
        var parents = ModelSnapshot.TypeCatalog.Find(typeof(ComposedMeasurements))!.DirectSupertypes;
        var parent = Assert.Single(parents, candidate => candidate.Name.EndsWith(".IntegerReadings", StringComparison.Ordinal));
        var application = Assert.Single(ModelSnapshot.TypeCatalog.Find(parent.Name)!.DirectSupertypes);
        Assert.Equal(ModelValueKind.Int32, Assert.Single(application.Arguments).Kind);

        var copy = ModelSnapshot.Reflection.DeepCopy(document);
        Assert.NotSame(document.History, copy.History);
        Assert.Equal(41, copy.Latest);
        Assert.Equal(new int?[] { 7, null, 9 }, copy.History.ToArray());
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void ExchangesSecondaryGenericSnapshotsAndPositionalJournals(string format)
    {
        var baseline = Measurements();
        var labels = Labels();
        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_GENERIC_SCALARS");
        foreach (bool references in new[] { false, true })
        {
            ExchangeSnapshot(baseline, "measurements", references, VerifyMeasurements);
            ExchangeSnapshot(labels, "labels", references, value =>
            {
                Assert.Equal("text", value.Id);
                Assert.Equal("false", value.Latest);
                Assert.Equal(new string?[] { "7", null, "true" }, value.History.ToArray());
            });
        }

        var journal = JournalRecorder<ComposedMeasurements>.RecordFrame(Measurements(), ModelSnapshot.Registry, Binding,
            CheckpointId, 4, model =>
            {
                ISampleValues<int?> samples = model;
                samples.Latest = 42;
                samples.History[1] = -3;
                samples.History.Add(null);
            });
        (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) storage = format switch
        {
            "json" => (JournalJson.Write, JournalJson.Read),
            "yaml" => (JournalYaml.Write, JournalYaml.Read),
            "xml" => (JournalXml.Write, JournalXml.Read),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
        string document = storage.Write(journal);
        var checkpointCodec = SnapshotCodec<ComposedMeasurements>(format, references: true);
        VerifyJournal(storage.Read(document), checkpointCodec.Read(checkpointCodec.Write(baseline)));
        if (directory is not null)
        {
            string javaCheckpoint = File.ReadAllText(Path.Combine(directory, $"java-secondary-measurements-true.{format}"));
            string javaJournal = File.ReadAllText(Path.Combine(directory, $"java-secondary-journal.{format}"));
            VerifyJournal(storage.Read(javaJournal), checkpointCodec.Read(javaCheckpoint));
            File.WriteAllText(Path.Combine(directory, $"csharp-secondary-journal.{format}"), document);
        }

        void ExchangeSnapshot<T>(T specimen, string name, bool references, Action<T> verify) where T : class
        {
            var codec = SnapshotCodec<T>(format, references);
            string snapshot = codec.Write(specimen);
            verify(codec.Read(snapshot));
            if (directory is null) return;

            string filename = $"{name}-{references.ToString().ToLowerInvariant()}.{format}";
            string java = File.ReadAllText(Path.Combine(directory, "java-secondary-" + filename));
            verify(codec.Read(java));
            if (format == "json")
                Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(snapshot)));
            File.WriteAllText(Path.Combine(directory, "csharp-secondary-" + filename), snapshot);
        }

        void VerifyJournal(ChangeJournal edits, ComposedMeasurements checkpoint)
        {
            Assert.Equal(1, edits.WireVersion);
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(journal)), JsonNode.Parse(JournalJson.Write(edits))));
            var replayed = JournalReplayer.Replay(checkpoint, Binding, CheckpointId, edits, ModelSnapshot.Registry);
            Assert.Equal(42, replayed.Latest);
            Assert.Equal(new int?[] { 7, -3, 9, null }, replayed.History.ToArray());
            VerifyMeasurements(checkpoint);
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

    private static ComposedMeasurements Measurements() =>
        ComposedMeasurements.NewBuilder().WithId("numeric").WithLatest(null).WithHistory([7, null, 9]).Build();

    private static ComposedLabels Labels() =>
        ComposedLabels.NewBuilder().WithId("text").WithLatest("false").WithHistory(["7", null, "true"]).Build();

    private static void VerifyMeasurements(ComposedMeasurements model)
    {
        Assert.Equal("numeric", model.Id);
        Assert.Null(model.Latest);
        Assert.Equal(new int?[] { 7, null, 9 }, model.History.ToArray());
    }
}
