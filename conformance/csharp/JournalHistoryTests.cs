using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class JournalHistoryTests
{
    private const string Model = "conformance.journal-history";
    private static readonly JournalBinding Binding = new(Model, 1,
        model => (model as ArchiveNode)?.Name ?? throw new ArgumentException("Checkpoint node needs an ID."));

    [Fact]
    public void SeeksOverlappingSegmentsAndReturnsIndependentGraphs()
    {
        var earlier = NewArchive();
        var longer = Record(earlier, "earlier", 20,
            root => root.Anchor = root.Children[0],
            root => root.Anchor = root.Inset,
            root => root.Anchor = null);
        var later = NewArchive();
        later.Anchor = later.Inset;
        var history = NewHistory();
        history.AddSegment("later", later, Journal("later", 22));
        history.AddSegment("earlier", earlier, longer);

        var middle = history.Seek(21);
        var overlap = history.Seek(22);
        Assert.Same(Assert.Single(middle.Children), middle.Anchor);
        Assert.Same(overlap.Inset, overlap.Anchor);
        Assert.Null(history.Seek(23).Anchor);
        Assert.Same(middle, Assert.Single(middle.Children).Parent);
        Assert.Same(middle, middle.Inset!.InsetOf);
        Assert.Same(middle.Anchor, middle.Trail[0]);
        Assert.Same(middle.Anchor, middle.Trail[3]);
        Assert.Null(middle.Trail[1]);
        Assert.NotSame(earlier.Children[0], middle.Children[0]);
        Assert.NotSame(later.Inset, overlap.Inset);
        Assert.NotSame(middle.Inset, overlap.Inset);

        earlier.Anchor = earlier.Inset;
        later.Inset = null;
        middle.Children.Clear();
        overlap.Anchor = null;

        Assert.Null(history.Seek(20).Anchor);
        Assert.Equal("left", history.Seek(21).Anchor!.Name);
        Assert.Equal("right", history.Seek(22).Anchor!.Name);
        Assert.Throws<ArgumentOutOfRangeException>(() => history.Seek(19));
        Assert.Throws<ArgumentOutOfRangeException>(() => history.Seek(24));
    }

    [Fact]
    public void ValidatesEachSegmentAndRejectsDuplicateCheckpointSequences()
    {
        var checkpoint = NewArchive();
        var journal = Journal("known", 20);
        var history = NewHistory();
        Assert.Throws<ArgumentOutOfRangeException>(() => history.Seek(20));
        Assert.Throws<InvalidOperationException>(() => history.AddSegment("wrong", checkpoint, journal));
        Assert.Throws<InvalidOperationException>(() => history.AddSegment("known", NewArchive("other"), journal));
        Assert.Throws<InvalidOperationException>(() => history.AddSegment("known", checkpoint,
            new ChangeJournal("another-model", 1, "main", "known", 20, [])));
        Assert.Throws<InvalidOperationException>(() => history.AddSegment("known", checkpoint,
            new ChangeJournal(Model, 2, "main", "known", 20, [])));
        Assert.Throws<ArgumentOutOfRangeException>(() => history.Seek(20));

        history.AddSegment("known", checkpoint, journal);
        Assert.Throws<ArgumentException>(() => history.AddSegment("duplicate", checkpoint, Journal("duplicate", 20)));
        var other = new ArchiveNode { Name = "other" };
        var differentRoot = new ChangeJournal(Model, 1, "other", "foreign-root", 21, []);
        Assert.Throws<ArgumentException>(() => history.AddSegment("foreign-root", other, differentRoot));
        var independent = NewHistory();
        independent.AddSegment("foreign-root", other, differentRoot);
        Assert.Equal("other", independent.Seek(21).Name);
        Assert.Null(history.Seek(20).Anchor);
        Assert.Throws<ArgumentOutOfRangeException>(() => history.Seek(21));
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void RejectsChangedDescendantBindingsBeforeReplayOrRecording(bool changeId)
    {
        var checkpoint = NewArchive();
        var originalChild = Assert.Single(checkpoint.Children);
        var binding = new JournalBinding(Model, 1, model => changeId && ReferenceEquals(model, originalChild)
            ? "left-live" : ((ArchiveNode)model).Name!)
            .WithObjectTypes(model => !changeId && ReferenceEquals(model, originalChild) ? "live-node" : "node",
                (_, _) => throw new InvalidOperationException("An empty journal cannot create objects."));
        string error = changeId ? "changed its IDs" : "changed an object type";

        var replayFailure = Assert.Throws<InvalidOperationException>(() =>
            JournalReplayer.Replay(checkpoint, binding, "known", Journal("known", 20), ModelSnapshot.Registry));
        Assert.Contains(error, replayFailure.Message);
        var recordingFailure = Assert.Throws<InvalidOperationException>(() =>
        {
            using var unexpected = JournalRecorder<ArchiveNode>.Start(checkpoint, ModelSnapshot.Registry, binding, "known", 20);
        });
        Assert.Contains(error, recordingFailure.Message);
        Assert.False(checkpoint.VmfChanges.IsRecording);
        Assert.Same(originalChild, Assert.Single(checkpoint.Children));
        Assert.Same(checkpoint, originalChild!.Parent);
    }

    [Fact]
    public void OnlyTheSelectedPrefixIsValidatedAndAStaleOverlapIsReported()
    {
        var earlier = NewArchive();
        var history = NewHistory();
        history.AddSegment("earlier", earlier, Record(earlier, "earlier", 30,
            root => root.Anchor = root.Children[0], root => root.Anchor = root.Inset));
        Assert.Equal("right", history.Seek(32).Anchor!.Name);

        var later = history.Seek(31);
        var staleChange = new JournalSet("main", "anchor", JournalValue.Reference("right"), JournalValue.FromScalar(null));
        history.AddSegment("later", later, Journal("later", 31, new JournalFrame(32, [staleChange])));

        Assert.Equal("left", history.Seek(31).Anchor!.Name);
        Assert.Throws<InvalidOperationException>(() => history.Seek(32));
        Assert.Equal("left", history.Seek(31).Anchor!.Name);
    }

    [Fact]
    public void CoversTheLargestSequenceWithoutOverflow()
    {
        var checkpoint = NewArchive();
        var history = NewHistory();
        var journal = Record(checkpoint, "near-limit", long.MaxValue - 1, root => root.Anchor = root.Inset);
        history.AddSegment("near-limit", checkpoint, journal);

        Assert.Null(history.Seek(long.MaxValue - 1).Anchor);
        Assert.Equal("right", history.Seek(long.MaxValue).Anchor!.Name);
        Assert.Throws<ArgumentOutOfRangeException>(() => history.Seek(long.MinValue));

        var atLimit = history.Seek(long.MaxValue);
        history.AddSegment("limit", atLimit, Journal("limit", long.MaxValue));
        Assert.Equal("right", history.Seek(long.MaxValue).Anchor!.Name);
    }

    [Fact]
    public void LiveCheckpointCopiesKeepOverridesOnlyForManagedCollections()
    {
        const string id = "managed-defaults";
        const long sequence = 70;
        var checkpoint = new CollectionProfile();
        checkpoint.Messages.AddRange(["kept", null]);
        DefaultField(checkpoint, "messages").SetDefault(new[] { "unused" });
        DefaultField(checkpoint, "counts").SetDefault(new[] { 2, 3 });
        DefaultField(checkpoint, "levels").SetDefault(new[] { 2.5 });
        DefaultField(checkpoint, "phases").SetDefault(new[] { "prepare", "finish" });
        DefaultField(checkpoint, "options").SetDefault(new Dictionary<string, bool?> { ["ready"] = true, ["absent"] = null });
        var binding = new JournalBinding(Model, 1, _ => "profile");
        var empty = new ChangeJournal(Model, 1, "profile", id, sequence, []);
        var direct = JournalReplayer.Replay(checkpoint, binding, id, empty, ModelSnapshot.Registry);
        VerifyManagedDefaults(direct);
        Assert.Equal(new object?[] { "unused" },
            Assert.IsAssignableFrom<IEnumerable<object?>>(DefaultField(checkpoint, "messages").DefaultValue));
        Assert.Empty(Assert.IsAssignableFrom<IEnumerable<object?>>(DefaultField(direct, "messages").DefaultValue));

        var initial = new JournalHistory<CollectionProfile>(binding, ModelSnapshot.Registry);
        initial.AddSegment(id, checkpoint, empty);
        var working = initial.Seek(sequence);
        VerifyManagedDefaults(working);
        int shadowInspections = 0;
        var recordingBinding = new JournalBinding(Model, 1, candidate =>
        {
            if (!ReferenceEquals(candidate, working))
            {
                VerifyManagedDefaults(Assert.IsType<CollectionProfile>(candidate));
                shadowInspections++;
            }
            return "profile";
        });
        using var recorder = JournalRecorder<CollectionProfile>.Start(working, ModelSnapshot.Registry, recordingBinding, id, sequence);
        working.Counts.Add(9);
        var journal = recorder.Finish();
        Assert.True(shadowInspections > 0);
        Assert.Equal(1, journal.WireVersion);

        var hydrated = ModelSnapshot.Read<CollectionProfile>(ModelSnapshot.Write(checkpoint));
        Assert.True(DefaultField(hydrated, "counts").IsSet);
        var plain = JournalRecorder<CollectionProfile>.RecordFrame(hydrated, ModelSnapshot.Registry, binding, id,
            sequence, profile => profile.Counts.Add(9));
        Assert.Equal(JournalJson.Write(plain), JournalJson.Write(journal));
        Assert.Equal(JournalYaml.Write(plain), JournalYaml.Write(journal));
        Assert.Equal(JournalXml.Write(plain), JournalXml.Write(journal));

        var history = new JournalHistory<CollectionProfile>(binding, ModelSnapshot.Registry);
        history.AddSegment(id, checkpoint, JournalJson.Read(JournalJson.Write(journal)));
        DefaultField(checkpoint, "counts").SetDefault(new[] { 12 });
        var replayed = history.Seek(sequence + 1);
        Assert.Equal([2, 3, 9], replayed.Counts);
        Assert.True(DefaultField(replayed.AsReadOnly(), "counts").IsSet);
        DefaultField(replayed, "counts").Reset();
        Assert.Equal([2, 3], replayed.Counts);
        Assert.False(DefaultField(replayed.AsReadOnly(), "counts").IsSet);
        replayed.Options.Clear();
        Assert.True(checkpoint.Options.ContainsKey("ready"));
        Assert.Equal([2, 3, 9], history.Seek(sequence + 1).Counts);
        Assert.Equal([12], checkpoint.Counts);
        VerifyManagedDefaults(history.Seek(sequence));
    }

    [Fact]
    public void ScalarOverrideKeepsTheLiveValueAndReturnsToItsDeclaredDefaultInReplay()
    {
        var checkpoint = new DefaultSettings();
        DefaultField(checkpoint, "limit").SetDefault(8);
        var binding = new JournalBinding(Model, 1, _ => "settings");
        var journal = new ChangeJournal(Model, 1, "settings", "scalar", 0, []);
        var copy = JournalReplayer.Replay(checkpoint, binding, "scalar", journal, ModelSnapshot.Registry);
        Assert.Equal(8, copy.Limit);
        Assert.Equal(7, DefaultField(copy, "limit").DefaultValue);
        Assert.True(DefaultField(copy, "limit").IsSet);
        DefaultField(copy, "limit").Reset();
        Assert.Equal(7, copy.Limit);
        Assert.Equal(8, checkpoint.Limit);
        Assert.False(DefaultField(checkpoint, "limit").IsSet);
    }

    private static void VerifyManagedDefaults(CollectionProfile profile)
    {
        foreach (var view in new object[] { profile, profile.AsReadOnly() })
        {
            Assert.Equal(new object?[] { 2, 3 }, Assert.IsAssignableFrom<IEnumerable<object?>>(DefaultField(view, "counts").DefaultValue));
            Assert.Equal(new object?[] { 2.5 }, Assert.IsAssignableFrom<IEnumerable<object?>>(DefaultField(view, "levels").DefaultValue));
            Assert.Equal(new object?[] { "prepare", "finish" },
                Assert.IsAssignableFrom<IEnumerable<object?>>(DefaultField(view, "phases").DefaultValue));
            var options = Assert.IsAssignableFrom<IReadOnlyDictionary<object, object?>>(DefaultField(view, "options").DefaultValue);
            Assert.Equal(true, options["ready"]);
            Assert.Null(options["absent"]);
            foreach (var name in new[] { "levels", "phases", "options" }) Assert.False(DefaultField(view, name).IsSet);
        }
        Assert.Equal(new string?[] { "kept", null }, profile.Messages);
    }

    private static ModelProperty DefaultField(object model, string name) => ModelSnapshot.Reflection.Reflect(model).FindProperty(name)!;

    [Theory]
    [InlineData(499)]
    [InlineData(500)]
    [InlineData(750)]
    public void InMemoryHistoryCanRetryAfterDeepCheckpointReplayFails(int depth)
    {
        var tail = new ArchiveNode { Name = $"child-{depth}" };
        var checkpoint = tail;
        for (int index = depth - 1; index >= 0; index--)
        {
            var parent = new ArchiveNode { Name = index == 0 ? "main" : $"child-{index}" };
            parent.Children.Add(checkpoint);
            checkpoint = parent;
        }
        checkpoint.Anchor = tail;
        checkpoint.Trail.AddRange([tail, null, tail]);
        if (depth == 499) Assert.NotEmpty(ModelSnapshot.Write(checkpoint));
        else Assert.Throws<InvalidOperationException>(() => ModelSnapshot.Write(checkpoint));

        const string id = "deep";
        const long sequence = 50;
        var initial = NewHistory();
        initial.AddSegment(id, checkpoint, Journal(id, sequence));
        var working = initial.Seek(sequence);
        var workingTail = CheckDeepGraph(working, depth);
        Assert.NotSame(tail, workingTail);
        Assert.Same(workingTail, working.Anchor);

        using var recorder = JournalRecorder<ArchiveNode>.Start(working, ModelSnapshot.Registry, Binding, id, sequence);
        working.Anchor = working;
        var recorded = recorder.Finish();
        Assert.Equal(1, recorded.WireVersion);
        var validFrame = Assert.Single(recorded.Transactions);
        Assert.IsType<JournalSet>(Assert.Single(validFrame.Operations));
        var stale = new JournalSet("main", "anchor", JournalValue.Reference(tail.Name!), JournalValue.FromScalar(null));
        var invalid = Journal(id, sequence, validFrame, new JournalFrame(sequence + 2, [stale]));
        var history = NewHistory();
        history.AddSegment(id, checkpoint, invalid);
        for (int attempt = 0; attempt < 2; attempt++)
        {
            var rejectedReplay = Assert.Throws<InvalidOperationException>(() =>
                JournalReplayer.Replay(checkpoint, Binding, id, invalid, ModelSnapshot.Registry));
            var rejectedSeek = Assert.Throws<InvalidOperationException>(() => history.Seek(sequence + 2));
            Assert.Contains("Journal value disagrees with the model at 'main.anchor'", rejectedReplay.Message);
            Assert.Equal(rejectedReplay.Message, rejectedSeek.Message);
            Assert.Same(tail, CheckDeepGraph(checkpoint, depth));
            Assert.Same(tail, checkpoint.Anchor);

            var baseline = history.Seek(sequence);
            Assert.Same(CheckDeepGraph(baseline, depth), baseline.Anchor);
            Assert.NotSame(tail, baseline.Anchor);
            var replayed = history.Seek(sequence + 1);
            Assert.Same(replayed, replayed.Anchor);
            Assert.NotSame(baseline.Anchor, CheckDeepGraph(replayed, depth));
            replayed.Trail.Clear();
        }
    }

    private static ArchiveNode CheckDeepGraph(ArchiveNode root, int depth)
    {
        var tail = root;
        for (int index = 0; index < depth; index++)
        {
            var parent = tail;
            tail = Assert.IsType<ArchiveNode>(Assert.Single(parent.Children));
            Assert.Same(parent, tail.Parent);
        }
        Assert.Empty(tail.Children);
        Assert.Equal($"child-{depth}", tail.Name);
        Assert.Equal(3, root.Trail.Count);
        Assert.Same(tail, root.Trail[0]);
        Assert.Null(root.Trail[1]);
        Assert.Same(tail, root.Trail[2]);
        return tail;
    }

    [Fact]
    public async Task ConcurrentSeeksAndAddsPreserveCheckpointsAndRejectDuplicateSequences()
    {
        var history = NewHistory();
        var baseline = NewArchive();
        history.AddSegment("initial", baseline, Journal("initial", 0));
        var operations = Enumerable.Range(1, 16).Select(sequence => Task.Run(() =>
        {
            var checkpoint = history.Seek(0);
            checkpoint.Anchor = checkpoint.Inset;
            history.AddSegment("parallel", checkpoint, Journal("parallel", sequence));
            checkpoint.Anchor = null;
            var restored = history.Seek(sequence);
            Assert.Same(restored.Inset, restored.Anchor);
            Assert.Null(history.Seek(0).Anchor);
        }));
        await Task.WhenAll(operations);

        var duplicateAttempts = Enumerable.Range(0, 8).Select(_ => Task.Run(() =>
        {
            try
            {
                history.AddSegment("contested", baseline, Journal("contested", 100));
                return true;
            }
            catch (ArgumentException)
            {
                return false;
            }
        }));
        Assert.Single(await Task.WhenAll(duplicateAttempts), accepted => accepted);
        Assert.Null(history.Seek(100).Anchor);
    }

    private static JournalHistory<ArchiveNode> NewHistory() => new(Binding, ModelSnapshot.Registry);

    private static ChangeJournal Journal(string id, long sequence, params JournalFrame[] frames) =>
        new(Model, 1, "main", id, sequence, frames);

    private static ChangeJournal Record(ArchiveNode checkpoint, string id, long sequence, params Action<ArchiveNode>[] edits)
    {
        var working = ModelSnapshot.Read<ArchiveNode>(ModelSnapshot.Write(checkpoint));
        using var recorder = JournalRecorder<ArchiveNode>.Start(working, ModelSnapshot.Registry, Binding, id, sequence);
        foreach (var edit in edits)
        {
            edit(working);
            recorder.PublishFrame();
        }
        return recorder.Finish();
    }

    private static ArchiveNode NewArchive(string id = "main")
    {
        var root = new ArchiveNode { Name = id };
        var left = new ArchiveNode { Name = "left" };
        var right = new ArchiveNode { Name = "right" };
        root.Children.Add(left);
        root.Inset = right;
        root.Trail.AddRange([left, null, right, left]);
        return root;
    }
}
