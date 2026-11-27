using Eu.Mihosoft.Vmf.Conformance.Genericbounds;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class GenericBoundTests
{
    [Fact]
    public void GetterOnlyBoundWorksForMutableAndImmutableTypeArguments()
    {
        Verify(Specimen());
    }

    [Fact]
    public void ClosedBoundPreservesWireFieldsAndAliasesInBothRuntimes()
    {
        string snapshot = ModelSnapshot.Write(Specimen());
        Assert.Contains("\"active_label\"", snapshot, StringComparison.Ordinal);
        Verify(ModelSnapshot.Read<LabelBook>(snapshot));
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_GENERIC_BOUNDS") is { } directory)
        {
            string java = File.ReadAllText(Path.Combine(directory, "java-generic-bounds.json"));
            Assert.Contains("\"active_label\"", java, StringComparison.Ordinal);
            Verify(ModelSnapshot.Read<LabelBook>(java));
            File.WriteAllText(Path.Combine(directory, "csharp-generic-bounds.json"), snapshot);
        }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void ExactGetterOnlyTraitArgumentSharesMutableAndImmutableReferenceIdentities(bool activeIsPermanent)
    {
        var working = WorkingLabel.NewBuilder().WithText("live").Build();
        var stable = new PermanentLabel("stable");
        var twin = new PermanentLabel("stable");
        var graph = DirectLabelSelection.NewBuilder().WithOwned([working]).WithChoices([working, stable, twin])
            .WithHistory([working, null, stable, working]).WithCurrent(activeIsPermanent ? stable : working).Build();
        VerifyDirect(graph, activeIsPermanent);

        string snapshot = ModelSnapshot.Write(graph);
        Assert.Contains("\"active_label\"", snapshot, StringComparison.Ordinal);
        VerifyDirect(ModelSnapshot.Read<DirectLabelSelection>(snapshot), activeIsPermanent);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_GENERIC_BOUNDS") is { } directory)
        {
            string variant = activeIsPermanent ? "permanent" : "working";
            string java = File.ReadAllText(Path.Combine(directory, $"java-direct-label-{variant}.json"));
            VerifyDirect(ModelSnapshot.Read<DirectLabelSelection>(java), activeIsPermanent);
            File.WriteAllText(Path.Combine(directory, $"csharp-direct-label-{variant}.json"), snapshot);
        }
    }

    [Fact]
    public void NamedTraitClosesItsGenericGetterForMutableAndImmutableValues()
    {
        var working = WorkingQualifiedLabel.NewBuilder().WithText("mutable").WithQualifier("m").Build();
        var permanent = PermanentQualifiedLabel.NewBuilder().WithText("immutable").WithQualifier("i").Build();
        var graph = NamedAppliedSelection.NewBuilder().WithOwned([working]).WithCurrent(working)
            .WithChoices([working, permanent]).WithHistory([working, permanent]).Build();
        VerifyNamedApplied(graph);

        string snapshot = ModelSnapshot.Write(graph);
        VerifyNamedApplied(ModelSnapshot.Read<NamedAppliedSelection>(snapshot));
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_GENERIC_BOUNDS") is { } directory)
        {
            string java = File.ReadAllText(Path.Combine(directory, "java-named-applied-label.json"));
            VerifyNamedApplied(ModelSnapshot.Read<NamedAppliedSelection>(java));
            File.WriteAllText(Path.Combine(directory, "csharp-named-applied-label.json"), snapshot);
        }
    }

    [Fact]
    public void NamedMutableOnlyTraitRetainsItsExistingClosedBaseClass()
    {
        var working = MutableQualifiedLabel.NewBuilder().WithQualifier("only").Build();
        var graph = NamedMutableAppliedSelection.NewBuilder().WithOwned([working]).WithCurrent(working)
            .WithChoices([working]).Build();
        LabelSlot<MutableStringQualifiedView> applied = graph;
        IReadOnlyLabelSlot<IReadOnlyMutableStringQualifiedView> view = graph.AsReadOnly();
        Assert.Same(working, applied.Current);
        Assert.True(applied.Choices.Contains(working));
        Assert.Same(working.AsReadOnly(), view.Current);
        Assert.Equal("only", view.Current!.Qualifier);
    }

    [Fact]
    public void BuilderImportsAClosedGetterOnlyBoundThroughItsGenericBase()
    {
        var working = new WorkingLabel { Text = "working" };
        var permanent = new PermanentLabel("stable");
        var source = DirectLabelSelection.NewBuilder().WithOwned([working]).WithCurrent(working)
            .WithChoices([working, permanent]).WithHistory([working, null, permanent, working]).Build();
        LabelSlot<IReadOnlyLabelView> applied = source;
        var staged = DirectLabelSelection.NewBuilder().AppendCollections(false).ApplyFrom(applied);
        applied.History.Clear();

        var target = DirectLabelSelection.NewBuilder().WithHistory([permanent]).WithChoices([permanent]).Build();
        var history = target.History;
        var choices = target.Choices;
        staged.ApplyTo(target);
        Assert.Same(history, target.History);
        Assert.Same(choices, target.Choices);
        Assert.Collection(history, value => Assert.Same(working, value), Assert.Null,
            value => Assert.Same(permanent, value), value => Assert.Same(working, value));
        Assert.Equal(2, choices.Count);
        Assert.True(choices.Contains(working));
        Assert.True(choices.Contains(permanent));
        Assert.Same(working, target.Current);
        Assert.Same(working.AsReadOnly(), target.AsReadOnly().Current);
        Assert.Empty(target.Owned);
        Assert.Same(working, Assert.Single(source.Owned));

        var moved = staged.ApplyFrom(source).Build();
        Assert.Same(working, Assert.Single(moved.Owned));
        Assert.Same(working, moved.Current);
        Assert.Empty(source.Owned);
        Assert.Empty(moved.History);
        Assert.True(moved.Choices.Contains(permanent));
    }

    private static void VerifyNamedApplied(NamedAppliedSelection graph)
    {
        LabelSlot<IReadOnlyStringQualifiedView> applied = graph;
        IReadOnlyLabelSlot<IReadOnlyStringQualifiedView> view = graph.AsReadOnly();
        IReadOnlyQualifiedView<string?> closedGetter = view.Current!;
        var working = Assert.Single(graph.Owned);
        Assert.IsAssignableFrom<QualifiedView<string?>>(working);
        Assert.Same(working, applied.Current);
        Assert.Same(working, applied.History[0]);
        Assert.Same(working.AsReadOnly(), closedGetter);
        var permanent = Assert.IsType<PermanentQualifiedLabel>(applied.History[1]);
        Assert.True(applied.Choices.Contains(working));
        Assert.True(applied.Choices.Contains(permanent));
        Assert.Same(permanent, view.History[1]);
        Assert.Equal("i", permanent.Qualifier);
        working.Qualifier = "edited";
        Assert.Equal("edited", closedGetter.Qualifier);
        working.Qualifier = "m";
    }

    private static void VerifyDirect(DirectLabelSelection graph, bool activeIsPermanent)
    {
        LabelSlot<IReadOnlyLabelView> applied = graph;
        IReadOnlyLabelSlot<IReadOnlyLabelView> projected = graph.AsReadOnly();
        var working = Assert.Single(graph.Owned);
        var stable = Assert.IsType<PermanentLabel>(applied.History[2]);
        Assert.Same(working, applied.History[0]);
        Assert.Null(applied.History[1]);
        Assert.Same(working, applied.History[3]);
        Assert.Equal(3, applied.Choices.Count);
        Assert.True(applied.Choices.Contains(working));
        Assert.True(applied.Choices.Contains(stable));
        Assert.Contains(applied.Choices, label => !ReferenceEquals(label, stable) && label.Equals(stable));
        Assert.Same(activeIsPermanent ? stable : working, applied.Current);
        Assert.Same(activeIsPermanent ? stable : working.AsReadOnly(), projected.Current);
        Assert.Same(working.AsReadOnly(), projected.History[0]);
        Assert.Same(stable, projected.History[2]);
        Assert.True(projected.Choices.Contains(working.AsReadOnly()));
        Assert.True(projected.Choices.Contains(stable));
        working.Text = "edited";
        Assert.Equal("edited", projected.History[0]!.Text);
        working.Text = "live";

        var type = ModelSnapshot.TypeCatalog.Find(typeof(DirectLabelSelection))!;
        Assert.EndsWith(".LabelView", Assert.Single(Assert.Single(type.DirectSupertypes).Arguments).Name);
        var current = ModelSnapshot.Reflection.Reflect(graph).FindProperty("current")!;
        Assert.Equal(typeof(IReadOnlyLabelView), current.ValueType);
        Assert.Equal("active_label", current.WireName);
    }

    private static LabelBook Specimen()
    {
        var permanent = new PermanentLabel("stable");
        var twin = new PermanentLabel("stable");
        var working = WorkingLabel.NewBuilder().WithText("live").WithRevision("draft").Build();
        var archived = PermanentSlot.NewBuilder().WithCurrent(permanent).WithChoices([permanent, twin])
            .WithHistory([permanent, null, permanent]).Build();
        var active = WorkingSlot.NewBuilder().WithCurrent(working).WithChoices([working])
            .WithHistory([working, null, working]).Build();
        return LabelBook.NewBuilder().WithPermanent(archived).WithWorking(active).WithOwned([working]).Build();
    }

    private static void Verify(LabelBook book)
    {
        var archived = Assert.IsType<PermanentSlot>(book.Permanent);
        var active = Assert.IsType<WorkingSlot>(book.Working);
        LabelSlot<PermanentLabel> permanent = archived;
        LabelSlot<WorkingLabel> working = active;
        IReadOnlyLabelSlot<PermanentLabel> fixedView = archived.AsReadOnly();
        IReadOnlyLabelSlot<IReadOnlyWorkingLabel> liveView = active.AsReadOnly();
        IReadOnlyLabelSlot<IReadOnlyLabelView> baseView = working.AsReadOnly();

        var stable = Assert.IsType<PermanentLabel>(permanent.Current);
        Assert.Same(stable, fixedView.Current);
        Assert.Same(stable, fixedView.History[0]);
        Assert.Null(fixedView.History[1]);
        Assert.Same(stable, fixedView.History[2]);
        Assert.Equal(2, fixedView.Choices.Count);
        Assert.True(fixedView.Choices.Contains(stable));
        Assert.Contains(fixedView.Choices, choice => !ReferenceEquals(choice, stable) && choice.Equals(stable));

        var note = Assert.Single(book.Owned);
        Assert.Same(note, working.Current);
        Assert.Same(note.AsReadOnly(), liveView.Current);
        Assert.Same(liveView.Current, baseView.Current);
        Assert.Same(liveView.Current, liveView.History[0]);
        Assert.Null(liveView.History[1]);
        Assert.Same(liveView.Current, liveView.History[2]);
        Assert.Same(liveView.Current, Assert.Single(liveView.Choices));
        note.Revision = "approved";
        Assert.Equal("approved", liveView.Current!.Revision);

        var permanentProperty = ModelSnapshot.Reflection.Reflect(archived).FindProperty("current");
        var workingProperty = ModelSnapshot.Reflection.Reflect(active).FindProperty("current");
        Assert.Equal(typeof(PermanentLabel), permanentProperty?.ValueType);
        Assert.Equal(typeof(WorkingLabel), workingProperty?.ValueType);
        Assert.Equal("active_label", permanentProperty?.WireName);
        var catalog = ModelSnapshot.TypeCatalog;
        var declaration = catalog.Find(typeof(LabelSlot<>))!;
        Assert.EndsWith(".LabelView", Assert.Single(Assert.Single(declaration.TypeParameters).UpperBounds).Name);
        Assert.EndsWith(".PermanentLabel", Assert.Single(Assert.Single(catalog.Find(typeof(PermanentSlot))!
            .DirectSupertypes).Arguments).Name);
        Assert.EndsWith(".WorkingLabel", Assert.Single(Assert.Single(catalog.Find(typeof(WorkingSlot))!
            .DirectSupertypes).Arguments).Name);
    }
}
