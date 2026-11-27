using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Nominalbounds;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class NominalBoundTests
{
    private const string CheckpointId = "origin";
    private static readonly JournalBinding Binding = new("conformance.nominal-bounds", 1, model => model switch
    {
        Library => "library", Relations => "relations", Review => "review",
        LabelNode node => node.Id ?? throw new ArgumentException("A node needs an identity."),
        FrozenLabel label => label.Id ?? throw new ArgumentException("A label needs an identity."),
        _ => throw new ArgumentException("Unknown journal model type.")
    });

    [Fact]
    public void DependentAndImmutableBoundsKeepTypedLiveViewsAndClosedMetadata()
    {
        var root = Specimen();
        var related = Assert.IsType<Relations>(root.Relations);
        var review = Assert.IsType<Review>(root.Review);
        var first = Assert.IsType<Leaf>(root.Nodes[0]);
        Related<LabelNode, Leaf> dependent = related;
        IReadOnlyRelated<IReadOnlyLabelNode, IReadOnlyLeaf> exact = related.AsReadOnly();
        IReadOnlyRelated<IReadOnlyLabelNode, IReadOnlyLabelNode> widened = dependent.AsReadOnly();
        Assert.Same(first.AsReadOnly(), exact.Selected);
        Assert.Same(exact.Selected, widened.Selected);
        Assert.Same(root.Nodes[2].AsReadOnly(), exact.Base);
        Assert.Null(exact.History[1]);
        first.Text = "updated";
        Assert.Equal("updated", exact.Selected!.Text);

        Shelf<FrozenLabel> shelf = review;
        IReadOnlyShelf<FrozenLabel> frozen = shelf.AsReadOnly();
        var stable = Assert.IsType<FrozenLabel>(review.Selected);
        var twin = Assert.IsType<FrozenLabel>(review.History[2]);
        Assert.Equal(stable, twin);
        Assert.NotSame(stable, twin);
        Assert.Same(stable, frozen.Selected);
        Assert.Same(twin, frozen.History[2]);
        Assert.Equal(2, frozen.Pinned.Count);
        Assert.True(frozen.Pinned.Contains(stable));
        Assert.True(frozen.Pinned.Contains(twin));

        var metadata = ModelSnapshot.Reflection.Reflect(related);
        Assert.Equal(typeof(LabelNode), metadata.FindProperty("base")?.ValueType);
        Assert.Equal(typeof(Leaf), metadata.FindProperty("selected")?.ValueType);
        Assert.Equal(typeof(FrozenLabel), ModelSnapshot.Reflection.Reflect(review).FindProperty("history")?.ValueType);
        var open = ModelSnapshot.TypeCatalog.Find(typeof(Related<,>))!;
        var dependentBound = Assert.Single(open.TypeParameters[1].UpperBounds);
        Assert.Equal(ModelValueKind.Parameter, dependentBound.Kind);
        Assert.Equal("B", dependentBound.Name);
        var immutableBound = Assert.Single(Assert.Single(ModelSnapshot.TypeCatalog.Find(typeof(Shelf<>))!
            .TypeParameters).UpperBounds);
        Assert.EndsWith(".FrozenLabel", immutableBound.Name);
        Assert.False(typeof(FrozenLabel).IsSealed);

        var copy = ModelSnapshot.Reflection.DeepCopy(root);
        Assert.Same(copy.Nodes[0], copy.Relations!.Selected);
        Assert.NotSame(first, copy.Nodes[0]);
        Assert.Same(stable, copy.Review!.Selected);
    }

    [Theory]
    [InlineData(VmfDocumentFormat.Json)]
    [InlineData(VmfDocumentFormat.Xml)]
    [InlineData(VmfDocumentFormat.Yaml)]
    public void AComposedNominalBoundKeepsBothRolesAndOneIdentity(VmfDocumentFormat format)
    {
        var item = WorkItem.NewBuilder().WithTitle("draft").WithRank(7).Build();
        var review = WorkReview.NewBuilder().WithSelected(item).Build();
        var book = RankedWorkBook.NewBuilder().WithWork(item).WithReview(review).Build();
        Verify(book);
        string document = format switch
        {
            VmfDocumentFormat.Xml => ModelSnapshot.WriteXml(book),
            VmfDocumentFormat.Yaml => ModelSnapshot.WriteYaml(book),
            _ => ModelSnapshot.Write(book),
        };
        Verify(Read(document));
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_NOMINAL_BOUNDS") is { } directory)
        {
            string suffix = $"composed-bound.{format.ToString().ToLowerInvariant()}";
            Verify(Read(File.ReadAllText(Path.Combine(directory, "java-" + suffix))));
            File.WriteAllText(Path.Combine(directory, "csharp-" + suffix), document);
        }

        RankedWorkBook Read(string text)
        {
            Assert.Contains("selected_item", text);
            return format switch
            {
                VmfDocumentFormat.Xml => ModelSnapshot.ReadXml<RankedWorkBook>(text),
                VmfDocumentFormat.Yaml => ModelSnapshot.ReadYaml<RankedWorkBook>(text),
                _ => ModelSnapshot.Read<RankedWorkBook>(text),
            };
        }

        static void Verify(RankedWorkBook root)
        {
            var work = Assert.IsType<WorkItem>(root.Work);
            RankedSelection<WorkItem> selection = Assert.IsType<WorkReview>(root.Review);
            var projected = Assert.IsAssignableFrom<IReadOnlyRankedTitle>(selection.AsReadOnly().Selected);
            Assert.Same(work, selection.Selected);
            Assert.Same(work.AsReadOnly(), projected);
            Assert.Equal("draft", projected.Title);
            Assert.Equal(7, projected.Rank);
            Assert.Equal(typeof(WorkItem), ModelSnapshot.Reflection.Reflect(selection).FindProperty("selected")?.ValueType);
            work.Title = "reviewed";
            Assert.Equal("reviewed", projected.Title);
            work.Title = "draft";
        }
    }

    [Fact]
    public void DependentBoundsProjectThroughGetterTraitsAndSecondaryComposition()
    {
        var leaf = new Leaf { Text = "secondary" };
        var composed = new ComposedRelations { Selected = leaf };
        composed.History.Add(leaf);
        IRelated<LabelNode, Leaf> throughSecondary = composed;
        IReadOnlyRelated<IReadOnlyLabelNode, IReadOnlyLabelNode> secondaryView = throughSecondary.AsReadOnly();
        Assert.Same(leaf.AsReadOnly(), secondaryView.Selected);
        Assert.Same(secondaryView.Selected, Assert.Single(secondaryView.History));

        var model = DetailedLabel.NewBuilder().WithText("shared").WithNote("details").Build();
        var details = DetailedRelations.NewBuilder().WithSelected(model).WithHistory([model]).Build();
        TraitRelations<DetailedLabel, DetailedLabel> throughTrait = details;
        IReadOnlyTraitRelations<IReadOnlyDetailedLabel, IReadOnlyDetailedLabel> detailView = details.AsReadOnly();
        Assert.Same(model, throughTrait.Selected);
        Assert.Same(model.AsReadOnly(), detailView.Selected);
        Assert.Same(detailView.Selected, Assert.Single(detailView.History));
    }

    [Fact]
    public void BothRuntimesExchangePolymorphicMutableAndImmutableIdentities()
    {
        var root = Specimen();
        var second = Assert.IsType<TaggedLeaf>(root.Nodes[1]);
        var twin = Assert.IsType<FrozenLabel>(root.Review!.History[2]);
        string checkpoint = ModelSnapshot.Write(root);
        var recorded = JournalRecorder<Library>.RecordFrame(root, ModelSnapshot.Registry, Binding, CheckpointId, 8, model =>
        {
            model.Relations!.Base = second;
            model.Relations.Selected = second;
            model.Relations.History[0] = second;
            model.Review!.Selected = twin;
            model.Review.History[0] = twin;
        });
        string journal = JournalJson.Write(recorded);
        Verify(checkpoint, journal);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_NOMINAL_BOUNDS") is { } directory)
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

    private static Library Specimen()
    {
        var first = new Leaf { Id = "n1", Text = "live" };
        var second = new TaggedLeaf { Id = "n2", Text = "second", Tag = "subtype" };
        var other = new Other { Id = "n3", Text = "other" };
        var related = Relations.NewBuilder().WithBase(other).WithSelected(first).WithHistory([first, null, second]).Build();
        var stable = FrozenLabel.NewBuilder().WithId("tag1").WithText("same").Build();
        var twin = FrozenLabel.NewBuilder().WithId("tag2").WithText("same").Build();
        var review = Review.NewBuilder().WithSelected(stable).WithHistory([stable, null, twin]).WithPinned([stable, twin]).Build();
        return Library.NewBuilder().WithNodes([first, second, other]).WithRelations(related).WithReview(review).Build();
    }

    private static void Verify(string checkpoint, string journal)
    {
        var input = ModelSnapshot.Read<Library>(checkpoint);
        var recorded = JournalJson.Read(journal);
        Assert.Equal(1, recorded.WireVersion);
        Assert.IsType<TaggedLeaf>(input.Nodes[1]);
        Assert.Same(input.Nodes[0], input.Relations!.Selected);
        Assert.Same(input.Review!.Selected, input.Review.History[0]);
        var restored = JournalReplayer.Replay(input, Binding, CheckpointId, recorded, ModelSnapshot.Registry);
        Assert.Same(restored.Nodes[1], restored.Relations!.Base);
        Assert.Same(restored.Nodes[1], restored.Relations.Selected);
        Assert.Same(restored.Nodes[1], restored.Relations.History[0]);
        Assert.Equal("tag2", restored.Review!.Selected!.Id);
        Assert.Same(restored.Review.Selected, restored.Review.History[0]);
        Assert.Same(restored.Review.Selected, restored.Review.History[2]);
        Assert.True(restored.Review.Pinned.Contains(restored.Review.Selected));
        Assert.Equal("tag1", input.Review.Selected!.Id);
    }
}
