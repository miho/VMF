using System.Text.Json;
using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class JournalTests
{
    private const string CheckpointId = "scalar-checkpoint-40";
    private const long InitialLong = 9_007_199_254_740_993L;
    private const string Inspection = "inspection 🔧";
    private static readonly double FinalProbe = Math.ScaleB(1.0 + 0x23 / 256.0, 45);
    private static readonly double NegativeZero = BitConverter.Int64BitsToDouble(long.MinValue);
    private static readonly string Fixtures = Path.Combine(AppContext.BaseDirectory, "fixtures");
    private static readonly JournalBinding Binding = new("conformance.scalar-journal", 1,
        model => model is ScalarSample ? "scalar-sample" : throw new ArgumentException("Unknown scalar model."),
        (_, field) => field switch { "description" => "NAME", "optionalDouble" => "probe", _ => field });

    [Fact]
    public void RecorderStartLeavesAnExistingNativeOrPortableRecordingIntact()
    {
        var native = NewSample();
        native.VmfChanges.Start();
        native.Description = "native edit";
        Assert.Throws<InvalidOperationException>(() =>
        {
            using var rejected = JournalRecorder<ScalarSample>.Start(native, ModelSnapshot.Registry, Binding, CheckpointId, 40);
        });
        Assert.True(native.VmfChanges.IsRecording);
        Assert.Equal("native edit", Assert.Single(native.VmfChanges.History).NewValue);
        native.VmfChanges.Stop();
        Assert.Single(native.VmfChanges.Transactions);

        var portable = NewSample();
        using var owner = JournalRecorder<ScalarSample>.Start(portable, ModelSnapshot.Registry, Binding, CheckpointId, 40);
        portable.Description = "portable edit";
        Assert.Throws<InvalidOperationException>(() =>
        {
            using var rejected = JournalRecorder<ScalarSample>.Start(portable, ModelSnapshot.Registry, Binding, CheckpointId, 40);
        });
        Assert.True(portable.VmfChanges.IsRecording);
        Assert.Equal("portable edit", Assert.Single(portable.VmfChanges.History).NewValue);
        var journal = owner.Finish();
        Assert.Equal("portable edit", Assert.IsType<JournalSet>(
            Assert.Single(Assert.Single(journal.Transactions).Operations)).After.Text);
        Assert.False(portable.VmfChanges.IsRecording);

        var reopened = JournalRecorder<ScalarSample>.RecordFrame(portable, ModelSnapshot.Registry, Binding,
            CheckpointId, 41, sample => sample.Description = "next recording");
        Assert.Equal("next recording", Assert.IsType<JournalSet>(
            Assert.Single(Assert.Single(reopened.Transactions).Operations)).After.Text);
    }

    [Fact]
    public void RecorderStartLeavesManualVersioningAndEarlierHistoryIntact()
    {
        var sample = NewSample();
        var changes = sample.VmfChanges;
        changes.Start();
        sample.Description = "recorded";
        changes.Stop();
        var recorded = Assert.Single(changes.History);
        var published = Assert.Single(changes.Transactions);

        changes.EnableModelVersioning();
        sample.Description = "version only";
        var before = changes.ModelVersion;
        Assert.Throws<InvalidOperationException>(() =>
        {
            using var rejected = JournalRecorder<ScalarSample>.Start(sample, ModelSnapshot.Registry, Binding, CheckpointId, 40);
        });
        Assert.False(changes.IsRecording);
        Assert.True(changes.IsModelVersioningEnabled);
        Assert.Same(before, changes.ModelVersion);
        sample.Description = "still versioned";
        Assert.Equal(before.VersionNumber + 1, changes.ModelVersion.VersionNumber);
        Assert.Same(recorded, Assert.Single(changes.History));
        Assert.Same(published, Assert.Single(changes.Transactions));
        changes.DisableModelVersioning();
    }

    [Fact]
    public void RecordFrameRejectsNativePublicationButPreservesAppliedEditsAndPublishedGroup()
    {
        var sample = NewSample();
        Assert.Throws<InvalidOperationException>(() =>
            JournalRecorder<ScalarSample>.RecordFrame(sample, ModelSnapshot.Registry, Binding, CheckpointId, 40, model =>
            {
                model.Description = "published";
                model.VmfChanges.PublishTransaction();
                model.OptionalInt = 88;
            }));

        Assert.Equal("published", sample.Description);
        Assert.Equal(88, sample.OptionalInt);
        Assert.False(sample.VmfChanges.IsRecording);
        Assert.Equal(2, sample.VmfChanges.History.Count);
        Assert.Equal("description", Assert.Single(Assert.Single(sample.VmfChanges.Transactions).Changes).PropertyName);
    }

    [Fact]
    public void RecordFrameRejectsAnEditThatProducesMultipleFramesWithoutUndoingIt()
    {
        var sample = NewSample();
        Assert.Throws<InvalidOperationException>(() =>
            JournalRecorder<ScalarSample>.RecordFrame(sample, ModelSnapshot.Registry, Binding, CheckpointId, 40, model =>
            {
                model.Description = "ungrouped";
                model.VmfChanges.StartTransaction();
                model.OptionalInt = 88;
            }));

        Assert.Equal("ungrouped", sample.Description);
        Assert.Equal(88, sample.OptionalInt);
        Assert.False(sample.VmfChanges.IsRecording);
    }

    [Fact]
    public void PublishFrameRejectsEarlierNativeGroupsAndKeepsPendingEditsUnpublished()
    {
        var sample = NewSample();
        using var recorder = JournalRecorder<ScalarSample>.Start(sample, ModelSnapshot.Registry, Binding, CheckpointId, 40);
        sample.Description = "native group";
        sample.VmfChanges.PublishTransaction();
        sample.OptionalInt = 88;

        Assert.Throws<InvalidOperationException>(() => recorder.PublishFrame());
        Assert.Equal("native group", sample.Description);
        Assert.Equal(88, sample.OptionalInt);
        Assert.False(sample.VmfChanges.IsRecording);
        Assert.Equal(2, sample.VmfChanges.History.Count);
        Assert.Equal("description", Assert.Single(Assert.Single(sample.VmfChanges.Transactions).Changes).PropertyName);
    }

    [Fact]
    public void PublishFrameRejectsObserverWritesEvenWhenTheFinalGraphMatches()
    {
        var sample = NewSample();
        using var recorder = JournalRecorder<ScalarSample>.Start(sample, ModelSnapshot.Registry, Binding, CheckpointId, 40);
        using var listener = sample.VmfChanges.Transactions.AddListener(_ =>
        {
            sample.Description = "temporary";
            sample.Description = "published";
        });
        sample.Description = "published";

        Assert.Throws<InvalidOperationException>(() => recorder.PublishFrame());
        Assert.Equal("published", sample.Description);
        Assert.False(sample.VmfChanges.IsRecording);
        Assert.Equal(3, sample.VmfChanges.History.Count);
        Assert.Single(Assert.Single(sample.VmfChanges.Transactions).Changes);
    }

    [Fact]
    public void PublishFrameRejectsNestedLifecycleCallsAndContinuesWhenObserverCatchesThem()
    {
        var sample = NewSample();
        using var recorder = JournalRecorder<ScalarSample>.Start(sample, ModelSnapshot.Registry, Binding, CheckpointId, 40);
        int callbacks = 0;
        using (sample.VmfChanges.Transactions.AddListener(_ =>
        {
            Assert.Throws<InvalidOperationException>(() => recorder.PublishFrame());
            Assert.Throws<InvalidOperationException>(() => recorder.Finish());
            Assert.Throws<InvalidOperationException>(() => recorder.Dispose());
            callbacks++;
        }))
        {
            sample.Description = "published";
            Assert.Equal(41L, recorder.PublishFrame()?.Sequence);
        }

        Assert.Equal(1, callbacks);
        Assert.True(sample.VmfChanges.IsRecording);
        sample.OptionalInt = 88;
        Assert.Equal(42L, recorder.PublishFrame()?.Sequence);
        Assert.Equal(2, recorder.Finish().Transactions.Count);
    }

    [Fact]
    public void PublishFramePropagatesUncaughtNestedFailureAndRetainsTheNativeGroup()
    {
        var sample = NewSample();
        using var recorder = JournalRecorder<ScalarSample>.Start(sample, ModelSnapshot.Registry, Binding, CheckpointId, 40);
        Exception? nested = null;
        using var listener = sample.VmfChanges.Transactions.AddListener(_ =>
        {
            try
            {
                recorder.PublishFrame();
            }
            catch (InvalidOperationException error)
            {
                nested = error;
                throw;
            }
        });
        sample.Description = "published";

        var failure = Assert.Throws<InvalidOperationException>(() => recorder.PublishFrame());
        Assert.Same(nested, failure);
        Assert.Equal("published", sample.Description);
        Assert.False(sample.VmfChanges.IsRecording);
        Assert.Single(sample.VmfChanges.History);
        Assert.Single(Assert.Single(sample.VmfChanges.Transactions).Changes);
    }

    [Fact]
    public void NativePublicationRetainsItsGroupWhenThePortableCaptureFails()
    {
        var sample = NewSample();
        bool remap = false;
        var binding = new JournalBinding("conformance.scalar-journal", 1, _ => "scalar-sample",
            (_, property) => remap ? "remapped-" + property : property);
        using var recorder = JournalRecorder<ScalarSample>.Start(sample, ModelSnapshot.Registry, binding, CheckpointId, 40);
        sample.Description = "native publication";
        remap = true;

        Assert.NotNull(sample.VmfChanges.PublishTransaction());
        Assert.True(sample.VmfChanges.IsRecording);
        Assert.Throws<InvalidOperationException>(() => recorder.Finish());

        Assert.Equal("native publication", sample.Description);
        Assert.False(sample.VmfChanges.IsRecording);
        Assert.Single(Assert.Single(sample.VmfChanges.Transactions).Changes);
    }

    [Fact]
    public void CSharpAndJavaExchangeScalarJournalsAndFreshCheckpoints()
    {
        string checkpoint = ModelSnapshot.WritePlain(NewSample());
        string journal = JournalJson.Write(Record());
        string fixtureJournal = File.ReadAllText(Path.Combine(Fixtures, "scalar-journal.json"));
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(fixtureJournal), JsonNode.Parse(journal)));

        VerifyReplay(checkpoint, journal);
        VerifyReplay(File.ReadAllText(Path.Combine(Fixtures, "scalar-journal-checkpoint.json")), fixtureJournal);

        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_JOURNAL");
        if (interchange is not null)
        {
            VerifyReplay(File.ReadAllText(Path.Combine(interchange, "java-checkpoint.json")),
                File.ReadAllText(Path.Combine(interchange, "java-journal.json")));
            File.WriteAllText(Path.Combine(interchange, "csharp-checkpoint.json"), checkpoint);
            File.WriteAllText(Path.Combine(interchange, "csharp-journal.json"), journal);
        }
    }

    [Fact]
    public void XmlJournalsPreserveControlCharactersAndCarriageReturns()
    {
        const string control = "inspection\u0001\rnext\nline\t";
        var working = NewSample();
        string baseline = ModelSnapshot.WritePlain(working);
        var journal = JournalRecorder<ScalarSample>.RecordFrame(working, ModelSnapshot.Registry,
            Binding, CheckpointId, 40, model => model.Description = control);
        string xml = JournalXml.Write(journal);
        Assert.Contains("encoding=\"base64\"", xml, StringComparison.Ordinal);
        Verify(baseline, JournalXml.Read(xml));
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_JOURNAL") is { } interchange)
        {
            var fromJava = JournalXml.Read(File.ReadAllText(Path.Combine(interchange, "java-control-journal.xml")));
            Verify(File.ReadAllText(Path.Combine(interchange, "java-checkpoint.json")), fromJava);
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(journal)), JsonNode.Parse(JournalJson.Write(fromJava))));
            File.WriteAllText(Path.Combine(interchange, "csharp-control-journal.xml"), xml);
        }

        void Verify(string checkpoint, ChangeJournal recorded)
        {
            var original = ModelSnapshot.ReadPlain<ScalarSample>(checkpoint);
            var restored = JournalReplayer.Replay(original, Binding, CheckpointId, recorded, ModelSnapshot.Registry);
            Assert.Equal(control, restored.Description);
            Assert.Equal("draft", original.Description);
        }
    }

    [Theory]
    [InlineData("<vmf-journal><version>1</version><version>1</version></vmf-journal>")]
    [InlineData("<!DOCTYPE vmf-journal><vmf-journal/>")]
    [InlineData("<vmf-journal><root encoding=\"base64\">/w==</root></vmf-journal>")]
    [InlineData("<vmf-journal><version>01</version></vmf-journal>")]
    public void XmlJournalsRejectDuplicateFieldsAndMalformedEnvelopes(string xml) =>
        Assert.Throws<JsonException>(() => JournalXml.Read(xml));

    [Fact]
    public void ReplayChecksIdentityAndRejectsLateFailuresWithoutChangingTheCheckpoint()
    {
        var original = NewSample();
        var journal = Record();
        Assert.Throws<InvalidOperationException>(() =>
            JournalReplayer.Replay(original, Binding, "wrong", journal, ModelSnapshot.Registry));

        var stale = NewSample();
        stale.OptionalDouble = NegativeZero;
        Assert.Throws<InvalidOperationException>(() =>
            JournalReplayer.Replay(stale, Binding, CheckpointId, journal, ModelSnapshot.Registry));
        Assert.Equal("draft", stale.Description);
        Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(Assert.IsType<double>(stale.OptionalDouble)));

        var content = JsonNode.Parse(JournalJson.Write(journal))!;
        content["transactions"]![1]!["operations"]![2]!["target"] = "missing";
        var invalid = JournalJson.Read(content.ToJsonString());
        Assert.Throws<InvalidOperationException>(() =>
            JournalReplayer.Replay(original, Binding, CheckpointId, invalid, ModelSnapshot.Registry));
        CheckOriginal(original);
    }

    [Fact]
    public void ReaderValidatesEnvelopeAndCanonicalTaggedValues()
    {
        string json = File.ReadAllText(Path.Combine(Fixtures, "scalar-journal.json"));
        var wrongVersion = JsonNode.Parse(json)!;
        wrongVersion["version"] = 99;
        Assert.Throws<JsonException>(() => JournalJson.Read(wrongVersion.ToJsonString()));

        var skipped = JsonNode.Parse(json)!;
        skipped["transactions"]![1]!["sequence"] = 43;
        Assert.Throws<JsonException>(() => JournalJson.Read(skipped.ToJsonString()));

        var invalid = JsonNode.Parse(json)!;
        invalid["transactions"]![0]!["operations"]![2]!["after"]![0]!["text"] = "-0x0.00p0";
        Assert.Throws<JsonException>(() => JournalJson.Read(invalid.ToJsonString()));
        Assert.Throws<JsonException>(() => JournalJson.Read(json.Insert(1, "\"version\":1,")));
        Assert.Throws<FormatException>(() => JournalValue.FromScalar("\uD800"));
    }

    [Fact]
    public void RecorderCapturesDescendantEditsAndKeepsStableIdsOnReplay()
    {
        var owner = new ArchiveOwner();
        var leaf = new ArchiveOwnedLeaf { Label = "initial", Owner = owner };
        var baseline = ModelSnapshot.Write(owner);
        var binding = new JournalBinding("conformance.descendant-journal", 1, model => model switch
        {
            ArchiveOwner => "owner",
            ArchiveOwnedLeaf => "leaf",
            _ => throw new ArgumentException("Unknown checkpoint model.")
        });

        using var recorder = JournalRecorder<ArchiveOwner>.Start(owner, ModelSnapshot.Registry, binding, "baseline", 4);
        leaf.Label = "changed";
        var journal = recorder.Finish();
        var edit = Assert.Single(Assert.Single(journal.Transactions).Operations);
        Assert.Equal("leaf", edit.Target);
        Assert.Equal("label", edit.Property);

        var fresh = ModelSnapshot.Read<ArchiveOwner>(baseline);
        var restored = JournalReplayer.Replay(fresh, binding, "baseline", journal, ModelSnapshot.Registry);
        var restoredLeaf = Assert.IsType<ArchiveOwnedLeaf>(Assert.Single(restored.Nodes));
        Assert.Equal("changed", restoredLeaf.Label);
        Assert.Same(restored, restoredLeaf.Owner);
        Assert.Equal("initial", Assert.IsType<ArchiveOwnedLeaf>(Assert.Single(fresh.Nodes)).Label);
    }

    [Fact]
    public void JournalPropertyUsesTheModelNameWhenTheJsonKeyWasRenamed()
    {
        var batch = new ScalarBatch { Title = "A" };
        var entry = new ScalarEntry { StatusLabel = "X-12" };
        batch.Entries.Add(entry);
        var baseline = ModelSnapshot.Write(batch);
        using var document = JsonDocument.Parse(baseline);
        Assert.Equal("X-12", document.RootElement.GetProperty("entries")[0].GetProperty("status_label").GetString());

        var binding = new JournalBinding("conformance.renamed-field", 1, model => model is ScalarBatch ? "batch" : "entry");
        using var recorder = JournalRecorder<ScalarBatch>.Start(batch, ModelSnapshot.Registry, binding, "baseline", 0);
        entry.StatusLabel = "X-13";
        var journal = recorder.Finish();
        Assert.Equal("statusLabel", Assert.Single(Assert.Single(journal.Transactions).Operations).Property);
        var restored = JournalReplayer.Replay(ModelSnapshot.Read<ScalarBatch>(baseline), binding, "baseline",
            journal, ModelSnapshot.Registry);
        Assert.Equal("X-13", Assert.Single(restored.Entries).StatusLabel);
    }

    [Fact]
    public void NativeTransactionPublicationPreservesUngroupedPrefixAndExplicitFrames()
    {
        var working = NewSample();
        using var recorder = JournalRecorder<ScalarSample>.Start(
            working, ModelSnapshot.Registry, Binding, CheckpointId, 40);
        working.Description = "ungrouped";
        working.VmfChanges.StartTransaction();
        working.OptionalInt = 9;
        working.Description = "grouped";
        working.VmfChanges.PublishTransaction();
        var journal = recorder.Finish();
        Assert.Collection(journal.Transactions,
            prefix => Assert.Single(prefix.Operations),
            group => Assert.Equal(2, group.Operations.Count));

        var replayed = JournalReplayer.Replay(NewSample(), Binding, CheckpointId, journal, ModelSnapshot.Registry);
        Assert.Equal("grouped", replayed.Description);
        Assert.Equal(9, replayed.OptionalInt);
    }

    [Fact]
    public void FailedRecordCallbackKeepsEditsButDoesNotPublishPendingFrame()
    {
        var working = NewSample();
        var failure = new InvalidOperationException("failed after editing");
        var thrown = Assert.Throws<InvalidOperationException>(() =>
            JournalRecorder<ScalarSample>.RecordFrame(working, ModelSnapshot.Registry, Binding, CheckpointId, 40,
                model =>
                {
                    model.Description = "applied";
                    throw failure;
                }));
        Assert.Same(failure, thrown);
        Assert.Equal("applied", working.Description);
        Assert.False(working.VmfChanges.IsRecording);
        Assert.Single(working.VmfChanges.History);
        Assert.Empty(working.VmfChanges.Transactions);
    }

    [Fact]
    public void DisposeKeepsAppliedEditsAndOmitsTheUnpublishedFrame()
    {
        var working = NewSample();
        using (JournalRecorder<ScalarSample>.Start(working, ModelSnapshot.Registry, Binding, CheckpointId, 40))
        {
            working.Description = "edited";
        }
        Assert.False(working.VmfChanges.IsRecording);
        Assert.Equal("edited", working.Description);
        Assert.Equal("edited", Assert.Single(working.VmfChanges.History).NewValue);
        Assert.Empty(working.VmfChanges.Transactions);
    }

    [Fact]
    public void RecorderRejectsChangedIdsWithoutRevertingTheWorkingModel()
    {
        var owner = new ArchiveOwner();
        var leaf = new ArchiveOwnedLeaf { Label = "original", Owner = owner };
        var binding = new JournalBinding("conformance.ownership-journal", 1,
            model => model is ArchiveOwner ? "owner" : ((ArchiveOwnedLeaf)model).Label!);

        using var recorder = JournalRecorder<ArchiveOwner>.Start(owner, ModelSnapshot.Registry, binding, "baseline", 0);
        leaf.Label = "renamed";
        Assert.Throws<InvalidOperationException>(() => recorder.Finish());
        Assert.False(owner.VmfChanges.IsRecording);
        Assert.Same(leaf, Assert.Single(owner.Nodes));
        Assert.Equal("renamed", leaf.Label);
    }

    private static ChangeJournal Record()
    {
        var working = NewSample();
        using var recorder = JournalRecorder<ScalarSample>.Start(working, ModelSnapshot.Registry, Binding, CheckpointId, 40);
        working.Description = Inspection;
        working.OptionalLong = long.MinValue;
        working.OptionalDouble = NegativeZero;
        working.SurrogateUnit = '\uD800';
        Assert.Equal(41, Assert.IsType<JournalFrame>(recorder.PublishFrame()).Sequence);
        Assert.Null(recorder.PublishFrame());

        working.OptionalDouble = FinalProbe;
        working.OptionalFloat = float.Epsilon;
        working.Description = "ready";
        var journal = recorder.Finish();
        Assert.Equal([41L, 42L], journal.Transactions.Select(transaction => transaction.Sequence));
        CheckFinal(working);
        return journal;
    }

    private static void VerifyReplay(string checkpoint, string document)
    {
        var original = ModelSnapshot.ReadPlain<ScalarSample>(checkpoint);
        var journal = JournalJson.Read(document);
        var first = new ChangeJournal(journal.Model, journal.ModelVersion, journal.Root, journal.Checkpoint,
            journal.BaseSequence, [journal.Transactions[0]]);
        var intermediate = JournalReplayer.Replay(original, Binding, CheckpointId, first, ModelSnapshot.Registry);
        Assert.NotSame(original, intermediate);
        Assert.Equal(Inspection, intermediate.Description);
        Assert.Equal(long.MinValue, intermediate.OptionalLong);
        Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(Assert.IsType<double>(intermediate.OptionalDouble)));
        Assert.Equal('\uD800', intermediate.SurrogateUnit);
        Assert.Null(intermediate.OptionalFloat);

        var restored = JournalReplayer.Replay(original, Binding, CheckpointId, journal, ModelSnapshot.Registry);
        Assert.NotSame(original, restored);
        Assert.NotSame(intermediate, restored);
        CheckFinal(restored);
        CheckOriginal(original);
    }

    private static ScalarSample NewSample() => new()
    {
        Description = "draft",
        OptionalLong = InitialLong,
        OptionalInt = 7,
        OptionalDouble = +0.0,
        SurrogateUnit = 'A'
    };

    private static void CheckOriginal(ScalarSample model)
    {
        Assert.Equal("draft", model.Description);
        Assert.Equal(InitialLong, model.OptionalLong);
        Assert.Equal(7, model.OptionalInt);
        Assert.Equal(0, BitConverter.DoubleToInt64Bits(Assert.IsType<double>(model.OptionalDouble)));
        Assert.Equal('A', model.SurrogateUnit);
        Assert.Null(model.OptionalFloat);
    }

    private static void CheckFinal(ScalarSample model)
    {
        Assert.Equal("ready", model.Description);
        Assert.Equal(long.MinValue, model.OptionalLong);
        Assert.Equal(7, model.OptionalInt);
        Assert.Equal(FinalProbe, model.OptionalDouble);
        Assert.Equal(float.Epsilon, model.OptionalFloat);
        Assert.Equal('\uD800', model.SurrogateUnit);
    }
}
