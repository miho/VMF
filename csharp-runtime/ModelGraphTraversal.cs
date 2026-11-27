using System.Collections;

namespace Vmf.Runtime;

/// <summary>Selects which model edges and occurrences a traversal visits.</summary>
public enum ModelTraversalStrategy
{
    /// <summary>Visits owning containment edges, yielding each occupied slot and expanding each mutable identity once.</summary>
    ContainmentTree,
    /// <summary>Visits containment and references, yielding each mutable identity once.</summary>
    UniqueNode,
    /// <summary>Yields aliases reached by different edges while expanding each mutable identity once.</summary>
    UniqueProperty,
}

public sealed partial class ModelReflectionRegistry
{
    /// <summary>Traverses in VMF property order and collection order, depth first, including the root.</summary>
    /// <remarks>Values reached through a read-only view use their generated read-only projection.
    /// Immutable values are visited for every occurrence. An inverse container is not a traversal edge.</remarks>
    public IEnumerable<object> Traverse(object root, ModelTraversalStrategy strategy = ModelTraversalStrategy.UniqueProperty)
    {
        ArgumentNullException.ThrowIfNull(root);
        if (!Enum.IsDefined(strategy)) throw new ArgumentOutOfRangeException(nameof(strategy));
        return TraverseFrom(root, strategy);
    }

    /// <summary>Visits expanded nodes with paired depth-first enter and exit callbacks.</summary>
    /// <remarks>Mutable identities are expanded once. Each immutable occurrence has its own callbacks.
    /// When the root is a read-only view, callbacks receive the same projections as traversal.</remarks>
    public void Visit(object root, Action<object> onEnter, Action<object> onExit,
        ModelTraversalStrategy strategy = ModelTraversalStrategy.UniqueNode)
    {
        ArgumentNullException.ThrowIfNull(root);
        ArgumentNullException.ThrowIfNull(onEnter);
        ArgumentNullException.ThrowIfNull(onExit);
        if (!Enum.IsDefined(strategy)) throw new ArgumentOutOfRangeException(nameof(strategy));
        foreach (var _ in TraverseFrom(root, strategy, onEnter, onExit)) { }
    }

    private IEnumerable<object> TraverseFrom(object root, ModelTraversalStrategy strategy,
        Action<object>? onEnter = null, Action<object>? onExit = null)
    {
        var expanded = new HashSet<object>(ReferenceEqualityComparer.Instance);
        var pending = new Stack<TraversalFrame>();
        try
        {
            for (object? next = root; next is not null; next = NextChild(pending, onExit))
            {
                var model = Bind(next);
                bool first = model.Schema.Snapshot.IsImmutable || expanded.Add(model.Identity);
                if (first) onEnter?.Invoke(next);
                if (first || strategy != ModelTraversalStrategy.UniqueNode) yield return next;
                if (first) pending.Push(new(next, RelatedModels(model, strategy).GetEnumerator()));
            }
        }
        finally
        {
            while (pending.TryPop(out var frame)) frame.Children.Dispose();
        }
    }

    private static object? NextChild(Stack<TraversalFrame> pending, Action<object>? onExit)
    {
        while (pending.TryPeek(out var frame))
        {
            if (frame.Children.MoveNext()) return frame.Children.Current;
            pending.Pop();
            frame.Children.Dispose();
            onExit?.Invoke(frame.Node);
        }
        return null;
    }

    private readonly record struct TraversalFrame(object Node, IEnumerator<object> Children);

    internal static bool FollowsEdge(SnapshotProperty? property, ModelTraversalStrategy strategy) =>
        property is not null && (property.IsContainment
            || strategy != ModelTraversalStrategy.ContainmentTree && property.IsReference);

    private static IEnumerable<object> RelatedModels(ModelBinding model, ModelTraversalStrategy strategy)
    {
        foreach (var field in model.Schema.Fields)
        {
            var snapshot = field.Snapshot;
            if (!FollowsEdge(snapshot, strategy)) continue;

            var value = field.Read(model);
            if (value is null) continue;
            if (!snapshot!.IsCollection)
            {
                yield return value;
            }
            else
            {
                foreach (object? member in (IEnumerable)value)
                {
                    if (member is not null) yield return member;
                }
            }
        }
    }
}
