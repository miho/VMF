using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Appliedcalls;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class AbstractArgumentsTests
{
    private static readonly JournalBinding Binding = new("abstract-parameters", 1, value => value switch
    {
        AbstractArgumentProject => "project",
        PrimaryArgument => "primary",
        ISecondaryArgument => "secondary",
        ArgumentCallHost => "host",
        _ => throw new ArgumentException($"Unexpected journal model {value.GetType()}.", nameof(value))
    });

    [Fact]
    public void DelegatesAcceptTheNativePrimaryAndCompanionContracts()
    {
        var rename = typeof(ArgumentCallHost).GetMethod(nameof(ArgumentCallHost.Rename))!;
        Assert.Equal(typeof(PrimaryArgument), rename.GetParameters()[0].ParameterType);
        Assert.Equal(typeof(PrimaryArgument), rename.ReturnType);
        var relabel = typeof(ArgumentCallHost).GetMethod(nameof(ArgumentCallHost.Relabel))!;
        Assert.Equal(typeof(ISecondaryArgument), relabel.GetParameters()[0].ParameterType);
        Assert.Equal(typeof(ISecondaryArgument), relabel.ReturnType);

        Invoke(Specimen());
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void EditsThroughAbstractArgumentsReplayFromEitherRuntime(string format)
    {
        (Func<AbstractArgumentProject, string> Write, Func<string, AbstractArgumentProject> Read) snapshots = format switch
        {
            "json" => (ModelSnapshot.Write, ModelSnapshot.Read<AbstractArgumentProject>),
            "yaml" => (ModelSnapshot.WriteYaml, ModelSnapshot.ReadYaml<AbstractArgumentProject>),
            "xml" => (ModelSnapshot.WriteXml, ModelSnapshot.ReadXml<AbstractArgumentProject>),
            _ => throw new ArgumentOutOfRangeException(nameof(format))
        };
        (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) journals = format switch
        {
            "json" => (JournalJson.Write, JournalJson.Read),
            "yaml" => (JournalYaml.Write, JournalYaml.Read),
            "xml" => (JournalXml.Write, JournalXml.Read),
            _ => throw new ArgumentOutOfRangeException(nameof(format))
        };

        string snapshot = snapshots.Write(Specimen());
        var recorded = JournalRecorder<AbstractArgumentProject>.RecordFrame(
            Specimen(), ModelSnapshot.Registry, Binding, "initial", 0, Invoke);
        string log = journals.Write(recorded);
        VerifyReplay(snapshots.Read(snapshot), journals.Read(log), recorded);

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_APPLIED_CALLS");
        if (exchange is null)
            return;

        string javaSnapshot = File.ReadAllText(Path.Combine(exchange, $"java-abstract-arguments-checkpoint.{format}"));
        string javaJournal = File.ReadAllText(Path.Combine(exchange, $"java-abstract-arguments-journal.{format}"));
        VerifyReplay(snapshots.Read(javaSnapshot), journals.Read(javaJournal), recorded);
        if (format == "json")
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(snapshot), JsonNode.Parse(javaSnapshot)));

        File.WriteAllText(Path.Combine(exchange, $"csharp-abstract-arguments-checkpoint.{format}"), snapshot);
        File.WriteAllText(Path.Combine(exchange, $"csharp-abstract-arguments-journal.{format}"), log);
    }

    private static void VerifyReplay(AbstractArgumentProject checkpoint, ChangeJournal journal, ChangeJournal expected)
    {
        Assert.Equal("before", Assert.IsType<PrimaryArgumentLeaf>(checkpoint.Main).Name);
        Assert.Equal("unset", Assert.IsType<ComposedArgument>(checkpoint.Detail).Label);
        Assert.Equal(1, journal.WireVersion);
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(expected)), JsonNode.Parse(JournalJson.Write(journal))));

        var replayed = JournalReplayer.Replay(checkpoint, Binding, "initial", journal, ModelSnapshot.Registry);
        Assert.Equal("after", replayed.Main?.Name);
        Assert.Equal("tagged", replayed.Detail?.Label);
        Assert.Equal("before", checkpoint.Main?.Name);
        Assert.Equal("unset", checkpoint.Detail?.Label);
    }

    private static AbstractArgumentProject Specimen() => new()
    {
        Main = new PrimaryArgumentLeaf { Name = "before", Data = "data" },
        Detail = new ComposedArgument { Title = "header", Label = "unset" },
        Skill = new ArgumentCallHost()
    };

    private static void Invoke(AbstractArgumentProject project)
    {
        PrimaryArgument primary = Assert.IsType<PrimaryArgumentLeaf>(project.Main);
        ISecondaryArgument secondary = Assert.IsType<ComposedArgument>(project.Detail);
        var host = Assert.IsType<ArgumentCallHost>(project.Skill);
        Assert.Same(primary, host.Rename(primary, "after"));
        Assert.Same(secondary, host.Relabel(secondary, "tagged"));
        Assert.Equal("after", primary.Name);
        Assert.Equal("tagged", secondary.Label);
        Assert.Null(host.Rename(null, "unused"));
        Assert.Null(host.Relabel(null, "unused"));
    }
}
