using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Inheritedopposites;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class GetterRoleReciprocalTests
{
    private const string Checkpoint = "getter-role-0";

    private static readonly JournalBinding Binding = new("conformance.getter-reciprocal", 1, model => model switch
    {
        GetterRoleTeam { Name: { } id } => id,
        GetterRolePerson { Name: { } id } => id,
        GetterRoleBoard => "board",
        _ => throw new ArgumentException($"Unexpected model {model.GetType()}.", nameof(model))
    });

    [Fact]
    public void MembershipAndConcreteSetterShareOneGetterRoleInverse()
    {
        var first = new GetterRoleTeam();
        var second = new GetterRoleTeam();
        var person = new GetterRolePerson();
        GetterRoleMember member = person;
        IReadOnlyGetterRoleMember view = person.AsReadOnly();
        Assert.False(typeof(GetterRoleMember).GetProperty(nameof(GetterRoleMember.Team))!.SetMethod!.IsPublic);
        Assert.True(typeof(GetterRolePerson).GetProperty(nameof(GetterRolePerson.Team))!.SetMethod!.IsPublic);

        var scalarTrace = new List<string>();
        var projectedTrace = new List<string>();
        var teamTrace = new List<string>();
        var projectedTeamTrace = new List<string>();
        using var scalarSubscription = person.VmfChanges.AddLocalListener(change => scalarTrace.Add(change.PropertyName));
        using var projectedSubscription = view.VmfChanges.AddLocalListener(change => projectedTrace.Add(change.PropertyName));
        using var teamSubscription = first.VmfChanges.AddLocalListener(change => teamTrace.Add(change.PropertyName));
        using var projectedTeamSubscription = first.AsReadOnly().VmfChanges
            .AddLocalListener(change => projectedTeamTrace.Add(change.PropertyName));

        Assert.True(first.Members.Add(member));
        Assert.Same(first, member.Team);
        Assert.Same(first.AsReadOnly(), view.Team);
        Assert.True(first.Members.Contains(view));
        Assert.True(first.AsReadOnly().Members.Contains(member));
        Assert.Same(view, Assert.Single(first.AsReadOnly().Members));

        Assert.True(second.Members.Add(member));
        Assert.Empty(first.Members);
        Assert.Same(person, Assert.Single(second.Members));
        Assert.Same(second.AsReadOnly(), view.Team);

        person.Team = first;
        Assert.True(first.Members.Contains(member));
        Assert.Empty(second.Members);
        Assert.True(first.Members.Remove(view));
        Assert.Null(member.Team);
        Assert.Null(view.Team);

        Assert.Equal(["team", "team", "team", "team", "team"], scalarTrace);
        Assert.Equal(scalarTrace, projectedTrace);
        Assert.Equal(["members", "members", "members", "members"], teamTrace);
        Assert.Equal(teamTrace, projectedTeamTrace);
    }

    [Fact]
    public void ReferenceAwareSnapshotsReconstructEitherOmittedEnd()
    {
        string wire = ModelSnapshot.Write(CreateCheckpoint());
        var setOnly = JsonNode.Parse(wire)!;
        setOnly["people"]![0]!.AsObject().Remove("team");
        CheckBaseline(ModelSnapshot.Read<GetterRoleBoard>(setOnly.ToJsonString()));
        var scalarOnly = JsonNode.Parse(wire)!;
        scalarOnly["teams"]![0]!.AsObject().Remove("members");
        CheckBaseline(ModelSnapshot.Read<GetterRoleBoard>(scalarOnly.ToJsonString()));
    }

    [Theory]
    [InlineData(VmfDocumentFormat.Json)]
    [InlineData(VmfDocumentFormat.Xml)]
    [InlineData(VmfDocumentFormat.Yaml)]
    public void ReciprocalRoleUsesV1ReplayFromSeparateSnapshotIdentities(VmfDocumentFormat format)
    {
        (Func<GetterRoleBoard, string> writeSnapshot, Func<string, GetterRoleBoard> readSnapshot,
            Func<ChangeJournal, string> writeJournal, Func<string, ChangeJournal> readJournal) codecs = format switch
        {
            VmfDocumentFormat.Json => (ModelSnapshot.Write, ModelSnapshot.Read<GetterRoleBoard>,
                JournalJson.Write, JournalJson.Read),
            VmfDocumentFormat.Xml => (ModelSnapshot.WriteXml, ModelSnapshot.ReadXml<GetterRoleBoard>,
                JournalXml.Write, JournalXml.Read),
            VmfDocumentFormat.Yaml => (ModelSnapshot.WriteYaml, ModelSnapshot.ReadYaml<GetterRoleBoard>,
                JournalYaml.Write, JournalYaml.Read),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
        var (writeSnapshot, readSnapshot, writeJournal, readJournal) = codecs;
        var board = CreateCheckpoint();
        string snapshot = writeSnapshot(board);
        string journal = writeJournal(Record(board));
        Replay(readSnapshot(snapshot), readJournal(journal));
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_SECONDARY_RECIPROCAL") is { } directory)
        {
            string extension = format.ToString().ToLowerInvariant();
            File.WriteAllText(Path.Combine(directory, $"csharp-getter-role-checkpoint.{extension}"), snapshot);
            File.WriteAllText(Path.Combine(directory, $"csharp-getter-role-journal.{extension}"), journal);
            Replay(readSnapshot(File.ReadAllText(Path.Combine(directory, $"java-getter-role-checkpoint.{extension}"))),
                readJournal(File.ReadAllText(Path.Combine(directory, $"java-getter-role-journal.{extension}"))));
        }
    }

    private static GetterRoleBoard CreateCheckpoint()
    {
        var board = new GetterRoleBoard();
        var first = new GetterRoleTeam { Name = "first" };
        var second = new GetterRoleTeam { Name = "second" };
        var person = new GetterRolePerson { Name = "person" };
        board.Teams.AddRange([first, second]);
        board.People.Add(person);
        person.Team = first;
        return board;
    }

    private static ChangeJournal Record(GetterRoleBoard board)
    {
        var first = board.Teams[0];
        var second = board.Teams[1];
        var person = board.People[0];
        using var recorder = JournalRecorder<GetterRoleBoard>.Start(board, ModelSnapshot.Registry, Binding, Checkpoint, 0);
        Assert.True(second.Members.Add(person));
        Assert.Equal(1, Assert.IsType<JournalFrame>(recorder.PublishFrame()).Sequence);
        person.Team = first;
        Assert.Equal(2, Assert.IsType<JournalFrame>(recorder.PublishFrame()).Sequence);
        Assert.True(first.Members.Remove(person.AsReadOnly()));
        CheckDetached(board);
        return recorder.Finish();
    }

    private static void Replay(GetterRoleBoard baseline, ChangeJournal changes)
    {
        Assert.Equal(1, changes.WireVersion);
        Assert.Equal(3, changes.Transactions.Count);
        var replayed = JournalReplayer.Replay(baseline, Binding, Checkpoint, changes, ModelSnapshot.Registry);
        CheckBaseline(baseline);
        CheckDetached(replayed);
        Assert.NotSame(baseline.People[0], replayed.People[0]);
    }

    private static void CheckBaseline(GetterRoleBoard board)
    {
        var first = board.Teams[0];
        var person = board.People[0];
        Assert.Same(first, person.Team);
        Assert.Same(person, Assert.Single(first.Members));
        Assert.Empty(board.Teams[1].Members);
    }

    private static void CheckDetached(GetterRoleBoard board)
    {
        Assert.Null(board.People[0].Team);
        Assert.All(board.Teams, team => Assert.Empty(team.Members));
    }
}
