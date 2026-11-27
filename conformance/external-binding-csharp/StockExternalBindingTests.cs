using System.Globalization;
using System.Text;
using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Externalbinding.Host;
using Eu.Mihosoft.Vmftest.Genericseries;
using Eu.Mihosoft.Vmftest.Journalvalues;
using Eu.Mihosoft.Vmftest.Readonlyexternallist;
using Vmf.Runtime;
using Xunit;
using ArchiveSnapshot = Eu.Mihosoft.Vmftest.Journalvalues.ModelSnapshot;
using BufferSnapshot = Eu.Mihosoft.Vmftest.Readonlyexternallist.ModelSnapshot;
using StateSnapshot = Eu.Mihosoft.Vmftest.Genericseries.ModelSnapshot;

namespace Vmf.CSharp.Conformance;

public class StockExternalBindingTests
{
    private static readonly Guid First = Guid.Parse("b0e36ead-e4e3-4de7-bca4-b2ba2a7c6035");
    private static readonly Guid Second = Guid.Parse("7bfefa79-00d2-44b6-823a-b7c29e8d2246");
    private static readonly Guid Third = Guid.Parse("0daa4f9e-7295-4f23-8c73-f99b7d04553b");
    private static readonly JournalBinding Archive = new("test.archive", 1, static entry => ((ArchiveEntry)entry).Id!,
        null, JournalValueCodecs.Standard.WithImmutable<DocumentRevision>("test.document-revision/v1",
            static revision => revision.Number.ToString(CultureInfo.InvariantCulture),
            static text => new DocumentRevision(int.Parse(text, CultureInfo.InvariantCulture))));

    [Fact]
    public void StockReadOnlyExternalListIsLiveAndExchangesTheOriginalByValuePayload()
    {
        var text = new StringBuilder("first");
        var root = Sample.NewBuilder().WithLabels(["label"]).WithBuffers([text, null])
            .WithEntries([new Entry { Name = "owned" }]).Build();
        var view = root.AsReadOnly();
        Assert.Same(text, view.Buffers[0]);
        Assert.IsNotAssignableFrom<IList<StringBuilder?>>(view.Buffers);
        Assert.Same(view.Buffers, BufferSnapshot.Reflection.Reflect(view).FindProperty("buffers")!.Get());
        text.Append(" & changed");
        Assert.Equal("first & changed", view.Buffers[0]!.ToString());

        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE");
        var codecs = new (string Extension, Func<Sample, string> Write, Func<string, Sample> Read)[]
        {
            ("json", BufferSnapshot.Write, BufferSnapshot.Read<Sample>),
            ("xml", BufferSnapshot.WriteXml, BufferSnapshot.ReadXml<Sample>),
            ("yaml", BufferSnapshot.WriteYaml, BufferSnapshot.ReadYaml<Sample>)
        };
        foreach (var codec in codecs)
        {
            var snapshot = codec.Write(root);
            VerifySnapshot(codec.Read(snapshot));
            if (directory is null) continue;
            VerifySnapshot(codec.Read(File.ReadAllText(Path.Combine(directory, $"java-stock-external-list.{codec.Extension}"))));
            File.WriteAllText(Path.Combine(directory, $"csharp-stock-external-list.{codec.Extension}"), snapshot);
        }
    }

    [Fact]
    public void BuildersTransferNativeExternalValuesWithoutCopyingTheirInstances()
    {
        var text = new StringBuilder("source");
        var child = new Entry { Name = "owned" };
        var source = Sample.NewBuilder().WithBuffers([text, null, text]).WithEntries([child]).Build();
        var staged = Sample.NewBuilder().ApplyFrom(source);
        source.Buffers.Clear();
        text.Append("-updated");

        var existing = new StringBuilder("existing");
        var target = Sample.NewBuilder().WithBuffers([existing]).Build();
        var buffers = target.Buffers;
        staged.ApplyTo(target);
        Assert.Same(buffers, target.Buffers);
        Assert.Collection(buffers, value => Assert.Same(existing, value), value => Assert.Same(text, value),
            Assert.Null, value => Assert.Same(text, value));
        Assert.Same(child, Assert.Single(target.Entries));
        Assert.Empty(source.Entries);
        Assert.Equal("source-updated", target.AsReadOnly().Buffers[1]!.ToString());

        staged.AppendCollections(false).ApplyTo(target);
        Assert.Collection(buffers, value => Assert.Same(text, value), Assert.Null, value => Assert.Same(text, value));

        var entry = ArchiveEntry.NewBuilder().WithId("source").WithScratch(text).Build();
        var imported = ArchiveEntry.NewBuilder().ApplyFrom(entry).Build();
        Assert.Same(text, imported.Scratch);
        imported.Scratch = null;
        ArchiveEntry.NewBuilder().ApplyFrom(imported).ApplyTo(entry);
        Assert.Null(entry.Scratch);
    }

    [Fact]
    public void StockReadOnlyExternalListForwardsEditsWithTheListAsSource()
    {
        var model = Sample.NewBuilder().WithLabels(["a", "b"]).Build();
        var readOnly = model.AsReadOnly();
        var labels = readOnly.Labels;
        var viewEvents = new List<ModelListChange<string?>>();
        var modelEvents = new List<ModelListChange<string?>>();
        var ownerEvents = new List<ModelChange>();
        using var modelListener = model.Labels.AddListener(modelEvents.Add);
        using var ownerListener = model.VmfChanges.AddPropertyListener("labels", ownerEvents.Add);
        using (labels.AddListener(viewEvents.Add))
        {
            Assert.IsNotAssignableFrom<IList<string?>>(labels);
            Assert.Empty(viewEvents);
            model.Labels.Add("c");
            model.Labels.RemoveAt(0);
        }
        Assert.Same(labels, readOnly.Labels);
        Assert.Same(labels, BufferSnapshot.Reflection.Reflect(readOnly).FindProperty("labels")!.Get());
        Assert.Equal(["b", "c"], labels);
        Assert.Collection(viewEvents, added =>
        {
            Assert.Empty(added.Removed);
            Assert.Equal(new ModelListEntry<string?>(2, "c"), Assert.Single(added.Added));
        }, removed =>
        {
            Assert.Empty(removed.Added);
            Assert.Equal(new ModelListEntry<string?>(0, "a"), Assert.Single(removed.Removed));
        });
        Assert.All(viewEvents, change => Assert.Same(labels, change.Source));
        Assert.All(modelEvents, change => Assert.Same(model.Labels, change.Source));
        Assert.All(ownerEvents, change => Assert.Same(model, change.Source));

        model.Labels[0] = "d";
        Assert.Equal(2, viewEvents.Count);
        Assert.Equal(3, ownerEvents.Count);
        var replacement = modelEvents[2];
        Assert.Equal(new ModelListEntry<string?>(0, "b"), Assert.Single(replacement.Removed));
        Assert.Equal(new ModelListEntry<string?>(0, "d"), Assert.Single(replacement.Added));
    }

    [Fact]
    public void AReadOnlyListListenerFailureKeepsTheEditAndModelHistory()
    {
        var model = new Sample();
        var labels = model.AsReadOnly().Labels;
        var expected = new InvalidOperationException("listener failed");
        using var failing = labels.AddListener(_ => throw expected);
        var changes = new List<ModelListChange<string?>>();
        using var remaining = labels.AddListener(changes.Add);
        var history = model.VmfChanges;
        history.Start();

        Assert.Same(expected, Assert.Throws<InvalidOperationException>(() => model.Labels.Add("kept")));
        Assert.Equal(["kept"], labels);
        var edit = Assert.Single(changes);
        Assert.Same(labels, edit.Source);
        Assert.Equal(new ModelListEntry<string?>(0, "kept"), Assert.Single(edit.Added));
        var recorded = Assert.IsType<ModelCollectionChange>(Assert.Single(history.History));
        Assert.Equal("labels", recorded.PropertyName);
        Assert.Equal("kept", Assert.Single(recorded.Added).Value);
        history.Stop();
    }

    [Fact]
    public void StockGenericSeriesUsesTheDirectNestedEnumBinding()
    {
        var model = StateSeries.NewBuilder().WithLatest(ThreadPhase.NEW)
            .WithSamples([ThreadPhase.BLOCKED, null, ThreadPhase.RUNNABLE]).Build();
        SampleSeries<ThreadPhase?> typed = model;
        Assert.Equal(ThreadPhase.NEW, typed.Latest);
        Assert.Equal(typeof(ThreadPhase?), StateSnapshot.Reflection.Reflect(model).FindProperty("latest")!.ValueType);

        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE");
        var codecs = new (string Extension, Func<StateSeries, string> Write, Func<string, StateSeries> Read)[]
        {
            ("json", StateSnapshot.Write, StateSnapshot.Read<StateSeries>),
            ("xml", StateSnapshot.WriteXml, StateSnapshot.ReadXml<StateSeries>),
            ("yaml", StateSnapshot.WriteYaml, StateSnapshot.ReadYaml<StateSeries>),
        };
        foreach (var codec in codecs)
        {
            string wire = codec.Write(model);
            if (directory is not null)
            {
                File.WriteAllText(Path.Combine(directory, $"csharp-stock-state-series.{codec.Extension}"), wire);
                wire = File.ReadAllText(Path.Combine(directory, $"java-stock-state-series.{codec.Extension}"));
            }
            var restored = codec.Read(wire).AsReadOnly();
            Assert.Equal(ThreadPhase.NEW, restored.Latest);
            Assert.Equal(new ThreadPhase?[] { ThreadPhase.BLOCKED, null, ThreadPhase.RUNNABLE }, restored.Samples);
        }
    }

    [Fact]
    public void StockUuidAndDocumentRevisionJournalRetainsCodecIdsAndBothFrames()
    {
        var entry = Baseline();
        using var recorder = JournalRecorder<ArchiveEntry>.Start(entry, ArchiveSnapshot.Registry, Archive, "initial", 0);
        entry.SourceId = Second;
        entry.RelatedIds.Add(Third);
        entry.Revision = new DocumentRevision(2);
        Assert.NotNull(recorder.PublishFrame());

        entry.RelatedIds[0] = First;
        entry.Revision = new DocumentRevision(3);
        var journal = recorder.Finish();

        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE");
        var codecs = new (string Extension, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read)
        };
        foreach (var codec in codecs)
        {
            var document = codec.Write(journal);
            VerifyArchive(codec.Read(document));
            if (directory is null) continue;
            VerifyArchive(codec.Read(File.ReadAllText(Path.Combine(directory, $"java-stock-archive-journal.{codec.Extension}"))));
            File.WriteAllText(Path.Combine(directory, $"csharp-stock-archive-journal.{codec.Extension}"), document);
        }
    }

    [Fact]
    public void InMemoryStockJournalsAndHistoryUseOnlyTheJournalValueCodec()
    {
        var registry = ArchiveSnapshot.Registry.WithExternalValueCodecs(ExternalSnapshotCodecs.Empty);
        var checkpoint = Baseline();
        var working = Baseline();
        var journal = JournalRecorder<ArchiveEntry>.RecordFrame(working, registry, Archive, "initial", 0,
            model => model.Revision = new DocumentRevision(2));
        journal = JournalJson.Read(JournalJson.Write(journal));
        Assert.Equal(1, journal.WireVersion);
        var operation = Assert.IsType<JournalSet>(Assert.Single(Assert.Single(journal.Transactions).Operations));
        Assert.Equal("test.document-revision/v1", operation.After.CodecId);
        Assert.Equal("2", operation.After.Text);

        var replayed = JournalReplayer.Replay(checkpoint, Archive, "initial", journal, registry);
        Assert.NotSame(checkpoint, replayed);
        Assert.Equal(new DocumentRevision(2), replayed.Revision);
        Assert.Equal(new DocumentRevision(1), checkpoint.Revision);

        var history = new JournalHistory<ArchiveEntry>(Archive, registry);
        history.AddSegment("initial", checkpoint, journal);
        var historical = history.Seek(1);
        Assert.Equal(new DocumentRevision(1), history.Seek(0).Revision);
        Assert.Equal(new DocumentRevision(2), historical.Revision);
        historical.Revision = new DocumentRevision(9);
        Assert.Equal(new DocumentRevision(2), history.Seek(1).Revision);

        Assert.Throws<JsonException>(() => SnapshotJson.Write(checkpoint, registry));
        string encoded = ArchiveSnapshot.Write(checkpoint);
        Assert.Throws<JsonException>(() => SnapshotJson.Read<ArchiveEntry>(encoded, registry));
    }

    [Fact]
    public void MutableSnapshotHostIsNotAnExternalJournalValue()
    {
        var checkpoint = Baseline();
        checkpoint.Scratch = new StringBuilder("mutable");
        Assert.Throws<ArgumentException>(() =>
            JournalRecorder<ArchiveEntry>.Start(checkpoint, ArchiveSnapshot.Registry, Archive, "initial", 0));
        Assert.False(checkpoint.VmfChanges.IsRecording);

        var entry = Baseline();
        using var recorder = JournalRecorder<ArchiveEntry>.Start(entry, ArchiveSnapshot.Registry, Archive, "initial", 0);
        entry.Scratch = new StringBuilder("mutable");
        Assert.Throws<InvalidOperationException>(() => recorder.Finish());
    }

    private static ArchiveEntry Baseline()
    {
        var entry = new ArchiveEntry { Id = "entry", SourceId = First, Revision = new DocumentRevision(1) };
        entry.RelatedIds.Add(Second);
        return entry;
    }

    private static void VerifySnapshot(Sample model)
    {
        Assert.Equal("label", Assert.Single(model.Labels));
        Assert.Equal(2, model.Buffers.Count);
        Assert.Equal("first & changed", model.Buffers[0]!.ToString());
        Assert.Null(model.Buffers[1]);
        Assert.Equal("owned", Assert.Single(model.Entries)!.Name);
    }

    private static void VerifyArchive(ChangeJournal journal)
    {
        Assert.Equal(1, journal.WireVersion);
        Assert.Equal(2, journal.Transactions.Count);
        var first = journal.Transactions[0].Operations;
        var uuid = Assert.IsType<JournalSet>(first[0]).After;
        var edition = Assert.IsType<JournalSet>(first[2]).After;
        Assert.Equal("java.uuid/v1", uuid.CodecId);
        Assert.Equal(Second.ToString("D"), uuid.Text);
        Assert.Equal("test.document-revision/v1", edition.CodecId);
        Assert.Equal("2", edition.Text);

        var replay = JournalReplayer.Replay(Baseline(), Archive, "initial", journal, ArchiveSnapshot.Registry);
        Assert.Equal(Second, replay.SourceId);
        Assert.Equal([First, Third], replay.RelatedIds);
        Assert.Equal(new DocumentRevision(3), replay.Revision);
    }
}
