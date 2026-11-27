namespace Vmf.Runtime;

internal sealed partial class ModelSequence<T>
{
    internal void ReplaceAll(Func<T, T> transform, bool allowNull)
    {
        if (Items.Count == 0) return;
        var replacement = ModelListReplacement.Stage(Items, transform);
        if (!allowNull)
        {
            foreach (T value in replacement) ArgumentNullException.ThrowIfNull(value);
        }
        ApplyRange(0, replacement.Length, replacement);
    }

    internal void Sort(Comparison<T> comparison)
    {
        ArgumentNullException.ThrowIfNull(comparison);
        if (Items.Count < 2) return;

        var original = Items.ToArray();
        long version = CursorVersion;
        var sorted = original.OrderBy(item => item, Comparer<T>.Create(comparison)).ToArray();
        if (CursorVersion != version)
            throw new InvalidOperationException("A comparison changed the list being sorted.");

        if (unique || upperBound >= 0)
        {
            // Java checked scalar lists compare by value and replace the whole range.
            if (!original.SequenceEqual(sorted, comparer))
                SetOrder(Enumerable.Range(0, original.Length).ToArray(), original, sorted);
            return;
        }

        int[] positions = Enumerable.Range(0, original.Length)
            .Where(index => !SameSortValue(original[index], sorted[index])).ToArray();
        if (positions.Length != 0)
        {
            SetOrder(positions, positions.Select(index => original[index]).ToArray(),
                positions.Select(index => sorted[index]).ToArray());
        }
    }

    private bool SameSortValue(T before, T after)
    {
        // JVM ordinary lists detect changed references, even for equal strings.
        if (!typeof(T).IsValueType) return ReferenceEquals(before, after);
        if (before is double d1 && after is double d2)
            return BitConverter.DoubleToInt64Bits(d1) == BitConverter.DoubleToInt64Bits(d2);
        if (before is float f1 && after is float f2)
            return BitConverter.SingleToInt32Bits(f1) == BitConverter.SingleToInt32Bits(f2);
        return comparer.Equals(before, after);
    }

    private void SetOrder(int[] positions, T[] before, T[] after)
    {
        var delivery = CaptureDelivery();
        for (int i = 0; i < positions.Length; i++) Items[positions[i]] = after[i];
        MarkCursorVersion(structural: false);
        // Sorting only permutes existing occurrences, so the opposite ends stay intact.
        if (!delivery.HasObservers) return;
        var edit = delivery.ListObservers.Count == 0 ? default : ModelListListeners<T>.Delivery.CreateEdit(positions, before, after);
        Dispatch(delivery, edit, owner => owner.Create(kind, isModelReference,
            ModelListSlots.Entries(positions, before), ModelListSlots.Entries(positions, after),
            matches: undo => MatchesOrdering(undo ? after : before),
            replay: undo =>
            {
                if (isModelReference) SetOrder(positions, undo ? after : before, undo ? before : after);
                else ApplyScalarOrder(positions, undo ? before : after);
            },
            apply: target =>
            {
                if (isModelReference) ApplyRecordedReferenceOrder();
                else if (owner.IsOwner(target)) ApplyScalarOrder(positions, after);
                else owner.ResolveTarget<ScalarList<T>>(target).ApplySort(positions, after);
            }, isOrdinaryCollection: true), null);

        bool MatchesOrdering(T[] expected) => ModelListSlots.Match(Items, positions, expected, Items.Count,
            isModelReference ? null : comparer);

        void ApplyRecordedReferenceOrder()
        {
            if (MatchesOrdering(before))
            {
                SetOrder(positions, before, after);
                return;
            }
            if (MatchesOrdering(after)) return;
            int start = positions[0];
            var replacement = Range(start, positions[^1] - start + 1);
            for (int i = 0; i < positions.Length; i++) replacement[positions[i] - start] = after[i];
            ApplyRange(start, replacement.Length, replacement);
        }
    }

    internal void ApplyScalarOrder(int[] positions, T[] values)
    {
        ModelListSlots.CheckBounds(Items, positions, adding: false);
        int start = positions[0];
        if (positions[^1] - start + 1 == positions.Length)
        {
            ApplyRange(start, values.Length, values);
            return;
        }

        // Native Change.apply and undo restore sparse scalar slots in index order.
        for (int i = 0; i < positions.Length; i++) Set(positions[i], values[i]);
    }

    internal int RemoveWhere(Predicate<T> predicate)
    {
        int[] positions = ModelListMembership.SelectSlots(Items, predicate);
        if (positions.Length != 0) RemoveSlots(positions);
        return positions.Length;
    }

    internal void RemoveSlots(int[] positions)
    {
        ModelListSlots.CheckBounds(Items, positions, adding: false);
        EditSlots(positions, positions.Select(index => Items[index]).ToArray(), []);
    }

    internal void RestoreOrderedSlots(int[] positions, T[] values)
    {
        ModelListSlots.CheckBounds(Items, positions, adding: true);
        ScalarCollectionBounds.CheckCapacity(upperBound, (long)Items.Count + values.Length);
        if (!HasUniqueResult(0, 0, values))
            throw new ArgumentException("The edit would duplicate an element in the ordered scalar list.");
        EditSlots(positions, [], values);
    }

    private void EditSlots(int[] positions, T[] removed, T[] added)
    {
        int countBefore = Items.Count;
        int countAfter = countBefore - removed.Length + added.Length;
        var delivery = CaptureDelivery();
        Reciprocal?.PrepareCollectionEdit(removed, added, default);
        ModelListSlots.Edit<T>(Items, positions, added);

        Exception? failure = null;
        ModelChange? prepared = null;
        if (Reciprocal is not null && delivery.Ordered is { HasPortableRecorder: true } captured
            && Reciprocal.HasCollectionOpposites(removed))
        {
            prepared = Create(source);
            captured.Prepare(prepared);
        }
        Reciprocal?.UpdateCollectionOpposites(removed, added, ref failure);
        if (delivery.HasObservers)
        {
            var edit = delivery.ListObservers.Count == 0 ? default : ModelListListeners<T>.Delivery.CreateEdit(positions, removed, added);
            var inverseFailure = failure;
            ReciprocalEndpoint.Attempt(() => Dispatch(delivery, edit, owner => prepared ?? Create(owner), inverseFailure, prepared is not null),
                ref failure);
        }
        ModelObserverFailures.Rethrow(failure);

        ModelChange Create(ModelChangeSource owner) => owner.Create(kind, isModelReference,
            ModelListSlots.Entries(positions, removed), ModelListSlots.Entries(positions, added),
            matches: undo => undo ? Matches(added, removed, countAfter) : Matches(removed, added, countBefore),
            replay: undo => ReplaySlots(undo ? removed : added),
            apply: target =>
            {
                if (owner.IsOwner(target)) ReplaySlots(added);
                else if (added.Length != 0)
                    owner.ResolveTarget<ScalarList<T>>(target).RestoreOrderedSlots(positions, added);
                else if (!isModelReference || !owner.TryApplyNullListRemoval(target, positions))
                    owner.ResolveTarget<IOrdinaryModelListMembership<T>>(target).RemoveSlots(positions);
            }, isOrdinaryCollection: Reciprocal is null || !unique, allowCompatibleNulls: !unique);

        bool Matches(T[] expected, T[] restoring, int expectedCount) =>
            ModelListSlots.Match(Items, positions, expected, expectedCount, isModelReference ? null : comparer)
                && (expected.Length != 0 || !unique || HasUniqueResult(0, 0, restoring));

        void ReplaySlots(T[] values)
        {
            if (values.Length == 0) RemoveSlots(positions);
            else if (unique) RestoreOrderedSlots(positions, values);
            else ModelListSlots.RestoreRuns(positions, values, (index, run) => ApplyRange(index, 0, run));
        }
    }
}
