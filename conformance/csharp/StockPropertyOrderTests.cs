using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmftest.Propertyorder;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class StockPropertyOrderTests
{
    private const string CheckpointId = "initial";
    private static readonly JournalBinding Binding = new("stock-property-order", 1, _ => "reading");

    [Fact]
    public void AbstractAndConcreteGettersExposeTheSameScalarAndChanges()
    {
        var reading = new Inherited { Value = 7 };
        BaseClass broad = reading;
        IReadOnlyBaseClass broadView = broad.AsReadOnly();
        IReadOnlyInherited typedView = reading.AsReadOnly();
        Assert.Same(broadView, typedView);
        Assert.Equal(7, Assert.IsType<int>(broad.Value));
        Assert.Equal(7, typedView.Value);

        var events = new List<ModelChange>();
        using var subscription = reading.VmfChanges.AddPropertyListener("value", events.Add);
        reading.Value = 14;
        var edit = Assert.Single(events);
        Assert.Equal(7, Assert.IsType<int>(edit.OldValue));
        Assert.Equal(14, Assert.IsType<int>(edit.NewValue));
        Assert.Equal(14, Assert.IsType<int>(broadView.Value));
        Assert.Equal(14, typedView.Value);
        var peer = new Inherited();
        edit.Apply(peer);
        Assert.Equal(14, peer.Value);

        reading.Value = null;
        Assert.Null(broad.Value);
        Assert.Null(broadView.Value);
        Assert.Null(typedView.Value);
        Assert.Equal(2, events.Count);

        var field = Assert.Single(ModelSnapshot.Reflection.Reflect(reading).Properties);
        Assert.Equal("value", field.Name);
        Assert.Equal(typeof(int?), field.ValueType);
        Assert.Equal(["z", "b", "d", "x"], ModelSnapshot.Reflection.Reflect(new CustomOrder())
            .Properties.Select(property => property.Name).ToArray());
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void StockNarrowedValueUsesScalarSnapshotsAndV1Journals(string format)
    {
        var baseline = Inherited.NewBuilder().WithValue(7).Build();
        foreach (bool references in new[] { false, true })
        {
            var codec = SnapshotCodec(format, references);
            Assert.Equal(7, codec.Read(codec.Write(baseline)).Value);
        }

        var recorded = JournalRecorder<Inherited>.RecordFrame(new Inherited { Value = 7 },
            ModelSnapshot.Registry, Binding, CheckpointId, 0, reading =>
            {
                reading.Value = 14;
                reading.Value = null;
                reading.Value = -24;
            });
        (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) storage = format switch
        {
            "json" => (JournalJson.Write, JournalJson.Read),
            "yaml" => (JournalYaml.Write, JournalYaml.Read),
            "xml" => (JournalXml.Write, JournalXml.Read),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
        var edits = storage.Read(storage.Write(recorded));
        Assert.Equal(1, edits.WireVersion);
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(recorded)), JsonNode.Parse(JournalJson.Write(edits))));

        var snapshot = SnapshotCodec(format, references: true);
        var checkpoint = snapshot.Read(snapshot.Write(baseline));
        var replayed = JournalReplayer.Replay(checkpoint, Binding, CheckpointId, edits, ModelSnapshot.Registry);
        Assert.Equal(7, checkpoint.Value);
        Assert.Equal(-24, replayed.Value);
    }

    private static (Func<Inherited, string> Write, Func<string, Inherited> Read) SnapshotCodec(string format, bool references) =>
        (format, references) switch
        {
            ("json", false) => (ModelSnapshot.WritePlain<Inherited>, ModelSnapshot.ReadPlain<Inherited>),
            ("json", true) => (ModelSnapshot.Write, ModelSnapshot.Read<Inherited>),
            ("yaml", false) => (ModelSnapshot.WritePlainYaml<Inherited>, ModelSnapshot.ReadPlainYaml<Inherited>),
            ("yaml", true) => (ModelSnapshot.WriteYaml, ModelSnapshot.ReadYaml<Inherited>),
            ("xml", false) => (ModelSnapshot.WritePlainXml<Inherited>, ModelSnapshot.ReadPlainXml<Inherited>),
            ("xml", true) => (ModelSnapshot.WriteXml, ModelSnapshot.ReadXml<Inherited>),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
}
