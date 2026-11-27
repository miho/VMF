using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Genericscalars;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class GenericScalarTests
{
    private const string CheckpointId = "origin";
    private static readonly JournalBinding Binding = new("conformance.unbounded-series", 1, model => model switch
    {
        Telemetry => "telemetry", Counts => "counts", Labels => "labels",
        _ => throw new InvalidOperationException("Unknown journal record owner.")
    }, (_, field) => field == "latest" ? "LATEST" : field);

    [Fact]
    public void GenericScalarFacadesKeepBoxedNullsAndClosedFieldTypes()
    {
        var root = Specimen();
        Series<int?> counts = root.Counts!;
        Series<string?> labels = root.Labels!;
        IReadOnlySeries<int?> countView = counts.AsReadOnly();
        IReadOnlySeries<string?> labelView = labels.AsReadOnly();
        Assert.Null(countView.Latest);
        Assert.Equal("draft", labelView.Latest);
        counts.Latest = 0;
        labels.Latest = null;
        Assert.Equal(0, countView.Latest);
        Assert.Null(labelView.Latest);
        Assert.Null(countView.Samples[1]);
        Assert.True(countView.Named.ContainsKey("paused"));
        Assert.Null(countView.Named["paused"]);

        Series<bool?> flags = new Flags();
        Assert.Null(flags.Latest);
        flags.Latest = false;
        Assert.False(flags.AsReadOnly().Latest);
        Numbered<long> numeric = new Longs();
        Series<long?> wide = numeric;
        IReadOnlySeries<long?> wideView = numeric.AsReadOnly();
        Assert.Null(wideView.Latest);
        wide.Latest = long.MaxValue;
        Assert.Equal(long.MaxValue, wideView.Latest);

        var reflected = ModelSnapshot.Reflection.Reflect(counts).FindProperty("latest");
        Assert.Equal(typeof(int?), reflected?.ValueType);
        Assert.Equal("latest_value", reflected?.WireName);
        Assert.Equal(typeof(string), ModelSnapshot.Reflection.Reflect(labels).FindProperty("latest")?.ValueType);
        var catalog = ModelSnapshot.TypeCatalog;
        var argument = Assert.Single(Assert.Single(catalog.Find(typeof(Counts))!.DirectSupertypes).Arguments);
        Assert.Equal(ModelValueKind.Int32, argument.Kind);
        Assert.False(argument.IsPrimitive);
        Assert.Same(catalog.Find(typeof(Series<>)), catalog.Find(typeof(Series<int?>)));
    }

    [Fact]
    public void BothRuntimesProduceTheSameTypedCheckpointAndJournal()
    {
        var root = Specimen();
        string checkpoint = ModelSnapshot.WritePlain(root);
        var recorded = JournalRecorder<Telemetry>.RecordFrame(root, ModelSnapshot.Registry, Binding, CheckpointId, 8,
            current =>
            {
                current.Counts!.Latest = 13;
                current.Labels!.Latest = null;
                current.Counts.Samples.Insert(1, 9);
                current.Labels.Samples[0] = "restored";
                current.Counts.Named.Put("paused", 13);
                current.Labels.Named.Add("new", null);
            });
        string journal = JournalJson.Write(recorded);
        Verify(checkpoint, journal);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_GENERIC_SCALARS") is { } directory)
        {
            string javaCheckpoint = File.ReadAllText(Path.Combine(directory, "java-checkpoint.json"));
            string javaJournal = File.ReadAllText(Path.Combine(directory, "java-journal.json"));
            Verify(javaCheckpoint, javaJournal);
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(javaCheckpoint), JsonNode.Parse(checkpoint)));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(javaJournal), JsonNode.Parse(journal)));
            File.WriteAllText(Path.Combine(directory, "csharp-checkpoint.json"), checkpoint);
            File.WriteAllText(Path.Combine(directory, "csharp-journal.json"), journal);
        }
    }

    private static Telemetry Specimen()
    {
        var counts = Counts.NewBuilder().WithSamples([7, null, 7]).WithNamed([new("paused", null)]).Build();
        var labels = Labels.NewBuilder().WithLatest("draft").WithSamples([null, "start"])
            .WithNamed([new("stable", "start")]).Build();
        return Telemetry.NewBuilder().WithCounts(counts).WithLabels(labels).Build();
    }

    private static void Verify(string checkpointDocument, string journalDocument)
    {
        Assert.Contains("\"latest_value\"", checkpointDocument, StringComparison.Ordinal);
        var checkpoint = ModelSnapshot.ReadPlain<Telemetry>(checkpointDocument);
        var journal = JournalJson.Read(journalDocument);
        Assert.Equal(1, journal.WireVersion);
        var proposal = JournalReplayer.Replay(checkpoint, Binding, CheckpointId, journal, ModelSnapshot.Registry);
        Assert.NotSame(checkpoint, proposal);
        Assert.Null(checkpoint.Counts!.Latest);
        Assert.Equal(new int?[] { 7, null, 7 }, checkpoint.Counts.Samples);
        Assert.True(checkpoint.Counts.Named.ContainsKey("paused"));
        Assert.Null(checkpoint.Counts.Named["paused"]);
        Assert.Equal("draft", checkpoint.Labels!.Latest);
        Assert.False(checkpoint.Labels.Named.ContainsKey("new"));

        Assert.Equal(13, proposal.Counts!.Latest);
        Assert.Equal(new int?[] { 7, 9, null, 7 }, proposal.Counts.Samples);
        Assert.Equal(13, proposal.Counts.Named["paused"]);
        Assert.Null(proposal.Labels!.Latest);
        Assert.Equal(["restored", "start"], proposal.Labels.Samples);
        Assert.True(proposal.Labels.Named.ContainsKey("new"));
        Assert.Null(proposal.Labels.Named["new"]);
    }
}
