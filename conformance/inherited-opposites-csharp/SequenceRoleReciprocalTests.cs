using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Inheritedopposites;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class SequenceRoleReciprocalTests
{
    private const string Checkpoint = "sequence-roles-0";

    private static readonly JournalBinding Binding = new("conformance.sequence-role-reciprocal", 1, model => model switch
    {
        OrderedRoleTeam { Name: { } id } => id,
        OrderedRolePerson { Name: { } id } => id,
        ListRoleTeam { Name: { } id } => id,
        ListRolePerson { Name: { } id } => id,
        SequenceRoleBoard => "board",
        _ => throw new ArgumentException($"Unexpected model {model.GetType()}.", nameof(model))
    });

    [Fact]
    public void OrderedSetsKeepIdentityAndRejectDuplicateInsertions()
    {
        var board = CreateCheckpoint();
        var team = board.OrderedTeams[0];
        var first = board.OrderedPeople[0];
        var second = board.OrderedPeople[1];
        Assert.False(team.Members.Add(first));
        Assert.Throws<ArgumentException>(() => team.Members.Insert(0, second));
        team.Members.Move(1, 0);
        Assert.Same(second, team.Members[0]);
        Assert.Same(first, team.Members[1]);
        Assert.Same(team, first.Team);
        Assert.Equal(1, team.Members.IndexOf(first.AsReadOnly()));
        Assert.Same(first.AsReadOnly(), team.AsReadOnly().Members[1]);
    }

    [Fact]
    public void OrdinaryListWithSingularOppositeRetainsLastDuplicateSlotAndNulls()
    {
        var board = CreateCheckpoint();
        var team = board.ListTeams[0];
        var first = board.ListPeople[0];
        var second = board.ListPeople[1];
        Assert.True(team.Members.Add(first));
        CheckPositions();
        team.Members.Insert(0, first);
        CheckPositions();
        Assert.Same(first.AsReadOnly(), team.AsReadOnly().Members[2]);
        Assert.True(team.Members.Remove(first.AsReadOnly()));
        Assert.Null(first.Team);

        void CheckPositions()
        {
            Assert.Collection(team.Members, Assert.Null, item => Assert.Same(second, item), item => Assert.Same(first, item));
            Assert.Same(team, first.Team);
        }
    }

    [Fact]
    public void SnapshotCollectionClaimsRestoreMissingSingularEndsAtOriginalPositions()
    {
        var json = JsonNode.Parse(ModelSnapshot.Write(CreateCheckpoint()))!;
        Assert.True(json["orderedPeople"]![0]!.AsObject().Remove("team"));
        Assert.True(json["listPeople"]![1]!.AsObject().Remove("team"));
        CheckBaseline(ModelSnapshot.Read<SequenceRoleBoard>(json.ToJsonString()));
    }

    [Fact]
    public void PositionalReciprocalRoleEditsReplayFromFreshV1Checkpoint()
    {
        var board = CreateCheckpoint();
        string snapshot = ModelSnapshot.Write(board);
        string journal = JournalJson.Write(Record(board));
        Replay(snapshot, journal);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_SECONDARY_RECIPROCAL") is { } directory)
        {
            File.WriteAllText(Path.Combine(directory, "csharp-sequence-role-checkpoint.json"), snapshot);
            File.WriteAllText(Path.Combine(directory, "csharp-sequence-role-journal.json"), journal);
            Replay(File.ReadAllText(Path.Combine(directory, "java-sequence-role-checkpoint.json")),
                File.ReadAllText(Path.Combine(directory, "java-sequence-role-journal.json")));
        }
    }

    [Theory]
    [InlineData("append")]
    [InlineData("earlier")]
    [InlineData("batch")]
    public void OrdinaryDuplicateReinsertionsKeepTheirV1ListOperation(string scenario)
    {
        string snapshot = ModelSnapshot.Write(CreateCheckpoint());
        var edited = ModelSnapshot.Read<SequenceRoleBoard>(snapshot);
        var members = edited.ListTeams[0].Members;
        using var recorder = JournalRecorder<SequenceRoleBoard>.Start(edited, ModelSnapshot.Registry, Binding, Checkpoint, 0);
        switch (scenario)
        {
            case "append": members.Add(edited.ListPeople[0]); break;
            case "earlier": members.Insert(0, edited.ListPeople[1]); break;
            case "batch": members.InsertRange(1, [edited.ListPeople[0], edited.ListPeople[1], null]); break;
            default: throw new ArgumentException("Unexpected reinsertion scenario.", nameof(scenario));
        }
        CheckReinsertion(edited, scenario);
        string wire = JournalJson.Write(recorder.Finish());
        ReplayReinsertion(snapshot, wire);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_SECONDARY_RECIPROCAL") is { } directory)
        {
            File.WriteAllText(Path.Combine(directory, $"csharp-sequence-role-reinsert-{scenario}.json"), wire);
            ReplayReinsertion(File.ReadAllText(Path.Combine(directory, "java-sequence-role-checkpoint.json")),
                File.ReadAllText(Path.Combine(directory, $"java-sequence-role-reinsert-{scenario}.json")));
        }

        void ReplayReinsertion(string checkpoint, string journal)
        {
            var baseline = ModelSnapshot.Read<SequenceRoleBoard>(checkpoint);
            var changes = JournalJson.Read(journal);
            Assert.Equal(1, changes.WireVersion);
            var operation = Assert.IsType<JournalListEdit>(Assert.Single(Assert.Single(changes.Transactions).Operations));
            Assert.Equal(JournalListEditKind.Add, operation.Kind);
            int[] indices = scenario == "batch" ? [1, 2, 3] : [scenario == "append" ? 3 : 0];
            Assert.Equal(indices, operation.Indices);
            CheckReinsertion(JournalReplayer.Replay(baseline, Binding, Checkpoint, changes, ModelSnapshot.Registry), scenario);
            CheckBaseline(baseline);
        }
    }

    [Fact]
    public void ReplayRejectsInvalidPositionsAndOldValues()
    {
        var baseline = CreateCheckpoint();
        var value = JournalValue.Reference("l-a");
        var operations = new[]
        {
            new JournalListEdit(JournalListEditKind.Add, "l-first", "members", [4], [], [value]),
            new JournalListEdit(JournalListEditKind.Remove, "l-first", "members", [1], [value], []),
            new JournalListEdit(JournalListEditKind.Set, "l-first", "members", [0, 2], [value, value], [value, value]),
        };
        foreach (var operation in operations)
        {
            var invalid = new ChangeJournal(Binding.Model, 1, "board", Checkpoint, 0, [new(1, [operation])]);
            Assert.Throws<InvalidOperationException>(() =>
                JournalReplayer.Replay(baseline, Binding, Checkpoint, invalid, ModelSnapshot.Registry));
        }
        CheckBaseline(baseline);
    }

    private static void CheckReinsertion(SequenceRoleBoard board, string scenario)
    {
        var team = board.ListTeams[0];
        var a = board.ListPeople[0];
        var b = board.ListPeople[1];
        ListRoleMember?[] expected = scenario switch
        {
            "append" => [null, b, a],
            "earlier" => [a, null, b],
            "batch" => [a, null, null, b],
            _ => throw new ArgumentException("Unexpected reinsertion scenario.", nameof(scenario))
        };
        Assert.Equal(expected.Length, team.Members.Count);
        for (int index = 0; index < expected.Length; index++)
        {
            Assert.Same(expected[index], team.Members[index]);
        }
        Assert.Same(team, a.Team);
        Assert.Same(team, b.Team);
    }

    [Fact]
    public void OrdinaryDuplicateReplacementRecordsOneV1SetAndReplaysJavaBatches()
    {
        var board = CreateCheckpoint();
        string snapshot = ModelSnapshot.Write(board);
        using var recorder = JournalRecorder<SequenceRoleBoard>.Start(board, ModelSnapshot.Registry, Binding, Checkpoint, 0);
        board.ListTeams[0].Members[2] = board.ListPeople[0];
        CheckReplacement(board, "single");
        string wire = JournalJson.Write(recorder.Finish());
        ReplayReplacement(snapshot, wire, "single", [2]);

        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_SECONDARY_RECIPROCAL") is { } directory)
        {
            File.WriteAllText(Path.Combine(directory, "csharp-sequence-role-replace-single.json"), wire);
            string java = File.ReadAllText(Path.Combine(directory, "java-sequence-role-checkpoint.json"));
            ReplayReplacement(java, File.ReadAllText(Path.Combine(directory, "java-sequence-role-replace-single.json")), "single", [2]);
            ReplayReplacement(java, File.ReadAllText(Path.Combine(directory, "java-sequence-role-replace-contiguous.json")), "contiguous", [1, 2]);
            ReplayReplacement(java, File.ReadAllText(Path.Combine(directory, "java-sequence-role-replace-swap.json")), "swap", [0, 2]);
            ReplayReplacement(java, File.ReadAllText(Path.Combine(directory, "java-sequence-role-replace-batch.json")), "batch", [0, 1, 2]);
        }
    }

    [Fact]
    public void OrdinarySortPublishesSparseChangesAndKeepsOppositesDuringUndoAndRedo()
    {
        var board = CreateCheckpoint();
        var team = board.ListTeams[0];
        var a = board.ListPeople[0];
        var b = board.ListPeople[1];
        IModelList<ListRoleMember?> list = team.Members;
        var view = team.AsReadOnly().Members;
        var directEdits = new List<ModelListChange<ListRoleMember?>>();
        var projectedEdits = new List<ModelListChange<IReadOnlyListRoleMember?>>();
        var sources = new List<object>();
        using var direct = list.AddListener(edit => { directEdits.Add(edit); sources.Add(edit.Source); });
        using var projected = view.AddListener(edit => { projectedEdits.Add(edit); sources.Add(edit.Source); });
        int inverseChanges = 0;
        using var observeA = a.VmfChanges.AddPropertyListener("team", _ => inverseChanges++);
        using var observeB = b.VmfChanges.AddPropertyListener("team", _ => inverseChanges++);

        team.VmfChanges.Start();
        list.Sort(SortOrder(board));
        var transaction = team.VmfChanges.PublishTransaction()!;
        team.VmfChanges.Stop();
        CheckReplacement(board, "swap");
        Assert.Collection(sources, source => Assert.Same(view, source), source => Assert.Same(list, source));
        var edit = Assert.Single(directEdits);
        Assert.Same(list, edit.Source);
        Assert.Equal([0, 2], edit.Removed.Select(entry => entry.Index));
        Assert.Equal([0, 2], edit.Added.Select(entry => entry.Index));
        Assert.Same(a, edit.Removed[0].Value);
        Assert.Same(b, edit.Added[0].Value);
        var viewEdit = Assert.Single(projectedEdits);
        Assert.Same(view, viewEdit.Source);
        Assert.Equal([0, 2], viewEdit.Added.Select(entry => entry.Index));
        Assert.Same(a.AsReadOnly(), viewEdit.Removed[0].Value);
        Assert.Same(b.AsReadOnly(), viewEdit.Added[0].Value);
        Assert.IsType<ModelCollectionChange>(Assert.Single(transaction.Changes));

        Assert.True(transaction.Undo());
        CheckBaseline(board);
        Assert.True(transaction.Redo());
        CheckReplacement(board, "swap");
        list.Sort(SortOrder(board));
        Assert.Equal(0, inverseChanges);
        Assert.Equal(3, directEdits.Count);
        Assert.Equal(3, projectedEdits.Count);
        Assert.All(directEdits, change => Assert.Equal([0, 2], change.Added.Select(entry => entry.Index)));
    }

    [Fact]
    public void OrdinaryReferenceSortIsStableWithTiesAndDuplicateSlots()
    {
        var first = new ListRolePerson { Name = "tie" };
        var second = new ListRolePerson { Name = "tie" };
        var list = new ReferenceList<ListRoleMember>();
        list.AddRange([first, null, second, first]);
        var edits = new List<ModelListChange<ListRoleMember?>>();
        using var events = list.AddListener(edits.Add);
        list.Sort((left, right) => (left is not null).CompareTo(right is not null));
        Assert.Collection(list, Assert.Null, value => Assert.Same(first, value),
            value => Assert.Same(second, value), value => Assert.Same(first, value));
        Assert.Equal([0, 1], Assert.Single(edits).Added.Select(entry => entry.Index));
    }

    [Fact]
    public void SparseSortExchangesV1SetFromBothProducers()
    {
        var board = CreateCheckpoint();
        string snapshot = ModelSnapshot.Write(board);
        using var recorder = JournalRecorder<SequenceRoleBoard>.Start(board, ModelSnapshot.Registry, Binding, Checkpoint, 0);
        board.ListTeams[0].Members.Sort(SortOrder(board));
        CheckReplacement(board, "swap");
        ExchangeReplacement(snapshot, recorder.Finish(), "swap", [0, 2]);
    }

    [Fact]
    public void ReplaceAllNormalizesTheSingularOppositeAndExchangesOnlyTheInitiatingEdit()
    {
        var board = CreateCheckpoint();
        var team = board.ListTeams[0];
        var a = board.ListPeople[0];
        var b = board.ListPeople[1];
        string snapshot = ModelSnapshot.Write(board);
        var events = new List<string>();
        using var view = team.AsReadOnly().Members.AddListener(edit => events.Add("view:" + Slots(edit)));
        using var writable = team.Members.AddListener(edit => events.Add("mutable:" + Slots(edit)));
        using var recorder = JournalRecorder<SequenceRoleBoard>.Start(board, ModelSnapshot.Registry, Binding, Checkpoint, 0);
        team.Members.ReplaceAll(member => ReferenceEquals(member, a) ? b : member);
        CheckReplacement(board, "operator");
        Assert.Equal(["view:[0]/[]", "mutable:[0]/[]", "view:[0,1,2]/[0,1,2]", "mutable:[0,1,2]/[0,1,2]"], events);
        ExchangeReplacement(snapshot, recorder.Finish(), "operator", [0, 1, 2]);

        static string Slots<T>(ModelListChange<T> edit) =>
            $"[{string.Join(',', edit.Removed.Select(slot => slot.Index))}]/[{string.Join(',', edit.Added.Select(slot => slot.Index))}]";
    }

    private static void ExchangeReplacement(string snapshot, ChangeJournal recorded, string scenario, int[] indices)
    {
        var directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_SECONDARY_RECIPROCAL");
        (string Extension, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[] formats =
        [
            ("json", JournalJson.Write, JournalJson.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read),
        ];
        foreach (var (extension, write, read) in formats)
        {
            string wire = write(recorded);
            ReplayReplacement(snapshot, read(wire), scenario, indices);
            if (directory is null) continue;
            File.WriteAllText(Path.Combine(directory, $"csharp-sequence-role-replace-{scenario}.{extension}"), wire);
            var java = read(File.ReadAllText(Path.Combine(directory, $"java-sequence-role-replace-{scenario}.{extension}")));
            Assert.Equal(JournalJson.Write(recorded), JournalJson.Write(java));
            ReplayReplacement(File.ReadAllText(Path.Combine(directory, "java-sequence-role-checkpoint.json")),
                java, scenario, indices);
        }
    }

    private static Comparison<ListRoleMember?> SortOrder(SequenceRoleBoard board)
    {
        var first = board.ListPeople[0];
        return (left, right) => Rank(left).CompareTo(Rank(right));

        int Rank(ListRoleMember? value) => value is null ? 1 : ReferenceEquals(value, first) ? 2 : 0;
    }

    private static void ReplayReplacement(string snapshot, string journal, string scenario, int[] indices) =>
        ReplayReplacement(snapshot, JournalJson.Read(journal), scenario, indices);

    private static void ReplayReplacement(string snapshot, ChangeJournal changes, string scenario, int[] indices)
    {
        var baseline = ModelSnapshot.Read<SequenceRoleBoard>(snapshot);
        Assert.Equal(1, changes.WireVersion);
        var operation = Assert.IsType<JournalListEdit>(Assert.Single(Assert.Single(changes.Transactions).Operations));
        Assert.Equal(JournalListEditKind.Set, operation.Kind);
        Assert.Equal(indices, operation.Indices);
        CheckReplacement(JournalReplayer.Replay(baseline, Binding, Checkpoint, changes, ModelSnapshot.Registry), scenario);
        CheckBaseline(baseline);
    }

    private static void CheckReplacement(SequenceRoleBoard board, string scenario)
    {
        var team = board.ListTeams[0];
        var a = board.ListPeople[0];
        var b = board.ListPeople[1];
        ListRoleMember?[] expected = scenario switch
        {
            "single" => [null, a],
            "contiguous" => [a, null],
            "swap" => [b, null, a],
            "batch" or "operator" => [null, b],
            _ => throw new ArgumentException("Unexpected replacement scenario.", nameof(scenario))
        };
        Assert.Equal(expected.Length, team.Members.Count);
        for (int index = 0; index < expected.Length; index++)
        {
            Assert.Same(expected[index], team.Members[index]);
        }
        Assert.Same(scenario is "batch" or "operator" ? null : team, a.Team);
        Assert.Same(scenario is "batch" or "operator" or "swap" ? team : null, b.Team);
    }

    private static SequenceRoleBoard CreateCheckpoint()
    {
        var board = new SequenceRoleBoard();
        var ordered = new OrderedRoleTeam { Name = "o-first" };
        var list = new ListRoleTeam { Name = "l-first" };
        board.OrderedTeams.AddRange([ordered, new OrderedRoleTeam { Name = "o-second" }]);
        board.ListTeams.AddRange([list, new ListRoleTeam { Name = "l-second" }]);
        board.OrderedPeople.AddRange([new OrderedRolePerson { Name = "o-a" }, new OrderedRolePerson { Name = "o-b" }]);
        board.ListPeople.AddRange([new ListRolePerson { Name = "l-a" }, new ListRolePerson { Name = "l-b" }]);
        ordered.Members.AddRange(board.OrderedPeople);
        list.Members.AddRange([board.ListPeople[0], null, board.ListPeople[1]]);
        return board;
    }

    private static ChangeJournal Record(SequenceRoleBoard board)
    {
        var ordered = board.OrderedTeams[0];
        var nextOrdered = board.OrderedTeams[1];
        var list = board.ListTeams[0];
        var nextList = board.ListTeams[1];
        var orderedPerson = board.OrderedPeople[1];
        var listPerson = board.ListPeople[1];
        using var recorder = JournalRecorder<SequenceRoleBoard>.Start(board, ModelSnapshot.Registry, Binding, Checkpoint, 0);
        nextOrdered.Members.Insert(0, orderedPerson);
        nextList.Members.Insert(0, listPerson);
        Assert.Equal(1, Assert.IsType<JournalFrame>(recorder.PublishFrame()).Sequence);
        orderedPerson.Team = ordered;
        listPerson.Team = list;
        Assert.Equal(2, Assert.IsType<JournalFrame>(recorder.PublishFrame()).Sequence);
        ordered.Members.Move(1, 0);
        list.Members.Insert(0, null);
        CheckFinal(board);
        return recorder.Finish();
    }

    private static void Replay(string snapshot, string wire)
    {
        var baseline = ModelSnapshot.Read<SequenceRoleBoard>(snapshot);
        var changes = JournalJson.Read(wire);
        Assert.Equal(1, changes.WireVersion);
        Assert.Equal(3, changes.Transactions.Count);
        var replayed = JournalReplayer.Replay(baseline, Binding, Checkpoint, changes, ModelSnapshot.Registry);
        CheckBaseline(baseline);
        CheckFinal(replayed);
        Assert.NotSame(baseline.OrderedPeople[0], replayed.OrderedPeople[0]);
        Assert.NotSame(baseline.ListPeople[0], replayed.ListPeople[0]);
    }

    private static void CheckBaseline(SequenceRoleBoard board)
    {
        AssertOrdered(board, board.OrderedPeople[0], board.OrderedPeople[1]);
        var list = board.ListTeams[0];
        Assert.Collection(list.Members, item => Assert.Same(board.ListPeople[0], item),
            Assert.Null, item => Assert.Same(board.ListPeople[1], item));
        Assert.All(board.ListPeople, member => Assert.Same(list, member.Team));
    }

    private static void CheckFinal(SequenceRoleBoard board)
    {
        AssertOrdered(board, board.OrderedPeople[1], board.OrderedPeople[0]);
        Assert.Empty(board.OrderedTeams[1].Members);
        var list = board.ListTeams[0];
        Assert.Collection(list.Members, Assert.Null, item => Assert.Same(board.ListPeople[0], item),
            Assert.Null, item => Assert.Same(board.ListPeople[1], item));
        Assert.All(board.ListPeople, member => Assert.Same(list, member.Team));
        Assert.Empty(board.ListTeams[1].Members);
    }

    private static void AssertOrdered(SequenceRoleBoard board, OrderedRolePerson first, OrderedRolePerson second)
    {
        var team = board.OrderedTeams[0];
        Assert.Collection(team.Members, item => Assert.Same(first, item), item => Assert.Same(second, item));
        Assert.All(board.OrderedPeople, member => Assert.Same(team, member.Team));
    }
}
