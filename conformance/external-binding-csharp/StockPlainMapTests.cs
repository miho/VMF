using System.Text.Json;
using System.Text.Json.Nodes;
using System.Xml.Linq;
using Eu.Mihosoft.Vmf.Conformance.Hosts;
using Eu.Mihosoft.Vmf.Jackson.Test.External_types02;
using Eu.Mihosoft.Vmf.Jackson.Test.Mapjournal;
using Vmf.Runtime;
using Xunit;
using ExternalSnapshot = Eu.Mihosoft.Vmf.Jackson.Test.External_types02.ModelSnapshot;
using LabelsSnapshot = Eu.Mihosoft.Vmf.Jackson.Test.Mapjournal.ModelSnapshot;

namespace Vmf.CSharp.Conformance;

public sealed class StockPlainMapTests
{
    private const string Checkpoint = "labels-without-plain-map";
    private static readonly JournalBinding Binding = new("stock.labels-map", 1, _ => "labels");

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void OrdinaryHostMapsUseTheirExistingSnapshotShapes(bool references)
    {
        var maps = new ExternalTypeModel02();
        var other = new ExternalTypeModel02();
        Assert.NotSame(maps.ObjectMap, other.ObjectMap);
        Assert.NotSame(maps.StringMap, other.StringMap);
        maps.ObjectMap!.Add("meta", new JsonObject { ["inner"] = "x", ["count"] = 7 });
        maps.ObjectMap.Add("num", 42);
        maps.ObjectMap.Add("flag", true);
        maps.ObjectMap.Add("tags", new JsonArray("a", "b", null));
        maps.ObjectMap.Add("single", new JsonArray("a"));
        maps.StringMap!.Add("zip", "01234");
        maps.StringMap.Add("missing", null);

        foreach (var (format, write, read) in MapFormats(references))
        {
            Exchange("stock-external-maps", references, format, write(maps), wire =>
            {
                bool xml = format == "xml";
                var restored = read(wire);
                var strings = restored.StringMap!;
                var entries = restored.ObjectMap!;
                Assert.Equal("01234", strings["zip"]!.GetValue<string>());
                Assert.True(strings.ContainsKey("missing"));
                Assert.Equal(xml ? "" : null, strings["missing"]?.GetValue<string>());
                Assert.Equal("x", entries["meta"]!["inner"]!.GetValue<string>());
                if (xml)
                {
                    Assert.Equal("external-type-model-02", XDocument.Parse(wire).Root!.Name.LocalName);
                    Assert.Equal("7", entries["meta"]!["count"]!.GetValue<string>());
                    Assert.Equal("42", entries["num"]!.GetValue<string>());
                    Assert.Equal("true", entries["flag"]!.GetValue<string>());
                    Assert.Equal("a", entries["single"]!.GetValue<string>());
                    Assert.Equal(["a", "b", ""], entries["tags"]!.AsArray().Select(member => member!.GetValue<string>()));
                }
                else
                {
                    Assert.True(maps.ContentEquals(restored));
                    Assert.Equal(7, entries["meta"]!["count"]!.GetValue<int>());
                    Assert.Equal(42, entries["num"]!.GetValue<int>());
                    Assert.True(entries["flag"]!.GetValue<bool>());
                    Assert.Equal("a", Assert.Single(entries["single"]!.AsArray())!.GetValue<string>());
                    Assert.Null(entries["tags"]![2]);
                }
            });
        }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void OrdinaryMapIsLiveWithoutObservingEntriesAndClearedPropertyIsOmitted(bool references)
    {
        var model = new Labels();
        Assert.NotSame(model.External, new Labels().External);
        var changes = new List<string>();
        using var listener = model.VmfChanges.AddListener(change => changes.Add(change.PropertyName));
        model.External!.Add("zip", "01234");
        model.External.Add("missing", null);
        Assert.Same(model.External, model.AsReadOnly().External);
        Assert.Empty(changes);
        model.Names.Put("managed-null", null);
        Assert.Equal(["names"], changes);
        model.External = new JavaMap { ["zip"] = "01234", ["missing"] = null };
        Assert.Equal(["names"], changes);

        foreach (var (format, write, read) in LabelFormats(references))
        {
            Exchange("stock-plain-managed-maps", references, format, write(model), wire =>
            {
                var copy = read(wire);
                Assert.Equal("01234", copy.External!["zip"]!.GetValue<string>());
                Assert.True(copy.External.ContainsKey("missing"));
                Assert.Equal("label", copy.Names["default"]);
                Assert.True(copy.Names.ContainsKey("managed-null"));
                Assert.Null(copy.Names["managed-null"]);
                if (format == "xml")
                {
                    var root = XDocument.Parse(wire).Root!;
                    Assert.Equal("01234", root.Element("external")!.Element("zip")!.Value);
                    Assert.NotEmpty(root.Element("names")!.Elements("entry"));
                }
            });
            Exchange("stock-cleared-plain-map", references, format, write(Baseline()), wire =>
            {
                Assert.DoesNotContain(format == "xml" ? "<external>" : "external", wire, StringComparison.Ordinal);
                Assert.Empty(read(wire).External!);
            });
        }
    }

    [Fact]
    public void JournalRejectsRawMapStateAndChangesButPreservesClearedLiveCheckpoints()
    {
        var unsupported = new Labels();
        Assert.Throws<ArgumentException>(() =>
            JournalRecorder<Labels>.Start(unsupported, LabelsSnapshot.Registry, Binding, Checkpoint, 10));
        Assert.False(unsupported.VmfChanges.IsRecording);

        var replacing = Baseline();
        using (var recorder = JournalRecorder<Labels>.Start(replacing, LabelsSnapshot.Registry, Binding, Checkpoint, 10))
        {
            var changes = new List<ModelChange>();
            using var listener = replacing.VmfChanges.AddPropertyListener("external", changes.Add);
            replacing.External = new JavaMap { ["key"] = "value" };
            Assert.Single(changes);
            Assert.Throws<InvalidOperationException>(() => recorder.Finish());
        }

        var journal = JournalRecorder<Labels>.RecordFrame(Baseline(), LabelsSnapshot.Registry, Binding,
            Checkpoint, 10, root => root.Names.Put("added", "managed"));
        foreach (var (format, write, read) in new (string, Func<ChangeJournal, string>, Func<string, ChangeJournal>)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read)
        })
        {
            string output = write(journal);
            VerifyReplay(read(output));
            string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE");
            if (exchange is null) continue;
            VerifyReplay(read(File.ReadAllText(Path.Combine(exchange, $"java-plain-map-boundary-journal.{format}"))));
            File.WriteAllText(Path.Combine(exchange, $"csharp-plain-map-boundary-journal.{format}"), output);
        }
    }

    private static Labels Baseline() => new() { External = null };

    private static void VerifyReplay(ChangeJournal journal)
    {
        Assert.Equal(1, journal.WireVersion);
        var edit = Assert.IsType<JournalMapEdit>(Assert.Single(Assert.Single(journal.Transactions).Operations));
        Assert.Equal("names", edit.Property);
        var baseModel = Baseline();
        var replay = JournalReplayer.Replay(baseModel, Binding, Checkpoint, journal, LabelsSnapshot.Registry);
        Assert.Null(replay.External);
        Assert.Equal("managed", replay.Names["added"]);
        Assert.Equal("label", replay.Names["default"]);
        Assert.False(baseModel.Names.ContainsKey("added"));
    }

    private static (string, Func<ExternalTypeModel02, string>, Func<string, ExternalTypeModel02>)[] MapFormats(bool references) =>
        references ? [
            ("json", ExternalSnapshot.Write, ExternalSnapshot.Read<ExternalTypeModel02>),
            ("xml", value => ExternalSnapshot.WriteXml(value, "external-type-model-02"), ExternalSnapshot.ReadXml<ExternalTypeModel02>),
            ("yaml", ExternalSnapshot.WriteYaml, ExternalSnapshot.ReadYaml<ExternalTypeModel02>)
        ] : [
            ("json", ExternalSnapshot.WriteWithoutReferences, ExternalSnapshot.ReadWithoutReferences<ExternalTypeModel02>),
            ("xml", value => ExternalSnapshot.WriteXmlWithoutReferences(value, "external-type-model-02"),
                ExternalSnapshot.ReadXmlWithoutReferences<ExternalTypeModel02>),
            ("yaml", ExternalSnapshot.WriteYamlWithoutReferences, ExternalSnapshot.ReadYamlWithoutReferences<ExternalTypeModel02>)
        ];

    private static (string, Func<Labels, string>, Func<string, Labels>)[] LabelFormats(bool references) =>
        references ? [
            ("json", LabelsSnapshot.Write, LabelsSnapshot.Read<Labels>),
            ("xml", LabelsSnapshot.WriteXml, LabelsSnapshot.ReadXml<Labels>),
            ("yaml", LabelsSnapshot.WriteYaml, LabelsSnapshot.ReadYaml<Labels>)
        ] : [
            ("json", LabelsSnapshot.WriteWithoutReferences, LabelsSnapshot.ReadWithoutReferences<Labels>),
            ("xml", LabelsSnapshot.WriteXmlWithoutReferences, LabelsSnapshot.ReadXmlWithoutReferences<Labels>),
            ("yaml", LabelsSnapshot.WriteYamlWithoutReferences, LabelsSnapshot.ReadYamlWithoutReferences<Labels>)
        ];

    private static void Exchange(string scenario, bool references, string format, string output, Action<string> verify)
    {
        verify(output);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE");
        if (exchange is null) return;
        string suffix = $"{scenario}-{references.ToString().ToLowerInvariant()}.{format}";
        verify(File.ReadAllText(Path.Combine(exchange, $"java-{suffix}")));
        File.WriteAllText(Path.Combine(exchange, $"csharp-{suffix}"), output);
    }
}
