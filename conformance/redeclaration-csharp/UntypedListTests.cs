using System.Numerics;
using System.Xml;
using Eu.Mihosoft.Vmf.Conformance.Redeclarations;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class UntypedListTests
{
    private static readonly JournalBinding Binding = new("untyped-list", 1, _ => "root");
    private static readonly object?[] JournalInitial = [(sbyte)2, (short)-3, 1L, -0.0f, null];
    private static readonly object?[] JournalResult = ['A', true, (short)-3, 1L, -0.0d];

    [Fact]
    public void OrdinaryStorageUsesAStableReadOnlyListAndCopiesBuilderSlots()
    {
        var list = Model(Values(nested: true));
        var view = list.AsReadOnly();
        Assert.Same(view.Items, view.Items);
        Assert.Null(view.Items[0]);
        list.Items.Add("tail");
        Assert.Equal("tail", view.Items[9]);
        list.Items.RemoveAt(9);

        var target = Model(["old"]);
        PayloadList.NewBuilder().AppendCollections(false).ApplyFrom(list).ApplyTo(target);
        Assert.Equal(list.Items.ToArray(), target.Items.ToArray());
        Assert.True(list.ContentEquals(target));
        Assert.Equal(list.ContentHashCode(), target.ContentHashCode());
        Assert.NotSame(list.Items, target.Items);
    }

    [Fact]
    public void UntypedSelectionComparesNestedValuesAndDistinguishesScalarBoxes()
    {
        var map = new Dictionary<string, object?> { ["members"] = new List<object?> { 1, -0.0d } };
        var nested = new List<object?> { "value", new List<object?> { false } };
        var source = Model([map, nested, -0.0d, 0d, 1, 1L]);
        var view = source.AsReadOnly().Items;
        var equalMap = new Dictionary<string, object?> { ["members"] = new List<object?> { 1, -0.0d } };
        var equalList = new List<object?> { "value", new List<object?> { false } };
        Assert.True(view.Contains(equalMap));
        Assert.Equal(1, view.IndexOf(equalList));
        Assert.Equal(2, view.IndexOf(-0.0d));
        Assert.Equal(3, view.IndexOf(0d));
        Assert.Equal(4, view.IndexOf(1));
        Assert.Equal(5, view.IndexOf(1L));
        Assert.False(view.Contains(1.0d));
        Assert.True(source.Items.Remove(equalMap));
        Assert.True(source.Items.Remove(equalList));
        Assert.Equal(4, view.Count);

        // An ordinary host list keeps its existing typed equality.
        var typed = new ScalarList<Dictionary<string, object?>>();
        typed.Add(map);
        Assert.Equal(-1, typed.IndexOf(equalMap));
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void SnapshotListFollowsJavaProfilesAndRoundTripsNestedContent(string format)
    {
        bool xml = format == "xml";
        var original = Model(Values(nested: !xml));
        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_REDECLARATIONS");
        foreach (bool references in new[] { false, true })
        {
            var (write, read) = SnapshotCodec(format, references);
            string wire = write(original);
            Verify(read(wire));
            if (directory is null) continue;
            string profile = references ? "references" : "values";
            string file = $"untyped-list-{profile}.{format}";
            Verify(read(File.ReadAllText(Path.Combine(directory, "java-" + file))));
            File.WriteAllText(Path.Combine(directory, "csharp-" + file), wire);
        }

        void Verify(PayloadList restored)
        {
            if (xml)
            {
                Assert.Equal(new object?[] { null, "1", "-0.0", "9007199254740993", "value", "false", "" }, restored.Items);
                return;
            }
            Assert.IsType<int>(restored.Items[1]);
            Assert.IsType<long>(restored.Items[3]);
            Assert.IsType<BigInteger>(restored.Items[7]);
            Assert.NotSame(original.Items[8], restored.Items[8]);
            Assert.True(original.ContentEquals(restored));
            Assert.Equal(original.ContentHashCode(), restored.ContentHashCode());
            restored.Items[2] = 0d;
            Assert.False(original.ContentEquals(restored));
        }
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void ExistingV1IndexedEditsRetainExactScalarTags(string format)
    {
        var journal = JournalRecorder<PayloadList>.RecordFrame(Model(JournalInitial), ModelSnapshot.Registry,
            Binding, "initial", 0, root =>
            {
                root.Items[0] = 'A';
                root.Items[3] = -0.0d;
                root.Items.Insert(1, true);
                root.Items.RemoveAt(5);
            });
        (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) codec = format switch
        {
            "json" => (JournalJson.Write, JournalJson.Read),
            "yaml" => (JournalYaml.Write, JournalYaml.Read),
            "xml" => (JournalXml.Write, JournalXml.Read),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
        var (write, read) = codec;
        string wire = write(journal);
        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_REDECLARATIONS");
        Verify(read(wire));
        if (directory is not null)
        {
            string filename = $"untyped-list.journal.{format}";
            Verify(read(File.ReadAllText(Path.Combine(directory, "java-" + filename))));
            File.WriteAllText(Path.Combine(directory, "csharp-" + filename), wire);
        }

        void Verify(ChangeJournal decoded)
        {
            Assert.Equal(1, decoded.WireVersion);
            Assert.Equal(JournalJson.Write(journal), JournalJson.Write(decoded));
            var checkpoint = Model(JournalInitial);
            var replay = JournalReplayer.Replay(checkpoint, Binding, "initial", decoded, ModelSnapshot.Registry);
            Assert.True(replay.ContentEquals(Model(JournalResult)));
            Assert.True(checkpoint.ContentEquals(Model(JournalInitial)));
            Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(Assert.IsType<double>(replay.Items[4])));
            if (format == "json")
            {
                var reboxed = ModelSnapshot.Read<PayloadList>(ModelSnapshot.Write(checkpoint));
                Assert.IsType<int>(reboxed.Items[0]);
                Assert.IsType<double>(reboxed.Items[3]);
                Assert.Throws<InvalidOperationException>(() =>
                    JournalReplayer.Replay(reboxed, Binding, "initial", decoded, ModelSnapshot.Registry));
            }
        }
    }

    [Fact]
    public void V1ReplaysFromIndependentlyDeserializedCanonicalJson()
    {
        object?[] initial = [1, 9007199254740993L, -0.0d, null];
        string snapshot = ModelSnapshot.Write(Model(initial));
        var working = ModelSnapshot.Read<PayloadList>(snapshot);
        var journal = JournalRecorder<PayloadList>.RecordFrame(working, ModelSnapshot.Registry,
            Binding, "canonical-json", 0, root =>
            {
                root.Items[0] = "after";
                root.Items[1] = 9007199254740994L;
                root.Items[2] = 0d;
                root.Items.RemoveAt(3);
            });
        string wire = JournalJson.Write(journal);
        Verify(snapshot, wire);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_REDECLARATIONS") is { } directory)
        {
            Verify(File.ReadAllText(Path.Combine(directory, "java-untyped-list-checkpoint.json")),
                File.ReadAllText(Path.Combine(directory, "java-untyped-list-checkpoint.journal.json")));
            File.WriteAllText(Path.Combine(directory, "csharp-untyped-list-checkpoint.json"), snapshot);
            File.WriteAllText(Path.Combine(directory, "csharp-untyped-list-checkpoint.journal.json"), wire);
        }

        void Verify(string startingDocument, string journalDocument)
        {
            var checkpoint = ModelSnapshot.Read<PayloadList>(startingDocument);
            var actual = JournalJson.Read(journalDocument);
            Assert.Equal(1, actual.WireVersion);
            Assert.Equal(JournalJson.Write(journal), JournalJson.Write(actual));
            var replay = JournalReplayer.Replay(checkpoint, Binding, "canonical-json", actual, ModelSnapshot.Registry);
            Assert.True(replay.ContentEquals(Model(["after", 9007199254740994L, 0d])));
            Assert.True(checkpoint.ContentEquals(Model(initial)));
        }
    }

    [Fact]
    public void StructuredListElementHasNoStockV1OrXmlScalarRepresentation()
    {
        var structured = new Dictionary<string, object?> { ["nested"] = true };
        Assert.Throws<XmlException>(() => ModelSnapshot.WriteXml(Model([structured])));
        var error = Assert.Throws<InvalidOperationException>(() => JournalRecorder<PayloadList>.RecordFrame(new PayloadList(),
            ModelSnapshot.Registry, Binding, "initial", 0, root => root.Items.Add(structured)));
        Assert.Contains("no journal scalar representation", Assert.IsType<ArgumentException>(error.InnerException).Message);
    }

    private static PayloadList Model(IEnumerable<object?> items) => PayloadList.NewBuilder().WithItems(items).Build();

    private static List<object?> Values(bool nested)
    {
        List<object?> values = [null, 1, -0.0d, 9007199254740993L, "value", false, ""];
        if (nested)
        {
            values.Add(BigInteger.Parse("9223372036854775809"));
            values.Add(new Dictionary<string, object?>
            {
                ["@vmf-id"] = "plain map data",
                ["@vmf-type"] = "plain map data",
                ["members"] = new List<object?> { "note", null, new Dictionary<string, object?> { ["valid"] = true },
                    new List<object?> { 4, -0.0d } },
            });
        }
        return values;
    }

    private static (Func<object, string>, Func<string, PayloadList>) SnapshotCodec(string format, bool references) =>
        (format, references) switch
        {
            ("json", false) => (ModelSnapshot.WriteWithoutReferences, ModelSnapshot.ReadWithoutReferences<PayloadList>),
            ("json", true) => (ModelSnapshot.Write, ModelSnapshot.Read<PayloadList>),
            ("yaml", false) => (ModelSnapshot.WriteYamlWithoutReferences, ModelSnapshot.ReadYamlWithoutReferences<PayloadList>),
            ("yaml", true) => (ModelSnapshot.WriteYaml, ModelSnapshot.ReadYaml<PayloadList>),
            ("xml", false) => (ModelSnapshot.WriteXmlWithoutReferences, ModelSnapshot.ReadXmlWithoutReferences<PayloadList>),
            ("xml", true) => (ModelSnapshot.WriteXml, ModelSnapshot.ReadXml<PayloadList>),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
}
