namespace Vmf.Runtime;

public sealed partial class ModelReflectionRegistry
{
    private sealed class Counterparts
    {
        private readonly ModelReflectionRegistry registry;
        private readonly GraphImage source;
        private readonly GraphImage desired;
        private readonly bool differentRoots;
        private readonly HashSet<object> sourceOwned;
        private readonly HashSet<object> desiredOwned;
        private readonly Dictionary<object, object> sourceUnkeyed;
        private readonly HashSet<object> desiredUnkeyed;
        private readonly HashSet<object> matchedSources = new(ReferenceEqualityComparer.Instance);
        private readonly Queue<(ImageNode Prior, ImageNode Next)> pending = new();
        private readonly Dictionary<object, int> hashes = new(ReferenceEqualityComparer.Instance);
        private readonly List<CollectionPair> referenceKeys = [];
        private readonly List<CollectionPair> detachedReferences = [];
        private readonly List<(object Prior, object Next)> singularFallback = [];

        internal Counterparts(ModelReflectionRegistry registry, GraphImage source, GraphImage desired)
        {
            this.registry = registry;
            this.source = source;
            this.desired = desired;
            differentRoots = source.Root.Model.Schema.Snapshot.RuntimeType != desired.Root.Model.Schema.Snapshot.RuntimeType;
            var sourceOwners = OwnsMembers(source);
            var desiredOwners = OwnsMembers(desired);
            sourceOwned = new HashSet<object>(sourceOwners.Keys, ReferenceEqualityComparer.Instance);
            desiredOwned = new HashSet<object>(desiredOwners.Keys, ReferenceEqualityComparer.Instance);
            sourceUnkeyed = sourceOwners.Where(entry => !entry.Value.Keyed)
                .ToDictionary(entry => entry.Key, entry => entry.Value.Owner, ReferenceEqualityComparer.Instance);
            desiredUnkeyed = new HashSet<object>(desiredOwners.Where(entry => !entry.Value.Keyed).Select(entry => entry.Key),
                ReferenceEqualityComparer.Instance);
        }

        internal Dictionary<object, object> Targets { get; } = new(ReferenceEqualityComparer.Instance);

        internal void Match()
        {
            Pair(source.Root.Model.Identity, desired.Root.Model.Identity, allowDifferentTypes: differentRoots);
            foreach (object exact in desired.Nodes.Keys)
            {
                if (source.Nodes.ContainsKey(exact)) Pair(exact, exact);
            }
            while (true)
            {
                WalkPairs();
                // A model-valued key may be resolved by a later owning slot on the same graph.
                foreach (var pair in referenceKeys)
                {
                    MatchMembers(pair, MatchModelKey);
                }
                if (pending.Count != 0) continue;

                MatchUnambiguousOwnedMembers();
                if (pending.Count != 0) continue;

                foreach (var (prior, next) in singularFallback) Pair(prior, next);
                singularFallback.Clear();
                if (pending.Count != 0) continue;

                foreach (var pair in detachedReferences)
                {
                    MatchMembers(pair, pair.MatchDetachedByType ? MatchDetached : MatchDetachedContents);
                }
                if (pending.Count == 0) break;
            }
        }

        private void WalkPairs()
        {
            while (pending.TryDequeue(out var pair))
            {
                bool sharedRoot = differentRoots && ReferenceEquals(pair.Prior, source.Root);
                foreach (var field in pair.Next.Fields)
                {
                    var property = field.Property.Snapshot!;
                    if (!HasModelMembers(property)) continue;
                    // JVM ownership matching requires the two owners to have the same concrete type.
                    if (property.IsContainment && sharedRoot) continue;

                    object? before = pair.Prior.Field(property.ModelName).Value;
                    object? after = field.Value;
                    if (property.IsContainment && !property.IsCollection)
                    {
                        if (before is not null && after is not null && before.GetType() == after.GetType()
                            && !Targets.ContainsKey(after) && !matchedSources.Contains(before))
                        {
                            if (registry.ContentEquals(before, after)) Pair(before, after);
                            else singularFallback.Add((before, after));
                        }
                        continue;
                    }
                    var members = new CollectionPair(ModelMembers(property, before).ToArray(),
                        ModelMembers(property, after).ToArray(), field.Property.Field.KeyPropertyName);
                    if (members.Before.Length == 0 || members.After.Length == 0) continue;
                    if (property.IsReference)
                    {
                        // The JVM pairs detached singular ends by concrete type. Collections use known counterparts only.
                        if (!sharedRoot || !property.IsCollection)
                        {
                            detachedReferences.Add(members with { MatchDetachedByType = sharedRoot });
                        }
                    }
                    else if (members.Key is null)
                    {
                        MatchMembers(members, SameContents);
                    }
                    else if (desired.Nodes[members.After[0]].Field(members.Key).Property.Snapshot!.IsReference)
                    {
                        referenceKeys.Add(members);
                    }
                    else
                    {
                        MatchMembers(members, MatchScalarKey);
                    }
                }
            }
        }

        private void Pair(object prior, object next, bool allowDifferentTypes = false)
        {
            var left = source.Nodes[prior];
            var right = desired.Nodes[next];
            if (left.Model.Schema.Snapshot.IsImmutable || !allowDifferentTypes && prior.GetType() != next.GetType()
                || Targets.ContainsKey(next) || !matchedSources.Add(prior)) return;

            Targets.Add(next, prior);
            pending.Enqueue((left, right));
        }

        private void MatchMembers(CollectionPair pair, Func<object, object, string?, bool> matches)
        {
            foreach (object next in pair.After)
            {
                if (desired.Nodes[next].Model.Schema.Snapshot.IsImmutable || Targets.ContainsKey(next)) continue;
                foreach (object prior in pair.Before)
                {
                    if (!matchedSources.Contains(prior) && prior.GetType() == next.GetType() && matches(prior, next, pair.Key))
                    {
                        Pair(prior, next);
                        break;
                    }
                }
            }
        }

        private bool SameContents(object prior, object next, string? _) => registry.ContentEquals(prior, next);

        private bool MatchDetached(object prior, object next, string? _) =>
            !sourceOwned.Contains(prior) && !desiredOwned.Contains(next);

        private bool MatchDetachedContents(object prior, object next, string? key) =>
            MatchDetached(prior, next, key) && registry.ContentEquals(prior, next);

        private bool MatchScalarKey(object prior, object next, string? key) =>
            ScalarEquality<object?>.Instance.Equals(source.Nodes[prior].Field(key!).Value, desired.Nodes[next].Field(key!).Value);

        private bool MatchModelKey(object prior, object next, string? key)
        {
            object? expected = desired.Nodes[next].Field(key!).Value;
            object? current = source.Nodes[prior].Field(key!).Value;
            return expected is not null && ReferenceEquals(current, Targets.GetValueOrDefault(expected, expected));
        }

        private void MatchUnambiguousOwnedMembers()
        {
            // Leaving an unmatched removed subtree intact lets undo reattach that subtree as it was.
            var candidates = sourceUnkeyed.Where(entry => matchedSources.Contains(entry.Value) && !matchedSources.Contains(entry.Key)
                    && (!differentRoots || !ReferenceEquals(entry.Value, source.Root.Model.Identity)))
                .Select(entry => entry.Key)
                .GroupBy(Candidate).Where(group => group.Count() == 1).ToDictionary(group => group.Key, group => group.Single());
            if (candidates.Count == 0) return;
            foreach (var group in desiredUnkeyed.Where(model => !Targets.ContainsKey(model)).GroupBy(Candidate))
            {
                if (group.Count() != 1 || !candidates.TryGetValue(group.Key, out object? prior)) continue;
                object next = group.Single();
                if (registry.ContentEquals(prior, next)) Pair(prior, next);
            }

            (Type Type, int Hash) Candidate(object model)
            {
                if (!hashes.TryGetValue(model, out int hash)) hashes.Add(model, hash = registry.ContentHashCode(model));
                return (model.GetType(), hash);
            }
        }

        private static Dictionary<object, (object Owner, bool Keyed)> OwnsMembers(GraphImage graph)
        {
            var owned = new Dictionary<object, (object Owner, bool Keyed)>(ReferenceEqualityComparer.Instance);
            foreach (var node in graph.Nodes.Values)
            {
                foreach (var field in node.Fields)
                {
                    var property = field.Property.Snapshot!;
                    if (property.IsContainment)
                    {
                        foreach (var member in OwnedIdentities(property, field.Value))
                        {
                            owned.Add(member, (node.Model.Identity, field.Property.Field.KeyPropertyName is not null));
                        }
                    }
                }
            }
            return owned;
        }

        private sealed record CollectionPair(object[] Before, object[] After, string? Key, bool MatchDetachedByType = false);
    }
}
