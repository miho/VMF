using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ListPredicateTests
{
    private const string Checkpoint = "list-predicate-0";
    private static readonly JournalBinding Binding = new("conformance.list-predicate", 1, model => model switch
    {
        CollectionProfile => "profile",
        ArchiveRoot => "archive",
        ArchiveNode { Name: { } name } => name,
        _ => throw new ArgumentException("Expected a predicate fixture model.", nameof(model))
    });

    [Fact]
    public void PredicateFailureDoesNotPublishOrRemoveAnySlots()
    {
        var profile = Profile();
        var events = new List<ModelListChange<string?>>();
        using var subscribed = profile.Messages.AddListener(events.Add);
        using var recorder = JournalRecorder<CollectionProfile>.Start(profile, ModelSnapshot.Registry, Binding, Checkpoint, 0);
        Assert.Throws<InvalidOperationException>(() => profile.Messages.RemoveWhere(value => value is null
            ? throw new InvalidOperationException("predicate failed")
            : true));
        Assert.Equal(0, profile.Messages.RemoveWhere(_ => false));
        Assert.Equal(new string?[] { "same", null, "same", "held", "cut" }, profile.Messages.AsEnumerable());
        Assert.Empty(events);
        Assert.Empty(profile.VmfChanges.History);
        Assert.Empty(recorder.Finish().Transactions);
    }

    [Fact]
    public void CheckedUniquePredicateUndoRestoresTheOriginalSparsePositionsInOneEdit()
    {
        var model = Profile();
        var order = new List<string>();
        var view = model.AsReadOnly().Phases;
        using var projected = view.AddListener(edit => order.Add("view:" + Positions(edit)));
        using var writable = model.Phases.AddListener(edit => order.Add("mutable:" + Positions(edit)));
        model.VmfChanges.Start();
        Assert.Equal(2, model.Phases.RemoveWhere(value => value != "keep"));
        var change = Assert.IsType<ModelCollectionChange>(Assert.Single(model.VmfChanges.History));
        model.VmfChanges.Stop();
        Assert.Equal(["view:remove[0,2]", "mutable:remove[0,2]"], order);
        order.Clear();
        Assert.True(change.Undo());
        Assert.Equal(["a", "keep", "c"], view.AsEnumerable());
        Assert.Equal(["view:add[0,2]", "mutable:add[0,2]"], order);
        order.Clear();
        Assert.True(change.Redo());
        Assert.Equal(["keep"], view.AsEnumerable());
        Assert.Equal(["view:remove[0,2]", "mutable:remove[0,2]"], order);

        model.Phases[0] = "a";
        Assert.False(change.CanUndo);
    }

    [Fact]
    public void ScalarAndReferencePredicatesExchangeOneSparseEditPerOperation()
    {
        Exchange("scalars", Profile(), model =>
        {
            var shared = model.Messages[0];
            AssertSparse(model.Messages, model.AsReadOnly().Messages, AtEnds(), "0,4");
            Assert.Same(shared, model.Messages[1]);
            AssertSparse(model.Phases, model.AsReadOnly().Phases, value => value != "keep", "0,2");
            AssertSparse(model.Counts, model.AsReadOnly().Counts, value => value != 2, "0,2");
        }, (before, after) =>
        {
            Assert.Equal(new string?[] { "same", null, "same", "held", "cut" }, before.Messages.AsEnumerable());
            Assert.Equal(new string?[] { null, "same", "held" }, after.Messages.AsEnumerable());
            Assert.Equal(["a", "keep", "c"], before.Phases.AsEnumerable());
            Assert.Equal(["keep"], after.Phases.AsEnumerable());
            Assert.Equal([1, 2, 3], before.Counts.AsEnumerable());
            Assert.Equal([2], after.Counts.AsEnumerable());
        }, [0, 4], [0, 2], [0, 2]);
        Exchange("references", Archive(), model =>
        {
            var head = model.Head!;
            IModelList<ArchiveNode?> trail = head.Trail;
            AssertSparse(trail, head.AsReadOnly().Trail, AtEnds<ArchiveNode?>(), "0,4");
            Assert.Same(model.Review[0], trail[1]);
        }, (before, after) =>
        {
            Assert.Equal(5, before.Head!.Trail.Count);
            Assert.Same(before.Review[0], before.Head.Trail[0]);
            var head = after.Head!;
            Assert.Collection(head.Trail, Assert.Null, value => Assert.Same(after.Review[0], value), value => Assert.Same(head, value));
        }, [0, 4]);
    }

    private static CollectionProfile Profile()
    {
        var model = new CollectionProfile();
        var shared = new string("same");
        model.Messages.AddRange([shared, null, shared, "held", "cut"]);
        model.Phases.AddRange(["a", "keep", "c"]);
        model.Counts.AddRange([1, 2, 3]);
        model.Levels.Add(1);
        model.Limits["one"] = 1;
        return model;
    }

    private static ArchiveRoot Archive()
    {
        var head = new ArchiveNode { Name = "head" };
        var alias = new ArchiveNode { Name = "alias" };
        var cut = new ArchiveNode { Name = "cut" };
        head.Children.AddRange([alias, cut]);
        head.Trail.AddRange([alias, null, alias, head, cut]);
        var archive = new ArchiveRoot { Head = head };
        archive.Review.AddRange([alias, cut]);
        return archive;
    }

    private static Predicate<string?> AtEnds() => AtEnds<string?>();
    private static Predicate<T> AtEnds<T>()
    {
        int index = -1;
        return _ => ++index is 0 or 4;
    }

    private static void AssertSparse<T, TView>(IModelList<T> list, IReadOnlyList<TView> view, Predicate<T> predicate, string positions)
    {
        var order = new List<string>();
        using var projected = view.AddListener(edit => order.Add("view:" + Positions(edit)));
        using var writable = list.AddListener(edit => order.Add("mutable:" + Positions(edit)));
        Assert.Equal(2, list.RemoveWhere(predicate));
        Assert.Equal([$"view:remove[{positions}]", $"mutable:remove[{positions}]"], order);
    }

    private static string Positions<T>(ModelListChange<T> edit) => edit.Added.Count == 0
        ? $"remove[{string.Join(',', edit.Removed.Select(entry => entry.Index))}]"
        : $"add[{string.Join(',', edit.Added.Select(entry => entry.Index))}]";

    private static void Exchange<T>(string name, T model, Action<T> mutate, Action<T, T> verify, params int[][] indices)
        where T : class
    {
        string snapshot = ModelSnapshot.Write(model);
        using var recorder = JournalRecorder<T>.Start(model, ModelSnapshot.Registry, Binding, Checkpoint, 0);
        mutate(model);
        var journal = recorder.Finish();
        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_COLLECTIONS");
        if (directory is not null)
            File.WriteAllText(Path.Combine(directory, $"csharp-predicate-{name}-checkpoint.json"), snapshot);

        (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[] formats =
        [
            ("json", JournalJson.Write, JournalJson.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read)
        ];
        foreach (var (format, write, read) in formats)
        {
            string wire = write(journal);
            Check(snapshot, read(wire));
            if (directory is null) continue;
            File.WriteAllText(Path.Combine(directory, $"csharp-predicate-{name}.{format}"), wire);
            var java = read(File.ReadAllText(Path.Combine(directory, $"java-predicate-{name}.{format}")));
            Assert.Equal(JournalJson.Write(journal), JournalJson.Write(java));
            Check(File.ReadAllText(Path.Combine(directory, $"java-predicate-{name}-checkpoint.json")), java);
        }

        void Check(string baseline, ChangeJournal changes)
        {
            Assert.Equal(1, changes.WireVersion);
            var operations = Assert.Single(changes.Transactions).Operations;
            Assert.Equal(indices.Length, operations.Count);
            for (int i = 0; i < operations.Count; i++)
            {
                var edit = Assert.IsType<JournalListEdit>(operations[i]);
                Assert.Equal(JournalListEditKind.Remove, edit.Kind);
                Assert.Equal(indices[i], edit.Indices);
            }
            var original = ModelSnapshot.Read<T>(baseline);
            var edited = JournalReplayer.Replay(original, Binding, Checkpoint, changes, ModelSnapshot.Registry);
            verify(original, edited);
        }
    }
}
