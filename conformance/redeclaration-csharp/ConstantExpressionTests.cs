using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Redeclarations;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ConstantExpressionTests
{
    private static readonly JournalBinding Binding = new("constant-expressions", 1, _ => "root");

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void PassiveGetterPreservesTheConcreteScalarInExistingWires(string format)
    {
        Verify<IntegerExpression>(format, "int", typeof(int?), 7, expression => expression.Value = 7);
        Verify<FloatingExpression>(format, "double", typeof(double?), -0.0, expression => expression.Value = -0.0);
        Verify<BooleanExpression>(format, "bool", typeof(bool?), true, expression => expression.Value = true);
        Verify<StringExpression>(format, "string", typeof(string), "literal", expression => expression.Value = "literal");
    }

    private static void Verify<T>(string format, string name, Type slotType, object expected, Action<T> edit)
        where T : Expression, IReadOnlyConstExpression, new()
    {
        Assert.False(typeof(IReadOnlyConstExpression).GetProperty("Value")!.CanWrite);
        Assert.False(typeof(ConstExpression).GetProperty("Value")!.SetMethod!.IsPublic);

        var root = new T();
        IReadOnlyConstExpression trait = root;
        var view = Assert.IsAssignableFrom<IReadOnlyConstExpression>(root.AsReadOnly());
        var reflected = Assert.Single(ModelSnapshot.Reflection.Reflect(root).Properties, p => p.Name == "value");
        Assert.Equal(slotType, reflected.ValueType);
        Assert.Null(reflected.Get());
        Assert.Null(trait.Value);
        Assert.Null(view.Value);

        var recorded = JournalRecorder<T>.RecordFrame(root, ModelSnapshot.Registry, Binding, "initial", 0, edit);
        AssertValue(expected, reflected.Get());
        AssertValue(expected, trait.Value);
        AssertValue(expected, view.Value);
        AssertValue(expected, ModelSnapshot.Reflection.Reflect(view).FindProperty("value")?.Get());

        (Func<object, string> Write, Func<string, T> Read) snapshots = format switch
        {
            "json" => (ModelSnapshot.Write, ModelSnapshot.Read<T>),
            "yaml" => (ModelSnapshot.WriteYaml, ModelSnapshot.ReadYaml<T>),
            "xml" => (ModelSnapshot.WriteXml, ModelSnapshot.ReadXml<T>),
            _ => throw new ArgumentOutOfRangeException(nameof(format))
        };
        (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) journals = format switch
        {
            "json" => (JournalJson.Write, JournalJson.Read),
            "yaml" => (JournalYaml.Write, JournalYaml.Read),
            "xml" => (JournalXml.Write, JournalXml.Read),
            _ => throw new ArgumentOutOfRangeException(nameof(format))
        };
        string snapshot = snapshots.Write(root);
        string checkpoint = snapshots.Write(new T());
        string log = journals.Write(recorded);
        AssertValue(expected, snapshots.Read(snapshot).Value);
        AssertReplay(snapshots.Read(checkpoint), journals.Read(log), recorded, expected);

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_REDECLARATIONS");
        if (exchange is null)
            return;

        string FileName(string prefix, string kind) => Path.Combine(exchange, $"{prefix}-constant-expression-{name}-{kind}.{format}");
        string javaSnapshot = File.ReadAllText(FileName("java", "snapshot"));
        AssertValue(expected, snapshots.Read(javaSnapshot).Value);
        AssertReplay(snapshots.Read(File.ReadAllText(FileName("java", "checkpoint"))),
            journals.Read(File.ReadAllText(FileName("java", "journal"))), recorded, expected);
        if (format == "json")
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(snapshot), JsonNode.Parse(javaSnapshot)));

        File.WriteAllText(FileName("csharp", "snapshot"), snapshot);
        File.WriteAllText(FileName("csharp", "checkpoint"), checkpoint);
        File.WriteAllText(FileName("csharp", "journal"), log);
    }

    private static void AssertReplay<T>(T checkpoint, ChangeJournal journal, ChangeJournal expectedJournal, object expected)
        where T : Expression, IReadOnlyConstExpression
    {
        Assert.Equal(1, journal.WireVersion);
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(expectedJournal)), JsonNode.Parse(JournalJson.Write(journal))));
        var replayed = JournalReplayer.Replay(checkpoint, Binding, "initial", journal, ModelSnapshot.Registry);
        Assert.Null(checkpoint.Value);
        AssertValue(expected, replayed.Value);
    }

    private static void AssertValue(object expected, object? actual)
    {
        if (expected is double numeric)
            Assert.Equal(BitConverter.DoubleToInt64Bits(numeric), BitConverter.DoubleToInt64Bits(Assert.IsType<double>(actual)));
        else
            Assert.Equal(expected, actual);
    }
}
