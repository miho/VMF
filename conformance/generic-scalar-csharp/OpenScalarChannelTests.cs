using Eu.Mihosoft.Vmf.Conformance.Genericscalars;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class OpenScalarChannelTests
{
    private static readonly JournalBinding Binding = new("open-scalar-channel", 1, _ => "channel");

    [Fact]
    public void ClosedSpecializationsKeepTypedRolesAndLiveReadOnlyViews()
    {
        var measurement = Measurements();
        OpenScalarChannel<string?, double?, long> combined = measurement;
        KeyedChannel<string?> keys = combined;
        IPayloadChannel<double?> payloads = combined;
        IRevisionChannel<long> revisions = combined;

        keys.ChannelKey = "next";
        payloads.NamedPayloads["secondary"] = 3.5;
        revisions.Revision = 6L;
        var view = combined.AsReadOnly();
        Assert.Same(view, payloads.AsReadOnly());
        Assert.Same(view, revisions.AsReadOnly());
        Assert.Equal("next", view.ChannelKey);
        Assert.Equal(new string?[] { "previous", null }, view.PreviousKeys.ToArray());
        Assert.Equal(12.5, view.LatestPayload);
        Assert.Equal(3.5, view.NamedPayloads["secondary"]);
        Assert.Equal(6L, view.Revision);
        var copy = ModelSnapshot.Reflection.DeepCopy(measurement);
        Assert.NotSame(measurement.PreviousKeys, copy.PreviousKeys);
        Assert.NotSame(measurement.NamedPayloads, copy.NamedPayloads);
        Assert.Equal(3.5, copy.NamedPayloads["secondary"]);

        OpenScalarChannel<int?, string?, int> text = TextChannel.NewBuilder()
            .WithChannelKey(20).WithLatestPayload("text").WithRevision(3).Build();
        Assert.Equal(20, text.ChannelKey);
        Assert.Equal("text", text.LatestPayload);
        Assert.Equal(3, text.Revision);
        var application = Assert.Single(ModelSnapshot.TypeCatalog.Find(typeof(TextChannel))!.DirectSupertypes);
        Assert.Equal(new[] { ModelValueKind.Int32, ModelValueKind.String, ModelValueKind.Int32 },
            application.Arguments.Select(argument => argument.Kind).ToArray());
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void ExchangesOpenRoleSnapshotsAndV1Journals(string format)
    {
        var baseline = Measurements();
        var journal = JournalRecorder<MeasurementChannel>.RecordFrame(baseline, ModelSnapshot.Registry, Binding,
            "initial", 4, model =>
            {
                OpenScalarChannel<string?, double?, long> combined = model;
                combined.PreviousKeys[1] = "next";
                ((IPayloadChannel<double?>)combined).NamedPayloads["secondary"] = 3.5;
                ((IRevisionChannel<long>)combined).Revision = 6L;
            });
        var referenceCodec = SecondaryScalarTests.SnapshotCodec<MeasurementChannel>(format, references: true);
        var plainCodec = SecondaryScalarTests.SnapshotCodec<MeasurementChannel>(format, references: false);
        string plain = plainCodec.Write(Measurements());
        string checkpoint = referenceCodec.Write(Measurements());
        (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) storage = format switch
        {
            "json" => (JournalJson.Write, JournalJson.Read),
            "yaml" => (JournalYaml.Write, JournalYaml.Read),
            "xml" => (JournalXml.Write, JournalXml.Read),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
        string changes = storage.Write(journal);

        VerifyInitial(plainCodec.Read(plain));
        VerifyReplay(checkpoint, changes);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_GENERIC_SCALARS") is { } directory)
        {
            string FileFor(string author, string kind) => Path.Combine(directory, $"{author}-open-channel-{kind}.{format}");
            VerifyInitial(plainCodec.Read(File.ReadAllText(FileFor("java", "plain"))));
            VerifyReplay(File.ReadAllText(FileFor("java", "checkpoint")), File.ReadAllText(FileFor("java", "journal")));
            File.WriteAllText(FileFor("csharp", "plain"), plain);
            File.WriteAllText(FileFor("csharp", "checkpoint"), checkpoint);
            File.WriteAllText(FileFor("csharp", "journal"), changes);
        }

        void VerifyReplay(string saved, string edits)
        {
            var original = referenceCodec.Read(saved);
            var loaded = storage.Read(edits);
            Assert.Equal(1, loaded.WireVersion);
            var restored = JournalReplayer.Replay(original, Binding, "initial", loaded, ModelSnapshot.Registry);
            Assert.Equal(new string?[] { "previous", "next" }, restored.PreviousKeys.ToArray());
            Assert.Equal(3.5, restored.NamedPayloads["secondary"]);
            Assert.Equal(6L, restored.Revision);
            VerifyInitial(original);
        }
    }

    private static MeasurementChannel Measurements() =>
        MeasurementChannel.NewBuilder().WithChannelKey("current").WithPreviousKeys(["previous", null])
            .WithLatestPayload(12.5).WithRevision(5L).Build();

    private static void VerifyInitial(MeasurementChannel model)
    {
        Assert.Equal("current", model.ChannelKey);
        Assert.Equal(new string?[] { "previous", null }, model.PreviousKeys.ToArray());
        Assert.Equal(12.5, model.LatestPayload);
        Assert.Equal(5L, model.Revision);
        Assert.Empty(model.NamedPayloads);
    }
}
