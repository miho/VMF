using System.Text.Json;
using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Redeclarations;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ValueChoiceTests
{
    private static readonly JournalBinding Binding = new("value-choices", 1,
        value => ModelSnapshot.Reflection.Reflect(value).TypeName);

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void ConcreteBranchesPreserveExistingSnapshotAndJournalSemantics(string format)
    {
        (Func<object, string> Write, Func<string, OptionDocument> Read) snapshots = format switch
        {
            "json" => (ModelSnapshot.Write, ModelSnapshot.Read<OptionDocument>),
            "yaml" => (ModelSnapshot.WriteYaml, ModelSnapshot.ReadYaml<OptionDocument>),
            "xml" => (ModelSnapshot.WriteXml, ModelSnapshot.ReadXml<OptionDocument>),
            _ => throw new ArgumentOutOfRangeException(nameof(format))
        };
        (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) journals = format switch
        {
            "json" => (JournalJson.Write, JournalJson.Read),
            "yaml" => (JournalYaml.Write, JournalYaml.Read),
            "xml" => (JournalXml.Write, JournalXml.Read),
            _ => throw new ArgumentOutOfRangeException(nameof(format))
        };

        var baseline = Specimen();
        var edited = Specimen();
        var journal = JournalRecorder<OptionDocument>.RecordFrame(edited, ModelSnapshot.Registry, Binding, "initial", 0, Edit);
        string snapshot = snapshots.Write(edited);
        string checkpoint = snapshots.Write(baseline);
        string log = journals.Write(journal);
        Verify(edited, xml: false);
        Verify(snapshots.Read(snapshot), format == "xml");
        Verify(JournalReplayer.Replay(snapshots.Read(checkpoint), Binding, "initial", journals.Read(log), ModelSnapshot.Registry), xml: false);
        Assert.Null(baseline.Options[3]?.Value);
        if (format == "json")
        {
            var values = JsonNode.Parse(snapshot)!["options"]!.AsArray();
            Assert.Equal("new", values[0]!["value"]!.GetValue<string>());
            Assert.True(values[2]!["value"]!.GetValue<bool>());
            Assert.False(values[5]!["value"]!.GetValue<bool>());
            Assert.NotEmpty(values[3]!["value"]!.GetValue<string>());
            Assert.NotEmpty(values[4]!["value"]!.GetValue<string>());
        }

        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_REDECLARATIONS");
        if (directory is null) return;

        string PathFor(string side, string kind) => Path.Combine(directory, $"{side}-value-choices-{kind}.{format}");
        Verify(snapshots.Read(File.ReadAllText(PathFor("java", "snapshot"))), format == "xml");
        var received = journals.Read(File.ReadAllText(PathFor("java", "journal")));
        Assert.Equal(1, received.WireVersion);
        Assert.Equal(JournalJson.Write(journal), JournalJson.Write(received));
        var original = snapshots.Read(File.ReadAllText(PathFor("java", "checkpoint")));
        Verify(JournalReplayer.Replay(original, Binding, "initial", received, ModelSnapshot.Registry), xml: false);
        Assert.Null(original.Options[3]?.Value);

        File.WriteAllText(PathFor("csharp", "checkpoint"), checkpoint);
        File.WriteAllText(PathFor("csharp", "snapshot"), snapshot);
        File.WriteAllText(PathFor("csharp", "journal"), log);
    }

    [Fact]
    public void UnownedModelAlternativeKeepsByValueOmissionAndReferenceValidation()
    {
        var value = new ObjectOption { Value = new OptionObject() };
        Assert.Same(value.Value, ((ValueChoice)value).Value);
        Assert.False(typeof(ValueChoice).GetProperty(nameof(ValueChoice.Value))!.CanWrite);
        Assert.Null(JsonNode.Parse(ModelSnapshot.WriteWithoutReferences(value, global::Vmf.Runtime.ReferenceMode.Omit))!["value"]);
        Assert.Throws<JsonException>(() => ModelSnapshot.Write(value));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void ImportedUntypedObjectsCompareByMapAndListContents(bool references)
    {
        const string left = """{"value":{"items":[1,null,-0.0,9007199254740993],"flag":true}}""";
        const string right = """{"value":{"flag":true,"items":[1,null,-0.0,9007199254740993]}}""";
        var reflection = ModelSnapshot.Reflection;
        NullOption Read(string json) => references ? ModelSnapshot.Read<NullOption>(json)
            : ModelSnapshot.ReadWithoutReferences<NullOption>(json);
        var first = Read(left);
        var second = Read(right);
        var firstView = first.AsReadOnly();
        var secondView = second.AsReadOnly();
        Assert.NotSame(first.Value, second.Value);
        Assert.True(reflection.ContentEquals(first, second));
        Assert.True(reflection.ContentEquals(second, first));
        Assert.True(reflection.ContentEquals(firstView, secondView));
        Assert.Equal(reflection.ContentHashCode(first), reflection.ContentHashCode(secondView));

        foreach (string different in new[] { right.Replace("-0.0", "0.0"), right.Replace("[1,", "[1.0,"),
                     right.Replace("1,null", "null,1"), right.Replace("9007199254740993", "9007199254740992") })
        {
            Assert.False(reflection.ContentEquals(firstView, Read(different)));
        }
    }

    [Fact]
    public void NonportableCyclicObjectValuesKeepTheirHostEquality()
    {
        var shared = new List<object?>();
        shared.Add(shared);
        var separate = new List<object?>();
        separate.Add(separate);
        var first = new NullOption { Value = shared };
        var second = new NullOption { Value = shared };
        var third = new NullOption { Value = separate };
        var reflection = ModelSnapshot.Reflection;
        Assert.True(reflection.ContentEquals(first, second));
        Assert.False(reflection.ContentEquals(first, third));
        Assert.Equal(reflection.ContentHashCode(first), reflection.ContentHashCode(second));
    }

    private static OptionDocument Specimen() => OptionDocument.NewBuilder()
        .WithObjectTarget(new OptionObject { Label = "object" })
        .WithArrayTarget(OptionArray.NewBuilder().WithLabels(["a", "b"]).Build())
        .WithOptions([
            new TextOption { Value = "old" }, new NumberOption { Value = 1.0 }, new FlagOption { Value = false },
            new ObjectOption(), new ArrayOption(), new NullOption { Value = "initial" },
        ]).Build();

    private static void Edit(OptionDocument root)
    {
        Assert.IsType<TextOption>(root.Options[0]).Value = "new";
        Assert.IsType<NumberOption>(root.Options[1]).Value = -0.0;
        Assert.IsType<FlagOption>(root.Options[2]).Value = true;
        Assert.IsType<ObjectOption>(root.Options[3]).Value = root.ObjectTarget;
        Assert.IsType<ArrayOption>(root.Options[4]).Value = root.ArrayTarget;
        Assert.IsType<NullOption>(root.Options[5]).Value = false;
    }

    private static void Verify(OptionDocument root, bool xml)
    {
        Assert.Equal("new", root.Options[0]?.Value);
        Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(Assert.IsType<double>(root.Options[1]?.Value)));
        Assert.Equal(true, root.Options[2]?.Value);
        Assert.Same(root.ObjectTarget, root.Options[3]?.Value);
        Assert.Same(root.ArrayTarget, root.Options[4]?.Value);
        Assert.Equal(xml ? (object)"false" : false, root.Options[5]?.Value);
        Type[] types = [typeof(string), typeof(double?), typeof(bool?), typeof(OptionObject), typeof(OptionArray), typeof(object)];
        for (int index = 0; index < types.Length; index++)
        {
            var field = Assert.Single(ModelSnapshot.Reflection.Reflect(root.Options[index]!).Properties, property => property.Name == "value");
            Assert.Equal(types[index], field.ValueType);
        }
        var view = root.AsReadOnly();
        Assert.Same(view.ObjectTarget, view.Options[3]?.Value);
        Assert.Same(view.ArrayTarget, view.Options[4]?.Value);
    }
}
