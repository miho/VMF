using System.Runtime.CompilerServices;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ProjectedListLifetimeTests
{
    [Fact]
    public void DormantWrappersCanBeCollectedWhileTheSourceRemainsLive()
    {
        var source = new CollectionProfile().Messages;
        var dormant = CreateDormantWrapper(source);
        CollectUnreferencedObjects();
        Assert.False(IsAlive(dormant));

        source.Add("cleanup");
        var received = new List<string?>();
        using var listener = new ReadOnlyList<string?, string?>(source, static value => value)
            .AddListener(edit => received.Add(Assert.Single(edit.Added).Value));
        source.Add("after cleanup");

        Assert.Equal(new[] { "after cleanup" }, received);
        GC.KeepAlive(source);
    }

    [Fact]
    public void SubscriptionsKeepTheirProjectedSourceUntilDisposed()
    {
        var source = new CollectionProfile().Messages;
        var received = new List<string?>();
        var (projection, subscription) = CreateObservedWrapper(source, received);
        CollectUnreferencedObjects();
        Assert.True(IsAlive(projection));
        source.Add("while subscribed");
        subscription.Dispose();

        CollectUnreferencedObjects();
        Assert.False(IsAlive(projection));
        source.Add("after disposal");

        Assert.Equal(new[] { "while subscribed" }, received);
        GC.KeepAlive(source);
        GC.KeepAlive(subscription);
    }

    [Fact]
    public void IgnoringTheSubscriptionHandleKeepsTheListenerActive()
    {
        var source = new CollectionProfile().Messages;
        var received = new List<string?>();
        var projection = SubscribeWithoutKeepingTheHandle(source, received);
        CollectUnreferencedObjects();
        Assert.True(IsAlive(projection));

        source.Add("observed");

        Assert.Equal(new[] { "observed" }, received);
        GC.KeepAlive(source);
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static bool IsAlive(WeakReference<ReadOnlyList<string?, string?>> projection) =>
        projection.TryGetTarget(out _);

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static WeakReference<ReadOnlyList<string?, string?>> SubscribeWithoutKeepingTheHandle(
        IReadOnlyList<string?> source, List<string?> received)
    {
        var projection = new ReadOnlyList<string?, string?>(source, static value => value);
        _ = projection.AddListener(edit => received.Add(Assert.Single(edit.Added).Value));
        return new(projection);
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static WeakReference<ReadOnlyList<string?, string?>> CreateDormantWrapper(IReadOnlyList<string?> source)
    {
        var projection = new ReadOnlyList<string?, string?>(source, static value => value);
        return new(projection);
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static (WeakReference<ReadOnlyList<string?, string?>>, IDisposable) CreateObservedWrapper(
        IReadOnlyList<string?> source, List<string?> received)
    {
        var projection = new ReadOnlyList<string?, string?>(source, static value => value);
        var subscription = projection.AddListener(edit => received.Add(Assert.Single(edit.Added).Value));
        return (new(projection), subscription);
    }

    private static void CollectUnreferencedObjects()
    {
        GC.Collect(GC.MaxGeneration, GCCollectionMode.Forced, blocking: true);
        GC.WaitForPendingFinalizers();
        GC.Collect(GC.MaxGeneration, GCCollectionMode.Forced, blocking: true);
    }
}
