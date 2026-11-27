using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;
using BehaviorModels = Eu.Mihosoft.Vmf.Conformance.Behavior.ModelSnapshot;
using ValueModels = Eu.Mihosoft.Vmf.Conformance.Values.ModelSnapshot;

namespace Vmf.CSharp.Conformance;

public sealed class JournalRegistryCompositionTests
{
    private const string Model = "conformance.combined-registry";
    private const string Base = "in-memory";
    private const string Archive = "eu.mihosoft.vmf.conformance.values.ArchiveNode";

    [Fact]
    public void CombinedGeneratedRegistriesKeepManagedDefaultsInHistoryAndRecorderShadow()
    {
        var aliased = ValueModels.Registry.WithTypeAliases(new Dictionary<string, string> { ["archive-app"] = Archive });
        var registry = SnapshotRegistry.CombineCanonical(aliased, BehaviorModels.Registry, ValueModels.Registry);
        var checkpoint = new CollectionProfile();
        Levels(checkpoint).SetDefault(new[] { 2.5 });
        var binding = new JournalBinding(Model, 1, _ => "profile");
        var empty = new ChangeJournal(Model, 1, "profile", Base, 0, []);
        var history = new JournalHistory<CollectionProfile>(binding, registry);
        history.AddSegment(Base, checkpoint, empty);
        var working = history.Seek(0);
        Assert.NotSame(working, checkpoint);
        Assert.False(Levels(working.AsReadOnly()).IsSet);
        Assert.Equal(2.5, Assert.Single(working.Levels));

        int inspected = 0;
        var recordingBinding = new JournalBinding(Model, 1, candidate =>
        {
            if (!ReferenceEquals(candidate, working))
            {
                Assert.Equal(new object?[] { 2.5 }, Assert.IsAssignableFrom<IEnumerable<object?>>(Levels(candidate).DefaultValue));
                inspected++;
            }
            return "profile";
        });
        var journal = JournalRecorder<CollectionProfile>.RecordFrame(working, registry, recordingBinding, Base, 0,
            profile => profile.Levels.Add(3.5));
        Assert.True(inspected > 0);
        Assert.Equal(1, journal.WireVersion);
        var copy = JournalReplayer.Replay(checkpoint, binding, Base, journal, registry);
        Assert.Equal([2.5, 3.5], copy.Levels.Order());
        Levels(copy).Reset();
        Assert.Equal(2.5, Assert.Single(copy.Levels));
        Assert.False(Levels(copy.AsReadOnly()).IsSet);
        Assert.Equal(2.5, Assert.Single(history.Seek(0).Levels));
    }

    [Fact]
    public void CombinedGeneratedRegistryStillCopiesADeepCheckpointNatively()
    {
        var registry = SnapshotRegistry.CombineCanonical(ValueModels.Registry, BehaviorModels.Registry);
        var tail = new ArchiveNode { Name = "tail" };
        var root = tail;
        for (int index = 499; index >= 0; index--)
        {
            var owner = new ArchiveNode { Name = $"parent-{index}" };
            owner.Children.Add(root);
            root = owner;
        }
        root.Anchor = tail;
        Assert.Throws<InvalidOperationException>(() => SnapshotJson.Write(root, registry));
        var binding = new JournalBinding(Model, 1, candidate => ((ArchiveNode)candidate).Name!);
        var journal = new ChangeJournal(Model, 1, root.Name!, Base, 0, []);
        var history = new JournalHistory<ArchiveNode>(binding, registry);
        history.AddSegment(Base, root, journal);
        var copy = history.Seek(0);
        var selected = copy;
        for (int index = 0; index < 500; index++) selected = Assert.IsType<ArchiveNode>(Assert.Single(selected.Children));
        Assert.Same(selected, copy.Anchor);
        Assert.NotSame(tail, selected);
        Assert.Same(tail, root.Anchor);
    }

    [Fact]
    public void SameClrTypeAndNameCannotBorrowAnotherDescriptorsReflection()
    {
        int creations = 0;
        var custom = new SnapshotRegistry([
            new SnapshotType("eu.mihosoft.vmf.conformance.values.CollectionProfile", typeof(CollectionProfile), () =>
            {
                creations++;
                return new CollectionProfile();
            }, [
                new SnapshotProperty("levels", SnapshotPropertyKind.ScalarSet, typeof(double),
                    static owner => ((CollectionProfile)owner).Levels,
                    add: static (owner, value) => ((CollectionProfile)owner).Levels.Add((double)value)),
            ]),
        ]);
        var registry = SnapshotRegistry.CombineCanonical(custom, ValueModels.Registry);
        var checkpoint = new CollectionProfile();
        Levels(checkpoint).SetDefault(new[] { 2.5 });
        var binding = new JournalBinding(Model, 1, _ => "profile");
        var empty = new ChangeJournal(Model, 1, "profile", Base, 0, []);
        var restored = JournalReplayer.Replay(checkpoint, binding, Base, empty, registry);
        Assert.Equal(1, creations);
        Assert.Equal(2.5, Assert.Single(restored.Levels));
        Assert.True(Levels(restored).IsSet);
    }

    private static ModelProperty Levels(object value) => ValueModels.Reflection.Reflect(value).FindProperty("levels")!;
}
