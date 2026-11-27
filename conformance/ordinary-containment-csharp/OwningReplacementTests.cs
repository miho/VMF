using Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class OwningReplacementTests
{
    private const string Checkpoint = "owning-replacement-0";
    private static readonly JournalBinding Binding = new("ordinary-owning-replacement", 1, model => model switch
    {
        LegacyLayout => "layout",
        LegacyContainer container => container.Name!,
        LegacyChild child => child.Name!,
        _ => throw new ArgumentException("Unexpected journal model.", nameof(model)),
    });

    [Fact]
    public void TransferReplacementDeliversItsFullRangeAndReplaysBothProducers()
    {
        var working = Fixture();
        var source = working.Containers[0];
        var target = working.Containers[1];
        var moving = source.Children[0]!;
        var leaving = target.Children[0]!;
        var trace = new List<string>();
        var view = target.AsReadOnly().Children;
        using var sourceListener = source.Children.AddListener(edit =>
        {
            Assert.Same(source, moving.Owner);
            Assert.Same(moving, target.Children[0]);
            trace.Add("source:" + Slots(edit));
        });
        using var movingListener = moving.VmfChanges.AddPropertyListener("owner",
            _ => trace.Add("moving:" + (moving.Owner?.Name ?? "null")));
        using var leavingListener = leaving.VmfChanges.AddPropertyListener("owner",
            _ => trace.Add("leaving:" + (leaving.Owner?.Name ?? "null")));
        using var projected = view.AddListener(edit => trace.Add("view:" + Slots(edit)));
        using var mutable = target.Children.AddListener(edit => trace.Add("target:" + Slots(edit)));
        using var recorder = JournalRecorder<LegacyLayout>.Start(working, ModelSnapshot.Registry, Binding, Checkpoint, 0);
        IModelList<LegacyChild?> destination = target.Children;
        destination.ReplaceAll(child => ReferenceEquals(child, leaving) ? moving : child);
        Assert.Equal(["source:[0]/[]", "moving:null", "moving:target", "leaving:null",
            "view:[0,1,2]/[0,1,2]", "target:[0,1,2]/[0,1,2]"], trace);
        Assert.Null(leaving.Owner);
        CheckFinal(working);

        var recorded = recorder.Finish();
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ORDINARY_CONTAINMENT");
        (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[] formats =
        [
            ("json", JournalJson.Write, JournalJson.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read)
        ];
        foreach (var (format, write, read) in formats)
        {
            string wire = write(recorded);
            Replay(read(wire));
            if (exchange is null) continue;
            File.WriteAllText(Path.Combine(exchange, $"csharp-owning-replace-all.{format}"), wire);
            var java = read(File.ReadAllText(Path.Combine(exchange, $"java-owning-replace-all.{format}")));
            Assert.Equal(JournalJson.Write(recorded), JournalJson.Write(java));
            Replay(java);
        }
    }

    [Fact]
    public void AliasesRemainAttachedAndNativeUndoRestoresTheNullAndAliasPositions()
    {
        var owner = new LegacyContainer { Name = "owner" };
        var alias = new LegacyChild { Name = "alias" };
        owner.Children.AddRange([new LegacyChild { Name = "placeholder" }, null, alias]);
        owner.Children[0] = alias;
        int index = 0;
        owner.VmfChanges.Start();
        owner.Children.ReplaceAll(child => index++ == 0 ? null : child);
        var edit = Assert.IsType<ModelCollectionChange>(Assert.Single(owner.VmfChanges.History));
        owner.VmfChanges.Stop();
        Assert.Same(owner, alias.Owner);
        Assert.Null(owner.Children[0]);
        Assert.Same(alias, owner.Children[2]);
        Assert.True(edit.Undo());
        Assert.Same(alias, owner.Children[0]);
        Assert.Null(owner.Children[1]);
        Assert.Same(alias, owner.Children[2]);
        Assert.True(edit.Redo());
        Assert.Same(owner, alias.Owner);
        Assert.Null(owner.Children[0]);
    }

    [Fact]
    public void RejectedTransformCycleAndKeyLeaveTheOriginalOwnershipIntact()
    {
        var graph = Fixture();
        var source = graph.Containers[0];
        var target = graph.Containers[1];
        var leaving = target.Children[0]!;
        var moving = source.Children[0]!;
        var observed = new List<string>();
        using var sourceWatch = source.Children.AddListener(_ => observed.Add("source"));
        using var targetWatch = target.Children.AddListener(_ => observed.Add("target"));
        Assert.Throws<InvalidOperationException>(() => target.Children.ReplaceAll(value => value is null
            ? throw new InvalidOperationException("transform failed") : moving));
        Assert.Same(leaving, target.Children[0]);
        Assert.Same(target, leaving.Owner);
        Assert.Same(moving, Assert.Single(source.Children));
        Assert.Same(source, moving.Owner);
        Assert.Empty(observed);

        var root = new BuilderCycleNode();
        var leaf = new BuilderCycleNode();
        root.Children.Add(leaf);
        Assert.Throws<ArgumentException>(() => root.Children.ReplaceAll(_ => root));
        Assert.Same(leaf, Assert.Single(root.Children));

        var room = new ReadingRoom();
        var first = new Shelf { Name = "first" };
        var second = new Shelf { Name = "second" };
        room.Shelves.AddRange([first, second]);
        using var keyedWatch = room.Shelves.AddListener(edit => observed.Add(Slots(edit)));
        Assert.Throws<ArgumentException>(() => room.Shelves.ReplaceAll(value => ReferenceEquals(value, first)
            ? new Shelf { Name = second.Name } : value));
        Assert.Empty(observed);
        Assert.Same(first, room.FindShelvesByName("first"));
        room.Shelves.ReplaceAll(value => ReferenceEquals(value, first) ? second : first);
        Assert.Equal(["[0,1]/[0,1]"], observed);
        Assert.Same(second, room.Shelves[0]);
        Assert.Same(second, room.FindShelvesByName("second"));
    }

    [Fact]
    public void EmptyOwningReplacementHasNoNativeOrPortableEdit()
    {
        var empty = new LegacyContainer { Name = "empty" };
        using var watching = empty.Children.AddListener(_ => throw new InvalidOperationException("Empty edit was delivered."));
        using var recorder = JournalRecorder<LegacyContainer>.Start(empty, ModelSnapshot.Registry, Binding, Checkpoint, 0);
        empty.Children.ReplaceAll(_ => throw new InvalidOperationException("There are no slots to transform."));
        Assert.Empty(empty.VmfChanges.History);
        Assert.Empty(recorder.Finish().Transactions);
    }

    private static string Slots<T>(ModelListChange<T> edit) =>
        $"[{string.Join(',', edit.Removed.Select(slot => slot.Index))}]/[{string.Join(',', edit.Added.Select(slot => slot.Index))}]";

    private static LegacyLayout Fixture()
    {
        var moving = new LegacyChild { Name = "moving" };
        var source = new LegacyContainer { Name = "source" };
        var target = new LegacyContainer { Name = "target" };
        source.Children.Add(moving);
        target.Children.AddRange([new LegacyChild { Name = "leaving" }, null, new LegacyChild { Name = "kept" }]);
        var graph = new LegacyLayout { Featured = moving };
        graph.Containers.AddRange([source, target]);
        return graph;
    }

    private static void Replay(ChangeJournal journal)
    {
        Assert.Equal(1, journal.WireVersion);
        var operations = Assert.Single(journal.Transactions).Operations;
        Assert.Collection(operations, operation => Check(operation, "source", JournalListEditKind.Remove, [0]),
            operation => Check(operation, "target", JournalListEditKind.Set, [0, 1, 2]));
        var checkpoint = Fixture();
        var replay = JournalReplayer.Replay(checkpoint, Binding, Checkpoint, journal, ModelSnapshot.Registry);
        CheckFinal(replay);
        Assert.Same(checkpoint.Featured, Assert.Single(checkpoint.Containers[0].Children));
        Assert.Equal("leaving", checkpoint.Containers[1].Children[0]!.Name);

        static void Check(JournalOperation operation, string target, JournalListEditKind kind, int[] indices)
        {
            var edit = Assert.IsType<JournalListEdit>(operation);
            Assert.Equal(target, edit.Target);
            Assert.Equal(kind, edit.Kind);
            Assert.Equal(indices, edit.Indices);
        }
    }

    private static void CheckFinal(LegacyLayout layout)
    {
        var target = layout.Containers[1];
        Assert.Empty(layout.Containers[0].Children);
        Assert.Collection(target.Children, moving => Assert.Same(layout.Featured, moving), Assert.Null,
            held => Assert.Equal("kept", held!.Name));
        Assert.Same(target, layout.Featured!.Owner);
    }
}
