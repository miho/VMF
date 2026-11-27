using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;
using Owning = Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;

namespace Vmf.CSharp.Conformance;

public sealed class UnorderedCursorTests
{
    private static readonly JournalBinding References = new("unordered-cursor-reference", 1, model => ((ArchiveNode)model).Name!);
    private static readonly JournalBinding Ownership = new("unordered-cursor-owning", 1, model => model switch
    {
        Owning.Shelf shelf => shelf.Name!,
        Owning.Volume volume => volume.Name!,
        _ => "room",
    });

    [Fact]
    public void InsertingIntoAnUnorderedReferenceSlotChangesLiveOrderWithoutChangingTheV1MembershipEdit()
    {
        var inserted = Record(cursorEdit: true);
        var appended = Record(cursorEdit: false);
        Assert.Equal(new[] { "a", "c", "b" }, inserted.Order);
        Assert.Equal(new[] { "a", "b", "c" }, appended.Order);
        Assert.Equal(JournalJson.Write(appended.Journal), JournalJson.Write(inserted.Journal));
        Assert.Equal(1, inserted.Journal.WireVersion);
        CheckMembership(Assert.Single(Assert.Single(inserted.Journal.Transactions).Operations),
            "root", "peers", removed: [], added: ["c"]);
        var replayed = JournalReplayer.Replay(ReferenceCheckpoint(), References, "base", inserted.Journal, ModelSnapshot.Registry);
        Assert.True(replayed.Peers.Contains(replayed.Children[2]));

        static (string?[] Order, ChangeJournal Journal) Record(bool cursorEdit)
        {
            var root = ReferenceCheckpoint();
            var view = root.AsReadOnly();
            var first = root.Children[0];
            var second = root.Children[1];
            var third = root.Children[2];
            using var recorder = JournalRecorder<ArchiveNode>.Start(root, ModelSnapshot.Registry, References, "base", 0);
            var observed = new List<ModelCollectionChange>();
            using var watch = root.VmfChanges.AddLocalListener(change => observed.Add(Assert.IsType<ModelCollectionChange>(change)));
            if (cursorEdit)
            {
                using var cursor = ModelSnapshot.Reflection.OpenGraphCursor(root, ModelTraversalStrategy.UniqueProperty);
                Move(cursor, root, first, second, third, first);
                Assert.True(cursor.CanInsertAfter);
                cursor.ReplaceCurrent(first);
                Assert.Throws<ArgumentException>(() => cursor.ReplaceCurrent(second));
                Assert.Throws<ArgumentNullException>(() => cursor.InsertAfterCurrent(null));
                Assert.Empty(observed);
                cursor.InsertAfterCurrent(third);
                Assert.False(cursor.CanReplace);
                Move(cursor, second);
                Assert.False(cursor.TryPeek(out _));
            }
            else
            {
                Assert.True(root.Peers.Add(third));
            }
            Assert.True(view.Peers.Contains(third.AsReadOnly()));
            var added = Assert.Single(Assert.Single(observed).Added);
            Assert.Same(third, added.Value);
            Assert.Null(added.Index);
            return (root.Peers.Select(model => model.Name).ToArray(), recorder.Finish());
        }
    }

    [Fact]
    public void ReplacingAnUnorderedReferenceSlotUsesOneMembershipEdit()
    {
        var root = ReferenceCheckpoint();
        var first = root.Children[0];
        var second = root.Children[1];
        var replacement = root.Children[2];
        using var recorder = JournalRecorder<ArchiveNode>.Start(root, ModelSnapshot.Registry, References, "base", 0);
        using var cursor = ModelSnapshot.Reflection.OpenGraphCursor(root, ModelTraversalStrategy.UniqueProperty);
        Move(cursor, root, first, second, replacement, first);
        ModelCollectionChange? observed = null;
        using var trace = root.VmfChanges.AddLocalListener(change =>
        {
            var edit = Assert.IsType<ModelCollectionChange>(change);
            observed = edit;
            Assert.Null(Assert.Single(edit.Removed).Index);
            Assert.Null(Assert.Single(edit.Added).Index);
            Assert.Same(first, Assert.Single(edit.Removed).Value);
            Assert.Same(replacement, Assert.Single(edit.Added).Value);
        });
        cursor.ReplaceCurrent(replacement);
        Move(cursor, second);
        Assert.Equal(new[] { replacement, second }, root.Peers);
        var journal = recorder.Finish();
        CheckMembership(Assert.Single(Assert.Single(journal.Transactions).Operations),
            "root", "peers", ["a"], ["c"]);
        trace.Dispose();
        Assert.True(Assert.IsType<ModelCollectionChange>(observed).Undo());
        Assert.True(root.Peers.Contains(first));
        Assert.False(root.Peers.Contains(replacement));
    }

    [Fact]
    public void ReplacingAnOwnedMemberTransfersTheReplacementAndDetachesThePreviousChild()
    {
        var root = OwningCheckpoint();
        var target = root.Shelves[0]!;
        var source = root.Shelves[1]!;
        var first = target.Volumes.First();
        var last = target.Volumes.Last();
        var moving = Assert.Single(source.Volumes);
        using var recorder = JournalRecorder<Owning.ReadingRoom>.Start(root, Owning.ModelSnapshot.Registry, Ownership, "base", 0);
        using var cursor = Owning.ModelSnapshot.Reflection.OpenGraphCursor(target, ModelTraversalStrategy.ContainmentTree);
        Move(cursor, target, first);
        Assert.Throws<ArgumentException>(() => cursor.ReplaceCurrent(last));
        Assert.Throws<ArgumentNullException>(() => cursor.ReplaceCurrent(null));
        cursor.ReplaceCurrent(moving);
        Move(cursor, last);
        Assert.Equal(new[] { moving, last }, target.Volumes);
        Assert.Empty(source.Volumes);
        Assert.Null(first.Shelf);
        Assert.Same(target, moving.Shelf);
        var journal = recorder.Finish();
        Assert.Collection(Assert.Single(journal.Transactions).Operations,
            edit => CheckMembership(edit, "source", "volumes", ["c"], []),
            edit => CheckMembership(edit, "target", "volumes", ["a"], ["c"]));
    }

    [Fact]
    public void InsertingAnOwnedMemberTransfersOwnershipWithTheNormalMembershipEventsAndV1Wire()
    {
        var inserted = Record(cursorEdit: true);
        var appended = Record(cursorEdit: false);
        Assert.Equal(new[] { "a", "c", "b" }, inserted.Order);
        Assert.Equal(new[] { "a", "b", "c" }, appended.Order);
        Assert.Equal(inserted.Trace, appended.Trace);
        Assert.Equal(new[] { "source", "detach", "attach", "target" }, inserted.Trace);
        Assert.Equal(JournalJson.Write(appended.Journal), JournalJson.Write(inserted.Journal));

        var frame = Assert.Single(inserted.Journal.Transactions);
        Assert.Collection(frame.Operations,
            edit => CheckMembership(edit, "source", "volumes", ["c"], []),
            edit => CheckMembership(edit, "target", "volumes", [], ["c"]));
        var replayed = JournalReplayer.Replay(OwningCheckpoint(), Ownership, "base", inserted.Journal, Owning.ModelSnapshot.Registry);
        Assert.Empty(replayed.Shelves[1]!.Volumes);
        var target = replayed.Shelves[0]!;
        Assert.Same(target, target.Volumes.Single(volume => volume.Name == "c").Shelf);

        static (string?[] Order, string[] Trace, ChangeJournal Journal) Record(bool cursorEdit)
        {
            var root = OwningCheckpoint();
            var target = root.Shelves[0]!;
            var source = root.Shelves[1]!;
            var first = target.Volumes.First();
            var last = target.Volumes.Last();
            var moving = Assert.Single(source.Volumes);
            using var recorder = JournalRecorder<Owning.ReadingRoom>.Start(root, Owning.ModelSnapshot.Registry, Ownership, "base", 0);
            var trace = new List<string>();
            using var removed = source.VmfChanges.AddLocalListener(change =>
            {
                Assert.Same(source, moving.Shelf);
                Assert.Null(Assert.Single(Assert.IsType<ModelCollectionChange>(change).Removed).Index);
                trace.Add("source");
            });
            using var moved = moving.VmfChanges.AddLocalListener(change => trace.Add(change.NewValue is null ? "detach" : "attach"));
            using var added = target.VmfChanges.AddLocalListener(change =>
            {
                Assert.Same(target, moving.Shelf);
                Assert.Null(Assert.Single(Assert.IsType<ModelCollectionChange>(change).Added).Index);
                trace.Add("target");
            });
            if (cursorEdit)
            {
                using var legacy = Owning.ModelSnapshot.Reflection.OpenContainmentCursor(target);
                Move(legacy, target, first);
                Assert.False(legacy.CanInsertAfter);
                using var cursor = Owning.ModelSnapshot.Reflection.OpenGraphCursor(target, ModelTraversalStrategy.ContainmentTree);
                Move(cursor, target, first);
                Assert.True(cursor.CanInsertAfter);
                cursor.InsertAfterCurrent(moving);
                Assert.False(cursor.CanReplace);
                Move(cursor, last);
                Assert.False(cursor.TryPeek(out _));
            }
            else
            {
                Assert.True(target.Volumes.Add(moving));
            }
            Assert.Empty(source.Volumes);
            Assert.Same(target, moving.Shelf);
            return (target.Volumes.Select(volume => volume.Name).ToArray(), trace.ToArray(), recorder.Finish());
        }
    }

    private static ArchiveNode ReferenceCheckpoint()
    {
        var root = new ArchiveNode { Name = "root" };
        root.Children.AddRange([new() { Name = "a" }, new() { Name = "b" }, new() { Name = "c" }]);
        root.Peers.AddRange([root.Children[0], root.Children[1]]);
        return root;
    }

    private static Owning.ReadingRoom OwningCheckpoint()
    {
        var root = new Owning.ReadingRoom();
        var target = new Owning.Shelf { Name = "target" };
        var source = new Owning.Shelf { Name = "source" };
        target.Volumes.AddRange([new() { Name = "a" }, new() { Name = "b" }]);
        source.Volumes.Add(new() { Name = "c" });
        root.Shelves.AddRange([target, source]);
        return root;
    }

    private static void CheckMembership(JournalOperation change, string target, string property, string[] removed, string[] added)
    {
        var edit = Assert.IsType<JournalMembershipEdit>(change);
        Assert.True(edit.References);
        Assert.Equal(target, edit.Target);
        Assert.Equal(property, edit.Property);
        Assert.Equal(removed, edit.Removed.Select(value => value.Text));
        Assert.Equal(added, edit.Added.Select(value => value.Text));
    }

    private static void Move(ModelGraphCursor cursor, params object[] expected)
    {
        foreach (var item in expected)
        {
            Assert.True(cursor.MoveNext());
            Assert.Same(item, cursor.Current);
        }
    }
}
