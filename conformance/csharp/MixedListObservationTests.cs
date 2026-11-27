using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;
using ModelSnapshot = Eu.Mihosoft.Vmf.Conformance.Values.ModelSnapshot;

namespace Vmf.CSharp.Conformance;

public sealed class MixedListObservationTests
{
    private static readonly Script[] Scripts = JsonSerializer.Deserialize<Script[]>(
        File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "fixtures", "mixed-list-observers.json")),
        new JsonSerializerOptions { PropertyNameCaseInsensitive = true })!;

    public static IEnumerable<object[]> Cases => Scripts.Select(script => new object[] { script.Name });

    [Theory]
    [MemberData(nameof(Cases))]
    public void DirectAndModeledCallbacksFollowTheListRelayOrder(string name)
    {
        var script = Array.Find(Scripts, available => available.Name == name)!;
        if (script.Kind == "containment-inverse")
        {
            Assert.Equal(script.Trace, ObserveContainingInverse());
            return;
        }
        if (script.Kind == "journal-reentry")
        {
            Assert.Equal(script.Trace, RecordReentry());
            return;
        }
        var owner = new CollectionProfile();
        var mutable = owner.Messages;
        var trace = new List<string>();
        var subscriptions = new List<IDisposable>();
        IReadOnlyList<string?>? projected = null;
        IDisposable? original = null;
        bool modelRan = false;

        try
        {
            foreach (var step in script.Steps!)
            {
                switch (step.Action)
                {
                    case "read-projected-list":
                        projected = owner.AsReadOnly().Messages;
                        break;
                    case "observe-original-projection":
                        original = projected!.AddListener(edit => trace.Add("old:" + Assert.Single(edit.Added).Value));
                        subscriptions.Add(original);
                        break;
                    case "observe-model-change":
                        subscriptions.Add(owner.VmfChanges.AddPropertyListener("messages", _ =>
                        {
                            trace.Add($"model:{mutable.Count}");
                            if (mutable.Count != 1) return;
                            original?.Dispose();
                            subscriptions.Add(projected!.AddListener(edit => trace.Add("new:" + Assert.Single(edit.Added).Value)));
                        }));
                        break;
                    case "observe-mutable-list":
                        subscriptions.Add(mutable.AddListener(_ =>
                        {
                            string label = modelRan ? "B" : "A";
                            trace.Add("register:" + label);
                            subscriptions.Add(projected!.AddListener(_ => trace.Add("projected:" + label)));
                        }));
                        break;
                    case "mark-mutable-list":
                        subscriptions.Add(mutable.AddListener(_ => trace.Add(step.Value!)));
                        break;
                    case "mark-projected-list":
                        subscriptions.Add(projected!.AddListener(_ => trace.Add(step.Value!)));
                        break;
                    case "mark-projected-model":
                        subscriptions.Add(owner.AsReadOnly().VmfChanges.AddPropertyListener("messages", _ => trace.Add(step.Value!)));
                        break;
                    case "mark-modeled-delivery":
                        subscriptions.Add(owner.VmfChanges.AddPropertyListener("messages", _ =>
                        {
                            modelRan = true;
                            trace.Add("model");
                        }));
                        break;
                    case "add":
                        mutable.Add(step.Value);
                        break;
                    default:
                        throw new InvalidOperationException($"Unknown observer step '{step.Action}'.");
                }
            }
        }
        finally
        {
            foreach (var subscription in subscriptions) subscription.Dispose();
        }
        Assert.Equal(script.Trace, trace);
    }

    private static List<string> RecordReentry()
    {
        var owner = new CollectionProfile();
        var trace = new List<string>();
        using var direct = owner.Messages.AddListener(edit =>
        {
            string? message = Assert.Single(edit.Added).Value;
            trace.Add("direct:" + message);
            if (message == "outer") owner.Messages.Add("nested");
        });
        var binding = new JournalBinding("conformance.mixed-list-observers", 1, _ => "root");
        using var recorder = JournalRecorder<CollectionProfile>.Start(owner, ModelSnapshot.Registry, binding, "empty", 0);
        owner.Messages.Add("outer");
        foreach (var edit in owner.VmfChanges.History)
        {
            var change = Assert.IsType<ModelCollectionChange>(edit);
            trace.Add("native:" + Assert.Single(change.Added).Value);
        }
        var journal = JournalJson.Read(JournalJson.Write(recorder.Finish()));
        Assert.Equal(1, journal.WireVersion);
        var result = JournalReplayer.Replay(new CollectionProfile(), binding, "empty", journal, ModelSnapshot.Registry);
        trace.AddRange(result.Messages.Select(message => "replay:" + message));
        return trace;
    }

    private static List<string> ObserveContainingInverse()
    {
        var owner = new LegacyContainer();
        var child = new LegacyChild();
        var source = owner.Children;
        var view = owner.AsReadOnly().Children;
        var trace = new List<string>();
        using var original = view.AddListener(_ => trace.Add("old"));
        IDisposable? replacement = null;
        using var inverse = child.VmfChanges.AddPropertyListener("owner", _ =>
        {
            trace.Add("inverse");
            original.Dispose();
            replacement = view.AddListener(_ => trace.Add("new"));
        });
        try
        {
            source.Add(child);
            return trace;
        }
        finally
        {
            replacement?.Dispose();
        }
    }

    private sealed record Script(string Name, Step[]? Steps, string[] Trace, string? Kind);
    private sealed record Step(string Action, string? Value);
}
