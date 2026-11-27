using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ArchiveBuilderTests
{
    [Fact]
    public void OrderedBuilderReplacementReplaysItsSparseEditsIntoAFreshGraph()
    {
        var target = new ArchiveNode { Name = "target" };
        var first = new ArchiveNode { Name = "first" };
        var second = new ArchiveNode { Name = "second" };
        target.Children.AddRange([new ArchiveNode { Name = "a" }, first, new ArchiveNode { Name = "b" }, second]);
        string checkpoint = ModelSnapshot.Write(target);
        var binding = new JournalBinding("conformance.ordered-builder", 1, node => ((ArchiveNode)node).Name!)
            .WithObjectTypes(_ => "node", (_, id) => new ArchiveNode { Name = id });
        using var recorder = JournalRecorder<ArchiveNode>.Start(target, ModelSnapshot.Registry, binding, "base", 0);
        ArchiveNode.NewBuilder().WithName("target").AppendCollections(false)
            .WithChildren([second, new ArchiveNode { Name = "added" }, first]).ApplyTo(target);
        var journal = recorder.Finish();
        Assert.Equal([JournalListEditKind.Remove, JournalListEditKind.Set, JournalListEditKind.Add],
            Assert.Single(journal.Transactions).Operations.Select(operation => Assert.IsType<JournalListEdit>(operation).Kind));

        ChangeJournal[] documents = [
            JournalJson.Read(JournalJson.Write(journal)),
            JournalYaml.Read(JournalYaml.Write(journal)),
            JournalXml.Read(JournalXml.Write(journal))
        ];
        foreach (var document in documents)
        {
            var original = ModelSnapshot.Read<ArchiveNode>(checkpoint);
            var replayed = JournalReplayer.Replay(original, binding, "base", document, ModelSnapshot.Registry);
            Assert.Equal(["second", "added", "first"], replayed.Children.Select(node => node.Name));
            Assert.All(replayed.Children, node => Assert.Same(replayed, node.Parent));
            Assert.Equal(["a", "first", "b", "second"], original.Children.Select(node => node.Name));
        }
    }

    [Fact]
    public void BuildImportsBothDirectionsOfOwnershipWhileApplyToPreservesSingularSlots()
    {
        var sourceOwner = new ArchiveNode { Name = "sourceOwner" };
        var source = new ArchiveNode { Name = "source" };
        var child = new ArchiveNode { Name = "child" };
        var inset = new ArchiveNode { Name = "inset" };
        var alias = new ArchiveNode { Name = "alias" };
        sourceOwner.Children.Add(source);
        source.Children.Add(child);
        source.Inset = inset;
        source.Anchor = alias;
        source.Trail.Add(alias);

        var staged = ArchiveNode.NewBuilder().ApplyFrom(source);
        Assert.Same(sourceOwner, source.Parent);
        Assert.Same(source, child.Parent);
        Assert.Same(source, inset.InsetOf);

        var built = staged.Build();
        Assert.Same(sourceOwner, built.Parent);
        Assert.Same(built, sourceOwner.Children[1]);
        Assert.Same(child, Assert.Single(built.Children));
        Assert.Same(built, child.Parent);
        Assert.Empty(source.Children);
        Assert.Same(inset, built.Inset);
        Assert.Same(built, inset.InsetOf);
        Assert.Null(source.Inset);
        Assert.Same(alias, built.Anchor);
        Assert.Same(alias, Assert.Single(built.Trail));

        var targetOwner = new ArchiveNode { Name = "targetOwner" };
        var target = new ArchiveNode { Name = "target" };
        var targetInset = new ArchiveNode { Name = "targetInset" };
        var obsoleteFirst = new ArchiveNode { Name = "obsoleteFirst" };
        var obsoleteSecond = new ArchiveNode { Name = "obsoleteSecond" };
        var keepFirst = new ArchiveNode { Name = "keepFirst" };
        var keepSecond = new ArchiveNode { Name = "keepSecond" };
        var appended = new ArchiveNode { Name = "appended" };
        targetOwner.Children.Add(target);
        target.Children.AddRange([obsoleteFirst, keepFirst, obsoleteSecond, keepSecond]);
        target.Inset = targetInset;
        var observed = new List<ModelCollectionChange>();
        using (target.VmfChanges.AddPropertyListener("children", change => observed.Add((ModelCollectionChange)change)))
        {
            ArchiveNode.NewBuilder().ApplyFrom(built).AppendCollections(false)
                .WithChildren([keepSecond, appended, keepFirst]).ApplyTo(target);
        }

        Assert.Same(keepSecond, target.Children[0]);
        Assert.Same(appended, target.Children[1]);
        Assert.Same(keepFirst, target.Children[2]);
        Assert.All(target.Children, item => Assert.Same(target, item.Parent));
        Assert.Null(obsoleteFirst.Parent);
        Assert.Null(obsoleteSecond.Parent);
        Assert.Same(targetInset, target.Inset);
        Assert.Same(targetOwner, target.Parent);
        Assert.Same(alias, target.Anchor);
        Assert.Same(alias, Assert.Single(target.Trail));
        Assert.Collection(observed,
            remove => Assert.Equal(new int?[] { 0, 2 }, remove.Removed.Select(entry => entry.Index)),
            reorder => Assert.Equal(new int?[] { 0, 1 }, reorder.Added.Select(entry => entry.Index)),
            insert => Assert.Equal(1, Assert.Single(insert.Added).Index));

        foreach (var edit in observed.AsEnumerable().Reverse()) Assert.True(edit.Undo());
        Assert.Equal([obsoleteFirst, keepFirst, obsoleteSecond, keepSecond], target.Children);
        foreach (var edit in observed) Assert.True(edit.Redo());
        Assert.Equal([keepSecond, appended, keepFirst], target.Children);
    }
}
