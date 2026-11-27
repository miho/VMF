using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Genericvalues;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class GenericValueTests
{
    private const string CheckpointId = "base";
    private static readonly JournalBinding Binding = new("conformance.generic-values", 1, model => model switch
    {
        Tag tag => tag.Key ?? throw new InvalidOperationException("A tag needs a journal identity."),
        Review => "review",
        CollectionReview collection => collection.Id!,
        _ => throw new InvalidOperationException("Unknown journal model type.")
    });

    [Fact]
    public void OneGenericContractSupportsScalarValuesAndImmutableModelIdentity()
    {
        Slot<int?> numbers = new Counts();
        Slot<string?> texts = new Texts();
        Assert.Null(numbers.Current);
        Assert.Null(texts.Current);
        numbers.Current = 0;
        texts.Current = "ready";
        numbers.History.Add(null);
        texts.History.Add(null);
        Assert.Equal(0, numbers.AsReadOnly().Current);
        Assert.Equal("ready", texts.AsReadOnly().Current);
        Assert.Null(Assert.Single(numbers.AsReadOnly().History));

        var source = Specimen();
        var first = Assert.IsType<Tag>(source.Current);
        var second = Assert.IsType<Tag>(source.History[2]);
        Assert.Equal(first, second);
        Assert.NotSame(first, second);
        Slot<Tag> typed = source;
        IReadOnlySlot<Tag> view = typed.AsReadOnly();
        Assert.Same(first, view.Current);
        Assert.Same(first, view.History[0]);
        Assert.Null(view.History[1]);
        Assert.Same(second, view.History[2]);
        Assert.Equal(0, view.History.IndexOf(second));

        var registry = ModelSnapshot.Reflection;
        var field = registry.Reflect(source).FindProperty("current");
        Assert.Equal(typeof(Tag), field?.ValueType);
        Assert.Equal(SnapshotPropertyKind.Reference, field?.Kind);
        Assert.Equal(SnapshotPropertyKind.ReferenceList, registry.Reflect(source).FindProperty("history")?.Kind);
        var deep = registry.DeepCopy(source);
        Assert.NotSame(source, deep);
        Assert.NotSame(source.History, deep.History);
        Assert.Same(first, deep.Current);
        Assert.Same(second, deep.History[2]);
        var shallow = registry.ShallowCopy(source);
        Assert.Same(first, shallow.Current);
        Assert.Same(source.History, shallow.History);

        var changes = new List<ModelChange>();
        using var listener = source.VmfChanges.AddLocalListener(changes.Add);
        typed.History[0] = second;
        typed.History[0] = first;
        Assert.Equal(2, changes.Count);
        Assert.True(changes[0].IsModelReference);
        Assert.False(changes[0].CanUndo);
        Assert.Same(first, view.History[0]);
    }

    [Fact]
    public void StructuralNullOnlyReplayUsesTheReceivingListAndItsConstraints()
    {
        var tag = Tag.NewBuilder().WithKey("tag").WithText("tag").Build();
        var source = Review.NewBuilder().WithHistory([null, tag, null]).Build();
        var texts = Texts.NewBuilder().WithHistory(["first", "kept", "last"]).Build();
        var bounded = BoundedHistory.NewBuilder().WithHistory([10, 20, 30]).Build();
        var removal = Record(() => Assert.True(source.History.RemoveAll([null])));
        var events = new List<ModelListChange<string?>>();
        using (texts.History.AddListener(events.Add))
        {
            removal.Apply(texts);
        }
        var received = Assert.Single(events);
        Assert.Equal(new[] { 0, 2 }, received.Removed.Select(slot => slot.Index));
        Assert.Equal(new[] { "first", "last" }, received.Removed.Select(slot => slot.Value));
        Assert.Equal("kept", Assert.Single(texts.History));
        removal.Apply(bounded);
        Assert.Equal(20, Assert.Single(bounded.History));
        Assert.Same(tag, Assert.Single(source.History));

        Assert.Throws<ArgumentException>(() => removal.Apply(texts.AsReadOnly()));
        Assert.Equal("kept", Assert.Single(texts.History));
        Assert.Throws<ArgumentOutOfRangeException>(() => removal.Apply(texts));
        Assert.Equal("kept", Assert.Single(texts.History));

        var addition = Record(() => source.History.Insert(0, null));
        addition.Apply(texts);
        Assert.Null(texts.History[0]);
        var boundedEvents = new List<ModelListChange<int>>();
        using (bounded.History.AddListener(boundedEvents.Add))
        {
            Assert.Throws<ArgumentException>(() => addition.Apply(bounded));
        }
        Assert.Equal(20, Assert.Single(bounded.History));
        Assert.Empty(boundedEvents);

        var replacement = Record(() => source.History[0] = null);
        texts.History[0] = "replace";
        replacement.Apply(texts);
        Assert.Null(texts.History[0]);

        var modelPayload = Record(() => source.History[0] = tag);
        Assert.Throws<ArgumentException>(() => modelPayload.Apply(texts));
        Assert.Null(texts.History[0]);

        ModelChange Record(Action edit)
        {
            source.VmfChanges.Start();
            edit();
            source.VmfChanges.Stop();
            return Assert.Single(source.VmfChanges.History);
        }
    }

    [Fact]
    public void BothRuntimesExchangeTheSameV1ImmutableReferenceJournal()
    {
        var source = Specimen();
        var second = source.History[2];
        string checkpoint = ModelSnapshot.Write(source);
        string journal = JournalJson.Write(JournalRecorder<Review>.RecordFrame(source, ModelSnapshot.Registry,
            Binding, CheckpointId, 0, model =>
            {
                model.Current = second;
                model.History[0] = second;
            }));
        Assert.Same(second, source.Current);
        Assert.Contains("IMMUTABLE_REFERENCE", journal, StringComparison.Ordinal);
        Verify(checkpoint, journal);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_GENERIC_VALUES") is { } directory)
        {
            string javaCheckpoint = File.ReadAllText(Path.Combine(directory, "java-checkpoint.json"));
            string javaJournal = File.ReadAllText(Path.Combine(directory, "java-journal.json"));
            Verify(javaCheckpoint, javaJournal);
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(javaCheckpoint), JsonNode.Parse(checkpoint)));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(javaJournal), JsonNode.Parse(journal)));
            File.WriteAllText(Path.Combine(directory, "csharp-checkpoint.json"), checkpoint);
            File.WriteAllText(Path.Combine(directory, "csharp-journal.json"), journal);
        }
    }

    private static Review Specimen()
    {
        var first = Tag.NewBuilder().WithKey("a").WithText("same").Build();
        var second = Tag.NewBuilder().WithKey("b").WithText("same").Build();
        return Review.NewBuilder().WithCurrent(first).WithHistory([first, null, second, first]).Build();
    }

    [Fact]
    public void ImmutableValuesInASecondaryRolePreserveIdentityThroughReplay()
    {
        var initial = Specimen();
        var source = CollectionReview.NewBuilder().WithId("collection-review").WithCurrent(initial.Current)
            .WithHistory(initial.History).Build();
        IForwarded<Tag> role = source;
        var view = role.AsReadOnly();
        Assert.Same(source.AsReadOnly(), view);
        Assert.Same(initial.Current, view.Current);
        Assert.Same(view.Current, view.History[0]);
        Assert.Same(view.Current, ModelSnapshot.Reflection.DeepCopy(source).Current);
        Assert.Equal(SnapshotPropertyKind.ReferenceList,
            ModelSnapshot.Reflection.Reflect(source).FindProperty("history")!.Kind);
        string checkpoint = ModelSnapshot.Write(source);
        string journal = JournalJson.Write(JournalRecorder<CollectionReview>.RecordFrame(source,
            ModelSnapshot.Registry, Binding, CheckpointId, 0, model =>
            {
                IForwarded<Tag> edited = model;
                edited.Current = edited.History[2];
                edited.History[0] = edited.Current;
            }));
        VerifySecondary(checkpoint, journal);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_GENERIC_VALUES") is { } directory)
        {
            VerifySecondary(File.ReadAllText(Path.Combine(directory, "java-secondary-checkpoint.json")),
                File.ReadAllText(Path.Combine(directory, "java-secondary-journal.json")));
            File.WriteAllText(Path.Combine(directory, "csharp-secondary-checkpoint.json"), checkpoint);
            File.WriteAllText(Path.Combine(directory, "csharp-secondary-journal.json"), journal);
        }
    }

    private static void VerifySecondary(string snapshot, string journal)
    {
        var original = ModelSnapshot.Read<CollectionReview>(snapshot);
        Assert.Same(original.Current, original.History[0]);
        Assert.Same(original.Current, original.History[3]);
        Assert.Null(original.History[1]);
        var changes = JournalJson.Read(journal);
        Assert.Equal(1, changes.WireVersion);
        var result = JournalReplayer.Replay(original, Binding, CheckpointId, changes, ModelSnapshot.Registry);
        Assert.Equal("a", original.Current!.Key);
        Assert.Equal("b", result.Current!.Key);
        Assert.Same(result.Current, result.History[0]);
        Assert.Same(result.Current, result.History[2]);
        Assert.NotSame(result.Current, result.History[3]);
    }

    private static void Verify(string checkpointDocument, string journalDocument)
    {
        var checkpoint = ModelSnapshot.Read<Review>(checkpointDocument);
        var journal = JournalJson.Read(journalDocument);
        Assert.Equal(1, journal.WireVersion);
        Assert.Same(checkpoint.Current, checkpoint.History[0]);
        Assert.Same(checkpoint.Current, checkpoint.History[3]);
        Assert.NotSame(checkpoint.Current, checkpoint.History[2]);
        Assert.Null(checkpoint.History[1]);
        var restored = JournalReplayer.Replay(checkpoint, Binding, CheckpointId, journal, ModelSnapshot.Registry);
        Assert.Equal("b", restored.Current!.Key);
        Assert.Same(restored.Current, restored.History[0]);
        Assert.Same(restored.Current, restored.History[2]);
        Assert.Equal("a", restored.History[3]!.Key);
        Assert.Equal("a", checkpoint.Current!.Key);
        checkpoint.Current = checkpoint.History[2];
        Assert.Throws<InvalidOperationException>(() =>
            JournalReplayer.Replay(checkpoint, Binding, CheckpointId, journal, ModelSnapshot.Registry));
        Assert.Same(checkpoint.History[2], checkpoint.Current);
    }
}
