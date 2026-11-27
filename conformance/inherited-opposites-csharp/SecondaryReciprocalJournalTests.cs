using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Inheritedopposites;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class SecondaryReciprocalJournalTests
{
    private const string Model = "conformance.secondary-reciprocal";
    private const string Checkpoint = "dispatch-40";
    private const long BaseSequence = 40;
    private static readonly JournalBinding Binding = new(Model, 1,
        model => model is DispatchHeader { Name: { } id } ? id : "board");
    private static readonly JournalValue None = JournalValue.FromScalar(null);

    [Theory]
    [InlineData("json")]
    [InlineData("xml")]
    [InlineData("yaml")]
    public void ReplaysSecondaryReciprocalTransfersAndDisplacementInV1Order(string format)
    {
        (Func<DispatchBoard, string> Write, Func<string, DispatchBoard> Read) snapshots = format switch
        {
            "json" => (ModelSnapshot.Write, ModelSnapshot.Read<DispatchBoard>),
            "xml" => (ModelSnapshot.WriteXml, ModelSnapshot.ReadXml<DispatchBoard>),
            "yaml" => (ModelSnapshot.WriteYaml, ModelSnapshot.ReadYaml<DispatchBoard>),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
        (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) journals = format switch
        {
            "json" => (JournalJson.Write, JournalJson.Read),
            "xml" => (JournalXml.Write, JournalXml.Read),
            "yaml" => (JournalYaml.Write, JournalYaml.Read),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };

        string snapshot = snapshots.Write(CreateCheckpoint());
        string records = journals.Write(Record(snapshots.Read(snapshot)));
        Replay(snapshot, records);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_SECONDARY_RECIPROCAL") is { } directory)
        {
            string SnapshotPath(string author) => Path.Combine(directory, $"{author}-dispatch-checkpoint.{format}");
            string JournalPath(string author) => Path.Combine(directory, $"{author}-dispatch-journal.{format}");
            File.WriteAllText(SnapshotPath("csharp"), snapshot);
            File.WriteAllText(JournalPath("csharp"), records);
            Replay(File.ReadAllText(SnapshotPath("java")), File.ReadAllText(JournalPath("java")));
        }

        void Replay(string checkpoint, string wire)
        {
            var fresh = snapshots.Read(checkpoint);
            var journal = journals.Read(wire);
            Assert.Equal(1, journal.WireVersion);
            string normalized = JournalJson.Write(journal);
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(Expected())), JsonNode.Parse(normalized)), normalized);
            var replayed = JournalReplayer.Replay(fresh, Binding, Checkpoint, journal, ModelSnapshot.Registry);
            Assert.NotSame(fresh.Teams[0], replayed.Teams[0]);
            Assert.NotSame(fresh.People[0], replayed.People[0]);
            CheckFinal(replayed);
            var north = fresh.Teams[0];
            var south = fresh.Teams[1];
            var first = fresh.People[0];
            var second = fresh.People[1];
            Assert.Same(second, Assert.Single(north.Members));
            Assert.Same(north, second.Team);
            Assert.Same(first, Assert.Single(south.Members));
            Assert.Same(south, first.Team);
            Assert.Same(first, north.Lead);
            Assert.Same(north, first.LeadOf);
            Assert.Same(second, south.Lead);
            Assert.Same(south, second.LeadOf);
        }
    }

    private static DispatchBoard CreateCheckpoint()
    {
        IDispatchTeam north = new SpecializedDispatchTeam { Name = "north" };
        IDispatchTeam south = new SpecializedDispatchTeam { Name = "south" };
        IDispatchMember first = new SpecializedDispatchMember { Name = "first" };
        IDispatchMember second = new SpecializedDispatchMember { Name = "second" };
        var board = new DispatchBoard();
        board.Teams.AddRange([north, south]);
        board.People.AddRange([first, second]);
        north.Members.Add(second);
        south.Members.Add(first);
        north.Lead = first;
        south.Lead = second;
        return board;
    }

    private static ChangeJournal Record(DispatchBoard board)
    {
        var north = board.Teams[0];
        var south = board.Teams[1];
        var first = board.People[0];
        var second = board.People[1];
        using var recorder = JournalRecorder<DispatchBoard>.Start(board, ModelSnapshot.Registry, Binding, Checkpoint, BaseSequence);
        second.Team = south;
        Assert.Equal(41, Assert.IsType<JournalFrame>(recorder.PublishFrame()).Sequence);
        south.Lead = first;
        Assert.Equal(42, Assert.IsType<JournalFrame>(recorder.PublishFrame()).Sequence);
        Assert.True(north.Members.Add(first));
        CheckFinal(board);
        return recorder.Finish();
    }

    private static ChangeJournal Expected()
    {
        var south = JournalValue.Reference("south");
        var first = JournalValue.Reference("first");
        var second = JournalValue.Reference("second");
        return new ChangeJournal(Model, 1, "board", Checkpoint, BaseSequence,
        [
            new(41, [new JournalMembershipEdit("north", "members", true, [second], []),
                new JournalSet("second", "team", None, south)]),
            new(42, [new JournalSet("north", "lead", first, None), new JournalSet("south", "lead", second, first)]),
            new(43, [new JournalMembershipEdit("south", "members", true, [first], []),
                new JournalMembershipEdit("north", "members", true, [], [first])]),
        ]);
    }

    private static void CheckFinal(DispatchBoard graph)
    {
        var north = Assert.IsType<SpecializedDispatchTeam>(graph.Teams[0]);
        var south = Assert.IsType<SpecializedDispatchTeam>(graph.Teams[1]);
        var first = Assert.IsType<SpecializedDispatchMember>(graph.People[0]);
        var second = Assert.IsType<SpecializedDispatchMember>(graph.People[1]);
        Assert.Same(first, Assert.Single(north.Members));
        Assert.Same(north, first.Team);
        Assert.Same(second, Assert.Single(south.Members));
        Assert.Same(south, second.Team);
        Assert.Null(north.Lead);
        Assert.Same(first, south.Lead);
        Assert.Same(south, first.LeadOf);
        Assert.Null(second.LeadOf);
    }
}
