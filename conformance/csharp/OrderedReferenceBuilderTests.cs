using System.Collections;
using System.Text.Json.Nodes;
using Opposites = Eu.Mihosoft.Vmf.Conformance.Opposites;
using Stock = Eu.Mihosoft.Vmftest.Referenceends;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class OrderedReferenceBuilderTests
{
    private const string Checkpoint = "ordered-panel-checkpoint";
    private static readonly JournalBinding Binding = new("conformance.ordered-builder", 1, model => model switch
    {
        Opposites.OrderedPanel panel => panel.Id!,
        Opposites.OrderedPanelLink link => link.Id!,
        _ => "gallery",
    });

    [Fact]
    public void StockPanelBuilderKeepsSparsePositionsOppositesAndReflectedDefaults()
    {
        var panel = new Stock.Panel();
        var a = new Stock.StationLink(); var b = new Stock.StationLink();
        var c = new Stock.StationLink(); var d = new Stock.StationLink();
        var e = new Stock.StationLink(); var x = new Stock.StationLink(); var y = new Stock.StationLink();
        Stock.StationLink[] original = [a, b, c, d, e];
        var links = panel.Links;
        links.AddRange(original);
        var field = Stock.ModelSnapshot.Reflection.Reflect(panel).FindProperty("links")!;
        field.SetDefault(new[] { d, e });
        var edits = new List<string>();
        var stream = panel.VmfChanges;
        using var listener = stream.AddPropertyListener("links", change =>
        {
            var edit = Assert.IsType<ModelCollectionChange>(change);
            Assert.Equal(ModelChangeKind.ReferenceSet, edit.Kind);
            var positions = edit.Added.Count == 0 ? edit.Removed : edit.Added;
            edits.Add($"[{string.Join(", ", positions.Select(item => item.Index))}]:{edit.Removed.Count}:{edit.Added.Count}");
            Assert.All(panel.Links, member => Assert.True(member.Panels.Contains(panel)));
        });

        Stock.Panel.NewBuilder().AppendCollections(false).ApplyFrom(panel).ApplyTo(panel);
        Assert.Throws<ArgumentException>(() => Stock.Panel.NewBuilder().WithLinks([a, a]).ApplyTo(panel));
        Assert.Throws<ArgumentNullException>(() => Stock.Panel.NewBuilder().WithLinks([a, null!]).ApplyTo(panel));
        Assert.Empty(edits);

        stream.Start();
        stream.StartTransaction();
        Stock.StationLink[] desired = [e, x, b, y];
        Stock.Panel.NewBuilder().WithLinks(desired).AppendCollections(false).ApplyTo(panel);
        var transaction = Assert.IsType<ModelTransaction>(stream.PublishTransaction());
        stream.Stop();
        listener.Dispose();
        Assert.Equal(["[0, 2, 3]:3:0", "[0, 1]:2:2", "[1, 3]:0:2"], edits);
        Assert.Same(links, panel.Links);
        AssertLinks(panel, desired, [a, c, d]);

        Assert.True(transaction.Undo());
        AssertLinks(panel, original, [x, y]);
        Assert.True(transaction.Redo());
        AssertLinks(panel, desired, [a, c, d]);
        Assert.True(field.IsSet);
        Assert.Equal([d, e], ((IEnumerable)field.DefaultValue!).Cast<Stock.StationLink>());
        field.Reset();
        Assert.False(field.IsSet);
        AssertLinks(panel, [d, e], [a, b, c, x, y]);
    }

    [Fact]
    public void SparseReplacementCompletesAfterThrowingObservers()
    {
        var panel = new Stock.Panel();
        var a = new Stock.StationLink(); var b = new Stock.StationLink();
        var c = new Stock.StationLink(); var d = new Stock.StationLink();
        var e = new Stock.StationLink(); var x = new Stock.StationLink(); var y = new Stock.StationLink();
        panel.Links.AddRange([a, b, c, d, e]);
        var first = new InvalidOperationException("removal");
        var second = new InvalidOperationException("reorder");
        var edits = new List<ModelCollectionChange>();
        using var listener = panel.VmfChanges.AddPropertyListener("links", change =>
        {
            edits.Add(Assert.IsType<ModelCollectionChange>(change));
            if (edits.Count == 1) throw first;
            if (edits.Count == 2) throw second;
        });

        var staged = Stock.Panel.NewBuilder().WithLinks([e, x, b, y]).AppendCollections(false);
        Assert.Same(first, Assert.Throws<InvalidOperationException>(() => staged.ApplyTo(panel)));

        Assert.Equal(3, edits.Count);
        Assert.Same(second, Assert.Single(ModelChanges.GetSuppressedObserverExceptions(first)));
        AssertLinks(panel, [e, x, b, y], [a, c, d]);
    }

    [Fact]
    public void V1JournalPreservesTheOriginalOrderOfSparseEditsAcrossJava()
    {
        var graph = new Opposites.OrderedPanelGallery { Panel = new Opposites.OrderedPanel { Id = "panel" } };
        var entries = new[] { "a", "b", "c", "d", "e", "x", "y" }
            .Select(id => new Opposites.OrderedPanelLink { Id = id }).ToArray();
        graph.Entries.AddRange(entries);
        var panel = graph.Panel!;
        panel.Links.AddRange(entries.Take(5));
        string checkpoint = Opposites.ModelSnapshot.Write(graph);
        using var recorder = JournalRecorder<Opposites.OrderedPanelGallery>.Start(graph,
            Opposites.ModelSnapshot.Registry, Binding, Checkpoint, 0);
        Opposites.OrderedPanel.NewBuilder().WithId("panel").WithLinks([entries[4], entries[5], entries[1], entries[6]])
            .AppendCollections(false).ApplyTo(panel);
        var journal = recorder.Finish();
        string wire = JournalJson.Write(journal);
        VerifyReplay(checkpoint, wire);

        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_RECIPROCAL") is not { } directory) return;
        string javaCheckpoint = File.ReadAllText(Path.Combine(directory, "java-ordered-builder-checkpoint.json"));
        string javaWire = File.ReadAllText(Path.Combine(directory, "java-ordered-builder.json"));
        VerifyReplay(javaCheckpoint, javaWire);
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(javaWire), JsonNode.Parse(wire)));
        File.WriteAllText(Path.Combine(directory, "csharp-ordered-builder-checkpoint.json"), checkpoint);
        File.WriteAllText(Path.Combine(directory, "csharp-ordered-builder.json"), wire);
    }

    private static void AssertLinks(Stock.Panel panel, Stock.StationLink[] present, Stock.StationLink[] absent)
    {
        Assert.Equal(present.Length, panel.Links.Count);
        for (int index = 0; index < present.Length; index++)
        {
            Assert.Same(present[index], panel.Links[index]);
            Assert.True(present[index].Panels.Contains(panel));
        }
        Assert.All(absent, member => Assert.False(member.Panels.Contains(panel)));
    }

    private static void VerifyReplay(string checkpoint, string wire)
    {
        var journal = JournalJson.Read(wire);
        Assert.Equal(1, journal.WireVersion);
        Assert.Collection(Assert.Single(journal.Transactions).Operations,
            edit => AssertEdit(edit, JournalListEditKind.Remove, [0, 2, 3], ["a", "c", "d"], []),
            edit => AssertEdit(edit, JournalListEditKind.Set, [0, 1], ["b", "e"], ["e", "b"]),
            edit => AssertEdit(edit, JournalListEditKind.Add, [1, 3], [], ["x", "y"]));
        var original = Opposites.ModelSnapshot.Read<Opposites.OrderedPanelGallery>(checkpoint);
        var result = JournalReplayer.Replay(original, Binding, Checkpoint, journal, Opposites.ModelSnapshot.Registry);
        Assert.NotSame(original, result);
        Assert.NotSame(original.Entries[4], result.Entries[4]);
        VerifyGraph(original, ["a", "b", "c", "d", "e"]);
        VerifyGraph(result, ["e", "x", "b", "y"]);
    }

    private static void AssertEdit(JournalOperation operation, JournalListEditKind kind, int[] indices,
        string[] before, string[] after)
    {
        var edit = Assert.IsType<JournalListEdit>(operation);
        Assert.Equal("panel", edit.Target);
        Assert.Equal("links", edit.Property);
        Assert.Equal(kind, edit.Kind);
        Assert.Equal(indices, edit.Indices);
        Assert.Equal(before, edit.Before.Select(value => value.Text));
        Assert.Equal(after, edit.After.Select(value => value.Text));
    }

    private static void VerifyGraph(Opposites.OrderedPanelGallery graph, string[] ids)
    {
        var panel = graph.Panel!;
        Assert.Equal(ids, panel.Links.Select(link => link.Id));
        for (int index = 0; index < ids.Length; index++)
        {
            string id = ids[index];
            Assert.Same(Assert.Single(graph.Entries, entry => entry?.Id == id), panel.Links[index]);
        }
        Assert.All(graph.Entries, entry => Assert.Equal(ids.Contains(entry!.Id), entry.Panels.Contains(panel)));
    }
}
