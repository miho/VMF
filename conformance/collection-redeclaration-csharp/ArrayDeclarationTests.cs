using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Collectionredeclarations;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ArrayDeclarationTests
{
    private static readonly JournalBinding Binding = new("mini-clang-array-shapes", 1, _ => "root");
    private static readonly string?[] Sizes = ["4", null, "4"];

    [Fact]
    public void BothParentOrdersShareOneMutableListAndOneReadOnlyView()
    {
        AssertSharedStorage(new ArrayDeclStatement());
        AssertSharedStorage(new Parameter());
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void ScalarListSnapshotsAndV1JournalsTravelInBothDirections(string format)
    {
        Exchange<ArrayDeclStatement>("array", format);
        Exchange<Parameter>("parameter", format);
    }

    private static void AssertSharedStorage(IDeclStatement model)
    {
        var other = Assert.IsAssignableFrom<IWithArraySizes>(model);
        var view = model.AsReadOnly();
        var otherView = other.AsReadOnly();
        Assert.Same(model.ArraySizes, other.ArraySizes);
        Assert.Same(view, otherView);
        Assert.Same(view.ArraySizes, otherView.ArraySizes);
        Assert.IsNotType<ScalarList<string?>>(view.ArraySizes);
        model.ArraySizes.AddRange(Sizes);
        Assert.Equal(Sizes, view.ArraySizes);

        var reflected = Assert.Single(ModelSnapshot.Reflection.Reflect(model).Properties, p => p.Name == "arraySizes");
        Assert.True(reflected.IsOrdered);
        Assert.False(reflected.IsUnique);
        Assert.Equal(0, reflected.LowerBound);
        Assert.Equal(-1, reflected.UpperBound);
        Assert.Same(model.ArraySizes, reflected.Get());
        Assert.Same(view.ArraySizes, ModelSnapshot.Reflection.Reflect(view).FindProperty("arraySizes")?.Get());
    }

    private static void Exchange<T>(string name, string format) where T : class, IDeclStatement, new()
    {
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

        var specimen = new T();
        var recorded = JournalRecorder<T>.RecordFrame(specimen, ModelSnapshot.Registry, Binding, "initial", 0,
            owner => owner.ArraySizes.AddRange(Sizes));
        string snapshot = snapshots.Write(specimen);
        string checkpoint = snapshots.Write(new T());
        string log = journals.Write(recorded);
        Assert.Equal(Sizes, snapshots.Read(snapshot).ArraySizes);
        AssertReplay(snapshots.Read(checkpoint), journals.Read(log), recorded);

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_COLLECTION_REDECLARATIONS");
        if (exchange is null)
            return;

        string FileName(string prefix, string kind) => Path.Combine(exchange, $"{prefix}-array-declarations-{name}-{kind}.{format}");
        string javaSnapshot = File.ReadAllText(FileName("java", "snapshot"));
        Assert.Equal(Sizes, snapshots.Read(javaSnapshot).ArraySizes);
        AssertReplay(snapshots.Read(File.ReadAllText(FileName("java", "checkpoint"))),
            journals.Read(File.ReadAllText(FileName("java", "journal"))), recorded);
        if (format == "json")
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(snapshot), JsonNode.Parse(javaSnapshot)));

        File.WriteAllText(FileName("csharp", "snapshot"), snapshot);
        File.WriteAllText(FileName("csharp", "checkpoint"), checkpoint);
        File.WriteAllText(FileName("csharp", "journal"), log);
    }

    private static void AssertReplay<T>(T checkpoint, ChangeJournal journal, ChangeJournal expected)
        where T : class, IDeclStatement
    {
        Assert.Equal(1, journal.WireVersion);
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(expected)), JsonNode.Parse(JournalJson.Write(journal))));
        var replayed = JournalReplayer.Replay(checkpoint, Binding, "initial", journal, ModelSnapshot.Registry);
        Assert.Empty(checkpoint.ArraySizes);
        Assert.Equal(Sizes, replayed.ArraySizes);
    }
}
