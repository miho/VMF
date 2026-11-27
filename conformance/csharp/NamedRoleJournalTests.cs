using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Traits;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class NamedRoleJournalTests
{
    private static readonly JournalBinding Binding = new("vmf-test.named-role-ownership", 1, model => model switch
    {
        NamedRoleJournalHistory => "root",
        NamedRoleFolder owner => owner.Name!,
        IReadOnlyNamedOwnedRole child => child.Label!,
        _ => throw new ArgumentException("Unrecognized journal identity.", nameof(model)),
    });

    [Fact]
    public void V1ReplaysAllNamedChildRoleOwnershipShapesAcrossADetachedFrame()
    {
        var live = Initial();
        string checkpoint = ModelSnapshot.Write(live);
        var moving = Assert.IsType<NamedOwnedTask>(live.Selected);
        using var recorder = JournalRecorder<NamedRoleJournalHistory>.Start(live, ModelSnapshot.Registry, Binding, "initial", 10);
        moving.OrdinaryOwner = live.Right;
        moving.OrderedOwner = live.Left;
        moving.UnorderedOwner = live.Right;
        Assert.Equal(11, recorder.PublishFrame()!.Sequence);
        live.Selected = null;
        moving.UnorderedOwner = null;
        Assert.Equal(12, recorder.PublishFrame()!.Sequence);
        moving.SingleOwner = live.Right;
        moving.TaskNumber = 72;
        live.Selected = moving;
        var journal = recorder.Finish();

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_TRAITS");
        string? javaCheckpoint = null;
        if (exchange is not null)
        {
            javaCheckpoint = File.ReadAllText(Path.Combine(exchange, "java-named-role-journal-checkpoint.json"));
            File.WriteAllText(Path.Combine(exchange, "csharp-named-role-journal-checkpoint.json"), checkpoint);
        }

        Check("json", JournalJson.Write, JournalJson.Read);
        Check("xml", JournalXml.Write, JournalXml.Read);
        Check("yaml", JournalYaml.Write, JournalYaml.Read);

        void Check(string extension, Func<ChangeJournal, string> write, Func<string, ChangeJournal> read)
        {
            string payload = write(journal);
            Verify(ModelSnapshot.Read<NamedRoleJournalHistory>(checkpoint), read(payload));
            if (exchange is null) return;
            var path = $"named-role-journal.{extension}";
            var java = read(File.ReadAllText(Path.Combine(exchange, "java-" + path)));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(journal)), JsonNode.Parse(JournalJson.Write(java))));
            Verify(ModelSnapshot.Read<NamedRoleJournalHistory>(javaCheckpoint!), java);
            File.WriteAllText(Path.Combine(exchange, "csharp-" + path), payload);
        }
    }

    private static NamedRoleJournalHistory Initial()
    {
        var moving = NamedOwnedTask.NewBuilder().WithLabel("moving").WithTaskNumber(71).Build();
        var left = new NamedRoleFolder { Name = "left", Single = moving };
        left.Ordered.Add(NamedOwnedNote.NewBuilder().WithLabel("stay-ordered").Build());
        var right = new NamedRoleFolder { Name = "right" };
        right.Ordinary.Add(NamedOwnedNote.NewBuilder().WithLabel("stay-list").Build());
        right.Unordered.Add(NamedOwnedNote.NewBuilder().WithLabel("stay-set").Build());
        return new NamedRoleJournalHistory { Left = left, Right = right, Selected = moving };
    }

    private static void Verify(NamedRoleJournalHistory original, ChangeJournal journal)
    {
        var frames = journal.Transactions;
        Assert.Equal(1, journal.WireVersion);
        Assert.Equal(new long[] { 11, 12, 13 }, frames.Select(frame => frame.Sequence));
        Assert.Equal(new[] { "SET left.single", "LIST_ADD right.ordinary", "LIST_REMOVE right.ordinary",
            "LIST_ADD left.ordered", "LIST_REMOVE left.ordered", "REF_SET_EDIT right.unordered" },
            frames[0].Operations.Select(Signature));
        Assert.Equal(new[] { "SET root.selected", "REF_SET_EDIT right.unordered" }, frames[1].Operations.Select(Signature));
        Assert.Equal(new[] { "SET right.single", "SET moving.taskNumber", "SET root.selected" },
            frames[2].Operations.Select(Signature));
        Assert.All(frames.SelectMany(frame => frame.Operations), operation => Assert.Empty(operation.Created));
        Assert.All(frames[0].Operations.Skip(1).Take(4), operation =>
            Assert.Equal(1, Assert.Single(Assert.IsType<JournalListEdit>(operation).Indices)));

        var first = ReplayPrefix(1);
        var unordered = Assert.IsType<NamedOwnedTask>(first.Selected);
        Assert.True(first.Right!.Unordered.Contains(unordered));
        Assert.Same(first.Right, unordered.UnorderedOwner);
        Assert.Null(unordered.SingleOwner);
        Assert.Null(unordered.OrdinaryOwner);
        Assert.Null(unordered.OrderedOwner);
        var detached = ReplayPrefix(2);
        Assert.Null(detached.Selected);
        Assert.Null(detached.Left!.Single);
        Assert.Null(detached.Right!.Single);
        Assert.Equal("stay-set", Assert.Single(detached.Right.Unordered).Label);

        var result = ReplayPrefix(frames.Count);
        var moving = Assert.IsType<NamedOwnedTask>(result.Right!.Single);
        Assert.Same(result.Right, moving.SingleOwner);
        Assert.Same(result.Right.AsReadOnly(), result.Right.AsReadOnly().Single!.SingleOwner);
        Assert.Equal(72, moving.TaskNumber);
        Assert.Same(moving, result.Selected);
        Assert.Null(result.Left!.Single);
        Assert.Single(result.Left.Ordered);
        Assert.Single(result.Right.Ordinary);
        Assert.Single(result.Right.Unordered);
        Assert.Same(original.Left!.Single, original.Selected);
        Assert.Null(original.Right!.Single);
        Assert.NotSame(original.Selected, result.Selected);

        NamedRoleJournalHistory ReplayPrefix(int count)
        {
            var prefix = new ChangeJournal(journal.Model, journal.ModelVersion, journal.Root,
                journal.Checkpoint, journal.BaseSequence, frames.Take(count));
            return JournalReplayer.Replay(original, Binding, "initial", prefix, ModelSnapshot.Registry);
        }
    }

    private static string Signature(JournalOperation operation)
    {
        string kind = operation switch
        {
            JournalSet => "SET",
            JournalListEdit { Kind: JournalListEditKind.Add } => "LIST_ADD",
            JournalListEdit { Kind: JournalListEditKind.Remove } => "LIST_REMOVE",
            JournalMembershipEdit { References: true } => "REF_SET_EDIT",
            _ => throw new ArgumentException("Unexpected V1 operation.", nameof(operation))
        };
        return $"{kind} {operation.Target}.{operation.Property}";
    }
}
