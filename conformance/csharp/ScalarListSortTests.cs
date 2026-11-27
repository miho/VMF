using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ScalarListSortTests
{
    private const string Checkpoint = "scalar-sort-0";
    private static readonly JournalBinding Binding = new("conformance.scalar-sort", 1, _ => "profile");
    private static int CompareMessages(string? left, string? right) => Rank(left).CompareTo(Rank(right));
    private static int Rank(string? value) => value is null ? 1 : value == "a" ? 0 : 2;

    [Fact]
    public void NativeSortUndoRedoAndCrossOwnerApplyUseTheCollectionShape()
    {
        VerifyNative(model => model.Messages, view => view.Messages, ["c", null, "a"], ["a", null, "c"],
            ["x", "retained", "y"], ["a", "retained", "c"], CompareMessages, [0, 2]);
        VerifyNative(model => model.Phases, view => view.Phases, ["c", "b", "a"], ["a", "b", "c"],
            ["x", "replaced", "y"], ["a", "b", "c"], StringComparer.Ordinal.Compare, [0, 1, 2]);
        VerifyNative(model => model.Counts, view => view.Counts, [3, 2, 1], [1, 2, 3], [6, 5, 4], [1, 2, 3],
            Comparer<int>.Default.Compare, [0, 1, 2]);
    }

    [Fact]
    public void OrdinarySortPreservesStableTiesAndDetectsDistinctButEqualStringInstances()
    {
        var messages = new CollectionProfile().Messages;
        var first = new string('x', 1);
        var second = new string('x', 1);
        messages.AddRange([first, null, second]);
        var observed = new List<ModelListChange<string?>>();
        using var watch = messages.AddListener(observed.Add);
        messages.Sort((left, right) => (left is null).CompareTo(right is null));
        Assert.Same(first, messages[0]);
        Assert.Same(second, messages[1]);
        messages.Clear();
        messages.AddRange([first, null, second]);
        observed.Clear();

        messages.Sort((left, right) => IdentityRank(left).CompareTo(IdentityRank(right)));
        Assert.Same(second, messages[0]);
        Assert.Same(first, messages[2]);
        Assert.Equal([0, 2], Assert.Single(observed).Added.Select(entry => entry.Index));

        int IdentityRank(string? value) => ReferenceEquals(value, second) ? 0 : value is null ? 1 : 2;
    }

    [Fact]
    public void OrdinaryFloatingSortKeepsTheOriginalBitPatternsAndSparsePositions()
    {
        const long nan1 = 0x7ff8000000000001;
        const long nan2 = 0x7ff8000000000002;
        var readings = new CollectionProfile().Readings;
        readings.AddRange([0d, null, BitConverter.Int64BitsToDouble(long.MinValue),
            BitConverter.Int64BitsToDouble(nan1), BitConverter.Int64BitsToDouble(nan2)]);
        var observed = new List<ModelListChange<double?>>();
        using var watch = readings.AddListener(observed.Add);
        readings.Sort((left, right) => FloatingRank(left).CompareTo(FloatingRank(right)));
        Assert.Equal([0, 2, 3, 4], Assert.Single(observed).Added.Select(entry => entry.Index));
        Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(readings[0]!.Value));
        Assert.Null(readings[1]);
        Assert.Equal(0, BitConverter.DoubleToInt64Bits(readings[2]!.Value));
        Assert.Equal(nan2, BitConverter.DoubleToInt64Bits(readings[3]!.Value));
        Assert.Equal(nan1, BitConverter.DoubleToInt64Bits(readings[4]!.Value));

        static int FloatingRank(double? value) => value is null ? 1 : BitConverter.DoubleToInt64Bits(value.Value) switch
        {
            long.MinValue => 0,
            0 => 2,
            nan2 => 3,
            _ => 4
        };
    }

    [Fact]
    public void SortV1WiresReplayAcrossBothTargetsAndAllFormats()
    {
        var profile = new CollectionProfile();
        profile.Messages.AddRange(["c", null, "a"]);
        profile.Phases.AddRange(["c", "b", "a"]);
        profile.Counts.AddRange([3, 2, 1]);
        profile.Levels.Add(0);
        profile.Limits["limit"] = 1L;
        string checkpoint = ModelSnapshot.Write(profile);
        using var recorder = JournalRecorder<CollectionProfile>.Start(profile, ModelSnapshot.Registry, Binding, Checkpoint, 0);
        profile.Messages.Sort(CompareMessages);
        profile.Phases.Sort(StringComparer.Ordinal.Compare);
        profile.Counts.Sort(Comparer<int>.Default.Compare);
        var journal = recorder.Finish();

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_COLLECTIONS");
        if (exchange is not null)
            File.WriteAllText(Path.Combine(exchange, "csharp-scalar-sort-checkpoint.json"), checkpoint);
        (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[] formats =
        [
            ("json", JournalJson.Write, JournalJson.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read)
        ];
        foreach (var (format, write, read) in formats)
        {
            string wire = write(journal);
            Replay(checkpoint, read(wire));
            if (exchange is null) continue;
            File.WriteAllText(Path.Combine(exchange, $"csharp-scalar-sort.{format}"), wire);
            var java = read(File.ReadAllText(Path.Combine(exchange, $"java-scalar-sort.{format}")));
            Assert.Equal(JournalJson.Write(journal), JournalJson.Write(java));
            Replay(File.ReadAllText(Path.Combine(exchange, "java-scalar-sort-checkpoint.json")), java);
        }
    }

    private static void VerifyNative<T>(Func<CollectionProfile, IModelList<T>> property,
        Func<IReadOnlyCollectionProfile, IReadOnlyList<T>> projection,
        T[] original, T[] sorted, T[] targetValues, T[] expectedTarget, Comparison<T> comparison, int[] positions)
    {
        var source = new CollectionProfile();
        var target = new CollectionProfile();
        var list = property(source);
        var view = projection(source.AsReadOnly());
        var otherList = property(target);
        foreach (var value in original) list.Add(value);
        foreach (var value in targetValues) otherList.Add(value);
        var sourceEdits = new List<int[]>();
        var projectedEdits = new List<int[]>();
        var targetEdits = new List<int[]>();
        using var sourceWatch = list.AddListener(change => sourceEdits.Add(change.Added.Select(entry => entry.Index).ToArray()));
        using var viewWatch = view.AddListener(change => projectedEdits.Add(change.Added.Select(entry => entry.Index).ToArray()));
        using var targetWatch = otherList.AddListener(change => targetEdits.Add(change.Added.Select(entry => entry.Index).ToArray()));

        source.VmfChanges.Start();
        list.Sort(comparison);
        list.Sort(comparison);
        var transaction = source.VmfChanges.PublishTransaction()!;
        source.VmfChanges.Stop();
        Assert.Equal(sorted, list);
        Assert.Equal(positions, Assert.Single(sourceEdits));
        Assert.Equal(positions, Assert.Single(projectedEdits));
        var change = Assert.IsType<ModelCollectionChange>(Assert.Single(transaction.Changes));

        sourceEdits.Clear();
        projectedEdits.Clear();
        Assert.True(transaction.Undo());
        Assert.Equal(original, list);
        CheckReplayEvents(sourceEdits, positions);
        CheckReplayEvents(projectedEdits, positions);
        sourceEdits.Clear();
        projectedEdits.Clear();
        Assert.True(transaction.Redo());
        Assert.Equal(sorted, list);
        CheckReplayEvents(sourceEdits, positions);
        CheckReplayEvents(projectedEdits, positions);
        change.Apply(target);
        Assert.Equal(expectedTarget, otherList);
        CheckReplayEvents(targetEdits, positions);
        Assert.Equal(sorted, list);
    }

    private static void CheckReplayEvents(List<int[]> observed, int[] positions)
    {
        if (positions.Length == 3) Assert.Equal(positions, Assert.Single(observed));
        else Assert.Collection(observed, first => Assert.Equal([0], first), last => Assert.Equal([2], last));
    }

    private static void Replay(string checkpoint, ChangeJournal journal)
    {
        Assert.Equal(1, journal.WireVersion);
        var operations = Assert.Single(journal.Transactions).Operations.Cast<JournalListEdit>();
        Assert.Collection(operations, edit => VerifyEdit(edit, [0, 2]),
            edit => VerifyEdit(edit, [0, 1, 2]), edit => VerifyEdit(edit, [0, 1, 2]));
        var original = ModelSnapshot.Read<CollectionProfile>(checkpoint);
        var sorted = JournalReplayer.Replay(original, Binding, Checkpoint, journal, ModelSnapshot.Registry);
        Assert.Equal(new string?[] { "a", null, "c" }, sorted.Messages.AsEnumerable());
        Assert.Equal(["a", "b", "c"], sorted.Phases.ToArray());
        Assert.Equal([1, 2, 3], sorted.Counts.ToArray());
        Assert.Equal(new string?[] { "c", null, "a" }, original.Messages.AsEnumerable());
        Assert.Equal(["c", "b", "a"], original.Phases.ToArray());
        Assert.Equal([3, 2, 1], original.Counts.ToArray());

        static void VerifyEdit(JournalListEdit edit, int[] positions)
        {
            Assert.Equal(JournalListEditKind.Set, edit.Kind);
            Assert.Equal(positions, edit.Indices);
        }
    }
}
