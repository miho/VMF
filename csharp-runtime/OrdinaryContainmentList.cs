namespace Vmf.Runtime;

public sealed partial class ContainmentList<TParent, TChild>
    where TParent : class
    where TChild : class?
{
    private void InsertOrdinaryRange(int index, IReadOnlyList<TChild> added)
    {
        var seen = new HashSet<TChild>(ReferenceEqualityComparer.Instance);
        bool hasRepeatedChild = added.Any(child => child is not null
            && (ReferenceEquals(getLink(child).CurrentCollection, this) || !seen.Add(child)));
        if (!hasRepeatedChild)
        {
            ReplaceRange(index, 0, added.ToArray());
            return;
        }

        var desired = new List<TChild>(items);
        desired.InsertRange(index, added);
        seen.Clear();
        foreach (var child in added)
        {
            if (child is null) continue;
            var link = getLink(child);
            link.RequireAvailable();
            if (ReferenceEquals(link.CurrentCollection, this) || !seen.Add(child))
            {
                desired.RemoveAt(desired.FindIndex(candidate => ReferenceEquals(candidate, child)));
            }
        }

        // Publish the stable positional edit after insertion has resolved the old occurrence.
        int first = 0;
        while (first < Count && first < desired.Count && ReferenceEquals(items[first], desired[first])) first++;
        if (first == Count && first == desired.Count) return;
        int oldEnd = Count;
        int newEnd = desired.Count;
        while (oldEnd > first && newEnd > first && ReferenceEquals(items[oldEnd - 1], desired[newEnd - 1]))
        {
            oldEnd--;
            newEnd--;
        }
        ReplaceRange(first, oldEnd - first, desired.GetRange(first, newEnd - first).ToArray());
    }

    private void ReplayOrdinarySlots(int[] positions, int removedCount, TChild[] restored)
    {
        if (restored.Length == 0)
        {
            ReplaceRange(0, removedCount, [], positions);
            return;
        }

        var seen = new HashSet<TChild>(items, ReferenceEqualityComparer.Instance);
        if (restored.Any(child => child is not null && !seen.Add(child)))
        {
            // Restoring an owning alias requires one edit to keep every occurrence
            // while attaching each identity at most once.
            ReplaceRange(0, 0, restored, positions);
            return;
        }
        ModelListSlots.RestoreRuns(positions, restored, (index, values) => ReplaceRange(index, 0, values));
    }

    private bool RemoveRepeatedForTransfer(object child, ref Exception? failure)
    {
        var positions = new List<int>();
        for (int index = 0; index < Count; index++)
        {
            if (ReferenceEquals(items[index], child)) positions.Add(index);
        }
        if (positions.Count < 2) return false;

        var delivery = CaptureListDelivery();
        var edit = delivery.NeedsEdit ? ModelListListeners<TChild>.Delivery.CreateRemoval(positions, (TChild)child) : default;
        var deliver = delivery.Prepare(edit, failure);
        var before = delivery.NeedsModelChange ? items.ToArray() : [];
        for (int index = positions.Count - 1; index >= 0; index--) items.RemoveAt(positions[index]);
        cursorVersion++;
        if (deliver is null)
        {
            if (!changeSource.IsTracked)
            {
                ModelChanges.ReconcileUntrackedContainment(this, [child], [], useMemberIndex: false);
            }
        }
        else
        {
            var after = items.ToArray();
            var delta = changeSource.Create(ModelChangeKind.List, isModelReference: true,
                positions.Select(index => new ModelCollectionEntry(child, Index: index)), [],
                matches: undo => HasSameSlots(undo ? after : before),
                replay: undo => ReplaceRange(0, Count, undo ? before : after),
                apply: _ => ReplaceRange(0, Count, after), isContainment: true, isTransferRemoval: true);
            ContainmentEdits.Notify(deliver, delta, ref failure);
        }
        failure = delivery.Complete(edit, failure);
        return true;
    }

    private bool HasSameSlots(TChild[] expected)
    {
        if (Count != expected.Length) return false;
        for (int index = 0; index < expected.Length; index++)
        {
            if (!ReferenceEquals(items[index], expected[index])) return false;
        }
        return true;
    }
}
