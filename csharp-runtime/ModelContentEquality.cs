using System.Collections;

namespace Vmf.Runtime;

public sealed partial class ModelReflectionRegistry
{
    /// <summary>Compares the participating contents of registered model objects or their read-only views.</summary>
    public bool ContentEquals(object? left, object? right)
    {
        if (ReferenceEquals(left, right)) return true;
        if (left is null || right is null) return false;

        object first = unwrapView?.Invoke(left) ?? left;
        object second = unwrapView?.Invoke(right) ?? right;
        if (ReferenceEquals(first, second)) return true;
        var type = first.GetType();
        if (type != second.GetType() || !models.TryGetValue(type, out var schema)) return false;

        var graph = new ContentGraph(this);
        int firstIndex = graph.Add(first, schema);
        int secondIndex = graph.Add(second, schema);
        graph.Capture();
        var partition = graph.Classify(includeHashes: false);
        return partition.Colors[firstIndex] == partition.Colors[secondIndex];
    }

    /// <summary>Hashes the participating contents of a model object or a read-only view.</summary>
    /// <remarks>The hash reflects current model contents and has meaning within the current process.</remarks>
    public int ContentHashCode(object model)
    {
        var binding = Bind(model);
        var graph = new ContentGraph(this);
        int root = graph.Add(binding.Identity, binding.Schema);
        graph.Capture();
        return graph.Classify(includeHashes: true).Hashes![root];
    }

    internal static class UntypedObjectComparer<T>
    {
        private static readonly ScalarEquality<object?> Scalars = ScalarEquality<object?>.Instance;

        internal static readonly IEqualityComparer<T> Instance = EqualityComparer<T>.Create(
            (left, right) => ReferenceEquals(left, right)
                || Scalars.Equals(ScalarField.CaptureUntyped(left), ScalarField.CaptureUntyped(right)),
            value => ScalarField.CaptureUntyped(value) is { } captured ? Scalars.GetHashCode(captured) : 0);
    }

    private sealed class ContentGraph(ModelReflectionRegistry registry)
    {
        private readonly Dictionary<object, int> identities = new(ReferenceEqualityComparer.Instance);
        private readonly List<ContentNode> nodes = new();

        internal int Add(object value, ModelSchema schema)
        {
            if (identities.TryGetValue(value, out int index)) return index;

            index = nodes.Count;
            identities.Add(value, index);
            nodes.Add(new(value, schema));
            return index;
        }

        internal void Capture()
        {
            for (int index = 0; index < nodes.Count; index++)
            {
                var node = nodes[index];
                var scalars = new List<ScalarField>();
                foreach (var property in node.Schema.ContentFields)
                {
                    var value = CaptureField(property, node.Value);
                    if (property.IsContainment || property.IsReference)
                    {
                        int[] targets = property.IsCollection
                            ? Array.ConvertAll((object?[])value!, AddTarget)
                            : [AddTarget(value)];
                        node.Relations.Add(new(targets, property.IsUnorderedSet));
                    }
                    else
                    {
                        if (property.IsUntypedObject)
                        {
                            value = property.IsScalarCollection
                                ? Array.ConvertAll((object?[])value!, ScalarField.CaptureUntyped)
                                : ScalarField.CaptureUntyped(value);
                        }
                        scalars.Add(new(property.Kind, value));
                    }
                }
                node.Local = new(node.Schema.Snapshot.RuntimeType, scalars.ToArray());
            }
        }

        private int AddTarget(object? value)
        {
            if (value is null) return -1;
            if (identities.TryGetValue(value, out int index)) return index;
            var model = registry.Bind(value);
            return Add(model.Identity, model.Schema);
        }

        internal ContentPartition Classify(bool includeHashes)
        {
            var localClasses = new Dictionary<LocalContent, int>();
            var localColors = new int[nodes.Count];
            for (int index = 0; index < nodes.Count; index++)
            {
                localColors[index] = AssignColor(localClasses, nodes[index].Local);
            }
            int[]? localHashes = includeHashes ? nodes.Select(node => node.Local.GetHashCode()).ToArray() : null;

            // Most containment graphs can be evaluated in one pass from leaves to roots.
            var order = PostOrder();
            if (order is not null)
            {
                var classes = new Dictionary<RelationContent, int>();
                var colors = new int[nodes.Count];
                int[]? hashes = includeHashes ? new int[nodes.Count] : null;
                foreach (int index in order)
                {
                    var node = nodes[index];
                    colors[index] = AssignColor(classes, new(localColors[index], node.Edges(colors)));
                    if (hashes is not null) hashes[index] = HashEdges(localHashes![index], node.Edges(hashes));
                }
                return new(colors, hashes);
            }

            // A cycle may revisit an equal state at a different identity or depth. Refine
            // structural classes until every edge leads to the same class in either graph.
            var current = new ContentPartition(localColors, localHashes);
            int classCount = localClasses.Count;
            while (true)
            {
                var classes = new Dictionary<RelationContent, int>();
                var colors = new int[nodes.Count];
                int[]? hashes = includeHashes ? new int[nodes.Count] : null;
                for (int index = 0; index < nodes.Count; index++)
                {
                    var node = nodes[index];
                    colors[index] = AssignColor(classes, new(current.Colors[index], node.Edges(current.Colors)));
                    if (hashes is not null) hashes[index] = HashEdges(localHashes![index], node.Edges(current.Hashes!));
                }
                // Each pass only splits classes. The number of passes depends on the
                // observable classes, so sharing or expanding a cycle cannot change the hash.
                current = new(colors, hashes);
                if (classCount == classes.Count) return current;
                classCount = classes.Count;
            }
        }

        private int[]? PostOrder()
        {
            var order = new int[nodes.Count];
            int position = 0;
            var states = new VisitState[nodes.Count];
            var pending = new Stack<(int Index, bool Leave)>();
            for (int index = 0; index < nodes.Count; index++)
            {
                if (states[index] != VisitState.New) continue;
                pending.Push((index, false));
                while (pending.TryPop(out var visit))
                {
                    if (visit.Leave)
                    {
                        states[visit.Index] = VisitState.Complete;
                        order[position++] = visit.Index;
                        continue;
                    }
                    if (states[visit.Index] == VisitState.Complete) continue;
                    if (states[visit.Index] == VisitState.Entered) return null;

                    states[visit.Index] = VisitState.Entered;
                    pending.Push((visit.Index, true));
                    foreach (var relation in nodes[visit.Index].Relations)
                    {
                        foreach (int target in relation.Targets)
                        {
                            if (target >= 0) pending.Push((target, false));
                        }
                    }
                }
            }
            return order;
        }

        private static int AssignColor<T>(Dictionary<T, int> classes, T shape) where T : notnull
        {
            if (classes.TryGetValue(shape, out int color)) return color;
            color = classes.Count;
            classes.Add(shape, color);
            return color;
        }

        private enum VisitState { New, Entered, Complete }
    }

    private sealed class ContentNode(object value, ModelSchema schema)
    {
        internal object Value { get; } = value;
        internal ModelSchema Schema { get; } = schema;
        internal LocalContent Local { get; set; } = null!;
        internal List<ContentRelation> Relations { get; } = new();

        internal int[] Edges(int[] colors)
        {
            if (Relations.Count == 0) return [];

            var edges = new int[Relations.Sum(relation => relation.Targets.Length + 1)];
            int offset = 0;
            foreach (var relation in Relations)
            {
                edges[offset++] = relation.Targets.Length;
                var members = edges.AsSpan(offset, relation.Targets.Length);
                for (int index = 0; index < members.Length; index++)
                {
                    int target = relation.Targets[index];
                    members[index] = target < 0 ? -1 : colors[target];
                }
                // Sort the colors, keeping a distinct slot for each identity-set member.
                if (relation.Unordered) members.Sort();
                offset += members.Length;
            }
            return edges;
        }
    }

    private readonly record struct ContentRelation(int[] Targets, bool Unordered);
    private readonly record struct ContentPartition(int[] Colors, int[]? Hashes);

    private sealed class RelationContent(int color, int[] edges) : IEquatable<RelationContent>
    {
        private readonly int color = color;
        private readonly int[] edges = edges;
        private readonly int hash = HashEdges(color, edges);

        public bool Equals(RelationContent? other) => other is not null && color == other.color
            && edges.AsSpan().SequenceEqual(other.edges);

        public override bool Equals(object? other) => other is RelationContent shape && Equals(shape);
        public override int GetHashCode() => hash;
    }

    private static int HashEdges(int state, int[] edges)
    {
        var hash = new HashCode();
        hash.Add(state);
        foreach (int edge in edges) hash.Add(edge);
        return hash.ToHashCode();
    }

    private sealed class LocalContent(Type type, ScalarField[] scalars) : IEquatable<LocalContent>
    {
        private readonly Type type = type;
        private readonly ScalarField[] scalars = scalars;
        private readonly int hash = HashScalars(type, scalars);

        public bool Equals(LocalContent? other) => other is not null && type == other.type
            && scalars.SequenceEqual(other.scalars);

        public override bool Equals(object? other) => other is LocalContent shape && Equals(shape);

        public override int GetHashCode() => hash;

        private static int HashScalars(Type type, ScalarField[] scalars)
        {
            var hash = new HashCode();
            hash.Add(type);
            foreach (var scalar in scalars) hash.Add(scalar);
            return hash.ToHashCode();
        }
    }

    private readonly struct ScalarField : IEquatable<ScalarField>
    {
        private static readonly ScalarEquality<object?> Comparer = ScalarEquality<object?>.Instance;
        private readonly SnapshotPropertyKind kind;
        private readonly object? value;

        internal ScalarField(SnapshotPropertyKind kind, object? value)
        {
            this.kind = kind;
            this.value = kind switch
            {
                SnapshotPropertyKind.ScalarSet => new HashSet<object?>((object?[])value!, Comparer),
                SnapshotPropertyKind.ScalarMap => (((object Key, object? Value)[])value!)
                    .ToDictionary(entry => entry.Key, entry => entry.Value, ScalarEquality<object>.Instance),
                _ => value,
            };
        }

        internal static object? CaptureUntyped(object? value)
        {
            if (!IsUntypedCollection(value)) return value;
            var active = new HashSet<object>(ReferenceEqualityComparer.Instance);
            return TryCaptureUntyped(value, active, 0, out var captured) ? captured : value;
        }

        private static bool IsUntypedCollection(object? value) => value is (IList or IDictionary) and not Array;

        private static bool TryCaptureUntyped(object? value, HashSet<object> active, int depth, out object? captured)
        {
            captured = value;
            if (!IsUntypedCollection(value)) return true;
            // Portable Object snapshots are acyclic. Leave other host values to their existing equality semantics.
            if (depth >= SnapshotJson.MaxUntypedValueDepth || !active.Add(value!)) return false;
            try
            {
                if (value is IDictionary map)
                {
                    var entries = new (object Key, object? Value)[map.Count];
                    int index = 0;
                    foreach (DictionaryEntry entry in map)
                    {
                        if (entry.Key is not string key
                            || !TryCaptureUntyped(entry.Value, active, depth + 1, out var member)) return false;
                        entries[index++] = (key, member);
                    }
                    captured = new ScalarField(SnapshotPropertyKind.ScalarMap, entries);
                }
                else
                {
                    var list = (IList)value!;
                    var members = new object?[list.Count];
                    for (int index = 0; index < members.Length; index++)
                    {
                        if (!TryCaptureUntyped(list[index], active, depth + 1, out members[index])) return false;
                    }
                    captured = new ScalarField(SnapshotPropertyKind.ScalarList, members);
                }
                return true;
            }
            finally
            {
                active.Remove(value!);
            }
        }

        public bool Equals(ScalarField other) => kind == other.kind && kind switch
        {
            SnapshotPropertyKind.ScalarList or SnapshotPropertyKind.OrderedScalarSet =>
                ((object?[])value!).SequenceEqual((object?[])other.value!, Comparer),
            SnapshotPropertyKind.ScalarSet => ((HashSet<object?>)value!).SetEquals((HashSet<object?>)other.value!),
            SnapshotPropertyKind.ScalarMap => EqualMaps((Dictionary<object, object?>)value!, (Dictionary<object, object?>)other.value!),
            _ => Comparer.Equals(value, other.value),
        };

        public override bool Equals(object? other) => other is ScalarField field && Equals(field);

        public override int GetHashCode() => kind switch
        {
            SnapshotPropertyKind.ScalarList or SnapshotPropertyKind.OrderedScalarSet =>
                HashEdges((int)kind, Array.ConvertAll((object?[])value!, HashScalar)),
            SnapshotPropertyKind.ScalarSet => UnorderedHash((HashSet<object?>)value!, HashScalar),
            SnapshotPropertyKind.ScalarMap => UnorderedHash((Dictionary<object, object?>)value!,
                entry => HashScalar(entry.Key) ^ HashScalar(entry.Value)),
            _ => HashScalar(value),
        };

        private static bool EqualMaps(Dictionary<object, object?> left, Dictionary<object, object?> right)
        {
            if (left.Count != right.Count) return false;
            foreach (var (key, value) in left)
            {
                if (!right.TryGetValue(key, out var other) || !Comparer.Equals(value, other)) return false;
            }
            return true;
        }

        private static int HashScalar(object? value) => value is null ? 0 : Comparer.GetHashCode(value);

        private static int UnorderedHash<T>(ICollection<T> values, Func<T, int> hash)
        {
            int result = values.Count;
            foreach (var value in values) result = unchecked(result + hash(value));
            return result;
        }
    }
}
