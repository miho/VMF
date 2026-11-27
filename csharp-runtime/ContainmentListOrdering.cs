namespace Vmf.Runtime;

public sealed partial class ContainmentList<TParent, TChild> : IModelListOrdering<TChild>
    where TParent : class
    where TChild : class?
{
    /// <summary>Stably sorts the owning list and reports one edit at the changed positions.</summary>
    public void Sort(Comparison<TChild> comparison)
    {
        ArgumentNullException.ThrowIfNull(comparison);
        RequireWritable();
        if (unordered) throw new InvalidOperationException("An unordered containment cannot be sorted.");
        if (Count < 2) return;

        var original = items.ToArray();
        TChild[] sorted;
        updating = true;
        try
        {
            sorted = original.OrderBy(child => child, Comparer<TChild>.Create(comparison)).ToArray();
        }
        finally
        {
            updating = false;
        }
        int[] positions = Enumerable.Range(0, original.Length)
            .Where(index => !ReferenceEquals(original[index], sorted[index])).ToArray();
        if (positions.Length == 0) return;
        SetOrderedSlots(positions, positions.Select(index => original[index]).ToArray(),
            positions.Select(index => sorted[index]).ToArray());
    }

    private void SetOrderedSlots(int[] positions, TChild[] before, TChild[] after)
    {
        RequireWritable();
        var delivery = CaptureListDelivery();
        for (int i = 0; i < positions.Length; i++) items[positions[i]] = after[i];
        cursorVersion++;

        Exception? failure = null;
        var edit = delivery.NeedsEdit ? ModelListListeners<TChild>.Delivery.CreateEdit(positions, before, after) : default;
        var deliver = delivery.Prepare(edit, failure);
        if (deliver is not null)
        {
            var kind = orderedSet ? ModelChangeKind.ReferenceSet : ModelChangeKind.List;
            var change = changeSource.Create(kind, isModelReference: true,
                before.Select((child, offset) => new ModelCollectionEntry(child, Index: positions[offset])),
                after.Select((child, offset) => new ModelCollectionEntry(child, Index: positions[offset])),
                matches: undo => MatchesOrdering(positions, undo ? after : before),
                replay: undo => SetOrderedSlots(positions, undo ? after : before, undo ? before : after),
                apply: _ => ApplyRecordedOrdering(positions, before, after), isContainment: true,
                isOrdinaryCollection: IsOrdinaryList, isKeyedContainment: key is not null);
            ContainmentEdits.Notify(deliver, change, ref failure);
        }
        ModelObserverFailures.Rethrow(delivery.Complete(edit, failure));
    }

    private bool MatchesOrdering(int[] positions, TChild[] expected)
    {
        if (positions[^1] >= Count) return false;
        for (int offset = 0; offset < positions.Length; offset++)
        {
            if (!ReferenceEquals(items[positions[offset]], expected[offset])) return false;
        }
        return true;
    }

    private void ApplyRecordedOrdering(int[] positions, TChild[] before, TChild[] after)
    {
        if (MatchesOrdering(positions, before))
        {
            SetOrderedSlots(positions, before, after);
            return;
        }

        // Applying onto a changed list must repair any actual ownership changes.
        int start = positions[0];
        int length = positions[^1] - start + 1;
        var replacement = items.GetRange(start, length).ToArray();
        for (int offset = 0; offset < positions.Length; offset++) replacement[positions[offset] - start] = after[offset];
        ReplaceRange(start, length, replacement);
    }
}
