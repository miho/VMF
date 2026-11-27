namespace Vmf.Runtime;

internal sealed partial class ModelSequence<T>
{
    internal void InsertReferencePositions(int[] indices, T[] values)
    {
        if (indices.Length != values.Length)
        {
            throw new ArgumentException("The number of positions and values must match.");
        }
        if (indices.Length == 0) return;
        var members = new HashSet<T>(Items, comparer);
        if (!values.All(members.Add))
        {
            throw new ArgumentException("The edit would duplicate an element in the ordered reference set.");
        }
        int countAfter = checked(Items.Count + values.Length);
        ScalarCollectionBounds.CheckCapacity(upperBound, countAfter);
        RequireSparsePositions(indices, countAfter);
        Items.EnsureCapacity(countAfter);
        EditReferencePositions(indices, [], values);
    }

    internal void RemoveReferencePositions(int[] indices)
    {
        if (indices.Length == 0) return;
        RequireSparsePositions(indices, Items.Count);
        EditReferencePositions(indices, indices.Select(index => Items[index]).ToArray(), []);
    }

    private static void RequireSparsePositions(int[] indices, int count)
    {
        int previous = -1;
        foreach (int index in indices)
        {
            if (index <= previous || index >= count)
            {
                throw new ArgumentException("Ordered reference positions must be increasing and within the edited list.");
            }
            previous = index;
        }
    }

    private void EditReferencePositions(int[] indices, T[] removed, T[] added)
    {
        var deliver = source.CaptureDelivery();
        int countBefore = Items.Count;
        Reciprocal?.PrepareCollectionEdit(removed, added, default);
        if (removed.Length != 0)
        {
            for (int offset = indices.Length - 1; offset >= 0; offset--) Items.RemoveAt(indices[offset]);
        }
        else
        {
            for (int offset = 0; offset < indices.Length; offset++) Items.Insert(indices[offset], added[offset]);
        }
        int countAfter = Items.Count;
        MarkCursorVersion();

        Exception? failure = null;
        Reciprocal?.UpdateCollectionOpposites(removed, added, ref failure);
        if (deliver is not null)
        {
            var change = source.Create(kind, isModelReference,
                removed.Select((value, offset) => new ModelCollectionEntry(value, Index: indices[offset])),
                added.Select((value, offset) => new ModelCollectionEntry(value, Index: indices[offset])),
                matches: undo => undo ? MatchesSparse(added, removed, countAfter) : MatchesSparse(removed, added, countBefore),
                replay: undo => ReplaySparse(undo ? removed : added),
                apply: _ => ReplaySparse(added), isOrdinaryCollection: Reciprocal is null);
            ReciprocalEndpoint.Attempt(() => deliver(change), ref failure);
        }
        ModelObserverFailures.Rethrow(failure);

        bool MatchesSparse(T[] expected, T[] inserting, int expectedCount)
        {
            if (expected.Length == 0)
            {
                return Items.Count == expectedCount
                    && !new HashSet<T>(inserting, comparer).Overlaps(Items);
            }
            if (indices[^1] >= Items.Count) return false;
            return indices.Select((index, offset) => ReferenceEquals(Items[index], expected[offset])).All(match => match);
        }

        void ReplaySparse(T[] inserting)
        {
            if (inserting.Length == 0) RemoveReferencePositions(indices);
            else InsertReferencePositions(indices, inserting);
        }
    }
}
