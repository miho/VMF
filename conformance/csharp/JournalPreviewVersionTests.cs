using System.Text.Json;
using Vmf.Runtime;
using Xunit;
using YamlDotNet.Core;

namespace Vmf.CSharp.Conformance;

public sealed class JournalPreviewVersionTests
{
    [Theory]
    [InlineData("json")]
    [InlineData("xml")]
    [InlineData("yaml")]
    public void OnlyVersionOneEnvelopesAreAccepted(string format)
    {
        var (_, read) = Serializer(format);
        Assert.Equal(23, read(Envelope(format, "1")).ModelVersion);
        var errorType = format == "yaml" ? typeof(YamlException) : typeof(JsonException);

        string?[] invalidVersions = [null, "null", "-1", "0", "2", "3", "4", "5", "6", "7", "2147483648", "1.0", "\"1\""];
        foreach (string? version in invalidVersions)
        {
            Assert.Throws(errorType, () => read(Envelope(format, version)));
        }

        string unknown = Envelope(format, "1").Replace("vmf-journal", "unknown-journal", StringComparison.Ordinal);
        Assert.Throws(errorType, () => read(unknown));
    }

    [Theory]
    [InlineData("json")]
    [InlineData("xml")]
    [InlineData("yaml")]
    public void MixedRecordShapesRoundTripInVersionOne(string format)
    {
        var none = JournalValue.FromScalar(null);
        var text = JournalValue.FromScalar("label");
        var number = JournalValue.FromScalar(7);
        var external = JournalValue.External("sample.codec", "encoded");
        var reference = JournalValue.Reference("source");
        var badge = JournalValue.ImmutableReference("badge");
        var textEntry = new JournalMapEntry(text, none);
        var typedEntry = new JournalMapEntry(number, text);
        var creation = new JournalCreation("badge", "Badge",
            new Dictionary<string, IReadOnlyList<JournalValue>> { ["data"] = [external] },
            mapProperties: new Dictionary<string, IReadOnlyList<JournalMapEntry>> { ["labels"] = [textEntry] },
            typedMapProperties: new Dictionary<string, IReadOnlyList<JournalMapEntry>> { ["ranks"] = [typedEntry] },
            setProperties: new Dictionary<string, IReadOnlyList<JournalValue>> { ["levels"] = [number] });
        var journal = new ChangeJournal("model", 23, "root", "start", 0,
            [new JournalFrame(1, [
                new JournalSet("root", "badge", none, badge, [creation]),
                new JournalSet("root", "data", none, external),
                new JournalListEdit(JournalListEditKind.Add, "root", "trail", [0], [], [badge]),
                new JournalMapEdit("root", "labels", typedKeys: false, [], [textEntry]),
                new JournalMapEdit("root", "ranks", typedKeys: true, [], [typedEntry]),
                new JournalMembershipEdit("root", "levels", references: false, [], [number]),
                new JournalMembershipEdit("root", "peers", references: true, [reference], [badge]),
            ])]);

        var (write, read) = Serializer(format);
        Assert.Equal(1, journal.WireVersion);
        var restored = read(write(journal));
        Assert.Equal(1, restored.WireVersion);
        Assert.Equal(23, restored.ModelVersion);
        Assert.Equal(JournalJson.Write(journal), JournalJson.Write(restored));
    }

    private static (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) Serializer(string format) => format switch
    {
        "json" => (JournalJson.Write, JournalJson.Read),
        "xml" => (JournalXml.Write, JournalXml.Read),
        "yaml" => (JournalYaml.Write, JournalYaml.Read),
        _ => throw new ArgumentOutOfRangeException(nameof(format)),
    };

    private static string Envelope(string format, string? version) => format switch
    {
        "json" => $$"""
            {"format":"vmf-journal",{{(version is null ? "" : $"\"version\":{version},")}}
             "model":{"id":"model","version":23},"root":"root","base":{"checkpoint":"start","sequence":0},"transactions":[]}
            """,
        "xml" => $"""
            <vmf-journal>{(version is null ? "" : $"<version>{version}</version>")}
              <model><id>model</id><version>23</version></model><root>root</root>
              <base><checkpoint>start</checkpoint><sequence>0</sequence></base><transactions/>
            </vmf-journal>
            """,
        "yaml" => $$"""
            format: vmf-journal
            {{(version is null ? "" : $"version: {version}")}}
            model: {id: model, version: 23}
            root: root
            base: {checkpoint: start, sequence: 0}
            transactions: []
            """,
        _ => throw new ArgumentOutOfRangeException(nameof(format)),
    };
}
