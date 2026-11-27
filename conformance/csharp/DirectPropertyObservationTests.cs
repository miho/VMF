using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class DirectPropertyObservationTests
{
    private static readonly Dictionary<string, string[]> Traces = JsonSerializer.Deserialize<Dictionary<string, string[]>>(
        File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "fixtures", "direct-property-observers.json")))!;

    [Fact]
    public void DirectAndModeledPropertyRoutesShareOneSourceOrder()
    {
        var source = new ArchiveNode();
        var trace = new List<string>();
        var changes = source.VmfChanges;
        using var early = changes.AddDirectPropertyListener(e =>
        {
            Assert.Same(source, e.Source);
            trace.Add($"early:{e.OldValue ?? "null"}:{e.NewValue}");
        });
        using var modeled = changes.AddLocalListener(e => trace.Add("model:" + e.PropertyName));
        using var named = changes.AddDirectPropertyListener("name", e => trace.Add("named:" + e.PropertyName));
        using var late = changes.AddDirectPropertyListener(e => trace.Add("late:" + e.PropertyName));
        source.Name = "one";
        source.Name = "one";
        source.Trail.Add(new ArchiveNode());
        Assert.Equal(Traces["order"], trace);
    }

    [Fact]
    public void EachAssignmentCapturesRawRecipientsAndCombinesFailures()
    {
        var source = new ArchiveNode();
        var changes = source.VmfChanges;
        var trace = new List<string>();
        var earlyFailure = new InvalidOperationException("early");
        var namedFailure = new ArgumentException("named");
        IDisposable? existing = null;
        IDisposable? added = null;
        using var early = changes.AddDirectPropertyListener(e =>
        {
            trace.Add("early:" + e.NewValue);
            if (Equals(e.NewValue, "outer"))
            {
                existing!.Dispose();
                added = changes.AddDirectPropertyListener(next => trace.Add("new:" + next.NewValue));
                source.Name = "nested";
                throw earlyFailure;
            }
        });
        using var original = changes.AddDirectPropertyListener(e => trace.Add("old:" + e.NewValue));
        existing = original;
        using var modeled = changes.AddLocalListener(e => trace.Add("model:" + e.NewValue));
        changes.Start();
        using var named = changes.AddDirectPropertyListener("name", e =>
        {
            trace.Add("named:" + e.NewValue);
            if (Equals(e.NewValue, "outer")) throw namedFailure;
        });
        Assert.Same(earlyFailure, Assert.Throws<InvalidOperationException>(() => source.Name = "outer"));
        Assert.Same(namedFailure, Assert.Single(ModelChanges.GetSuppressedObserverExceptions(earlyFailure)));
        added?.Dispose();
        trace.AddRange(changes.History.Select(change => "history:" + change.NewValue));
        Assert.Equal(Traces["reentry"], trace);
        Assert.Equal("nested", source.Name);
        changes.Stop();
    }

    [Fact]
    public void DirectReadOnlyNotificationsPreserveTheProjectedIdentities()
    {
        var source = new ArchiveNode();
        var old = new ArchiveNode { Name = "same" };
        var replacement = new ArchiveNode { Name = "same" };
        source.Anchor = old;
        var view = source.AsReadOnly();
        var trace = new List<string>();
        using var raw = source.VmfChanges.AddDirectPropertyListener(e =>
        {
            Assert.Same(old, e.OldValue);
            Assert.Same(replacement, e.NewValue);
            trace.Add("raw:" + e.PropertyName);
        });
        using var projected = view.VmfChanges.AddDirectPropertyListener(e =>
        {
            Assert.Same(view, e.Source);
            Assert.Same(old.AsReadOnly(), e.OldValue);
            Assert.Same(replacement.AsReadOnly(), e.NewValue);
            trace.Add("projected:" + e.PropertyName);
        });
        using var modeled = source.VmfChanges.AddPropertyListener("anchor", e => trace.Add("model:" + e.PropertyName));
        using var named = source.VmfChanges.AddDirectPropertyListener("anchor", e => trace.Add("named:" + e.PropertyName));
        source.Anchor = replacement;
        Assert.Equal(Traces["projected"], trace);
    }

    [Fact]
    public void InverseCallbacksCanRegisterForALaterPropertyEvent()
    {
        var parent = new ArchiveNode();
        var child = new ArchiveNode();
        var trace = new List<string>();
        using var parentExisting = parent.VmfChanges.AddDirectPropertyListener(e => trace.Add("parentExisting:" + e.PropertyName));
        IDisposable? parentAdded = null;
        IDisposable? parentModelAdded = null;
        using var inverse = child.VmfChanges.AddDirectPropertyListener(e =>
        {
            Assert.Same(child, e.Source);
            Assert.Same(parent, e.NewValue);
            trace.Add($"child:{e.PropertyName}:{(ReferenceEquals(parent.Inset, child) ? "true" : "false")}");
            parentAdded = parent.VmfChanges.AddDirectPropertyListener(next => trace.Add("parentAdded:" + next.PropertyName));
            parentModelAdded = parent.VmfChanges.AddLocalListener(next => trace.Add("parentModelAdded:" + next.PropertyName));
        });
        parent.Inset = child;
        parentAdded?.Dispose();
        parentModelAdded?.Dispose();
        Assert.Equal(Traces["ownership"], trace);
    }

    [Fact]
    public void RecorderOpenedInARawCallbackStartsAtTheCommittedValue()
    {
        var source = new ArchiveNode();
        var trace = new List<string>();
        var binding = new JournalBinding("conformance.direct-property-observers", 1, _ => "root");
        JournalRecorder<ArchiveNode>? recorder = null;
        using var raw = source.VmfChanges.AddDirectPropertyListener(e =>
        {
            trace.Add("raw:" + e.NewValue);
            if (Equals(e.NewValue, "outer"))
            {
                recorder = JournalRecorder<ArchiveNode>.Start(source, ModelSnapshot.Registry, binding, "assigned", 0);
                source.Name = "nested";
            }
        });
        using var modeled = source.VmfChanges.AddLocalListener(e => trace.Add("model:" + e.NewValue));
        source.Name = "outer";
        using var recording = Assert.IsType<JournalRecorder<ArchiveNode>>(recorder);
        var journal = JournalJson.Read(JournalJson.Write(recording.Finish()));
        Assert.Equal(1, journal.WireVersion);
        var baseline = ModelSnapshot.Read<ArchiveNode>(ModelSnapshot.Write(new ArchiveNode { Name = "outer" }));
        var replay = JournalReplayer.Replay(baseline, binding, "assigned", journal, ModelSnapshot.Registry);
        trace.Add("replay:" + replay.Name);
        trace.Add("checkpoint:" + baseline.Name);
        Assert.Equal(Traces["recorder"], trace);
    }
}
