using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ContainmentObservationTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void SeparateSingularRecordingRelaysKeepBothSidesOfAReparent(bool throwing)
    {
        var sourceRoot = new ArchiveNode { Name = "source-root" };
        var targetRoot = new ArchiveNode { Name = "target-root" };
        var source = new ArchiveNode { Name = "source" };
        var target = new ArchiveNode { Name = "target" };
        var moving = new ArchiveNode { Name = "moving" };
        sourceRoot.Children.Add(source);
        targetRoot.Children.Add(target);
        source.Inset = moving;
        var sourceChanges = sourceRoot.VmfChanges;
        var targetChanges = targetRoot.VmfChanges;
        var order = new List<string>();
        var failure = new InvalidOperationException("source observer");
        using var sourceLocal = source.VmfChanges.AddPropertyListener("inset", _ =>
        {
            order.Add("source local");
            sourceChanges.Start();
            if (throwing) throw failure;
        });
        using var targetLocal = target.VmfChanges.AddPropertyListener("inset", _ =>
        {
            order.Add("target local");
            targetChanges.Start();
        });
        using var sourceRecursive = sourceChanges.AddPropertyListener("inset", change =>
        {
            order.Add("source recursive");
            Assert.Same(moving, change.OldValue);
            Assert.Null(change.NewValue);
        }, ModelChangeScope.Recursive);
        using var targetRecursive = targetChanges.AddPropertyListener("inset", change =>
        {
            order.Add("target recursive");
            Assert.Null(change.OldValue);
            Assert.Same(moving, change.NewValue);
        }, ModelChangeScope.Recursive);
        using var inverse = moving.VmfChanges.AddPropertyListener("insetOf", change =>
            order.Add(change.NewValue is null ? "detach" : "attach"));

        if (throwing) Assert.Same(failure, Assert.Throws<InvalidOperationException>(() => target.Inset = moving));
        else target.Inset = moving;
        moving.Name = "after transfer";

        sourceLocal.Dispose();
        targetLocal.Dispose();
        sourceRecursive.Dispose();
        targetRecursive.Dispose();
        inverse.Dispose();
        sourceChanges.Stop();
        targetChanges.Stop();

        Assert.Equal(["source local", "source recursive", "detach", "attach", "target local", "target recursive"], order);
        Assert.Equal(["source:inset"], sourceChanges.History.Select(Describe));
        Assert.Equal(["target:inset", "name:after transfer"], targetChanges.History.Select(Describe));
        Assert.DoesNotContain(sourceChanges.History.Concat(targetChanges.History), change => change.IsInverse);
        Assert.True(Assert.Single(targetChanges.Transactions).Undo());
        Assert.True(Assert.Single(sourceChanges.Transactions).Undo());
        Assert.Same(moving, source.Inset);
        Assert.Same(source, moving.InsetOf);
        Assert.Null(target.Inset);
        Assert.Equal("moving", moving.Name);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void OrderedTransferJournalsReplayTheSourceRemovalBeforeAnObserverReplacement(bool recursive)
    {
        var source = new ArchiveNode { Name = "source" };
        var destination = new ArchiveNode { Name = "destination" };
        var moving = new ArchiveNode { Name = "moving" };
        var kept = new ArchiveNode { Name = "kept" };
        var root = ArchiveNode.NewBuilder().WithName("root").WithChildren([source, destination]).Build();
        source.Children.AddRange([moving, kept]);
        var checkpoint = ModelSnapshot.Reflection.DeepCopy(root);
        var nested = new ArchiveNode { Name = "nested" };
        var scope = recursive ? root.VmfChanges : source.VmfChanges;
        using var observer = scope.AddListener(change =>
        {
            if (ReferenceEquals(change.Source, source) && change.PropertyName == "children"
                && ReferenceEquals(source.Children[0], kept))
            {
                Assert.Same(source, moving.Parent);
                Assert.Same(moving, destination.Children[0]);
                source.Children[0] = nested;
            }
        }, recursive ? ModelChangeScope.Recursive : ModelChangeScope.Local);
        var binding = new JournalBinding("ordered-transfer-journal", 1, value => ((ArchiveNode)value).Name!);
        using var recorder = JournalRecorder<ArchiveNode>.Start(root, ModelSnapshot.Registry, binding, "initial", 0);
        destination.Children.Add(moving);
        Assert.Equal(3, root.VmfChanges.History.Count);
        Assert.Same(nested, Assert.Single(Assert.IsType<ModelCollectionChange>(root.VmfChanges.History[0]).Added).Value);
        var journal = recorder.Finish();
        Assert.Equal([JournalListEditKind.Remove, JournalListEditKind.Set, JournalListEditKind.Add],
            Assert.Single(journal.Transactions).Operations.Select(op => Assert.IsType<JournalListEdit>(op).Kind));
        var replayed = JournalReplayer.Replay(checkpoint, binding, "initial", journal, ModelSnapshot.Registry);
        Assert.Equal("nested", Assert.Single(replayed.Children[0].Children).Name);
        Assert.Equal("moving", Assert.Single(replayed.Children[1].Children).Name);
    }

    [Fact]
    public void ListReparentingExposesTheSameStagesAndKeepsUndoOrder()
    {
        var graph = new Fixture();
        var source = graph.Source;
        var destination = graph.Destination;
        var child = graph.Child;
        var other = new ArchiveNode();
        source.Children.Add(child);
        var changes = graph.Root.VmfChanges;
        var order = new List<string>();
        using var recursive = changes.AddRecursiveListener(change =>
        {
            if (ReferenceEquals(change.Source, source))
            {
                order.Add("source removed");
                Assert.Empty(source.Children);
                Assert.Same(source, child.Parent);
                Assert.Same(child, Assert.Single(destination.Children));
                var removal = Assert.IsType<ModelCollectionChange>(change);
                Assert.Equal(ModelChangeKind.ReferenceSet, removal.Kind);
                Assert.True(removal.IsContainment);
                Assert.Equal([new ModelCollectionEntry(child, 0)], removal.Removed);
                Assert.Empty(removal.Added);
                Assert.ThrowsAny<ArgumentException>(() => other.Children.Add(child));
                Assert.ThrowsAny<ArgumentException>(() => destination.Children.Remove(child));
                Assert.ThrowsAny<ArgumentException>(() => child.Parent = null);
                Assert.Empty(other.Children);
                child.Name = "during removal";
            }
            else if (ReferenceEquals(change.Source, destination))
            {
                order.Add("destination added");
                Assert.Same(destination, child.Parent);
                Assert.Same(child, Assert.Single(destination.Children));
                var addition = Assert.IsType<ModelCollectionChange>(change);
                Assert.Empty(addition.Removed);
                Assert.Equal([new ModelCollectionEntry(child, 0)], addition.Added);
            }
            else
            {
                order.Add("recursive " + change.PropertyName + " " + change.NewValue);
            }
        });
        using var inverse = child.VmfChanges.AddPropertyListener("parent", change =>
        {
            Assert.True(change.IsInverse);
            if (change.NewValue is null)
            {
                Assert.Same(source, change.OldValue);
                Assert.Null(child.Parent);
                order.Add("child detached");
            }
            else
            {
                Assert.Null(change.OldValue);
                Assert.Same(destination, change.NewValue);
                Assert.Same(destination, child.Parent);
                order.Add("child attached");
            }
        });

        changes.Start();
        destination.Children.Add(child);
        child.Name = "after move";
        recursive.Dispose();
        inverse.Dispose();
        changes.Stop();

        Assert.Equal(["source removed", "recursive name during removal", "child detached", "child attached",
            "destination added", "recursive name after move"], order);
        Assert.Equal(["name:during removal", "source:children", "destination:children", "name:after move"],
            changes.History.Select(Describe));
        Assert.True(Assert.Single(changes.Transactions).Undo());
        Assert.Same(child, Assert.Single(source.Children));
        Assert.Same(source, child.Parent);
        Assert.Empty(destination.Children);
        Assert.Equal("child", child.Name);
    }

    [Fact]
    public void SingularReplacementClearsTheFormerChildBeforeMovingTheIncomingChild()
    {
        var graph = new Fixture();
        var source = graph.Source;
        var destination = graph.Destination;
        var child = graph.Child;
        var former = new ArchiveNode { Name = "former" };
        var competitor = new ArchiveNode { Name = "competitor" };
        source.Inset = child;
        destination.Inset = former;
        var changes = graph.Root.VmfChanges;
        var order = new List<string>();
        using var recursive = changes.AddRecursiveListener(change =>
        {
            Assert.Equal("inset", change.PropertyName);
            Assert.True(change.IsContainment);
            Assert.False(change.IsInverse);
            if (ReferenceEquals(change.Source, source))
            {
                order.Add("source removed");
                Assert.Same(child, change.OldValue);
                Assert.Null(change.NewValue);
                Assert.Null(source.Inset);
                Assert.Same(source, child.InsetOf);
                Assert.ThrowsAny<ArgumentException>(() => destination.Inset = competitor);
                Assert.ThrowsAny<ArgumentException>(() => child.InsetOf = null);
                Assert.Null(destination.Inset);
                Assert.Null(competitor.InsetOf);
            }
            else if (change.NewValue is null)
            {
                order.Add("destination cleared");
                Assert.Same(destination, change.Source);
                Assert.Same(former, change.OldValue);
                Assert.Same(destination, former.InsetOf);
                Assert.Null(destination.Inset);
            }
            else
            {
                order.Add("destination added");
                Assert.Same(destination, change.Source);
                Assert.Null(change.OldValue);
                Assert.Same(child, change.NewValue);
                Assert.Same(child, destination.Inset);
                Assert.Same(destination, child.InsetOf);
            }
        });
        using var outgoing = former.VmfChanges.AddPropertyListener("insetOf", _ =>
        {
            Assert.Null(former.InsetOf);
            order.Add("former detached");
        });
        using var incoming = child.VmfChanges.AddPropertyListener("insetOf", change =>
        {
            if (change.NewValue is null)
            {
                Assert.Same(source, change.OldValue);
                Assert.Null(child.InsetOf);
                order.Add("child detached");
            }
            else
            {
                Assert.Null(change.OldValue);
                Assert.Same(destination, change.NewValue);
                Assert.Same(destination, child.InsetOf);
                Assert.Null(destination.Inset);
                Assert.ThrowsAny<ArgumentException>(() => destination.Inset = competitor);
                Assert.ThrowsAny<ArgumentException>(() => child.InsetOf = null);
                order.Add("child attached");
            }
        });

        changes.Start();
        child.InsetOf = destination;
        recursive.Dispose();
        outgoing.Dispose();
        incoming.Dispose();
        changes.Stop();

        Assert.Equal(["destination cleared", "former detached", "source removed", "child detached",
            "child attached", "destination added"], order);
        Assert.Equal(["destination:inset", "source:inset", "destination:inset"], changes.History.Select(Describe));
        Assert.True(Assert.Single(changes.Transactions).Undo());
        Assert.Same(child, source.Inset);
        Assert.Same(source, child.InsetOf);
        Assert.Same(former, destination.Inset);
        Assert.Same(destination, former.InsetOf);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void ObserverFailuresDoNotInterruptReparentingOrHideLaterEvents(bool singular)
    {
        var graph = new Fixture();
        var source = graph.Source;
        var destination = graph.Destination;
        var child = graph.Child;
        if (singular)
        {
            source.Inset = child;
        }
        else
        {
            source.Children.Add(child);
        }

        string forward = singular ? "inset" : "children";
        string reverse = singular ? "insetOf" : "parent";
        var changes = graph.Root.VmfChanges;
        var order = new List<string>();
        var initial = new InvalidOperationException("first source callback");
        var following = new NotSupportedException("second source callback");
        var detach = new ArgumentException("inverse detach callback");
        var last = new InvalidOperationException("destination callback");
        using var first = changes.AddRecursiveListener(change =>
        {
            if (ReferenceEquals(change.Source, source))
            {
                order.Add("first source");
                throw initial;
            }
            if (ReferenceEquals(change.Source, destination))
            {
                order.Add("first destination");
                throw last;
            }
            order.Add("recursive " + change.PropertyName);
        });
        using var second = changes.AddRecursiveListener(change =>
        {
            if (ReferenceEquals(change.Source, source))
            {
                order.Add("second source");
                throw following;
            }
            if (ReferenceEquals(change.Source, destination))
            {
                order.Add("second destination");
            }
        });
        using var watchInverse = child.VmfChanges.AddPropertyListener(reverse, change =>
        {
            if (change.NewValue is null)
            {
                order.Add("child detached");
                throw detach;
            }
            order.Add("child attached");
        });

        changes.Start();
        var error = Assert.Throws<InvalidOperationException>(() =>
        {
            if (singular)
            {
                destination.Inset = child;
            }
            else
            {
                destination.Children.Add(child);
            }
        });
        Assert.Same(initial, error);
        Assert.Equal<Exception>([following, detach, last], ModelChanges.GetSuppressedObserverExceptions(error));
        if (singular)
        {
            Assert.Null(source.Inset);
            Assert.Same(child, destination.Inset);
            Assert.Same(destination, child.InsetOf);
        }
        else
        {
            Assert.Empty(source.Children);
            Assert.Same(child, Assert.Single(destination.Children));
            Assert.Same(destination, child.Parent);
        }
        child.Name = "verified";
        changes.Stop();
        Assert.Equal(["first source", "second source", "child detached", "child attached", "first destination",
            "second destination", "recursive name"], order);
        Assert.Equal(["source:" + forward, "destination:" + forward, "name:verified"], changes.History.Select(Describe));
    }

    [Fact]
    public void AChildReattachedByItsDetachListenerRetainsBothScopesUntilRemovalSettles()
    {
        var former = new ArchiveNode();
        var destination = new ArchiveNode();
        var child = new ArchiveNode();
        former.Children.Add(child);

        var formerNames = new List<string?>();
        var destinationNames = new List<string?>();
        int formerEdits = 0;
        int destinationEdits = 0;
        using var watchFormer = former.VmfChanges.AddRecursiveListener(change =>
        {
            if (change.PropertyName == "name") formerNames.Add(change.NewValue as string);
            if (ReferenceEquals(change.Source, former) && change.PropertyName == "children")
            {
                formerEdits++;
                child.Name = "during old owner callback";
            }
        });
        using var watchDestination = destination.VmfChanges.AddRecursiveListener(change =>
        {
            if (change.PropertyName == "name") destinationNames.Add(change.NewValue as string);
            if (ReferenceEquals(change.Source, destination) && change.PropertyName == "children") destinationEdits++;
        });
        using var reattach = child.VmfChanges.AddPropertyListener("parent", change =>
        {
            if (change.NewValue is null)
            {
                destination.Children.Add(child);
                child.Name = "after nested attachment";
            }
        });

        Assert.True(former.Children.Remove(child));
        child.Name = "after old scope settled";
        Assert.Empty(former.Children);
        Assert.Same(child, Assert.Single(destination.Children));
        Assert.Same(destination, child.Parent);
        Assert.Equal(1, formerEdits);
        Assert.Equal(1, destinationEdits);
        Assert.Equal(["after nested attachment", "during old owner callback"], formerNames);
        Assert.Equal(["after nested attachment", "during old owner callback", "after old scope settled"], destinationNames);
    }

    private static string Describe(ModelChange change) => change.PropertyName == "name"
        ? "name:" + change.NewValue
        : ((ArchiveNode)change.Source).Name + ":" + change.PropertyName;

    private sealed class Fixture
    {
        internal ArchiveRoot Root { get; }
        internal ArchiveNode Source { get; } = new() { Name = "source" };
        internal ArchiveNode Destination { get; } = new() { Name = "destination" };
        internal ArchiveNode Child { get; } = new() { Name = "child" };

        internal Fixture()
        {
            var head = new ArchiveNode { Name = "head" };
            Root = new ArchiveRoot { Head = head };
            head.Children.Add(Source);
            head.Children.Add(Destination);
        }
    }
}
