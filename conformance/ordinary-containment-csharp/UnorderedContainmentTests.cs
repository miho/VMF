using Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class UnorderedContainmentTests
{
    private static readonly JournalBinding Binding = new("unordered-containment", 1, model => model switch
    {
        Shelf shelf => shelf.Name!,
        Volume volume => volume.Name!,
        _ => "room",
    });

    [Fact]
    public void IdentityMembershipHasLiveReadOnlyAndValidatedOwnership()
    {
        var model = Checkpoint();
        var source = model.Shelves[0]!;
        var peer = new Volume { Name = "A" };
        Assert.False(source.Volumes.Add(model.Featured!));
        Assert.Throws<ArgumentNullException>(() => source.Volumes.AddRange([peer, null!]));
        Assert.Null(peer.Shelf);
        Assert.Equal(2, source.Volumes.Count);
        var view = source.AsReadOnly();
        Assert.True(source.Volumes.Add(peer));
        Assert.True(view.Volumes.Contains(peer.AsReadOnly()));
        Assert.Equal("/shelves/0/volumes", Assert.Single(model.Validate()).ModelPath);
        Assert.True(source.Volumes.Remove(peer));
        Assert.Null(peer.Shelf);
        Assert.Empty(model.Validate());
        Assert.False(ModelSnapshot.Reflection.Reflect(source).FindProperty("volumes")!.IsOrdered);

        var copy = ModelSnapshot.Reflection.DeepCopy(model);
        CheckCheckpoint(copy);
        Assert.NotSame(model.Featured, copy.Featured);
        var other = copy.Shelves[0]!;
        var first = Named(other, "A");
        other.Volumes.Remove(first);
        other.Volumes.Add(first);
        Assert.True(model.ContentEquals(copy));
        Assert.Equal(model.ContentHashCode(), copy.ContentHashCode());
    }

    [Fact]
    public void LocalHistoryAndGraphPatchReuseTheTransferredIdentity()
    {
        var initial = Checkpoint();
        var source = initial.Shelves[0]!;
        var target = initial.Shelves[1]!;
        var child = initial.Featured!;
        var changes = initial.VmfChanges;
        var observed = new List<ModelCollectionChange>();
        using var watch = changes.AddRecursiveListener(change =>
        {
            if (change is ModelCollectionChange collection) observed.Add(collection);
        });
        changes.Start();
        changes.StartTransaction();
        child.Shelf = target;
        var transaction = changes.PublishTransaction();
        changes.Stop();

        Assert.Same(child, Assert.Single(target.Volumes));
        Assert.Equal(2, observed.Count);
        Assert.All(observed, delta =>
        {
            Assert.True(delta.IsContainment);
            Assert.Equal(ModelChangeKind.ReferenceSet, delta.Kind);
            Assert.Null(Assert.Single(delta.Removed.Concat(delta.Added)).Index);
        });
        watch.Dispose();
        Assert.True(transaction!.Undo());
        Assert.Same(source, child.Shelf);
        Assert.Empty(target.Volumes);

        var desired = Checkpoint();
        desired.Featured!.Shelf = desired.Shelves[1];
        var patch = ModelSnapshot.Reflection.Diff(initial, desired);
        patch.Apply(initial);
        Assert.Same(child, Assert.Single(target.Volumes));
        Assert.Same(child, initial.Featured);
        patch.Undo(initial);
        CheckCheckpoint(initial);
        Assert.Same(child, initial.Featured);
    }

    [Fact]
    public void WritableUnorderedContainmentResolvesTheHeldReadOnlyAlias()
    {
        var graph = Checkpoint();
        var owner = graph.Shelves[0]!;
        var child = graph.Featured!;
        IContainmentSet<Volume> writable = owner.Volumes;
        var alias = child.AsReadOnly();
        Assert.True(writable.Contains(alias));
        Assert.True(writable.Remove(alias));
        Assert.False(writable.Contains(alias));
        Assert.False(writable.Remove(alias));
        Assert.Null(child.Shelf);
        Assert.Same(child, graph.Featured);
        Assert.Equal("B", Assert.Single(writable).Name);
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void ReplaysCreatedAndTransferredChildrenFromV1Records(string format)
    {
        var checkpoint = Checkpoint();
        var journal = JournalRecorder<ReadingRoom>.RecordFrame(Checkpoint(), ModelSnapshot.Registry, Binding, "base", 0, model =>
        {
            var target = model.Shelves[1]!;
            model.Featured!.Shelf = target;
            target.Volumes.Add(new Volume { Name = "C" });
        });
        (Func<ReadingRoom, string> Write, Func<string, ReadingRoom> Read) snapshots = format switch
        {
            "json" => (ModelSnapshot.Write, ModelSnapshot.Read<ReadingRoom>),
            "yaml" => (ModelSnapshot.WriteYaml, ModelSnapshot.ReadYaml<ReadingRoom>),
            "xml" => (ModelSnapshot.WriteXml, ModelSnapshot.ReadXml<ReadingRoom>),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
        (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) journals = format switch
        {
            "json" => (JournalJson.Write, JournalJson.Read),
            "yaml" => (JournalYaml.Write, JournalYaml.Read),
            "xml" => (JournalXml.Write, JournalXml.Read),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
        string native = snapshots.Write(checkpoint);
        string wire = journals.Write(journal);
        Replay(native, wire);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ORDINARY_CONTAINMENT") is { } path)
        {
            string SnapshotPath(string producer) => Path.Combine(path, $"{producer}-unordered-checkpoint.{format}");
            string JournalPath(string producer) => Path.Combine(path, $"{producer}-unordered-journal.{format}");
            Replay(File.ReadAllText(SnapshotPath("java")), File.ReadAllText(JournalPath("java")));
            File.WriteAllText(SnapshotPath("csharp"), native);
            File.WriteAllText(JournalPath("csharp"), wire);
        }

        void Replay(string snapshot, string records)
        {
            var fresh = snapshots.Read(snapshot);
            CheckCheckpoint(fresh);
            var changes = journals.Read(records);
            Assert.Equal(1, changes.WireVersion);
            Assert.Equal(3, changes.Transactions.SelectMany(frame => frame.Operations).OfType<JournalMembershipEdit>().Count());
            var restored = JournalReplayer.Replay(fresh, Binding, "base", changes, ModelSnapshot.Registry);
            var source = restored.Shelves[0]!;
            var target = restored.Shelves[1]!;
            Assert.Equal("B", Assert.Single(source.Volumes).Name);
            Assert.Same(source, Named(source, "B").Shelf);
            Assert.Equal(2, target.Volumes.Count);
            Assert.Same(restored.Featured, Named(target, "A"));
            Assert.Same(target, restored.Featured!.Shelf);
            Assert.Same(target, Named(target, "C").Shelf);
            CheckCheckpoint(fresh);
        }
    }

    [Theory]
    [InlineData(ModelChangeScope.Local)]
    [InlineData(ModelChangeScope.Recursive)]
    public void ReentrantTransferKeepsNativeHistoryAndReplaysMembershipInCommitOrder(ModelChangeScope scope)
    {
        var working = ReentrantCheckpoint();
        var source = working.Shelves[0]!;
        var target = working.Shelves[1]!;
        var third = working.Shelves[2]!;
        var moving = working.Featured!;
        var trace = new List<string>();
        bool redirected = false;
        var stream = scope == ModelChangeScope.Local ? target.VmfChanges : working.VmfChanges;
        using var reentry = stream.AddPropertyListener("volumes", change =>
        {
            if (redirected || !ReferenceEquals(change.Source, target)) return;
            redirected = true;
            trace.Add("target callback");
            Assert.Empty(source.Volumes);
            Assert.Same(moving, Assert.Single(target.Volumes));
            Assert.Same(target, moving.Shelf);
            Assert.True(third.Volumes.Add(moving));
        }, scope);
        using var inverse = moving.VmfChanges.AddPropertyListener("shelf", change =>
        {
            Assert.True(change.IsInverse);
            trace.Add(change.NewValue is Shelf next ? "attach " + next.Name : "detach " + ((Shelf)change.OldValue!).Name);
        });
        using var ownership = working.VmfChanges.AddRecursiveListener(change =>
        {
            if (change is ModelCollectionChange edit && (!ReferenceEquals(edit.Source, target) || edit.Added.Count == 0))
            {
                trace.Add(EditName(edit, " "));
            }
        });

        using var recorder = JournalRecorder<ReadingRoom>.Start(working, ModelSnapshot.Registry, Binding, "base", 0);
        Assert.True(target.Volumes.Add(moving));
        Assert.True(redirected);
        Assert.Equal(["source remove", "detach source", "attach target", "target callback",
            "target remove", "detach target", "attach third", "third add"], trace);
        // The triggering edit is recorded before the edits its listeners made (portable contract H4).
        Assert.Equal(["source:remove", "target:add", "target:remove", "third:add"],
            working.VmfChanges.History.OfType<ModelCollectionChange>().Select(edit => EditName(edit, ":")));
        CheckRedirected(working);
        var journal = recorder.Finish();

        var formats = new (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
        };
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ORDINARY_CONTAINMENT");
        foreach (var (format, write, read) in formats)
        {
            string wire = write(journal);
            Verify(read(wire));
            if (exchange is null) continue;
            string name = $"unordered-reentrant-{scope.ToString().ToLowerInvariant()}-journal.{format}";
            Verify(read(File.ReadAllText(Path.Combine(exchange, "java-" + name))));
            File.WriteAllText(Path.Combine(exchange, "csharp-" + name), wire);
        }

        static string EditName(ModelCollectionChange edit, string separator) =>
            ((Shelf)edit.Source).Name + separator + (edit.Added.Count == 0 ? "remove" : "add");

        static void CheckRedirected(ReadingRoom room)
        {
            Assert.Empty(room.Shelves[0]!.Volumes);
            Assert.Empty(room.Shelves[1]!.Volumes);
            Assert.Same(room.Featured, Assert.Single(room.Shelves[2]!.Volumes));
            Assert.Same(room.Shelves[2], room.Featured!.Shelf);
        }

        static void Verify(ChangeJournal recorded)
        {
            Assert.Equal(1, recorded.WireVersion);
            var frame = Assert.Single(recorded.Transactions);
            Assert.Equal(1, frame.Sequence);
            Assert.Collection(frame.Operations,
                change => AssertMembership(change, "source", adding: false),
                change => AssertMembership(change, "target", adding: true),
                change => AssertMembership(change, "target", adding: false),
                change => AssertMembership(change, "third", adding: true));
            var checkpoint = ReentrantCheckpoint();
            var replayed = JournalReplayer.Replay(checkpoint, Binding, "base", recorded, ModelSnapshot.Registry);
            CheckRedirected(replayed);
            Assert.NotSame(checkpoint.Featured, replayed.Featured);
            Assert.Same(checkpoint.Shelves[0], checkpoint.Featured!.Shelf);
            Assert.Same(checkpoint.Featured, Assert.Single(checkpoint.Shelves[0]!.Volumes));
        }

        static void AssertMembership(JournalOperation operation, string target, bool adding)
        {
            var edit = Assert.IsType<JournalMembershipEdit>(operation);
            Assert.True(edit.References);
            Assert.Equal(target, edit.Target);
            Assert.Equal("volumes", edit.Property);
            Assert.Equal("A", Assert.Single(adding ? edit.Added : edit.Removed).Text);
            Assert.Empty(adding ? edit.Removed : edit.Added);
            Assert.Empty(edit.Created);
        }
    }

    private static ReadingRoom ReentrantCheckpoint()
    {
        var moving = new Volume { Name = "A" };
        var source = new Shelf { Name = "source" };
        source.Volumes.Add(moving);
        return ReadingRoom.NewBuilder().WithShelves([source, new Shelf { Name = "target" }, new Shelf { Name = "third" }])
            .WithFeatured(moving).Build();
    }

    private static ReadingRoom Checkpoint()
    {
        var first = new Volume { Name = "A" };
        return ReadingRoom.NewBuilder()
            .WithShelves([Shelf.NewBuilder().WithName("source").WithVolumes([first, new Volume { Name = "B" }]).Build(),
                new Shelf { Name = "target" }])
            .WithFeatured(first)
            .Build();
    }

    private static Volume Named(Shelf shelf, string name) => shelf.Volumes.Single(volume => volume.Name == name);

    private static void CheckCheckpoint(ReadingRoom model)
    {
        var source = model.Shelves[0]!;
        Assert.Equal(2, source.Volumes.Count);
        Assert.Empty(model.Shelves[1]!.Volumes);
        Assert.Same(model.Featured, Named(source, "A"));
        Assert.Same(source, model.Featured!.Shelf);
    }
}
