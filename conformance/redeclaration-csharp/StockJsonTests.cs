using System.Text.Json;
using Eu.Mihosoft.Vmftest.Complex.Vmf_text.Generated.Json;
using Vmf.Runtime;
using Xunit;
using JsonArrayModel = Eu.Mihosoft.Vmftest.Complex.Vmf_text.Generated.Json.Array;

namespace Vmf.CSharp.Conformance;

public sealed class StockJsonTests
{
    private static readonly JournalBinding Value = new("stock-json-value", 1, _ => "value");

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void OriginalValueBranchesKeepTheirStorageAndExistingWireRules(string format)
    {
        var obj = new Obj();
        var array = new JsonArrayModel();
        var objectValue = new ObjectValue { Value = obj };
        var arrayValue = new ArrayValue { Value = array };
        Assert.Same(obj, ((Val)objectValue).Value);
        Assert.Same(array, ((Val)arrayValue).Value);
        Assert.Same(obj.AsReadOnly(), ((Val)objectValue).AsReadOnly().Value);
        Assert.Same(array.AsReadOnly(), ((Val)arrayValue).AsReadOnly().Value);
        Assert.False(typeof(Val).GetProperty(nameof(Val.Value))!.CanWrite);
        Assert.Throws<JsonException>(() => ModelSnapshot.Write(objectValue));

        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_REDECLARATIONS");
        Exchange(new StringValue { Value = "text" }, "text", "text");
        Exchange(new NumberValue { Value = 5.0 }, "number", 5.0);
        Exchange(new BooleanValue { Value = true }, "boolean", true);
        Exchange(objectValue, "object", null);
        Exchange(arrayValue, "array", null);
        var baseline = new NullValue { Value = 7 };
        Exchange(baseline, "null", format == "xml" ? "7" : 7);

        var journal = JournalRecorder<NullValue>.RecordFrame(new NullValue { Value = 7 }, ModelSnapshot.Registry,
            Value, "start", 0, value => value.Value = "edited");
        var (writeJournal, readJournal) = JournalCodecs(format);
        var changes = readJournal(ExchangeText("journal", writeJournal(journal)));
        Assert.Equal(1, changes.WireVersion);
        var replayed = JournalReplayer.Replay(baseline, Value, "start", changes, ModelSnapshot.Registry);
        Assert.Equal("edited", replayed.Value);
        Assert.Equal(7, baseline.Value);

        void Exchange<T>(T model, string branch, object? expected) where T : Val
        {
            var (write, read) = Codecs<T>(format);
            Assert.Equal(expected, read(ExchangeText(branch, write(model))).Value);
        }

        string ExchangeText(string branch, string document)
        {
            if (directory is null) return document;
            File.WriteAllText(Path.Combine(directory, $"csharp-stock-json-{branch}.{format}"), document);
            return File.ReadAllText(Path.Combine(directory, $"java-stock-json-{branch}.{format}"));
        }
    }

    private static (Func<T, string> Write, Func<string, T> Read) Codecs<T>(string format) where T : Val => format switch
    {
        "xml" => (model => ModelSnapshot.WriteXmlWithoutReferences(model, global::Vmf.Runtime.ReferenceMode.Omit),
            ModelSnapshot.ReadXmlWithoutReferences<T>),
        "yaml" => (model => ModelSnapshot.WriteYamlWithoutReferences(model, global::Vmf.Runtime.ReferenceMode.Omit),
            ModelSnapshot.ReadYamlWithoutReferences<T>),
        _ => (model => ModelSnapshot.WriteWithoutReferences(model, global::Vmf.Runtime.ReferenceMode.Omit),
            ModelSnapshot.ReadWithoutReferences<T>),
    };

    private static (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) JournalCodecs(string format) =>
        format switch
        {
            "xml" => (JournalXml.Write, JournalXml.Read),
            "yaml" => (JournalYaml.Write, JournalYaml.Read),
            _ => (JournalJson.Write, JournalJson.Read),
        };
}
