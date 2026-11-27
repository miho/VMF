using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;
using SummarySnapshot = Eu.Mihosoft.Vmf.Conformance.Modelsummaries.ModelSnapshot;
using SummaryValues = Eu.Mihosoft.Vmf.Conformance.Modelsummaries.SummaryValues;

namespace Vmf.CSharp.Conformance;

public class ObserverOrderTests
{
    [Theory]
    [InlineData(true, true)]
    [InlineData(false, true)]
    [InlineData(true, false)]
    [InlineData(false, false)]
    public void AncestorOrderFollowsRegistrationOnTheSource(bool ancestorFirst, bool attachedFirst)
    {
        var root = new ArchiveNode();
        var child = new ArchiveNode();
        if (attachedFirst) root.Children.Add(child);
        var trace = new List<string>();
        using var first = ancestorFirst ? SubscribeToAncestor() : SubscribeToChild();
        using var second = ancestorFirst ? SubscribeToChild() : SubscribeToAncestor();
        if (!attachedFirst) root.Children.Add(child);

        string[] expected = ancestorFirst && attachedFirst ? ["root", "child"] : ["child", "root"];
        child.Name = "edit";
        Assert.Equal(expected, trace);
        trace.Clear();
        child.Trail.Add(new ArchiveNode());
        Assert.Equal(expected, trace);

        IDisposable SubscribeToAncestor() => root.VmfChanges.AddFilteredListener(IsObserved, _ => trace.Add("root"),
            ModelChangeScope.Recursive);
        IDisposable SubscribeToChild() => child.VmfChanges.AddFilteredListener(IsObserved, _ => trace.Add("child"));
        static bool IsObserved(ModelChange change) => change.PropertyName is "name" or "trail";
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void SameSourceGroupsDeliverAllTheirOwnListenersBeforeTheNextScope(bool localFirst)
    {
        var root = new ArchiveNode();
        var trace = new List<string>();
        using var first = Add(localFirst, 1);
        using var second = Add(!localFirst, 1);
        using var third = Add(localFirst, 2);
        using var fourth = Add(!localFirst, 2);

        root.Name = "edit";

        string[] expected = localFirst
            ? ["local 1", "local 2", "recursive 1", "recursive 2"]
            : ["recursive 1", "recursive 2", "local 1", "local 2"];
        Assert.Equal(expected, trace);

        IDisposable Add(bool local, int index) => root.VmfChanges.AddListener(
            _ => trace.Add($"{(local ? "local" : "recursive")} {index}"),
            local ? ModelChangeScope.Local : ModelChangeScope.Recursive);
    }

    [Theory]
    [InlineData(false, false)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public void LaterGroupReadsSubscriptionsAtItsTurn(bool initiallyActive, bool replaceOld)
    {
        var root = new ArchiveNode();
        var child = new ArchiveNode();
        root.Children.Add(child);
        var trace = new List<string>();
        IDisposable? late = null;
        IDisposable? old = null;
        using var ancestor = ObserveName(root, "root", trace, value =>
        {
            if (value != "outer") return;
            if (replaceOld) old!.Dispose();
            late = child.VmfChanges.AddLocalListener(change => trace.Add($"late {change.NewValue}"));
            child.Name = "nested";
        });
        using var oldRegistration = old = initiallyActive
            ? child.VmfChanges.AddLocalListener(change => trace.Add($"old {change.NewValue}"))
            : null;

        try
        {
            child.Name = "outer";
            child.Name = "again";

            string[] expected = (initiallyActive, replaceOld) switch
            {
                (false, false) => ["root outer", "root nested", "late nested", "root again", "late again"],
                (true, false) => ["root outer", "root nested", "old nested", "late nested", "old outer", "late outer",
                    "root again", "old again", "late again"],
                _ => ["root outer", "root nested", "late nested", "late outer", "root again", "late again"],
            };
            Assert.Equal(expected, trace);
        }
        finally
        {
            late?.Dispose();
        }
    }

    [Fact]
    public void ReparentingReattachesRecursiveGroupsInTheirNewOrder()
    {
        var root = new ArchiveNode();
        var left = new ArchiveNode();
        var right = new ArchiveNode();
        var child = new ArchiveNode();
        root.Children.AddRange([left, right]);
        left.Children.Add(child);
        var trace = new List<string>();
        using var rootNames = ObserveName(root, "root", trace);
        using var leftNames = ObserveName(left, "left", trace);
        using var rightNames = ObserveName(right, "right", trace);
        using var childName = child.VmfChanges.AddPropertyListener("name", change => trace.Add($"child {change.NewValue}"));
        using var destinationList = right.VmfChanges.AddPropertyListener("children", _ => child.Name = "during");

        child.Name = "before";
        right.Children.Add(child);
        child.Name = "after";

        Assert.Equal(["root before", "left before", "child before", "child during", "root during", "right during",
            "child after", "root after", "right after"], trace);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void ListAndSlotCaptureGroupsAtTheirOwnDispatchBoundary(bool singular)
    {
        var owner = new ArchiveNode();
        var child = new ArchiveNode();
        string ownerProperty = singular ? "inset" : "children";
        string inverseProperty = singular ? "insetOf" : "parent";
        var trace = new List<string>();
        IDisposable? ownerEdits = null;
        IDisposable? childNames = null;
        using var inverse = child.VmfChanges.AddPropertyListener(inverseProperty, change =>
        {
            if (!ReferenceEquals(change.NewValue, owner)) return;
            trace.Add("inverse delivered");
            ownerEdits = owner.VmfChanges.AddPropertyListener(ownerProperty, _ => trace.Add("owner delivered"));
            childNames = ObserveName(owner, "name", trace);
            child.Name = "inverse";
        });

        try
        {
            if (singular) owner.Inset = child;
            else owner.Children.Add(child);
            child.Name = "after";
            if (!singular) owner.Children.Remove(child);

            string[] expected = singular
                ? ["inverse delivered", "owner delivered", "name after"]
                : ["inverse delivered", "name inverse", "name after", "owner delivered"];
            Assert.Equal(expected, trace);
        }
        finally
        {
            ownerEdits?.Dispose();
            childNames?.Dispose();
        }
    }

    [Fact]
    public void ReentrantCrossScopeCallbacksKeepNativeRelayOrderAndPortableMutationOrder()
    {
        var root = new ArchiveNode { Name = "root" };
        var child = new ArchiveNode { Name = "initial" };
        root.Children.Add(child);
        string checkpoint = ModelSnapshot.Write(root);
        var trace = new List<string>();
        using var local = child.VmfChanges.AddPropertyListener("name", change =>
        {
            trace.Add($"child {change.NewValue}");
            if (change.NewValue is "outer") child.Name = "nested";
        });
        var binding = new JournalBinding("conformance.observer-order", 1,
            model => (model as ArchiveNode)?.Parent is null ? "root" : "child");
        using var recorder = JournalRecorder<ArchiveNode>.Start(root, ModelSnapshot.Registry, binding, "initial", 0);
        using var ancestor = ObserveName(root, "root", trace);

        child.Name = "outer";
        var history = root.VmfChanges.History;
        Assert.Equal(["child outer", "child nested", "root nested", "root outer"], trace);
        Assert.Equal(["nested", "outer"], history.Select(change => change.NewValue));
        var journal = JournalJson.Read(JournalJson.Write(recorder.Finish()));
        var operations = Assert.Single(journal.Transactions).Operations.Cast<JournalSet>().ToArray();
        Assert.Equal([("initial", "outer"), ("outer", "nested")],
            operations.Select(edit => (edit.Before.Text, edit.After.Text)));

        var fresh = ModelSnapshot.Read<ArchiveNode>(checkpoint);
        var replayed = JournalReplayer.Replay(fresh, binding, "initial", journal, ModelSnapshot.Registry);
        Assert.Equal("initial", Assert.Single(fresh.Children).Name);
        Assert.Equal("nested", Assert.Single(replayed.Children).Name);
    }

    public enum RecordingAction { Start, Stop, Restart, Clear }

    [Theory]
    [InlineData(RecordingAction.Start, false, false)]
    [InlineData(RecordingAction.Start, false, true)]
    [InlineData(RecordingAction.Start, true, true)]
    [InlineData(RecordingAction.Stop, false, false)]
    [InlineData(RecordingAction.Stop, false, true)]
    [InlineData(RecordingAction.Stop, true, true)]
    [InlineData(RecordingAction.Restart, false, false)]
    [InlineData(RecordingAction.Restart, false, true)]
    [InlineData(RecordingAction.Restart, true, true)]
    [InlineData(RecordingAction.Clear, false, false)]
    [InlineData(RecordingAction.Clear, false, true)]
    [InlineData(RecordingAction.Clear, true, true)]
    public void ScalarRecordingLifecycleTakesEffectAtTheCurrentRelay(
        RecordingAction action, bool insideAncestor, bool existingRelay)
    {
        var root = new ArchiveNode { Name = "root" };
        var child = new ArchiveNode { Name = "initial" };
        root.Children.Add(child);
        var recording = root.VmfChanges;
        var live = new List<string>();
        var duringCallback = new List<string>();

        void ChangeRecording(ModelChange change)
        {
            if (change.NewValue is not "outer") return;
            ApplyRecordingAction(recording, action);
            child.Name = "nested";
            duringCallback.AddRange(RecordedValues(recording));
        }

        using var local = insideAncestor ? null : child.VmfChanges.AddPropertyListener("name", ChangeRecording);
        using var ancestor = existingRelay ? recording.AddRecursiveListener(change =>
        {
            if (!ReferenceEquals(change.Source, child)) return;
            live.Add(Assert.IsType<string>(change.NewValue));
            if (insideAncestor) ChangeRecording(change);
        }) : null;

        if (action != RecordingAction.Start)
        {
            recording.Start();
            root.Name = "seed";
        }
        child.Name = "outer";

        string[] expected = action == RecordingAction.Stop ? ["seed"]
            : insideAncestor || (action == RecordingAction.Start && !existingRelay) ? ["nested"] : ["nested", "outer"];
        string[] visible = action == RecordingAction.Stop ? ["seed"]
            : insideAncestor && action != RecordingAction.Start ? [] : ["nested"];
        Assert.Equal(visible, duringCallback);
        Assert.Equal(expected, RecordedValues(recording));
        Assert.Equal(insideAncestor ? ["outer", "nested"] : existingRelay ? ["nested", "outer"] : [], live);
        recording.Stop();
        Assert.Equal(expected, Assert.Single(recording.Transactions).Changes.Select(change => change.NewValue));
    }

    [Fact]
    public void RestartInsideTheFirstOfTwoRelaysDoesNotRecordTheOuterEditAgain()
    {
        var node = new ArchiveNode { Name = "initial" };
        var recording = node.VmfChanges;
        using var local = recording.AddLocalListener(change =>
        {
            if (change.NewValue is not "outer") return;
            ApplyRecordingAction(recording, RecordingAction.Restart);
            node.Name = "nested";
            Assert.Empty(recording.History);
        });
        using var recursive = recording.AddRecursiveListener(_ => { });
        recording.Start();
        node.Name = "outer";
        Assert.Equal(["nested"], RecordedValues(recording));
        recording.Stop();
    }

    [Fact]
    public void StartingAPortableRecorderInACallbackKeepsItsPostCommitCheckpoint()
    {
        var root = new ArchiveNode { Name = "root" };
        var child = new ArchiveNode { Name = "initial" };
        root.Children.Add(child);
        var binding = new JournalBinding("conformance.observer-order", 1,
            model => ((ArchiveNode)model).Parent is null ? "root" : "child");
        JournalRecorder<ArchiveNode>? late = null;
        string? checkpoint = null;
        using var local = child.VmfChanges.AddPropertyListener("name", change =>
        {
            if (change.NewValue is not "outer") return;
            checkpoint = ModelSnapshot.Write(root);
            late = JournalRecorder<ArchiveNode>.Start(root, ModelSnapshot.Registry, binding, "after-outer", 0);
            child.Name = "nested";
        });
        using var ancestor = root.VmfChanges.AddRecursiveListener(_ => { });

        child.Name = "outer";

        using var recorder = Assert.IsType<JournalRecorder<ArchiveNode>>(late);
        Assert.Equal(["nested"], RecordedValues(root.VmfChanges));
        var journal = JournalJson.Read(JournalJson.Write(recorder.Finish()));
        var edit = Assert.IsType<JournalSet>(Assert.Single(Assert.Single(journal.Transactions).Operations));
        Assert.Equal(("outer", "nested"), (edit.Before.Text, edit.After.Text));
        var fresh = ModelSnapshot.Read<ArchiveNode>(Assert.IsType<string>(checkpoint));
        var replayed = JournalReplayer.Replay(fresh, binding, "after-outer", journal, ModelSnapshot.Registry);
        Assert.Equal("outer", Assert.Single(fresh.Children).Name);
        Assert.Equal("nested", Assert.Single(replayed.Children).Name);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void StartingAPortableRecorderInAModelPropertyCallbackKeepsItsPostCommitCheckpoint(bool owning)
    {
        var root = new ArchiveNode { Name = "root" };
        var editor = new ArchiveNode { Name = "editor" };
        var outer = new ArchiveNode { Name = "outer" };
        var nested = new ArchiveNode { Name = "nested" };
        var property = owning ? "inset" : "anchor";
        root.Children.AddRange(owning ? [editor, nested] : [editor, outer, nested]);
        var binding = new JournalBinding("conformance.observer.reference-start", 1, model => ((ArchiveNode)model).Name!);
        JournalRecorder<ArchiveNode>? late = null;
        string? checkpoint = null;
        using var local = editor.VmfChanges.AddPropertyListener(property, change =>
        {
            if (!ReferenceEquals(change.NewValue, outer)) return;
            checkpoint = ModelSnapshot.Write(root);
            late = JournalRecorder<ArchiveNode>.Start(root, ModelSnapshot.Registry, binding, "after-outer", 0);
            editor.Anchor = nested;
        });
        var live = new List<object?>();
        using var ancestor = root.VmfChanges.AddPropertyListener(property, change => live.Add(change.NewValue),
            ModelChangeScope.Recursive);

        if (owning) editor.Inset = outer;
        else editor.Anchor = outer;
        using var recorder = Assert.IsType<JournalRecorder<ArchiveNode>>(late);
        Assert.Equal(owning ? new[] { outer } : [nested, outer], live);
        Assert.Same(nested, Assert.Single(root.VmfChanges.History).NewValue);
        var journal = recorder.Finish();
        var formats = new (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read)[]
        {
            (JournalJson.Write, JournalJson.Read),
            (JournalYaml.Write, JournalYaml.Read),
            (JournalXml.Write, JournalXml.Read),
        };
        foreach (var (write, read) in formats)
        {
            var restored = read(write(journal));
            Assert.Equal(1, restored.WireVersion);
            var edit = Assert.IsType<JournalSet>(Assert.Single(Assert.Single(restored.Transactions).Operations));
            Assert.Equal("anchor", edit.Property);
            Assert.Equal(owning ? ("NULL", null) : ("REFERENCE", "outer"), (edit.Before.Kind, edit.Before.Text));
            Assert.Equal(("REFERENCE", "nested"), (edit.After.Kind, edit.After.Text));
            var fresh = ModelSnapshot.Read<ArchiveNode>(Assert.IsType<string>(checkpoint));
            var replayed = JournalReplayer.Replay(fresh, binding, "after-outer", restored, ModelSnapshot.Registry);
            var freshEditor = fresh.Children[0];
            var replayEditor = replayed.Children[0];
            if (owning)
            {
                Assert.Null(freshEditor.Anchor);
                Assert.Same(freshEditor, Assert.IsType<ArchiveNode>(freshEditor.Inset).InsetOf);
                Assert.Same(replayEditor, Assert.IsType<ArchiveNode>(replayEditor.Inset).InsetOf);
            }
            else Assert.Same(fresh.Children[1], freshEditor.Anchor);
            Assert.Same(replayed.Children[owning ? 1 : 2], replayEditor.Anchor);
        }
    }

    private static void ApplyRecordingAction(ModelChanges recording, RecordingAction action)
    {
        switch (action)
        {
            case RecordingAction.Start:
                recording.Start();
                break;
            case RecordingAction.Stop:
                recording.Stop();
                break;
            case RecordingAction.Restart:
                recording.Stop();
                recording.Start();
                break;
            case RecordingAction.Clear:
                recording.Clear();
                break;
        }
    }

    private static IEnumerable<string> RecordedValues(ModelChanges recording) =>
        recording.History.Select(change => Assert.IsType<string>(change.NewValue));

    [Theory]
    [InlineData(false, false)]
    [InlineData(false, true)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public void NativeReentrantScalarUndoFollowsRecordedRelayOrder(bool ancestorFirst, bool separateSlot)
    {
        var root = new ArchiveNode { Name = "root" };
        var child = new ArchiveNode { Name = "initial" };
        var target = separateSlot ? new ArchiveNode { Name = "other" } : child;
        root.Children.Add(child);
        if (separateSlot) root.Children.Add(target);

        var recording = root.VmfChanges;
        if (ancestorFirst) recording.Start();
        using (child.VmfChanges.AddPropertyListener("name", change =>
        {
            if (change.NewValue is not "outer") return;
            Assert.Equal(ancestorFirst ? ["outer"] : Array.Empty<string>(),
                recording.History.Select(edit => edit.NewValue));
            target.Name = "nested";
        }))
        {
            if (!ancestorFirst) recording.Start();
            child.Name = "outer";
        }

        recording.Stop();
        var native = Assert.Single(recording.Transactions);
        Assert.Equal(ancestorFirst ? ["outer", "nested"] : ["nested", "outer"],
            native.Changes.Select(change => change.NewValue));
        Assert.Equal(ancestorFirst || separateSlot, native.CanUndo);
        Assert.Equal(ancestorFirst || separateSlot, native.Undo());
        Assert.Equal(separateSlot || ancestorFirst ? "initial" : "nested", child.Name);
        Assert.Equal(separateSlot ? "other" : child.Name, target.Name);
        if (ancestorFirst || separateSlot)
        {
            Assert.True(native.CanRedo);
            Assert.True(native.Redo());
            Assert.Equal(separateSlot ? "outer" : "nested", child.Name);
            Assert.Equal("nested", target.Name);
        }
    }

    [Fact]
    public void APortableFrameCannotPublishWhileAScalarEditAwaitsItsRecordingRelay()
    {
        var root = new ArchiveNode { Name = "root" };
        var child = new ArchiveNode { Name = "initial" };
        root.Children.Add(child);
        var binding = new JournalBinding("conformance.observer-order", 1,
            model => ((ArchiveNode)model).Parent is null ? "root" : "child");
        JournalRecorder<ArchiveNode>? active = null;
        using var local = child.VmfChanges.AddPropertyListener("name", change =>
        {
            if (change.NewValue is not "outer") return;
            child.Name = "nested";
            Assert.Equal("nested", Assert.Single(root.VmfChanges.History).NewValue);
            var failure = Assert.Throws<InvalidOperationException>(() => active!.PublishFrame());
            Assert.Contains("pending in native history", failure.Message);
        });
        using var recorder = JournalRecorder<ArchiveNode>.Start(root, ModelSnapshot.Registry, binding, "initial", 0);
        active = recorder;

        child.Name = "outer";

        Assert.Equal("nested", child.Name);
        Assert.False(root.VmfChanges.IsRecording);
        Assert.Throws<ObjectDisposedException>(() => recorder.Finish());
    }

    [Theory]
    [InlineData("values")]
    [InlineData("doubles")]
    public void AManagedCollectionCommitPrecedesTheScalarEditItTriggers(string property)
    {
        var working = new SummaryValues();
        var checkpoint = new SummaryValues();
        var binding = new JournalBinding("conformance.observer-mixed", 1, _ => "root");
        using var listener = working.VmfChanges.AddPropertyListener(property, _ => working.Magnitude = 1.5f);
        using var recorder = JournalRecorder<SummaryValues>.Start(working, SummarySnapshot.Registry, binding, "base", 0);
        if (property == "values") working.Values.Put("x", 1);
        else working.Doubles.Add(1.0);

        Assert.Equal([property, "magnitude"], working.VmfChanges.History.Select(edit => edit.PropertyName));
        var journal = recorder.Finish();
        Assert.Equal([property, "magnitude"], Assert.Single(journal.Transactions).Operations.Select(edit => edit.Property));
        var replayed = JournalReplayer.Replay(checkpoint, binding, "base", journal, SummarySnapshot.Registry);
        Assert.Equal(1.5f, replayed.Magnitude);
        if (property == "values") Assert.Equal(1, replayed.Values["x"]);
        else Assert.Contains(1.0, replayed.Doubles);
    }

    [Fact]
    public void ReentrantDirectReferenceRecordingReplaysAgainstTheCheckpointGraph()
    {
        var root = new ArchiveNode { Name = "root" };
        var editor = new ArchiveNode { Name = "editor" };
        var initial = new ArchiveNode { Name = "initial" };
        var outer = new ArchiveNode { Name = "outer" };
        var nested = new ArchiveNode { Name = "nested" };
        root.Children.AddRange([editor, initial, outer, nested]);
        editor.Anchor = initial;
        string checkpoint = ModelSnapshot.Write(root);
        var binding = new JournalBinding("conformance.observer.direct-reference", 1,
            model => Assert.IsType<ArchiveNode>(model).Name!);
        var edits = new List<string>();
        using var local = editor.VmfChanges.AddPropertyListener("anchor", change =>
        {
            var target = Assert.IsType<ArchiveNode>(change.NewValue);
            edits.Add(target.Name!);
            if (ReferenceEquals(target, outer)) editor.Anchor = nested;
        });
        using var recorder = JournalRecorder<ArchiveNode>.Start(root, ModelSnapshot.Registry, binding, "initial", 0);

        editor.Anchor = outer;
        Assert.Equal(["outer", "nested"], edits);
        Assert.Collection(root.VmfChanges.History,
            change => Assert.Same(nested, change.NewValue),
            change => Assert.Same(outer, change.NewValue));
        var journal = JournalJson.Read(JournalJson.Write(recorder.Finish()));
        var fresh = ModelSnapshot.Read<ArchiveNode>(checkpoint);
        var replayed = JournalReplayer.Replay(fresh, binding, "initial", journal, ModelSnapshot.Registry);
        Assert.Same(fresh.Children[1], fresh.Children[0].Anchor);
        Assert.Same(replayed.Children[3], replayed.Children[0].Anchor);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void CallbackInitializationSurvivesJournalCreationAlongsideRecordedEdits(bool owns)
    {
        var working = new ArchiveNode { Name = "root" };
        var existing = new ArchiveNode { Name = "existing" };
        var introduced = new ArchiveNode { Name = "introduced" };
        working.Children.Add(existing);
        var checkpoint = ModelSnapshot.Read<ArchiveNode>(ModelSnapshot.Write(working));
        var binding = new JournalBinding("conformance.observer.created-reference", 1, value => ((ArchiveNode)value).Name!)
            .WithObjectTypes(_ => "node", (_, id) => new ArchiveNode { Name = id });
        var field = owns ? "inset" : "anchor";
        using var listener = working.VmfChanges.AddPropertyListener(field, change =>
        {
            if (ReferenceEquals(change.NewValue, introduced))
            {
                introduced.Anchor = existing;
                existing.Anchor = working;
            }
        });
        using var recorder = JournalRecorder<ArchiveNode>.Start(working, ModelSnapshot.Registry, binding, "initial", 0);
        if (owns)
        {
            working.Inset = introduced;
        }
        else
        {
            working.Anchor = introduced;
            working.Children.Add(introduced);
        }

        var journal = JournalJson.Read(JournalJson.Write(recorder.Finish()));
        var replayed = JournalReplayer.Replay(checkpoint, binding, "initial", journal, ModelSnapshot.Registry);
        Assert.Equal(owns ? 1 : 2, replayed.Children.Count);
        var known = Assert.IsType<ArchiveNode>(replayed.Children[0]);
        var fresh = Assert.IsType<ArchiveNode>(owns ? replayed.Inset : replayed.Anchor);
        Assert.Same(known, fresh.Anchor);
        Assert.Same(replayed, known.Anchor);
        if (owns) Assert.Same(replayed, fresh.InsetOf);
        else Assert.Same(fresh, replayed.Children[1]);
        Assert.Null(checkpoint.Anchor);
        Assert.Null(checkpoint.Inset);
    }

    private static IDisposable ObserveName(ArchiveNode owner, string prefix, List<string> trace, Action<string>? seen = null) =>
        owner.VmfChanges.AddPropertyListener("name", change =>
        {
            var name = Assert.IsType<string>(change.NewValue);
            trace.Add($"{prefix} {name}");
            seen?.Invoke(name);
        }, ModelChangeScope.Recursive);
}
