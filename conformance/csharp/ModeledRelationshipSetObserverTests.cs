using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Opposites;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ModeledRelationshipSetObserverTests
{
    private static readonly Dictionary<string, string[]> Expected =
        JsonSerializer.Deserialize<Dictionary<string, string[]>>(File.ReadAllText(Path.Combine(
            AppContext.BaseDirectory, "fixtures", "modeled-relationship-set-observers.json")))!;

    [Theory]
    [InlineData("late")]
    [InlineData("early")]
    [InlineData("dormant")]
    public void OrderedKeyedModelScopesUseFirstModeledRegistration(string timing) =>
        Verify("ordered-keyed-" + timing, Keyed(), timing, mutableObservers: true);

    [Theory]
    [InlineData(false, "late")]
    [InlineData(false, "early")]
    [InlineData(false, "dormant")]
    [InlineData(false, "fresh")]
    [InlineData(true, "late")]
    [InlineData(true, "early")]
    [InlineData(true, "dormant")]
    [InlineData(true, "fresh")]
    public void ReciprocalSetGroupsPreserveProjectionOrderInBothDirections(bool editOpposite, string timing) =>
        Verify("reciprocal-" + (editOpposite ? "opposite-" : "primary-") + timing,
            Reciprocal(editOpposite), timing, mutableObservers: true);

    [Theory]
    [InlineData("dormant")]
    [InlineData("fresh")]
    public void ExistingReciprocalProjectionCanActivateWithoutAnOwnerObserver(string timing) =>
        Verify("reciprocal-primary-only-" + timing, Reciprocal(editOpposite: false), timing, mutableObservers: false);

    [Fact]
    public void OrderedKeyedDirectExtensionKeepsItsSourceAndRegistrationPosition()
    {
        var root = new KeyedInventory();
        var key = new KeyedPart();
        root.Parts.Add(key);
        var child = new KeyedAssignment { Part = key };
        var trace = new List<string>();
        var raw = root.Assignments;
        using var first = raw.AddListener(_ => trace.Add("raw-first"));
        var projected = root.AsReadOnly().Assignments;
        using var second = raw.AddListener(edit =>
        {
            Assert.Same(raw, edit.Source);
            var added = Assert.Single(edit.Added);
            Assert.Same(child, added.Value);
            Assert.Equal(0, added.Index);
            trace.Add("raw-last");
        });
        using var directView = projected.AddListener(edit =>
        {
            Assert.Same(projected, edit.Source);
            var added = Assert.Single(edit.Added);
            Assert.Same(child.AsReadOnly(), added.Value);
            Assert.Equal(0, added.Index);
            trace.Add("projected-direct");
        });
        using var modeled = root.AsReadOnly().VmfChanges.AddLocalListener(_ => trace.Add("projected-modeled"));

        raw.Add(child);

        Assert.Equal(["raw-first", "projected-direct", "raw-last", "projected-modeled"], trace);
    }

    private static void Verify(string name, Fixture fixture, string timing, bool mutableObservers)
    {
        var trace = new List<string>();
        var subscriptions = new List<IDisposable>();
        void Watch(bool projected, ModelChangeScope scope, string label) =>
            subscriptions.Add(fixture.Watch(projected, scope, () => trace.Add(label)));
        void Activate() => Watch(true, ModelChangeScope.Local, "Rnew");

        using var first = mutableObservers ? fixture.Watch(false, ModelChangeScope.Local, () =>
        {
            trace.Add("M0");
            if (fixture.Opposite is null && timing == "dormant") Activate();
        }) : null;
        fixture.CollectionView();
        if (timing == "early") Watch(true, ModelChangeScope.Local, "R0");
        if (timing == "dormant")
        {
            fixture.Watch(true, ModelChangeScope.Local,
                () => throw new InvalidOperationException("Disposed listener was reactivated")).Dispose();
        }
        if (mutableObservers) Watch(false, ModelChangeScope.Recursive, "M1");
        if (timing == "late") Watch(true, ModelChangeScope.Local, "R0");
        if (timing is "early" or "late") Watch(true, ModelChangeScope.Recursive, "R1");
        using var opposite = fixture.Opposite?.AddPropertyListener("groups", _ =>
        {
            trace.Add("opposite");
            if (timing is "dormant" or "fresh") Activate();
        });

        try
        {
            fixture.Edit();
            ListenerTraces.EqualByKind(Expected[name], trace);
        }
        finally
        {
            foreach (var subscription in subscriptions) subscription.Dispose();
        }
    }

    private static Fixture Keyed()
    {
        var owner = new KeyedInventory();
        var key = new KeyedPart();
        owner.Parts.Add(key);
        var child = new KeyedAssignment { Part = key };
        var view = owner.AsReadOnly();
        return new(owner, owner.VmfChanges, view, "assignments", () => view.Assignments,
            child, child.AsReadOnly(), 0, null, () =>
            {
                owner.Assignments.Add(child);
                Assert.Same(child, owner.FindAssignmentsByPart(key));
            });
    }

    private static Fixture Reciprocal(bool editOpposite)
    {
        var owner = new Endpoint();
        var child = new Endpoint();
        var view = owner.AsReadOnly();
        return new(owner, owner.VmfChanges, view, "members", () => view.Members,
            child, child.AsReadOnly(), null, child.VmfChanges, () =>
            {
                if (editOpposite) child.Groups.Add(owner);
                else owner.Members.Add(child);
                Assert.Contains(child, owner.Members);
                Assert.Contains(owner, child.Groups);
            });
    }

    private sealed record Fixture(object Owner, ModelChanges Mutable, IReadOnlyModelView View,
        string Property, Func<object> CollectionView, object Target, object TargetView, int? Index,
        ModelChanges? Opposite, Action Edit)
    {
        internal IDisposable Watch(bool projected, ModelChangeScope scope, Action callback) => projected
            ? View.VmfChanges.AddListener(edit =>
            {
                if (edit.PropertyName != Property) return;
                VerifyChange(edit.Kind, View, edit.Source, TargetView, edit.Removed, edit.Added, callback);
            }, scope)
            : Mutable.AddListener(edit =>
            {
                if (edit.PropertyName != Property) return;
                var delta = Assert.IsType<ModelCollectionChange>(edit);
                VerifyChange(edit.Kind, Owner, edit.Source, Target, delta.Removed, delta.Added, callback);
            }, scope);

        private void VerifyChange(ModelChangeKind kind, object source, object actualSource, object target,
            IReadOnlyList<ModelCollectionEntry> removed, IReadOnlyList<ModelCollectionEntry> added, Action callback)
        {
            Assert.Equal(ModelChangeKind.ReferenceSet, kind);
            Assert.Same(source, actualSource);
            Assert.Empty(removed);
            var member = Assert.Single(added);
            Assert.Same(target, member.Value);
            Assert.Equal(Index, member.Index);
            callback();
        }
    }
}
