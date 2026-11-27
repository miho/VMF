using Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ProjectedListObservationTests
{
    [Fact]
    public void OrdinaryScalarListsRelayAtProjectionMaterialization() =>
        Verify(() =>
        {
            var owner = new CollectionProfile();
            return new Fixture<string?, string?>(owner.Messages, () => owner.AsReadOnly(), () => owner.AsReadOnly().Messages,
                value => owner.Messages.Add(value), value => value, value => value);
        });

    [Fact]
    public void OrdinaryReferenceListsRelayAtProjectionMaterialization() =>
        Verify(() =>
        {
            var owner = new ArchiveNode();
            return new Fixture<ArchiveNode?, IReadOnlyArchiveNode?>(owner.Trail, () => owner.AsReadOnly(),
                () => owner.AsReadOnly().Trail, value => owner.Trail.Add(new ArchiveNode { Name = value }),
                value => value?.Name, value => value?.Name);
        });

    [Fact]
    public void OrdinaryContainmentListsRelayAtProjectionMaterialization() =>
        Verify(() =>
        {
            var owner = new LegacyContainer();
            return new Fixture<LegacyChild?, IReadOnlyLegacyChild?>(owner.Children, () => owner.AsReadOnly(),
                () => owner.AsReadOnly().Children, value => owner.Children.Add(new LegacyChild { Name = value }),
                value => value?.Name, value => value?.Name);
        });

    [Fact]
    public void AModeledCallbackChangingTheProjectionKeepsTheCapturedListenersEligible()
    {
        var owner = new CollectionProfile();
        var source = owner.Messages;
        var view = owner.AsReadOnly().Messages;
        var projected = new List<string>();
        using var original = view.AddListener(edit => projected.Add("old:" + edit.Added[0].Value));
        IDisposable? replacement = null;
        using var modeled = owner.VmfChanges.AddLocalListener(edit =>
        {
            if (edit.PropertyName != "messages" || source.Count != 1) return;
            original.Dispose();
            replacement = view.AddListener(delta => projected.Add("new:" + delta.Added[0].Value));
        });

        source.Add("outer");
        using var cleanup = replacement;
        source.Add("future");

        Assert.Equal(["old:outer", "new:future"], projected);
    }

    private static void Verify<T, TView>(Func<Fixture<T, TView>> create)
    {
        VerifyMaterialization(create());
        VerifySubscriptionReentry(create(), initiallyObserved: false);
        VerifySubscriptionReentry(create(), initiallyObserved: true);
        VerifyActiveProjectionSnapshot(create());
    }

    private static void VerifyMaterialization<T, TView>(Fixture<T, TView> fixture)
    {
        var events = new List<string>();
        using var beforeView = fixture.Source.AddListener(_ => events.Add("before view"));
        fixture.MaterializeModel();
        using var beforeList = fixture.Source.AddListener(_ => events.Add("before list"));
        var view = fixture.View();
        fixture.Add("unobserved");
        Expect(events, "before view", "before list");
        using var afterList = fixture.Source.AddListener(_ => events.Add("after list"));
        using var first = view.AddListener(change =>
        {
            Assert.Same(view, change.Source);
            events.Add("first projected");
        });
        using var last = fixture.Source.AddListener(_ => events.Add("last mutable"));
        using var second = view.AddListener(_ => events.Add("second projected"));
        fixture.Add("started");
        Expect(events, "before view", "before list", "first projected", "second projected", "after list", "last mutable");
        first.Dispose();
        second.Dispose();
        fixture.Add("unobserved again");
        Expect(events, "before view", "before list", "after list", "last mutable");
        using var restart = view.AddListener(_ => events.Add("restarted"));
        fixture.Add("again");
        Expect(events, "before view", "before list", "restarted", "after list", "last mutable");
    }

    private static void VerifySubscriptionReentry<T, TView>(Fixture<T, TView> fixture, bool initiallyObserved)
    {
        var events = new List<string>();
        IDisposable? old = null;
        IDisposable? addedProjected = null;
        IDisposable? addedMutable = null;
        using var early = fixture.Source.AddListener(change =>
        {
            var key = fixture.SourceKey(change.Added[0].Value);
            events.Add("early:" + key);
            if (key != "outer") return;
            old?.Dispose();
            addedProjected = fixture.View().AddListener(edit => events.Add("new:" + fixture.ViewKey(edit.Added[0].Value)));
            addedMutable = fixture.Source.AddListener(edit => events.Add("new mutable:" + fixture.SourceKey(edit.Added[0].Value)));
            fixture.Add("nested");
        });
        var view = fixture.View();
        using var original = old = initiallyObserved
            ? view.AddListener(edit => events.Add("old:" + fixture.ViewKey(edit.Added[0].Value))) : null;
        using var late = fixture.Source.AddListener(edit => events.Add("late:" + fixture.SourceKey(edit.Added[0].Value)));
        fixture.Add("outer");
        using var cleanupProjected = addedProjected;
        using var cleanupMutable = addedMutable;
        Expect(events, "early:outer", "early:nested", "new:nested", "late:nested", "new mutable:nested", "new:outer", "late:outer");
    }

    private static void VerifyActiveProjectionSnapshot<T, TView>(Fixture<T, TView> fixture)
    {
        var events = new List<string>();
        var view = fixture.View();
        IDisposable? old = null;
        IDisposable? added = null;
        using var first = view.AddListener(change =>
        {
            var key = fixture.ViewKey(change.Added[0].Value);
            events.Add("first:" + key);
            if (key != "outer") return;
            old!.Dispose();
            added = view.AddListener(edit => events.Add("new:" + fixture.ViewKey(edit.Added[0].Value)));
            fixture.Add("nested");
        });
        using var original = old = view.AddListener(edit => events.Add("old:" + fixture.ViewKey(edit.Added[0].Value)));
        using var late = fixture.Source.AddListener(edit => events.Add("late:" + fixture.SourceKey(edit.Added[0].Value)));
        fixture.Add("outer");
        using var cleanup = added;
        Expect(events, "first:outer", "first:nested", "new:nested", "late:nested", "old:outer", "late:outer");
    }

    private static void Expect(List<string> events, params string[] expected)
    {
        Assert.Equal(expected, events);
        events.Clear();
    }

    private sealed record Fixture<T, TView>(IReadOnlyList<T> Source, Action MaterializeModel,
        Func<IReadOnlyList<TView>> View, Action<string> Add, Func<T, string?> SourceKey, Func<TView, string?> ViewKey);
}
