using Eu.Mihosoft.Vmf.Conformance.Opposites;
using Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ReferenceListObservationTests
{
    [Fact]
    public void OrdinaryReferenceListProjectsAliasedAndNullValuesAndHonorsDisposal()
    {
        var owner = new ArchiveNode();
        var item = new ArchiveNode { Name = "item" };
        var replacement = new ArchiveNode { Name = "replacement" };
        var list = owner.Trail;
        var view = owner.AsReadOnly().Trail;
        var mutable = new List<ModelListChange<ArchiveNode?>>();
        var projected = new List<ModelListChange<IReadOnlyArchiveNode?>>();
        using var watchList = list.AddListener(mutable.Add);
        using var watchView = view.AddListener(projected.Add);

        list.AddRange([item, null, item]);
        list[1] = replacement;
        Assert.Equal(2, mutable.Count);
        Assert.Equal(2, projected.Count);
        Assert.All(mutable, edit => Assert.Same(list, edit.Source));
        Assert.All(projected, edit => Assert.Same(view, edit.Source));
        Assert.Equal([0, 1, 2], projected[0].Added.Select(entry => entry.Index));
        Assert.Same(item, mutable[0].Added[0].Value);
        Assert.Same(item.AsReadOnly(), projected[0].Added[0].Value);
        Assert.Null(projected[0].Added[1].Value);
        Assert.Same(projected[0].Added[0].Value, projected[0].Added[2].Value);
        Assert.Equal(new ModelListEntry<IReadOnlyArchiveNode?>(1, null), Assert.Single(projected[1].Removed));
        var substitution = Assert.Single(projected[1].Added);
        Assert.Equal(1, substitution.Index);
        Assert.Same(replacement.AsReadOnly(), substitution.Value);

        watchView.Dispose();
        list.RemoveAt(2);
        watchList.Dispose();
        list.Clear();
        Assert.Equal(3, mutable.Count);
        Assert.Equal(2, projected.Count);
        Assert.Same(item.AsReadOnly(), projected[0].Added[2].Value);
    }

    [Fact]
    public void ReciprocalReferenceListProjectsOneSparseInverseRemoval()
    {
        var owner = new Endpoint();
        var group = new Endpoint();
        var list = owner.Groups;
        list.AddRange([group, null, group]);

        VerifySparseRemoval(list, owner.AsReadOnly().Groups, group, group.AsReadOnly(),
            () => Assert.True(group.Members.Remove(owner)));

        Assert.Null(Assert.Single(list));
        Assert.Empty(group.Members);
    }

    [Fact]
    public void OrdinaryContainmentTransferPublishesSparseRemovalAndLiveProjection()
    {
        var source = new LegacyContainer();
        var destination = new LegacyContainer();
        var child = new LegacyChild();
        var retained = new LegacyChild();
        var list = source.Children;
        list.AddRange([child, retained, null]);
        list[2] = child;
        var view = source.AsReadOnly().Children;
        var additions = new List<ModelListChange<IReadOnlyLegacyChild?>>();
        var target = destination.AsReadOnly().Children;
        using var watchTarget = target.AddListener(additions.Add);

        VerifySparseRemoval(list, view, child, child.AsReadOnly(), () => destination.Children.Add(child));

        Assert.Same(retained, Assert.Single(list));
        Assert.Same(destination, child.Owner);
        var captured = Assert.Single(additions);
        Assert.Same(target, captured.Source);
        Assert.Equal(0, Assert.Single(captured.Added).Index);
        Assert.Same(child.AsReadOnly(), captured.Added[0].Value);
        Assert.Same(destination.AsReadOnly(), target[0]!.Owner);
    }

    [Fact]
    public void FailingProjectionListenerDoesNotInterruptTransferAndCanBeDisposed()
    {
        var source = new LegacyContainer();
        var destination = new LegacyContainer();
        var child = new LegacyChild();
        var list = source.Children;
        list.AddRange([child, null, null]);
        list[2] = child;
        var view = source.AsReadOnly().Children;
        var expected = new InvalidOperationException("first projected listener");
        var events = new List<string>();
        using (view.AddListener(_ => throw expected))
        using (view.AddListener(_ => events.Add("projected")))
        using (list.AddListener(_ => events.Add("source")))
        using (destination.Children.AddListener(_ => events.Add("destination")))
        {
            Assert.Same(expected, Assert.Throws<InvalidOperationException>(() => destination.Children.Add(child)));
        }
        Assert.Equal(["projected", "source", "destination"], events);
        Assert.Same(destination, child.Owner);

        source.Children.Add(child);
        Assert.Equal(3, events.Count);
        Assert.Same(source, child.Owner);
    }

    [Fact]
    public void RepeatingDisposalLeavesTheOtherRegistrationOfTheSameCallback()
    {
        var owner = new ArchiveNode();
        VerifyIndependentHandles(owner.Trail, () => owner.Trail.Add(new ArchiveNode()));
        VerifyIndependentHandles(owner.AsReadOnly().Trail, () => owner.Trail.Add(new ArchiveNode()));
        var parent = new LegacyContainer();
        VerifyIndependentHandles(parent.Children, () => parent.Children.Add(new LegacyChild()));
        VerifyIndependentHandles(parent.AsReadOnly().Children, () => parent.Children.Add(new LegacyChild()));
    }

    private static void VerifyIndependentHandles<T>(IReadOnlyList<T> list, Action edit)
    {
        var calls = 0;
        Action<ModelListChange<T>> callback = _ => calls++;
        using var first = list.AddListener(callback);
        using var second = list.AddListener(callback);
        first.Dispose();
        first.Dispose();
        edit();
        Assert.Equal(1, calls);
        second.Dispose();
        edit();
        Assert.Equal(1, calls);
    }

    private static void VerifySparseRemoval<T, TView>(IReadOnlyList<T> list, IReadOnlyList<TView> view,
        T value, TView projectedValue, Action edit)
        where T : class?
        where TView : class?
    {
        var mutable = new List<ModelListChange<T>>();
        var projected = new List<ModelListChange<TView>>();
        using var watchList = list.AddListener(mutable.Add);
        using var watchView = view.AddListener(projected.Add);
        edit();

        var native = Assert.Single(mutable);
        Assert.Same(list, native.Source);
        Assert.Empty(native.Added);
        Assert.Equal([0, 2], native.Removed.Select(entry => entry.Index));
        Assert.All(native.Removed, entry => Assert.Same(value, entry.Value));
        var readOnly = Assert.Single(projected);
        Assert.Same(view, readOnly.Source);
        Assert.Empty(readOnly.Added);
        Assert.Equal([0, 2], readOnly.Removed.Select(entry => entry.Index));
        Assert.All(readOnly.Removed, entry => Assert.Same(projectedValue, entry.Value));
    }
}
