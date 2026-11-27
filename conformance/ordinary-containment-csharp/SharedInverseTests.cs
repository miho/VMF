using Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class SharedInverseTests
{
    private static readonly JournalBinding Binding = new("shared-inverse", 1, model => model switch
    {
        ForestLayout => "forest",
        BranchNode node => node.Code!,
        _ => throw new ArgumentException("Unexpected journal model.", nameof(model)),
    });

    [Fact]
    public void OneReadOnlyInverseFollowsNamedSlotsAndCurrentOwnerIdentity()
    {
        var model = Checkpoint();
        var first = model.Roots[0]!;
        var second = model.Roots[1]!;
        var twig = Assert.IsType<Twig>(first.Left);
        Assert.False(typeof(Twig).GetProperty(nameof(Twig.Parent))!.CanWrite);
        var events = new List<string>();
        using var listener = twig.VmfChanges.AddListener(change =>
            events.Add($"{change.PropertyName}:{twig.Parent?.Code ?? "-"}"));

        first.Right = twig;
        Assert.Null(first.Left);
        Assert.Same(first, twig.Parent);
        second.Branches.Add(twig);
        Assert.Null(first.Right);
        Assert.Same(second, twig.Parent);
        first.Staging = twig;
        Assert.Empty(second.Branches);
        Assert.Null(twig.Parent);
        first.Branches.Add(twig);
        Assert.Null(first.Staging);
        Assert.Same(first, twig.Parent);

        Assert.Equal(["parent:-", "parent:first", "parent:-", "parent:second", "parent:-",
            "__vmf_prop_unnamed_container:-", "__vmf_prop_unnamed_container:-", "parent:first"], events);
        var view = model.AsReadOnly().Roots[0]!;
        Assert.Same(view, view.Branches[0]!.Parent);
        var parent = ModelSnapshot.Reflection.Reflect(twig).FindProperty("parent")!;
        Assert.Same(first, parent.Get());
        Assert.False(parent.CanWrite);
        Assert.Throws<InvalidOperationException>(() => parent.Set(second));
        Assert.Same(first, twig.Parent);
    }

    [Fact]
    public void TransactionUndoRestoresTheOriginalNamedSlotAndInverse()
    {
        var model = Checkpoint();
        var child = model.Roots[0]!.Left;
        var changes = model.VmfChanges;
        changes.Start();
        changes.StartTransaction();
        MoveChild(model);
        var transaction = changes.PublishTransaction();
        changes.Stop();
        CheckMoved(model);

        Assert.True(transaction!.Undo());
        CheckInitial(model);
        Assert.Same(child, model.Roots[0]!.Left);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void ClearingASingularSlotReportsOneOwningEditThroughEveryScope(bool anonymous)
    {
        var model = anonymous ? DetachCheckpoint() : Checkpoint();
        var owner = model.Roots[0]!;
        var child = (anonymous ? owner.Staging : owner.Left)!;
        var name = anonymous ? "staging" : "left";
        var view = owner.AsReadOnly();
        var local = new List<ModelChange>();
        var named = new List<ModelChange>();
        var recursive = new List<ModelChange>();
        var inverse = new List<ModelChange>();
        var readOnly = new List<ReadOnlyModelChange>();
        var readOnlyAncestor = new List<ReadOnlyModelChange>();
        var changes = model.VmfChanges;
        var viewChanges = view.VmfChanges;
        using (owner.VmfChanges.AddLocalListener(local.Add))
        using (owner.VmfChanges.AddPropertyListener(name, named.Add))
        using (changes.AddRecursiveListener(recursive.Add))
        using (child.VmfChanges.AddLocalListener(inverse.Add))
        using (viewChanges.AddRecursiveListener(readOnly.Add))
        using (model.AsReadOnly().VmfChanges.AddRecursiveListener(readOnlyAncestor.Add))
        {
            changes.Start();
            viewChanges.Start();
            Clear();
            viewChanges.Stop();
            changes.Stop();
        }
        Assert.Empty(readOnlyAncestor);
        var group = Assert.Single(changes.Transactions);
        foreach (var events in new IReadOnlyList<ModelChange>[] { local, named, recursive, changes.History, group.Changes })
        {
            var edit = Assert.Single(events);
            Assert.Same(owner, edit.Source);
            Assert.Equal(name, edit.PropertyName);
            Assert.Same(child, edit.OldValue);
            Assert.Null(edit.NewValue);
        }
        foreach (var events in new IReadOnlyList<ReadOnlyModelChange>[]
            { readOnly, viewChanges.History, Assert.Single(viewChanges.Transactions).Changes })
        {
            var edit = Assert.Single(events);
            Assert.Same(view, edit.Source);
            Assert.Equal(name, edit.PropertyName);
            Assert.Same(child.AsReadOnly(), edit.OldValue);
            Assert.Null(edit.NewValue);
        }
        var inverseEdit = Assert.Single(inverse);
        Assert.True(inverseEdit.IsInverse);
        Assert.Same(child, inverseEdit.Source);
        Assert.Equal(anonymous ? "__vmf_prop_unnamed_container" : "parent", inverseEdit.PropertyName);
        Assert.Same(owner, inverseEdit.OldValue);
        Assert.Null(inverseEdit.NewValue);
        CheckDetached(model);
        Assert.Null(child.Parent);
        Assert.True(group.Undo());
        Assert.Same(child, anonymous ? owner.Staging : owner.Left);
        Assert.Same(anonymous ? null : owner, child.Parent);

        void Clear()
        {
            if (anonymous) owner.Staging = null;
            else owner.Left = null;
        }
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void ExchangesSnapshotsAndExistingV1JournalsWithJava(string format)
    {
        var checkpoint = Checkpoint();
        var journal = JournalRecorder<ForestLayout>.RecordFrame(Checkpoint(), ModelSnapshot.Registry, Binding,
            "checkpoint", 0, MoveChild);
        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ORDINARY_CONTAINMENT");
        foreach (bool references in new[] { true, false })
        {
            var codec = SnapshotCodec(format, references);
            string snapshot = codec.Write(checkpoint);
            CheckInitial(codec.Read(snapshot));
            Exchange(interchange, $"shared-inverse-{(references ? "refs" : "values")}.{format}", snapshot,
                java => CheckInitial(codec.Read(java)));
        }
        (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) journalCodec = format switch
        {
            "json" => (JournalJson.Write, JournalJson.Read),
            "yaml" => (JournalYaml.Write, JournalYaml.Read),
            "xml" => (JournalXml.Write, JournalXml.Read),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
        string wire = journalCodec.Write(journal);
        Replay(wire);
        Exchange(interchange, $"shared-inverse-journal.{format}", wire, Replay);

        var detachJournal = JournalRecorder<ForestLayout>.RecordFrame(DetachCheckpoint(), ModelSnapshot.Registry, Binding,
            "checkpoint", 0, model => model.Roots[0]!.Staging = null);
        string detachWire = journalCodec.Write(detachJournal);
        ReplayDetach(detachWire);
        Exchange(interchange, $"scalar-detach-journal.{format}", detachWire, ReplayDetach);
        CheckInitial(checkpoint);

        void Replay(string source)
        {
            var read = journalCodec.Read(source);
            Assert.Equal(1, read.WireVersion);
            CheckMoved(JournalReplayer.Replay(checkpoint, Binding, "checkpoint", read, ModelSnapshot.Registry));
        }

        void ReplayDetach(string source)
        {
            var read = journalCodec.Read(source);
            Assert.Equal(1, read.WireVersion);
            var frame = Assert.Single(read.Transactions);
            var operation = Assert.IsType<JournalSet>(Assert.Single(frame.Operations));
            Assert.Equal("first", operation.Target);
            Assert.Equal("staging", operation.Property);
            Assert.True(operation.Before.IsReference);
            Assert.Equal("twig", operation.Before.Text);
            Assert.Equal("NULL", operation.After.Kind);
            var baseline = DetachCheckpoint();
            CheckDetached(JournalReplayer.Replay(baseline, Binding, "checkpoint", read, ModelSnapshot.Registry));

            var nul = JournalValue.FromScalar(null);
            var legacy = new ChangeJournal(read.Model, read.ModelVersion, read.Root, read.Checkpoint, read.BaseSequence,
                [new JournalFrame(frame.Sequence, [operation, new JournalSet("first", "staging", nul, nul)])]);
            CheckDetached(JournalReplayer.Replay(baseline, Binding, "checkpoint", legacy, ModelSnapshot.Registry));
        }
    }

    private static (Func<ForestLayout, string> Write, Func<string, ForestLayout> Read) SnapshotCodec(string format, bool references) =>
        (format, references) switch
        {
            ("json", true) => (ModelSnapshot.Write, ModelSnapshot.Read<ForestLayout>),
            ("json", false) => (ModelSnapshot.WriteWithoutReferences, ModelSnapshot.ReadWithoutReferences<ForestLayout>),
            ("yaml", true) => (ModelSnapshot.WriteYaml, ModelSnapshot.ReadYaml<ForestLayout>),
            ("yaml", false) => (ModelSnapshot.WriteYamlWithoutReferences, ModelSnapshot.ReadYamlWithoutReferences<ForestLayout>),
            ("xml", true) => (ModelSnapshot.WriteXml, ModelSnapshot.ReadXml<ForestLayout>),
            ("xml", false) => (ModelSnapshot.WriteXmlWithoutReferences, ModelSnapshot.ReadXmlWithoutReferences<ForestLayout>),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };

    private static void Exchange(string? directory, string name, string outbound, Action<string> readJava)
    {
        if (directory is null) return;
        readJava(File.ReadAllText(Path.Combine(directory, "java-" + name)));
        File.WriteAllText(Path.Combine(directory, "csharp-" + name), outbound);
    }

    private static ForestLayout Checkpoint()
    {
        var model = new ForestLayout();
        model.Roots.AddRange([new Bough { Code = "first", Left = new Twig { Code = "twig" } }, new Bough { Code = "second" }]);
        return model;
    }

    private static ForestLayout DetachCheckpoint()
    {
        var model = Checkpoint();
        var first = model.Roots[0]!;
        first.Staging = first.Left;
        return model;
    }

    private static void MoveChild(ForestLayout model)
    {
        var first = model.Roots[0]!;
        var twig = first.Left;
        first.Right = twig;
        model.Roots[1]!.Branches.Add(twig);
    }

    private static void CheckInitial(ForestLayout model)
    {
        var first = model.Roots[0]!;
        Assert.Same(first, Assert.IsType<Twig>(first.Left).Parent);
    }

    private static void CheckMoved(ForestLayout model)
    {
        var first = model.Roots[0]!;
        var second = model.Roots[1]!;
        Assert.Null(first.Left);
        Assert.Null(first.Right);
        var twig = Assert.IsType<Twig>(Assert.Single(second.Branches));
        Assert.Equal("twig", twig.Code);
        Assert.Same(second, twig.Parent);
    }

    private static void CheckDetached(ForestLayout model)
    {
        var owner = model.Roots[0]!;
        Assert.Null(owner.Left);
        Assert.Null(owner.Staging);
        Assert.Empty(owner.Branches);
    }
}
