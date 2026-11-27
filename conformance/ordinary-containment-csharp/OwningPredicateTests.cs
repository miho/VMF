using Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class OwningPredicateTests
{
    private const string Checkpoint = "owning-predicate-0";
    private static readonly JournalBinding Binding = new("ordinary-owning-predicate", 1, model => model switch
    {
        LegacyLayout => "layout",
        LegacyContainer owner => owner.Name!,
        LegacyChild child => child.Name!,
        _ => throw new ArgumentException("Unexpected predicate model.", nameof(model)),
    });

    [Fact]
    public void PredicateRemovalAndUndoUseOriginalPositionsWithoutDetachingTheRetainedAlias()
    {
        var graph = Fixture();
        var owner = graph.Containers[0];
        var alias = graph.Featured!;
        var cut = owner.Children[3]!;
        owner.Children[0] = alias;
        var delivered = new List<string>();
        using var aliasInverse = alias.VmfChanges.AddPropertyListener("owner", _ => delivered.Add("alias"));
        using var cutInverse = cut.VmfChanges.AddPropertyListener("owner",
            _ => delivered.Add(cut.Owner is null ? "cut:detach" : "cut:attach"));
        using var view = owner.AsReadOnly().Children.AddListener(edit => delivered.Add("view:" + Slots(edit)));
        using var writable = owner.Children.AddListener(edit => delivered.Add("mutable:" + Slots(edit)));
        owner.VmfChanges.Start();
        RemoveEnds(owner);
        var edit = Assert.IsType<ModelCollectionChange>(Assert.Single(owner.VmfChanges.History));
        owner.VmfChanges.Stop();
        CheckFinal(graph);
        Assert.Null(cut.Owner);
        Assert.Equal(["cut:detach", "view:[0,3]/[]", "mutable:[0,3]/[]"], delivered);
        delivered.Clear();
        Assert.True(edit.Undo());
        Assert.Collection(owner.Children, item => Assert.Same(alias, item), Assert.Null,
            item => Assert.Same(alias, item), item => Assert.Same(cut, item));
        Assert.Same(owner, cut.Owner);
        Assert.Equal(["cut:attach", "view:[]/[0,3]", "mutable:[]/[0,3]"], delivered);
        delivered.Clear();
        Assert.True(edit.Redo());
        Assert.Equal(["cut:detach", "view:[0,3]/[]", "mutable:[0,3]/[]"], delivered);
        CheckFinal(graph);
    }

    [Fact]
    public void PredicateFailureIsSilentAndTheTemporaryAliasExchangeReplaysInOneFrame()
    {
        var graph = Fixture();
        var owner = graph.Containers[0];
        int delivered = 0;
        using var watch = owner.Children.AddListener(_ => delivered++);
        using var recorder = JournalRecorder<LegacyLayout>.Start(graph, ModelSnapshot.Registry, Binding, Checkpoint, 0);

        Assert.Throws<InvalidOperationException>(() => owner.Children.RemoveWhere(value => value is null
            ? throw new InvalidOperationException("predicate failed") : true));
        Assert.Equal(0, owner.Children.RemoveWhere(_ => false));
        Assert.Equal(0, delivered);
        Assert.Empty(graph.VmfChanges.History);
        Assert.Equal("original", owner.Children[0]!.Name);
        Assert.Equal("cut", owner.Children[3]!.Name);
        Assert.Same(graph.Featured, owner.Children[2]);

        owner.Children[0] = graph.Featured;
        RemoveEnds(owner);
        CheckFinal(graph);
        var journal = recorder.Finish();
        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ORDINARY_CONTAINMENT");
        (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[] formats =
        [
            ("json", JournalJson.Write, JournalJson.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read)
        ];
        foreach (var (format, write, read) in formats)
        {
            string wire = write(journal);
            Replay(read(wire));
            if (directory is null) continue;
            File.WriteAllText(Path.Combine(directory, $"csharp-owning-remove-where.{format}"), wire);
            var java = read(File.ReadAllText(Path.Combine(directory, $"java-owning-remove-where.{format}")));
            Assert.Equal(JournalJson.Write(journal), JournalJson.Write(java));
            Replay(java);
        }
    }

    private static void RemoveEnds(LegacyContainer owner)
    {
        int visits = 0;
        IModelList<LegacyChild?> children = owner.Children;
        Assert.Equal(2, children.RemoveWhere(_ => visits++ is 0 or 3));
        Assert.Equal(4, visits);
    }

    private static void Replay(ChangeJournal journal)
    {
        Assert.Equal(1, journal.WireVersion);
        Assert.Collection(Assert.Single(journal.Transactions).Operations,
            operation => Check(operation, JournalListEditKind.Set, [0]),
            operation => Check(operation, JournalListEditKind.Remove, [0, 3]));
        var baseline = Fixture();
        CheckFinal(JournalReplayer.Replay(baseline, Binding, Checkpoint, journal, ModelSnapshot.Registry));
        Assert.Equal(4, baseline.Containers[0].Children.Count);
        Assert.Equal("original", baseline.Containers[0].Children[0]!.Name);
        Assert.Same(baseline.Featured, baseline.Containers[0].Children[2]);

        static void Check(JournalOperation operation, JournalListEditKind kind, int[] positions)
        {
            var edit = Assert.IsType<JournalListEdit>(operation);
            Assert.Equal(kind, edit.Kind);
            Assert.Equal("owner", edit.Target);
            Assert.Equal(positions, edit.Indices);
        }
    }

    private static void CheckFinal(LegacyLayout layout)
    {
        var owner = Assert.Single(layout.Containers);
        Assert.Collection(owner.Children, Assert.Null, alias => Assert.Same(layout.Featured, alias));
        Assert.Same(owner, layout.Featured!.Owner);
    }

    private static LegacyLayout Fixture()
    {
        var alias = new LegacyChild { Name = "alias" };
        var owner = new LegacyContainer { Name = "owner" };
        owner.Children.AddRange([new LegacyChild { Name = "original" }, null, alias, new LegacyChild { Name = "cut" }]);
        var graph = new LegacyLayout { Featured = alias };
        graph.Containers.Add(owner);
        return graph;
    }

    private static string Slots<T>(ModelListChange<T> edit) =>
        $"[{string.Join(',', edit.Removed.Select(slot => slot.Index))}]/[{string.Join(',', edit.Added.Select(slot => slot.Index))}]";
}
