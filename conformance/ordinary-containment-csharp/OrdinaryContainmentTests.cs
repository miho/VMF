using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class OrdinaryContainmentTests
{
    private static readonly JournalBinding Binding = new("ordinary-containment", 1, model => model switch
    {
        LegacyLayout => "layout",
        LegacyContainer owner => owner.Name!,
        LegacyChild child => child.Name!,
        _ => throw new ArgumentException("Unexpected journal model.", nameof(model)),
    });

    [Theory]
    [InlineData(ModelChangeScope.Local)]
    [InlineData(ModelChangeScope.Recursive)]
    public void OwningListCreationPreservesInitializationDuringTheAttachCallback(ModelChangeScope scope)
    {
        var root = new LegacyContainer { Name = "root" };
        var child = new LegacyChild { Name = "fresh" };
        var binding = new JournalBinding("ordinary-containment-observer-init", 1, model => model is LegacyContainer ? "root" : "fresh");
        using var observer = root.VmfChanges.AddPropertyListener("children", _ =>
        {
            Assert.Same(root, child.Owner);
            root.Name = "root-edited";
            child.Name = "fresh-edited";
        }, scope);
        using var recorder = JournalRecorder<LegacyContainer>.Start(root, ModelSnapshot.Registry, binding, "initial", 0);
        root.Children.Add(child);
        Assert.Equal(["name", "children"], root.VmfChanges.History.Select(change => change.PropertyName));
        string wire = JournalJson.Write(recorder.Finish());
        Verify(wire);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ORDINARY_CONTAINMENT");
        if (exchange is not null)
        {
            string filename = $"fresh-owning-child-{scope.ToString().ToLowerInvariant()}.json";
            Verify(File.ReadAllText(Path.Combine(exchange, "java-" + filename)));
            File.WriteAllText(Path.Combine(exchange, "csharp-" + filename), wire);
        }

        void Verify(string text)
        {
            var journal = JournalJson.Read(text);
            Assert.Equal(1, journal.WireVersion);
            var ops = Assert.Single(journal.Transactions).Operations;
            Assert.Equal(["children", "name"], ops.Select(op => op.Property));
            Assert.Equal(JournalListEditKind.Add, Assert.IsType<JournalListEdit>(ops[0]).Kind);
            Assert.Equal("fresh", Assert.Single(ops[0].Created).Id);
            var checkpoint = new LegacyContainer { Name = "root" };
            var replay = JournalReplayer.Replay(checkpoint, binding, "initial", journal, ModelSnapshot.Registry);
            var fresh = Assert.Single(replay.Children);
            Assert.NotNull(fresh);
            Assert.Equal("fresh-edited", fresh.Name);
            Assert.Same(replay, fresh.Owner);
            Assert.Equal("root-edited", replay.Name);
            Assert.Empty(checkpoint.Children);
        }
    }

    [Theory]
    [InlineData("local")]
    [InlineData("recursive")]
    [InlineData("direct")]
    public void TransferSourceCallbacksReplayAgainstTheAlreadyRemovedPosition(string scope)
    {
        var checkpoint = TransferFixture();
        var working = ModelSnapshot.Reflection.DeepCopy(checkpoint);
        var source = working.Containers[0];
        var destination = working.Containers[1];
        var moving = source.Children[0]!;
        var kept = source.Children[1];
        var nested = new LegacyChild { Name = "nested" };
        var trace = new List<string>();
        void ReplaceSibling()
        {
            if (trace.Count != 0) return;
            Assert.Same(kept, Assert.Single(source.Children));
            Assert.Same(source, moving.Owner);
            Assert.Same(moving, Assert.Single(destination.Children));
            trace.Add("source");
            source.Children[0] = nested;
            trace.Add("nested");
        }

        using var sourceObserver = scope switch
        {
            "local" => source.VmfChanges.AddPropertyListener("children", _ => ReplaceSibling()),
            "direct" => source.Children.AddListener(_ => ReplaceSibling()),
            _ => working.VmfChanges.AddRecursiveListener(change =>
            {
                if (ReferenceEquals(change.Source, source) && change.PropertyName == "children") ReplaceSibling();
            }),
        };
        using var inverseObserver = moving.VmfChanges.AddPropertyListener("owner",
            change => trace.Add(change.NewValue is null ? "detach" : "attach"));
        using var adoptionObserver = destination.VmfChanges.AddPropertyListener("children", _ => trace.Add("adopt"));
        using var recorder = JournalRecorder<LegacyLayout>.Start(working, ModelSnapshot.Registry, Binding, "checkpoint", 0);
        destination.Children.Add(moving);
        Assert.Equal(["source", "nested", "detach", "attach", "adopt"], trace);
        var history = working.VmfChanges.History.OfType<ModelCollectionChange>().Where(change => change.PropertyName == "children").ToArray();
        Assert.Equal(3, history.Length);
        // The nested edit reaches native history while the source removal is still being delivered.
        Assert.Same(nested, Assert.Single(history[0].Added).Value);
        Assert.Same(moving, Assert.Single(history[1].Removed).Value);
        Assert.Same(moving, Assert.Single(history[2].Added).Value);
        var journal = recorder.Finish();

        var formats = new (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
        };
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ORDINARY_CONTAINMENT");
        foreach (var (format, write, read) in formats)
        {
            string wire = write(journal);
            Verify(read(wire));
            if (exchange is null) continue;
            string name = $"transfer-source-{scope}-journal.{format}";
            Verify(read(File.ReadAllText(Path.Combine(exchange, "java-" + name))));
            File.WriteAllText(Path.Combine(exchange, "csharp-" + name), wire);
        }

        void Verify(ChangeJournal recorded)
        {
            Assert.Equal(1, recorded.WireVersion);
            var edits = Assert.Single(recorded.Transactions).Operations.Select(Assert.IsType<JournalListEdit>).ToArray();
            Assert.Equal(["source", "source", "destination"], edits.Select(edit => edit.Target));
            Assert.Equal([JournalListEditKind.Remove, JournalListEditKind.Set, JournalListEditKind.Add],
                edits.Select(edit => edit.Kind));
            Assert.All(edits, edit => Assert.Equal(0, Assert.Single(edit.Indices)));
            Assert.Equal("moving", Assert.Single(edits[0].Before).Text);
            Assert.Equal("kept", Assert.Single(edits[1].Before).Text);
            Assert.Equal("nested", Assert.Single(edits[1].After).Text);
            Assert.Equal("moving", Assert.Single(edits[2].After).Text);
            var revised = JournalReplayer.Replay(checkpoint, Binding, "checkpoint", recorded, ModelSnapshot.Registry);
            var former = revised.Containers[0];
            var latter = revised.Containers[1];
            var sibling = Assert.Single(former.Children);
            var transferred = Assert.Single(latter.Children);
            Assert.Equal("nested", sibling?.Name);
            Assert.Same(former, sibling!.Owner);
            Assert.Equal("moving", transferred?.Name);
            Assert.Same(latter, transferred!.Owner);
            Assert.Equal(["moving", "kept"], checkpoint.Containers[0].Children.Select(child => child!.Name));
        }

        static LegacyLayout TransferFixture()
        {
            var origin = LegacyContainer.NewBuilder().WithName("source")
                .WithChildren([new LegacyChild { Name = "moving" }, new LegacyChild { Name = "kept" }]).Build();
            return LegacyLayout.NewBuilder().WithContainers([origin, new LegacyContainer { Name = "destination" }]).Build();
        }
    }

    [Fact]
    public void SparseTransferReportsRealSlotsAndUndoRestoresTheAlias()
    {
        var graph = Fixture("repeat");
        var source = graph.Containers[0];
        var target = graph.Containers[1];
        var child = source.Children[0]!;
        Assert.Same(child, source.Children[2]);
        Assert.Same(source, child.Owner);
        Assert.Same(child.AsReadOnly(), source.AsReadOnly().Children[2]);

        var changes = graph.VmfChanges;
        var observed = new List<ModelCollectionChange>();
        using var subscription = changes.AddRecursiveListener(change =>
        {
            if (change is ModelCollectionChange collection) observed.Add(collection);
        });
        changes.Start();
        changes.StartTransaction();
        target.Children.Add(child);
        var transaction = changes.PublishTransaction();
        changes.Stop();
        subscription.Dispose();

        Assert.NotNull(transaction);
        Assert.Collection(observed,
            removal =>
            {
                Assert.Same(source, removal.Source);
                Assert.Equal(new int?[] { 0, 2 }, removal.Removed.Select(entry => entry.Index));
                Assert.Empty(removal.Added);
            },
            insertion =>
            {
                Assert.Same(target, insertion.Source);
                Assert.Empty(insertion.Removed);
                Assert.Equal(0, Assert.Single(insertion.Added).Index);
            });
        Assert.Equal(2, changes.History.Count);
        Assert.Equal("b", Assert.Single(source.Children)?.Name);
        Assert.Same(target, child.Owner);
        Assert.True(transaction.Undo());
        Assert.Equal(new[] { "a", "b", "a" }, source.Children.Select(entry => entry!.Name));
        Assert.Same(child, source.Children[0]);
        Assert.Same(child, source.Children[2]);
        Assert.Same(source, child.Owner);
        Assert.Empty(target.Children);
    }

    [Fact]
    public void PublishedReplacementCanUndoAfterAnObserverAppendsToAnUnrelatedSlot()
    {
        var owner = new LegacyContainer { Name = "before" };
        var original = new LegacyChild { Name = "original" };
        var replacement = new LegacyChild { Name = "replacement" };
        var unrelated = new LegacyChild { Name = "unrelated" };
        owner.Children.Add(original);

        var changes = owner.VmfChanges;
        changes.Start();
        owner.Children[0] = replacement;
        owner.Name = "after";
        var group = changes.PublishTransaction();
        changes.Stop();
        Assert.Same(group, Assert.Single(changes.Transactions));
        Assert.Equal(2, group!.Changes.Count);
        Assert.True(group.CanUndo);

        var observed = new List<string>();
        using (changes.AddLocalListener(change =>
        {
            observed.Add(change.PropertyName);
            if (change.PropertyName == "name") owner.Children.Add(unrelated);
        }))
        {
            Assert.True(group.Undo());
        }

        Assert.Equal(new[] { original, unrelated }, owner.Children);
        Assert.Same(owner, original.Owner);
        Assert.Same(owner, unrelated.Owner);
        Assert.Null(replacement.Owner);
        Assert.Equal(new[] { "name", "children", "children" }, observed);
        Assert.Equal(2, changes.History.Count);

        foreach (var edit in group.Changes) edit.Apply(owner);
        Assert.Equal(new[] { replacement, unrelated }, owner.Children);
        Assert.Equal("after", owner.Name);
        owner.Children[0] = original;
        Assert.False(group.CanUndo);
        Assert.False(group.Undo());
        Assert.Equal("after", owner.Name);
    }

    [Fact]
    public void InsertionUndoMatchesItsSlotButRemovalUndoRequiresTheRecordedCount()
    {
        var owner = new LegacyContainer();
        var original = new LegacyChild();
        var added = new LegacyChild();
        owner.Children.Add(original);

        var changes = owner.VmfChanges;
        changes.Start();
        owner.Children.Add(added);
        changes.Stop();
        var insertion = Assert.Single(changes.History);
        owner.Children.Add(null);
        Assert.True(insertion.CanUndo);
        Assert.True(insertion.Undo());
        Assert.Equal(new[] { original, null }, owner.Children);
        Assert.Null(added.Owner);

        changes.Clear();
        changes.Start();
        owner.Children.RemoveAt(0);
        changes.Stop();
        var removal = Assert.Single(changes.History);
        owner.Children.Add(null);
        Assert.False(removal.CanUndo);
        Assert.False(removal.Undo());
        Assert.Equal(new LegacyChild?[] { null, null }, owner.Children);
        Assert.Null(original.Owner);
    }

    [Fact]
    public void ReinsertingAnExistingChildReordersAndKeepsTheInverse()
    {
        var graph = Fixture("plain");
        var source = graph.Containers[0];
        var child = source.Children[1]!;
        var readOnly = source.AsReadOnly();
        Assert.Null(readOnly.Children[0]);
        Assert.True(source.Children.Add(child));
        Assert.Equal(new string?[] { null, "a", "b" }, source.Children.Select(entry => entry?.Name));
        Assert.Same(source, child.Owner);
        Assert.Same(child.AsReadOnly(), readOnly.Children[2]);
        child.Owner = null;
        Assert.Equal(new string?[] { null, "a" }, source.Children.Select(entry => entry?.Name));
        Assert.Empty(graph.Validate());
    }

    [Fact]
    public void NativeCopyPreservesAliasingAndSerializationRejectsDuplicateIdDefinitions()
    {
        var graph = Fixture("repeat");
        graph.Featured = graph.Containers[0].Children[0];
        var copy = ModelSnapshot.Reflection.DeepCopy(graph);
        Assert.Same(copy.Featured, copy.Containers[0].Children[0]);
        Assert.Same(copy.Featured, copy.Containers[0].Children[2]);
        Assert.NotSame(graph.Featured, copy.Featured);
        Assert.Same(copy.Containers[0], copy.Featured!.Owner);
        var error = Assert.Throws<JsonException>(() => ModelSnapshot.Write(graph));
        Assert.Contains("repeated containment definition", error.Message);
    }

    [Fact]
    public void GraphPatchCanAddAnOccurrenceWithoutReplacingTheOwnedObject()
    {
        var graph = Fixture("plain");
        var owner = graph.Containers[0];
        var existing = owner.Children[2];
        var desired = ModelSnapshot.Reflection.DeepCopy(owner);
        desired.Children[0] = desired.Children[2];

        var patch = ModelSnapshot.Reflection.Diff(owner, desired);
        patch.Apply(owner);
        Assert.Same(existing, owner.Children[0]);
        Assert.Same(existing, owner.Children[2]);
        Assert.Same(owner, existing!.Owner);
        patch.Undo(owner);
        Assert.Null(owner.Children[0]);
        Assert.Same(existing, owner.Children[2]);
        Assert.Same(owner, existing.Owner);
    }

    [Fact]
    public void ExistingV1JournalReplaysSparseTransfersAndNullEditsAcrossFormats()
    {
        var checkpoint = Fixture("plain");
        var working = ModelSnapshot.Read<LegacyLayout>(ModelSnapshot.Write(checkpoint));
        var journal = JournalRecorder<LegacyLayout>.RecordFrame(working, ModelSnapshot.Registry, Binding,
            "checkpoint", 0, model =>
            {
                var source = model.Containers[0];
                var child = source.Children[2];
                source.Children[0] = child;
                model.Containers[1].Children.Add(child);
                source.Children.Add(null);
            });
        var formats = new (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
        };
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ORDINARY_CONTAINMENT");
        foreach (var (name, write, read) in formats)
        {
            string wire = write(journal);
            VerifyReplay(read(wire));
            if (exchange is null) continue;
            VerifyReplay(read(File.ReadAllText(Path.Combine(exchange, $"java-journal.{name}"))));
            File.WriteAllText(Path.Combine(exchange, $"csharp-journal.{name}"), wire);
        }
        AssertSnapshot(checkpoint, "plain");

        void VerifyReplay(ChangeJournal records)
        {
            Assert.Equal(1, records.WireVersion);
            var restored = JournalReplayer.Replay(checkpoint, Binding, "checkpoint", records, ModelSnapshot.Registry);
            var source = restored.Containers[0];
            var target = restored.Containers[1];
            Assert.Equal(new string?[] { "b", null }, source.Children.Select(entry => entry?.Name));
            Assert.Same(source, source.Children[0]!.Owner);
            var moved = Assert.Single(target.Children);
            Assert.Equal("a", moved?.Name);
            Assert.Same(target, moved!.Owner);
        }
    }

    [Fact]
    public void V1ReplaysTemporaryAliasesRestoredByUndoWithinOneFrame()
    {
        var failure = Assert.Throws<InvalidOperationException>(() => Record(removeAliasBeforeCommit: false));
        Assert.Contains("containment entry", failure.ToString());
        var journal = Record(removeAliasBeforeCommit: true);
        var formats = new (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
        };
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ORDINARY_CONTAINMENT");
        foreach (var (name, write, read) in formats)
        {
            string wire = write(journal);
            VerifyReplay(read(wire), sparseInsert: false);
            if (exchange is null) continue;
            VerifyReplay(read(File.ReadAllText(Path.Combine(exchange, $"java-alias-undo-journal.{name}"))),
                sparseInsert: true);
            File.WriteAllText(Path.Combine(exchange, $"csharp-alias-undo-journal.{name}"), wire);
        }

        static ChangeJournal Record(bool removeAliasBeforeCommit)
        {
            var graph = Fixture("repeat");
            var changes = graph.VmfChanges;
            changes.Start();
            TransferFirstChild(graph);
            changes.Stop();
            var transfer = Assert.Single(changes.Transactions);
            return JournalRecorder<LegacyLayout>.RecordFrame(graph, ModelSnapshot.Registry, Binding,
                "checkpoint", 0, model =>
                {
                    Assert.True(transfer.Undo());
                    if (removeAliasBeforeCommit) model.Containers[0].Children.RemoveAt(0);
                });
        }

        static void VerifyReplay(ChangeJournal records, bool sparseInsert)
        {
            Assert.Equal(1, records.WireVersion);
            var operations = Assert.Single(records.Transactions).Operations.Select(Assert.IsType<JournalListEdit>).ToArray();
            const JournalListEditKind remove = JournalListEditKind.Remove;
            const JournalListEditKind add = JournalListEditKind.Add;
            Assert.Equal(sparseInsert ? [remove, add, remove] : new[] { remove, remove, add, remove },
                operations.Select(edit => edit.Kind));
            Assert.Equal(sparseInsert ? [0, 2] : new[] { 0, 1, 2 }, operations[^2].Indices);
            Assert.All(operations[^2].After, value => Assert.True(value.IsReference));
            Assert.Equal(sparseInsert ? ["a", "a"] : new[] { "a", "b", "a" },
                operations[^2].After.Select(value => value.Text));
            var checkpoint = Fixture("repeat");
            TransferFirstChild(checkpoint);
            var restored = JournalReplayer.Replay(checkpoint, Binding, "checkpoint", records, ModelSnapshot.Registry);
            var owner = restored.Containers[0];
            Assert.Equal(new[] { "b", "a" }, owner.Children.Select(child => child?.Name));
            Assert.Same(owner, owner.Children[1]!.Owner);
            Assert.Empty(restored.Containers[1].Children);
        }

        static void TransferFirstChild(LegacyLayout graph) =>
            graph.Containers[1].Children.Add(graph.Containers[0].Children[0]);
    }

    [Fact]
    public void UnevenNativeReinsertionRecordsExistingRemoveAndAddOperations()
    {
        var checkpoint = Fixture("plain");
        var working = ModelSnapshot.Reflection.DeepCopy(checkpoint);
        var journal = JournalRecorder<LegacyLayout>.RecordFrame(working, ModelSnapshot.Registry, Binding,
            "checkpoint", 0, model =>
            {
                var children = model.Containers[0].Children;
                children.AddRange([new LegacyChild { Name = "c" }, children[1]]);
            });
        Assert.Equal(1, journal.WireVersion);
        Assert.Equal(new[] { JournalListEditKind.Remove, JournalListEditKind.Add },
            Assert.Single(journal.Transactions).Operations.OfType<JournalListEdit>().Select(edit => edit.Kind));
        var replayed = JournalReplayer.Replay(checkpoint, Binding, "checkpoint", journal, ModelSnapshot.Registry);
        Assert.Equal(new string?[] { null, "a", "c", "b" }, replayed.Containers[0].Children.Select(entry => entry?.Name));
        Assert.All(replayed.Containers[0].Children.OfType<LegacyChild>(),
            child => Assert.Same(replayed.Containers[0], child.Owner));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void MixedOrdinaryReinsertExchangesOneV1Frame(bool transfer)
    {
        var checkpoint = MixedFixture(transfer);
        var working = ModelSnapshot.Reflection.DeepCopy(checkpoint);
        var owner = working.Containers[0];
        var source = working.Containers[1];
        var a = owner.Children[0]!;
        var c = transfer ? source.Children[0]! : new LegacyChild { Name = "c" };
        var journal = JournalRecorder<LegacyLayout>.RecordFrame(working, ModelSnapshot.Registry, Binding,
            "checkpoint", 0, _ => owner.Children.AddRange([c, a]));
        Assert.Equal(["b", "c", "a"], owner.Children.Select(child => child?.Name));
        Assert.Same(owner, a.Owner);
        Assert.Same(owner, c.Owner);

        var formats = new (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
        };
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ORDINARY_CONTAINMENT");
        string scenario = transfer ? "transfer" : "fresh";
        foreach (var (format, write, read) in formats)
        {
            var wire = write(journal);
            Verify(read(wire));
            if (exchange is null) continue;
            Verify(read(File.ReadAllText(Path.Combine(exchange, $"java-{scenario}-mixed-reinsert-journal.{format}"))));
            File.WriteAllText(Path.Combine(exchange, $"csharp-{scenario}-mixed-reinsert-journal.{format}"), wire);
        }

        void Verify(ChangeJournal records)
        {
            Assert.Equal(1, records.WireVersion);
            var edits = Assert.Single(records.Transactions).Operations.Select(Assert.IsType<JournalListEdit>).ToArray();
            Assert.Equal(transfer ? 3 : 2, edits.Length);
            if (transfer)
            {
                Assert.Equal(JournalListEditKind.Remove, edits[0].Kind);
                Assert.Equal("source", edits[0].Target);
                Assert.Equal([0], edits[0].Indices);
                Assert.Equal("c", Assert.Single(edits[0].Before).Text);
            }
            var remove = edits[^2];
            var add = edits[^1];
            Assert.Equal(JournalListEditKind.Remove, remove.Kind);
            Assert.Equal("destination", remove.Target);
            Assert.Equal([0, 1], remove.Indices);
            Assert.Equal(["a", "b"], remove.Before.Select(value => value.Text));
            Assert.Equal(JournalListEditKind.Add, add.Kind);
            Assert.Equal("destination", add.Target);
            Assert.Equal([0, 1, 2], add.Indices);
            Assert.Equal(["b", "c", "a"], add.After.Select(value => value.Text));
            Assert.Empty(remove.Created);
            if (transfer) Assert.Empty(add.Created);
            else Assert.Equal("c", Assert.Single(add.Created).Id);

            var result = JournalReplayer.Replay(checkpoint, Binding, "checkpoint", records, ModelSnapshot.Registry);
            Assert.Equal(["b", "c", "a"], result.Containers[0].Children.Select(child => child?.Name));
            Assert.Empty(result.Containers[1].Children);
            Assert.All(result.Containers[0].Children, child => Assert.Same(result.Containers[0], child!.Owner));
            Assert.Equal(["a", "b"], checkpoint.Containers[0].Children.Select(child => child?.Name));
        }
    }

    [Theory]
    [InlineData(false, ModelChangeScope.Local)]
    [InlineData(false, ModelChangeScope.Recursive)]
    [InlineData(true, ModelChangeScope.Local)]
    [InlineData(true, ModelChangeScope.Recursive)]
    public void OwningAddAndSortReplayBeforeTheirReentrantObserverEdits(bool sort, ModelChangeScope scope)
    {
        var checkpoint = new LegacyContainer { Name = "owner" };
        checkpoint.Children.AddRange([new LegacyChild { Name = "left" }, new LegacyChild { Name = "middle" },
            new LegacyChild { Name = "right" }]);
        var owner = ModelSnapshot.Reflection.DeepCopy(checkpoint);
        var children = owner.Children;
        var outer = sort ? children[2]! : new LegacyChild { Name = "outer" };
        var nested = new LegacyChild { Name = "nested" };
        int index = sort ? 0 : 3;
        bool notified = false;
        using var subscription = owner.VmfChanges.AddPropertyListener("children", change =>
        {
            if (notified || !ReferenceEquals(change.Source, owner)) return;
            notified = true;
            Assert.Same(outer, children[index]);
            Assert.Same(owner, outer.Owner);
            children[index] = nested;
            Assert.Same(owner, nested.Owner);
            Assert.Single(owner.VmfChanges.History);
        }, scope);

        using var recorder = JournalRecorder<LegacyContainer>.Start(owner, ModelSnapshot.Registry, Binding, "checkpoint", 0);
        if (sort) children.Sort((a, b) => StringComparer.Ordinal.Compare(b?.Name, a?.Name));
        else children.Add(outer);
        Assert.True(notified);
        Assert.Null(outer.Owner);
        var history = owner.VmfChanges.History.Select(Assert.IsType<ModelCollectionChange>).ToArray();
        Assert.Equal(2, history.Length);
        Assert.Same(nested, Assert.Single(history[0].Added).Value);
        Assert.Equal(index, history[0].Added[0].Index);
        Assert.Same(outer, history[1].Added[0].Value);
        Assert.Equal(sort ? [0, 2] : new[] { 3 }, history[1].Added.Select(slot => slot.Index!.Value));
        Assert.NotNull(recorder.PublishFrame());
        var journal = recorder.Finish();

        var formats = new (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
        };
        var exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ORDINARY_CONTAINMENT");
        foreach (var (format, write, read) in formats)
        {
            var wire = write(journal);
            Verify(read(wire));
            if (exchange is null) continue;
            string name = $"owning-{(sort ? "sort" : "add")}-{scope.ToString().ToLowerInvariant()}-journal.{format}";
            Verify(read(File.ReadAllText(Path.Combine(exchange, "java-" + name))));
            File.WriteAllText(Path.Combine(exchange, "csharp-" + name), wire);
        }

        void Verify(ChangeJournal records)
        {
            Assert.Equal(1, records.WireVersion);
            var edits = Assert.Single(records.Transactions).Operations.Select(Assert.IsType<JournalListEdit>).ToArray();
            Assert.Equal(2, edits.Length);
            Assert.Equal(sort ? JournalListEditKind.Set : JournalListEditKind.Add, edits[0].Kind);
            Assert.Equal(sort ? [0, 2] : new[] { 3 }, edits[0].Indices);
            Assert.Equal(sort ? ["right", "left"] : new[] { "outer" }, edits[0].After.Select(value => value.Text));
            if (sort) Assert.Empty(edits[0].Created);
            else Assert.Equal("outer", Assert.Single(edits[0].Created).Id);
            Assert.Equal(JournalListEditKind.Set, edits[1].Kind);
            Assert.Equal(index, Assert.Single(edits[1].Indices));
            Assert.Equal(sort ? "right" : "outer", Assert.Single(edits[1].Before).Text);
            Assert.Equal("nested", Assert.Single(edits[1].After).Text);
            Assert.Equal("nested", Assert.Single(edits[1].Created).Id);
            var restored = JournalReplayer.Replay(checkpoint, Binding, "checkpoint", records, ModelSnapshot.Registry);
            Assert.Equal(sort ? ["nested", "middle", "left"] : new[] { "left", "middle", "right", "nested" },
                restored.Children.Select(child => child!.Name));
            Assert.All(restored.Children, child => Assert.Same(restored, child!.Owner));
            Assert.Equal(["left", "middle", "right"], checkpoint.Children.Select(child => child!.Name));
        }
    }

    [Theory]
    [InlineData(ModelChangeScope.Local)]
    [InlineData(ModelChangeScope.Recursive)]
    public void DirectOwningRemovalReplaysBeforeASecondRemovalAtTheSamePosition(ModelChangeScope scope)
    {
        var seed = new LegacyContainer { Name = "owner" };
        seed.Children.AddRange([new LegacyChild { Name = "left" }, new LegacyChild { Name = "middle" },
            new LegacyChild { Name = "right" }]);
        var owner = ModelSnapshot.Reflection.DeepCopy(seed);
        var first = Assert.IsType<LegacyChild>(owner.Children[0]);
        var middle = Assert.IsType<LegacyChild>(owner.Children[1]);
        var callbacks = new List<string>();
        using var listener = owner.VmfChanges.AddPropertyListener("children", change =>
        {
            var removed = Removed(change);
            callbacks.Add(removed.Name!);
            if (ReferenceEquals(removed, first)) owner.Children.RemoveAt(0);
        }, scope);
        using var recorder = JournalRecorder<LegacyContainer>.Start(owner, ModelSnapshot.Registry, Binding, "checkpoint", 0);
        owner.Children.RemoveAt(0);
        Assert.Equal(["left", "middle"], callbacks);
        Assert.Null(first.Owner);
        Assert.Null(middle.Owner);
        Assert.Collection(owner.VmfChanges.History,
            change => Assert.Same(middle, Removed(change)), change => Assert.Same(first, Removed(change)));
        var journal = recorder.Finish();

        (string Extension, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[] formats =
        [
            ("json", JournalJson.Write, JournalJson.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
        ];
        var exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ORDINARY_CONTAINMENT");
        foreach (var format in formats)
        {
            var wire = format.Write(journal);
            Verify(format.Read(wire));
            if (exchange is null) continue;
            string name = $"owning-remove-{scope.ToString().ToLowerInvariant()}-journal.{format.Extension}";
            Verify(format.Read(File.ReadAllText(Path.Combine(exchange, "java-" + name))));
            File.WriteAllText(Path.Combine(exchange, "csharp-" + name), wire);
        }

        void Verify(ChangeJournal records)
        {
            Assert.Equal(1, records.WireVersion);
            var edits = Assert.Single(records.Transactions).Operations.Select(Assert.IsType<JournalListEdit>).ToArray();
            Assert.Equal(["left", "middle"], edits.Select(edit => Assert.Single(edit.Before).Text));
            Assert.All(edits, edit =>
            {
                Assert.Equal(JournalListEditKind.Remove, edit.Kind);
                Assert.Equal(0, Assert.Single(edit.Indices));
                Assert.Empty(edit.Created);
            });
            var result = JournalReplayer.Replay(seed, Binding, "checkpoint", records, ModelSnapshot.Registry);
            var child = Assert.IsType<LegacyChild>(Assert.Single(result.Children));
            Assert.Equal("right", child.Name);
            Assert.Same(result, child.Owner);
            Assert.Equal(["left", "middle", "right"], seed.Children.Select(value => value!.Name));
        }

        static LegacyChild Removed(ModelChange change) =>
            Assert.IsType<LegacyChild>(Assert.Single(Assert.IsType<ModelCollectionChange>(change).Removed).Value);
    }

    [Fact]
    public void NestedMixedOrdinaryReinsertPreservesLiveHistoryAndReplaysInCausalOrder()
    {
        var checkpoint = MixedFixture(transfer: false);
        var graph = ModelSnapshot.Reflection.DeepCopy(checkpoint);
        var owner = graph.Containers[0];
        var children = owner.Children;
        var a = children[0];
        var c = new LegacyChild { Name = "c" };
        var d = new LegacyChild { Name = "d" };
        var failure = new InvalidOperationException("nested source observer");
        var trace = new List<string>();
        using var subscription = graph.VmfChanges.AddRecursiveListener(change =>
        {
            if (!ReferenceEquals(change.Source, owner) || change is not ModelCollectionChange { Added.Count: 3 }) return;
            trace.Add("before nested edits");
            Assert.Equal(["b", "c", "a"], children.Select(child => child?.Name));
            Assert.Same(owner, c.Owner);
            children[1] = d;
            children.Add(null);
            trace.Add("after nested edits");
            Assert.Equal(2, graph.VmfChanges.History.Count);
            throw failure;
        });

        using var recorder = JournalRecorder<LegacyLayout>.Start(graph, ModelSnapshot.Registry, Binding, "checkpoint", 0);
        Assert.Same(failure, Assert.Throws<InvalidOperationException>(() => children.AddRange([c, a])));
        Assert.Equal(["before nested edits", "after nested edits"], trace);
        Assert.Equal(["b", "d", "a", null], children.Select(child => child?.Name));
        Assert.Null(c.Owner);
        var history = graph.VmfChanges.History.Select(Assert.IsType<ModelCollectionChange>).ToArray();
        Assert.Equal(3, history.Length);
        Assert.Same(d, Assert.Single(history[0].Added).Value);
        Assert.Null(Assert.Single(history[1].Added).Value);
        Assert.Same(c, history[2].Added[1].Value);
        Assert.NotNull(recorder.PublishFrame());
        var journal = recorder.Finish();

        var formats = new (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
        };
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ORDINARY_CONTAINMENT");
        foreach (var (format, write, read) in formats)
        {
            var wire = write(journal);
            Verify(read(wire));
            if (exchange is null) continue;
            Verify(read(File.ReadAllText(Path.Combine(exchange, $"java-nested-mixed-reinsert-journal.{format}"))));
            File.WriteAllText(Path.Combine(exchange, $"csharp-nested-mixed-reinsert-journal.{format}"), wire);
        }

        void Verify(ChangeJournal records)
        {
            Assert.Equal(1, records.WireVersion);
            var edits = Assert.Single(records.Transactions).Operations.Select(Assert.IsType<JournalListEdit>).ToArray();
            Assert.Equal(4, edits.Length);
            Assert.Equal(JournalListEditKind.Remove, edits[0].Kind);
            Assert.Equal([0, 1], edits[0].Indices);
            Assert.Equal(["a", "b"], edits[0].Before.Select(value => value.Text));
            Assert.Empty(edits[0].Created);
            Assert.Equal(JournalListEditKind.Add, edits[1].Kind);
            Assert.Equal([0, 1, 2], edits[1].Indices);
            Assert.Equal(["b", "c", "a"], edits[1].After.Select(value => value.Text));
            Assert.Equal("c", Assert.Single(edits[1].Created).Id);
            Assert.Equal(JournalListEditKind.Set, edits[2].Kind);
            Assert.Equal([1], edits[2].Indices);
            Assert.Equal("c", Assert.Single(edits[2].Before).Text);
            Assert.Equal("d", Assert.Single(edits[2].After).Text);
            Assert.Equal("d", Assert.Single(edits[2].Created).Id);
            Assert.Equal(JournalListEditKind.Add, edits[3].Kind);
            Assert.Equal([3], edits[3].Indices);
            Assert.Equal("NULL", Assert.Single(edits[3].After).Kind);
            var restored = JournalReplayer.Replay(checkpoint, Binding, "checkpoint", records, ModelSnapshot.Registry);
            Assert.Equal(["b", "d", "a", null], restored.Containers[0].Children.Select(child => child?.Name));
            Assert.Same(restored.Containers[0], restored.Containers[0].Children[1]!.Owner);
            Assert.Equal(["a", "b"], checkpoint.Containers[0].Children.Select(child => child?.Name));
        }
    }

    [Fact]
    public void MixedOrdinaryReinsertRepairsBeforeNotifyingDestinationAndDefersSourceFailure()
    {
        var graph = MixedFixture(transfer: true);
        var owner = graph.Containers[0];
        var source = graph.Containers[1];
        var a = owner.Children[0]!;
        var c = source.Children[0]!;
        var contender = new LegacyContainer { Name = "contender" };
        var events = new List<string>();
        var failure = new InvalidOperationException("source observer");
        using var sourceFailing = source.Children.AddListener(_ =>
        {
            events.Add("source");
            Assert.Equal(["b", "c", "a"], owner.Children.Select(child => child?.Name));
            Assert.Same(owner, a.Owner);
            Assert.Same(source, c.Owner);
            Assert.Throws<ArgumentException>(() => source.Children.Add(c));
            Assert.Throws<ArgumentException>(() => contender.Children.Add(a));
            Assert.Empty(contender.Children);
            throw failure;
        });
        using var sourceLater = source.Children.AddListener(_ => events.Add("source-later"));
        var readOnly = owner.AsReadOnly().Children;
        using var projected = readOnly.AddListener(edit =>
        {
            if (edit.Removed.Count == 0) return;
            events.Add("readonly");
            Assert.Same(readOnly, edit.Source);
            Assert.Same(a.AsReadOnly(), edit.Removed[0].Value);
            Assert.Same(c.AsReadOnly(), edit.Added[1].Value);
        });
        using var destination = owner.Children.AddListener(edit =>
        {
            if (edit.Removed.Count == 0)
            {
                events.Add("nested");
                return;
            }
            events.Add("destination");
            Assert.Same(owner.Children, edit.Source);
            Assert.Same(owner, a.Owner);
            Assert.Same(owner, c.Owner);
            Assert.Equal([0, 1], edit.Removed.Select(entry => entry.Index));
            Assert.Equal([0, 1, 2], edit.Added.Select(entry => entry.Index));
            owner.Children.Add(null);
        });
        Assert.Same(failure, Assert.Throws<InvalidOperationException>(() => owner.Children.AddRange([c, a])));
        Assert.Equal(["source", "source-later", "readonly", "destination", "nested"], events);
        Assert.Equal(["b", "c", "a", null], owner.Children.Select(child => child?.Name));

        var undoOwner = MixedFixture(transfer: false).Containers[0];
        var retained = undoOwner.Children[0]!;
        var fresh = new LegacyChild { Name = "c" };
        var changes = undoOwner.VmfChanges;
        changes.Start();
        undoOwner.Children.AddRange([fresh, retained]);
        var group = changes.PublishTransaction();
        changes.Stop();
        var edit = Assert.Single(group!.Changes);
        Assert.True(group.Undo());
        Assert.Equal(["a", "b"], undoOwner.Children.Select(child => child?.Name));
        Assert.Same(undoOwner, retained.Owner);
        Assert.Null(fresh.Owner);
        edit.Apply(undoOwner);
        Assert.Equal(["b", "c", "a"], undoOwner.Children.Select(child => child?.Name));
        Assert.Same(undoOwner, fresh.Owner);

        var sequential = MixedFixture(transfer: false).Containers[0];
        var sequentialEvents = new List<string>();
        using var observeSequential = sequential.Children.AddListener(_ =>
            sequentialEvents.Add(string.Join(",", sequential.Children.Select(child => child?.Name))));
        sequential.Children.Add(new LegacyChild { Name = "c" });
        sequential.Children.Add(sequential.Children[0]);
        Assert.Equal(["a,b,c", "b,c,a"], sequentialEvents);
    }

    private static LegacyLayout MixedFixture(bool transfer)
    {
        var destination = new LegacyContainer { Name = "destination" };
        destination.Children.AddRange([new LegacyChild { Name = "a" }, new LegacyChild { Name = "b" }]);
        var source = new LegacyContainer { Name = "source" };
        if (transfer) source.Children.Add(new LegacyChild { Name = "c" });
        var graph = new LegacyLayout();
        graph.Containers.AddRange([destination, source]);
        return graph;
    }

    [Fact]
    public void RootInsertExchangesNestedJavaSublistPositions()
    {
        var checkpoint = MixedFixture(transfer: false);
        checkpoint.Containers[0].Children.Add(new LegacyChild { Name = "d" });
        var graph = ModelSnapshot.Reflection.DeepCopy(checkpoint);
        var children = graph.Containers[0].Children;
        var journal = JournalRecorder<LegacyLayout>.RecordFrame(graph, ModelSnapshot.Registry, Binding, "checkpoint", 0,
            _ => children.InsertRange(2, [new LegacyChild { Name = "c" }, children[0]]));
        var formats = new (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
        };
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ORDINARY_CONTAINMENT");
        foreach (var (format, write, read) in formats)
        {
            var document = write(journal);
            Verify(read(document));
            if (exchange is null) continue;
            Verify(read(File.ReadAllText(Path.Combine(exchange, $"java-sublist-reinsert-journal.{format}"))));
            File.WriteAllText(Path.Combine(exchange, $"csharp-sublist-reinsert-journal.{format}"), document);
        }

        void Verify(ChangeJournal records)
        {
            Assert.Equal(1, records.WireVersion);
            var edits = Assert.Single(records.Transactions).Operations.Select(Assert.IsType<JournalListEdit>).ToArray();
            Assert.Equal(2, edits.Length);
            var remove = edits[0];
            var add = edits[1];
            Assert.Equal(JournalListEditKind.Remove, remove.Kind);
            Assert.Equal([0, 1], remove.Indices);
            Assert.Equal(["a", "b"], remove.Before.Select(value => value.Text));
            Assert.Equal(JournalListEditKind.Add, add.Kind);
            Assert.Equal([0, 1, 2], add.Indices);
            Assert.Equal(["b", "c", "a"], add.After.Select(value => value.Text));
            Assert.Equal("c", Assert.Single(add.Created).Id);
            var restored = JournalReplayer.Replay(checkpoint, Binding, "checkpoint", records, ModelSnapshot.Registry);
            Assert.Equal(["b", "c", "a", "d"], restored.Containers[0].Children.Select(child => child?.Name));
            Assert.Same(restored.Containers[0], restored.Containers[0].Children[3]!.Owner);
        }
    }

    [Fact]
    public void OrdinaryReinsertExchangesOneStablePositionalV1Edit()
    {
        var checkpoint = Fixture("plain");
        var working = ModelSnapshot.Reflection.DeepCopy(checkpoint);
        var owner = working.Containers[0];
        var children = owner.Children;
        var child = children[1]!;
        var events = new List<ModelListChange<LegacyChild?>>();
        using var listener = children.AddListener(edit =>
        {
            Assert.Same(children, edit.Source);
            Assert.Same(owner, child.Owner);
            events.Add(edit);
        });
        var journal = JournalRecorder<LegacyLayout>.RecordFrame(working, ModelSnapshot.Registry, Binding,
            "checkpoint", 0, _ =>
            {
                children.Insert(0, child);
                Assert.Empty(events);
                children.Add(child);
            });
        var movement = Assert.Single(events);
        Assert.Equal([1, 2], movement.Removed.Select(entry => entry.Index));
        Assert.Equal([1, 2], movement.Added.Select(entry => entry.Index));
        Assert.Same(child, children[2]);
        var formats = new (string Name, Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            ("json", JournalJson.Write, JournalJson.Read),
            ("yaml", JournalYaml.Write, JournalYaml.Read),
            ("xml", JournalXml.Write, JournalXml.Read),
        };
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ORDINARY_CONTAINMENT");
        foreach (var (format, write, read) in formats)
        {
            var wire = write(journal);
            Verify(read(wire));
            if (exchange is null) continue;
            Verify(read(File.ReadAllText(Path.Combine(exchange, $"java-reinsert-journal.{format}"))));
            File.WriteAllText(Path.Combine(exchange, $"csharp-reinsert-journal.{format}"), wire);
        }

        void Verify(ChangeJournal records)
        {
            Assert.Equal(1, records.WireVersion);
            var edit = Assert.IsType<JournalListEdit>(Assert.Single(Assert.Single(records.Transactions).Operations));
            Assert.Equal(JournalListEditKind.Set, edit.Kind);
            Assert.Equal([1, 2], edit.Indices);
            Assert.Equal(["b", "a"], edit.Before.Select(value => value.Text));
            Assert.Equal(["a", "b"], edit.After.Select(value => value.Text));
            var replayed = JournalReplayer.Replay(checkpoint, Binding, "checkpoint", records, ModelSnapshot.Registry);
            var result = replayed.Containers[0];
            Assert.Equal([null, "a", "b"], result.Children.Select(entry => entry?.Name));
            Assert.Equal([null, "b", "a"], checkpoint.Containers[0].Children.Select(entry => entry?.Name));
            Assert.Same(result, result.Children[1]!.Owner);
            Assert.Same(result, result.Children[2]!.Owner);
        }

        var bulk = Fixture("plain").Containers[0];
        var bulkEdits = new List<ModelListChange<LegacyChild?>>();
        using var observeBulk = bulk.Children.AddListener(bulkEdits.Add);
        bulk.Children.AddRange([bulk.Children[2], bulk.Children[1]]);
        Assert.Equal([null, "a", "b"], bulk.Children.Select(entry => entry?.Name));
        var bulkMove = Assert.Single(bulkEdits);
        Assert.Equal([1, 2], bulkMove.Removed.Select(entry => entry.Index));
        Assert.Equal([1, 2], bulkMove.Added.Select(entry => entry.Index));
    }

    [Theory]
    [InlineData("json", "plain")]
    [InlineData("json", "refs")]
    [InlineData("json", "repeat")]
    [InlineData("yaml", "plain")]
    [InlineData("yaml", "refs")]
    [InlineData("yaml", "repeat")]
    [InlineData("xml", "plain")]
    [InlineData("xml", "refs")]
    [InlineData("xml", "repeat")]
    public void JavaAndCSharpExchangeOrderedNullableContainment(string format, string profile)
    {
        var graph = Fixture(profile);
        bool plain = profile == "plain";
        (Func<LegacyLayout, string> Write, Func<string, LegacyLayout> Read) codec = (format, plain) switch
        {
            ("json", true) => (ModelSnapshot.WritePlain<LegacyLayout>, ModelSnapshot.ReadPlain<LegacyLayout>),
            ("json", false) => (ModelSnapshot.Write, ModelSnapshot.Read<LegacyLayout>),
            ("yaml", true) => (ModelSnapshot.WritePlainYaml<LegacyLayout>, ModelSnapshot.ReadPlainYaml<LegacyLayout>),
            ("yaml", false) => (ModelSnapshot.WriteYaml, ModelSnapshot.ReadYaml<LegacyLayout>),
            ("xml", true) => (ModelSnapshot.WritePlainXml<LegacyLayout>, ModelSnapshot.ReadPlainXml<LegacyLayout>),
            ("xml", false) => (ModelSnapshot.WriteXml, ModelSnapshot.ReadXml<LegacyLayout>),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
        string wire = codec.Write(graph);
        AssertSnapshot(codec.Read(wire), profile);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_ORDINARY_CONTAINMENT") is { } exchange)
        {
            AssertSnapshot(codec.Read(File.ReadAllText(Path.Combine(exchange, $"java-{profile}.{format}"))), profile);
            File.WriteAllText(Path.Combine(exchange, $"csharp-{profile}.{format}"), wire);
        }
    }

    private static void AssertSnapshot(LegacyLayout graph, string profile)
    {
        var source = graph.Containers[0];
        Assert.Equal(new string?[] { profile == "repeat" ? "a" : null, "b", "a" },
            source.Children.Select(entry => entry?.Name));
        Assert.NotSame(source.Children[0], source.Children[2]);
        Assert.Same(source, source.Children[1]!.Owner);
        Assert.Same(source, source.Children[2]!.Owner);
        Assert.Same(profile == "refs" ? source.Children[1] : null, graph.Featured);
    }

    private static LegacyLayout Fixture(string profile)
    {
        var first = new LegacyChild { Name = "a" };
        var second = new LegacyChild { Name = "b" };
        var source = LegacyContainer.NewBuilder().WithName("source")
            .WithChildren(new LegacyChild?[] { first, null, second, first }).Build();
        Assert.Equal(new string?[] { null, "b", "a" }, source.Children.Select(entry => entry?.Name));
        var graph = LegacyLayout.NewBuilder().WithContainers(new[] { source, new LegacyContainer { Name = "target" } }).Build();
        if (profile == "repeat") source.Children[0] = first;
        if (profile == "refs") graph.Featured = second;
        return graph;
    }
}
