using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ScalarMembershipObserverRouteTests
{
    private static readonly string[] EditKeys = ["outer", "nested", "future"];
    private static readonly JournalBinding Journal = new("conformance.membership-observer-route", 1, _ => "profile");
    private static readonly Dictionary<string, Dictionary<string, string[]>> ExpectedTraces =
        JsonSerializer.Deserialize<Dictionary<string, Dictionary<string, string[]>>>(
            File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "fixtures", "scalar-membership-observers.json")))!;

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void ModeledAndDirectCallbacksFollowTheirCollectionRegistrationOrder(bool map)
    {
        var fixture = Create(map);
        var trace = new List<string>();
        fixture.Raw(_ => trace.Add("M0"));
        fixture.Model.AsReadOnly();
        fixture.Raw(_ => trace.Add("M1"));
        fixture.MutableModel(_ => trace.Add("C0"), ModelChangeScope.Local);
        var view = fixture.ReadOnly();
        fixture.Raw(_ => trace.Add("M2"));
        view(_ => trace.Add("P0"));
        fixture.Raw(_ => trace.Add("M3"));
        fixture.ProjectedModel(_ => trace.Add("R0"), ModelChangeScope.Local);
        view(_ => trace.Add("P1"));
        fixture.MutableModel(_ => trace.Add("C1"), ModelChangeScope.Recursive);
        fixture.ProjectedModel(_ => trace.Add("R1"), ModelChangeScope.Recursive);
        fixture.Edit("outer");

        Assert.Equal(fixture.Expected["mixed-order"], trace);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void ReadOnlyModelRegistrationObservesCollectionsBeforeExplicitGetter(bool map)
    {
        var fixture = Create(map);
        var trace = new List<string>();
        fixture.Raw(_ => trace.Add("M0"));
        fixture.ProjectedModel(_ => trace.Add("R0"), ModelChangeScope.Local);
        fixture.Raw(_ => trace.Add("M1"));
        fixture.MutableModel(_ => trace.Add("C0"), ModelChangeScope.Local);
        fixture.ReadOnly()(_ => trace.Add("P0"));
        fixture.Raw(_ => trace.Add("M2"));
        fixture.Edit("outer");

        Assert.Equal(fixture.Expected["implicit-projection"], trace);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void ProjectionMaterializationAndResubscriptionKeepTheirDeliveryPosition(bool map)
    {
        var fixture = Create(map);
        var trace = new List<string>();
        fixture.Raw(_ => trace.Add("M0"));
        var view = fixture.ReadOnly();
        fixture.Raw(_ => trace.Add("M1"));
        using (view(_ => trace.Add("P0")))
        {
            fixture.Edit("outer");
            Assert.Equal(fixture.Expected["first-projected-subscription"], trace);
        }
        trace.Clear();
        fixture.Edit("nested");
        Assert.Equal(fixture.Expected["unsubscribed-projection"], trace);

        trace.Clear();
        fixture.Raw(_ => trace.Add("M2"));
        view(_ => trace.Add("Pnew"));
        fixture.Edit("future");
        Assert.Equal(fixture.Expected["resubscribed-projection"], trace);
    }

    [Theory]
    [InlineData(true, false)]
    [InlineData(true, true)]
    [InlineData(false, false)]
    [InlineData(false, true)]
    public void MutableCallbacksChangingProjectedSubscriptionsRespectTheCollectionSnapshot(bool map, bool initiallyObserved)
    {
        var fixture = Create(map);
        var trace = new List<string>();
        IDisposable? old = null;
        fixture.Raw(key =>
        {
            trace.Add("early:" + key);
            if (key != "outer") return;
            old?.Dispose();
            fixture.ReadOnly()(edit => trace.Add("Pnew:" + edit));
            fixture.Raw(edit => trace.Add("Mnew:" + edit));
            fixture.Edit("nested");
        });
        var view = fixture.ReadOnly();
        if (initiallyObserved) old = view(key => trace.Add("Pold:" + key));
        fixture.Raw(key => trace.Add("late:" + key));
        fixture.Edit("outer");

        string scenario = initiallyObserved ? "source-reentry-existing-projection" : "source-reentry-empty-projection";
        Assert.Equal(fixture.Expected[scenario], trace);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void ActiveProjectionCallbacksKeepTheirSnapshotAcrossReentry(bool map)
    {
        var fixture = Create(map);
        var trace = new List<string>();
        var view = fixture.ReadOnly();
        IDisposable? old = null;
        view(key =>
        {
            trace.Add("Pfirst:" + key);
            if (key != "outer") return;
            old!.Dispose();
            view(edit => trace.Add("Pnew:" + edit));
            fixture.Edit("nested");
        });
        old = view(key => trace.Add("Pold:" + key));
        fixture.Raw(key => trace.Add("late:" + key));
        fixture.Edit("outer");

        Assert.Equal(fixture.Expected["projected-reentry"], trace);
    }

    [Theory]
    [InlineData(true, false)]
    [InlineData(true, true)]
    [InlineData(false, false)]
    [InlineData(false, true)]
    public void ProjectedModeledGroupHasItsOwnSubscriberSnapshot(bool map, bool initialGroup)
    {
        var fixture = Create(map);
        var trace = new List<string>();
        IDisposable? old = null;
        fixture.Raw(_ =>
        {
            trace.Add("early");
            old?.Dispose();
            fixture.ProjectedModel(_ => trace.Add("Rnew"), ModelChangeScope.Local);
        });
        fixture.ReadOnly()(_ => trace.Add("P"));
        if (initialGroup)
        {
            fixture.ProjectedModel(_ => trace.Add("Rkeep"), ModelChangeScope.Local);
            old = fixture.ProjectedModel(_ => trace.Add("Rold"), ModelChangeScope.Local);
        }
        fixture.Raw(_ => trace.Add("late"));
        fixture.Edit("outer");

        string scenario = initialGroup ? "modeled-reentry-existing-scope" : "modeled-reentry-new-scope";
        Assert.Equal(fixture.Expected[scenario], trace);
    }

    [Theory]
    [InlineData(true, false)]
    [InlineData(true, true)]
    [InlineData(false, false)]
    [InlineData(false, true)]
    public void DirectCallbackSeesNativeHistoryAtItsPositionRelativeToTheRecordingRelay(bool map, bool recordingFirst)
    {
        var fixture = Create(map);
        var stream = fixture.Model.VmfChanges;
        var visible = new List<string>();
        if (recordingFirst) stream.Start();
        using var direct = fixture.Raw(_ =>
        {
            visible.AddRange(stream.History.Select(edit => edit.PropertyName));
            fixture.Model.Messages.Add("nested");
        });
        if (!recordingFirst) stream.Start();
        try
        {
            fixture.Edit("outer");
            string recording = recordingFirst ? "early-recording" : "late-recording";
            Assert.Equal(fixture.Expected[recording + "-visible-in-direct-callback"], visible);
            Assert.Equal(fixture.Expected[recording + "-history"], stream.History.Select(edit => edit.PropertyName));
        }
        finally
        {
            stream.Stop();
        }
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void FirstFailureKeepsItsSuppressionTreeAndTheCommittedEditReplays(bool map)
    {
        var fixture = Create(map);
        var checkpoint = ModelSnapshot.Reflection.DeepCopy(fixture.Model);
        var trace = new List<string>();
        var rawFailure = new InvalidOperationException("raw-fail");
        var firstProjectedFailure = new InvalidOperationException("P0-fail");
        var secondProjectedFailure = new InvalidOperationException("P1-fail");
        using var raw = fixture.Raw(_ =>
        {
            trace.Add("raw");
            throw rawFailure;
        });
        var view = fixture.ReadOnly();
        using var first = view(_ =>
        {
            trace.Add("P0");
            throw firstProjectedFailure;
        });
        using var late = fixture.Raw(_ => trace.Add("late"));
        using var last = view(_ =>
        {
            trace.Add("P1");
            throw secondProjectedFailure;
        });
        using var recorder = JournalRecorder<CollectionProfile>.Start(fixture.Model, ModelSnapshot.Registry, Journal, "checkpoint", 0);

        Assert.Same(rawFailure, Assert.Throws<InvalidOperationException>(() => fixture.Edit("outer")));
        Assert.Equal(fixture.Expected["first-failure-order"], trace);
        Assert.Equal(Assert.Single(fixture.Expected["first-failure-suppression"]), SuppressionTree(rawFailure));
        Assert.Equal(fixture.Expected["first-failure-native-history"],
            fixture.Model.VmfChanges.History.Select(change => change.PropertyName));
        Assert.True(fixture.Contains(fixture.Model, "outer"));
        Assert.False(fixture.Contains(checkpoint, "outer"));

        VerifyReplay(fixture, checkpoint, recorder.Finish(), "outer");
        Assert.False(fixture.Contains(checkpoint, "outer"));
    }

    [Theory]
    [InlineData(true, false, false)]
    [InlineData(true, false, true)]
    [InlineData(true, true, false)]
    [InlineData(true, true, true)]
    [InlineData(false, false, false)]
    [InlineData(false, false, true)]
    [InlineData(false, true, false)]
    [InlineData(false, true, true)]
    public void StartingRecordingInsideAListenerCapturesOnlyTheNestedEdit(bool map, bool projected, bool priorGroup)
    {
        var fixture = Create(map);
        var trace = new List<string>();
        JournalRecorder<CollectionProfile>? active = null;
        CollectionProfile? checkpoint = null;
        var source = projected ? fixture.ReadOnly() : fixture.Raw;
        using var direct = source(_ =>
        {
            if (active is not null)
            {
                trace.Add("direct inner");
                return;
            }
            trace.Add("direct outer");
            checkpoint = ModelSnapshot.Reflection.DeepCopy(fixture.Model);
            active = JournalRecorder<CollectionProfile>.Start(fixture.Model, ModelSnapshot.Registry, Journal, "checkpoint", 0);
            fixture.Edit("nested");
        });
        using var modeled = priorGroup ? fixture.MutableModel(_ => trace.Add("modeled"), ModelChangeScope.Recursive) : null;
        try
        {
            fixture.Edit("outer");
            Assert.NotNull(active);
            Assert.NotNull(checkpoint);
            Assert.True(fixture.Contains(checkpoint, "outer"));
            Assert.False(fixture.Contains(checkpoint, "nested"));
            string channel = projected ? "projected" : "raw";
            string group = priorGroup ? "with-group" : "without-group";
            Assert.Equal(fixture.Expected[$"portable-initiation-{channel}-{group}"], trace);
            VerifyReplay(fixture, checkpoint, active.Finish(), "outer", "nested");
            Assert.False(fixture.Contains(checkpoint, "nested"));
        }
        finally
        {
            active?.Dispose();
        }
    }

    private static void VerifyReplay(Fixture fixture, CollectionProfile checkpoint, ChangeJournal journal, params string[] expected)
    {
        ChangeJournal[] documents =
        [
            JournalJson.Read(JournalJson.Write(journal)),
            JournalXml.Read(JournalXml.Write(journal)),
            JournalYaml.Read(JournalYaml.Write(journal))
        ];
        foreach (var document in documents)
        {
            Assert.Equal(1, document.WireVersion);
            var operation = Assert.Single(Assert.Single(document.Transactions).Operations);
            Assert.Equal(fixture.Property, operation.Property);
            if (fixture.Property == "limits") Assert.IsType<JournalMapEdit>(operation);
            else Assert.IsType<JournalMembershipEdit>(operation);

            var replayed = JournalReplayer.Replay(checkpoint, Journal, "checkpoint", document, ModelSnapshot.Registry);
            Assert.NotSame(fixture.Model, replayed);
            Assert.NotSame(checkpoint, replayed);
            Assert.All(expected, key => Assert.True(fixture.Contains(replayed, key)));
        }
    }

    private static string SuppressionTree(Exception failure) =>
        failure.Message + "[" + string.Join(", ", ModelChanges.GetSuppressedObserverExceptions(failure).Select(SuppressionTree)) + "]";

    private static Fixture Create(bool map)
    {
        var model = new CollectionProfile();
        if (map)
        {
            var source = model.Limits;
            return new Fixture(model, "limits", ExpectedTraces["map"], action => source.AddListener(edit =>
            {
                Assert.Same(source, edit.Source);
                action(Assert.Single(edit.Added).Key);
            }), () =>
            {
                var view = model.AsReadOnly().Limits;
                return action => view.AddListener(edit =>
                {
                    Assert.Same(view, edit.Source);
                    action(Assert.Single(edit.Added).Key);
                });
            }, key => source.Put(key, 1));
        }
        var set = model.Levels;
        return new Fixture(model, "levels", ExpectedTraces["set"], action => set.AddListener(edit =>
        {
            Assert.Same(set, edit.Source);
            action(EditKeys[(int)Assert.Single(edit.Added) - 1]);
        }), () =>
        {
            var view = model.AsReadOnly().Levels;
            return action => view.AddListener(edit =>
            {
                Assert.Same(view, edit.Source);
                action(EditKeys[(int)Assert.Single(edit.Added) - 1]);
            });
        }, key => set.Add(Array.IndexOf(EditKeys, key) + 1));
    }

    private delegate IDisposable Observe(Action<string> callback);

    private sealed record Fixture(CollectionProfile Model, string Property, IReadOnlyDictionary<string, string[]> Expected,
        Observe Raw, Func<Observe> ReadOnly, Action<string> Edit)
    {
        public bool Contains(CollectionProfile value, string key) => Property == "limits"
            ? value.Limits.TryGetValue(key, out var entry) && entry == 1L
            : value.Levels.Contains(Array.IndexOf(EditKeys, key) + 1);

        public IDisposable MutableModel(Action<string> callback, ModelChangeScope scope) =>
            Model.VmfChanges.AddListener(edit =>
            {
                if (edit.PropertyName == Property) callback(Property);
            }, scope);

        public IDisposable ProjectedModel(Action<string> callback, ModelChangeScope scope) =>
            Model.AsReadOnly().VmfChanges.AddListener(edit =>
            {
                if (edit.PropertyName == Property) callback(Property);
            }, scope);
    }
}
