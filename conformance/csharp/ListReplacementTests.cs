using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ListReplacementTests
{
    private const string Checkpoint = "list-replacement-0";
    private static readonly JournalBinding Binding = new("conformance.list-replacement", 1, _ => "profile");

    [Fact]
    public void TransformAndCheckedValidationFailBeforeChangingAnySlots()
    {
        var profile = Profile();
        var ordinary = new List<ModelListChange<string?>>();
        var managed = new List<ModelListChange<string>>();
        using var watch = profile.Messages.AddListener(ordinary.Add);
        using var watchManaged = profile.Phases.AddListener(managed.Add);
        using var recorder = JournalRecorder<CollectionProfile>.Start(profile, ModelSnapshot.Registry, Binding, Checkpoint, 0);

        Assert.Throws<InvalidOperationException>(() => profile.Messages.ReplaceAll(value => value is null
            ? throw new InvalidOperationException("transform failed") : "changed"));
        int visited = 0;
        Assert.Throws<ArgumentNullException>(() => profile.Phases.ReplaceAll(value =>
        {
            visited++;
            return value == "a" ? null! : value;
        }));
        Assert.Equal(3, visited);
        Assert.Throws<ArgumentException>(() => profile.Phases.ReplaceAll(_ => "duplicate"));
        profile.Counts.ReplaceAll(_ => throw new InvalidOperationException("Empty list has no slots."));
        Assert.Empty(ordinary);
        Assert.Empty(managed);
        Assert.Empty(profile.VmfChanges.History);
        Assert.Empty(recorder.Finish().Transactions);
        Assert.Equal(new string?[] { "a", null, "c" }, profile.Messages.AsEnumerable());
        Assert.Equal(["a", "b", "c"], profile.Phases.AsEnumerable());
    }

    [Fact]
    public void OrdinaryReferenceReplacementKeepsNullsAndAliasesThroughNativeUndoAndRedo()
    {
        var node = new ArchiveNode { Name = "head" };
        var alias = new ArchiveNode { Name = "alias" };
        node.Trail.AddRange([alias, null, alias, node]);
        var order = new List<string>();
        using var project = node.AsReadOnly().Trail.AddListener(edit => order.Add("view:" + Indices(edit)));
        using var raw = node.Trail.AddListener(edit => order.Add("mutable:" + Indices(edit)));

        node.VmfChanges.Start();
        node.Trail.ReplaceAll(item => ReferenceEquals(item, alias) ? node : item);
        var change = Assert.IsType<ModelCollectionChange>(Assert.Single(node.VmfChanges.History));
        node.VmfChanges.Stop();
        Assert.Equal(["view:0,1,2,3", "mutable:0,1,2,3"], order);
        Assert.Collection(node.Trail, item => Assert.Same(node, item), Assert.Null,
            item => Assert.Same(node, item), item => Assert.Same(node, item));
        Assert.True(change.Undo());
        Assert.Same(alias, node.Trail[0]);
        Assert.Same(alias, node.Trail[2]);
        Assert.True(change.Redo());
        Assert.Same(node, node.Trail[0]);
        Assert.Same(node, node.Trail[2]);
        Assert.Null(node.Trail[1]);
    }

    [Fact]
    public void ScalarReplacementAndIdentityTransformExchangeOneFullV1EditEach()
    {
        var profile = Profile();
        string snapshot = ModelSnapshot.Write(profile);
        var observed = new List<string>();
        using var view = profile.AsReadOnly().Messages.AddListener(edit => observed.Add("view:" + Indices(edit)));
        var failure = new InvalidOperationException("observer failed");
        using var failingObserver = profile.Messages.AddListener(_ => throw failure);
        using var mutable = profile.Messages.AddListener(edit => observed.Add("mutable:" + Indices(edit)));
        using var recorder = JournalRecorder<CollectionProfile>.Start(profile, ModelSnapshot.Registry, Binding, Checkpoint, 0);

        IModelList<string?> messages = profile.Messages;
        Assert.Same(failure, Assert.Throws<InvalidOperationException>(() =>
            messages.ReplaceAll(value => value?.ToUpperInvariant())));
        Assert.Equal(["view:0,1,2", "mutable:0,1,2"], observed);
        Assert.Equal(1, recorder.PublishFrame()!.Sequence);
        profile.Phases.ReplaceAll(value => value == "a" ? "c" : value == "c" ? "a" : value);
        Assert.Equal(2, recorder.PublishFrame()!.Sequence);
        profile.Phases.ReplaceAll(value => value);
        var journal = recorder.Finish();

        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_COLLECTIONS");
        if (directory is not null)
            File.WriteAllText(Path.Combine(directory, "csharp-list-replacement-checkpoint.json"), snapshot);
        (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[] formats =
        [
            ("json", JournalJson.Write, JournalJson.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read)
        ];
        foreach (var (format, write, read) in formats)
        {
            string wire = write(journal);
            Replay(snapshot, read(wire));
            if (directory is null) continue;
            File.WriteAllText(Path.Combine(directory, $"csharp-list-replacement.{format}"), wire);
            var java = read(File.ReadAllText(Path.Combine(directory, $"java-list-replacement.{format}")));
            Assert.Equal(JournalJson.Write(journal), JournalJson.Write(java));
            Replay(File.ReadAllText(Path.Combine(directory, "java-list-replacement-checkpoint.json")), java);
        }
    }

    private static void Replay(string checkpoint, ChangeJournal journal)
    {
        Assert.Equal(1, journal.WireVersion);
        Assert.Equal(3, journal.Transactions.Count);
        Assert.All(journal.Transactions, transaction =>
        {
            var operation = Assert.IsType<JournalListEdit>(Assert.Single(transaction.Operations));
            Assert.Equal(JournalListEditKind.Set, operation.Kind);
            Assert.Equal([0, 1, 2], operation.Indices);
        });
        var original = ModelSnapshot.Read<CollectionProfile>(checkpoint);
        var replaced = JournalReplayer.Replay(original, Binding, Checkpoint, journal, ModelSnapshot.Registry);
        Assert.Equal(new string?[] { "A", null, "C" }, replaced.Messages.AsEnumerable());
        Assert.Equal(["c", "b", "a"], replaced.Phases.AsEnumerable());
        Assert.Equal(new string?[] { "a", null, "c" }, original.Messages.AsEnumerable());
        Assert.Equal(["a", "b", "c"], original.Phases.AsEnumerable());
    }

    private static string Indices<T>(ModelListChange<T> edit)
    {
        Assert.Equal(edit.Removed.Select(slot => slot.Index), edit.Added.Select(slot => slot.Index));
        return string.Join(',', edit.Added.Select(slot => slot.Index));
    }

    private static CollectionProfile Profile()
    {
        var profile = new CollectionProfile();
        profile.Messages.AddRange(["a", null, "c"]);
        profile.Phases.AddRange(["a", "b", "c"]);
        profile.Limits["one"] = 1;
        profile.Levels.Add(1);
        return profile;
    }
}
