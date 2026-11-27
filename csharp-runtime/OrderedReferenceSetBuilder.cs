namespace Vmf.Runtime;

public sealed partial class OrderedReferenceSet<T> where T : class
{
    [System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
    public void ReplaceBuilderContents(IEnumerable<T> values)
    {
        ArgumentNullException.ThrowIfNull(values);
        T[] desired = values.ToArray();
        var wanted = new HashSet<T>(ReferenceEqualityComparer.Instance);
        foreach (T value in desired)
        {
            ArgumentNullException.ThrowIfNull(value);
            if (!wanted.Add(value)) throw new ArgumentException("Duplicate model reference.", nameof(values));
        }

        if (SameBuilderSequence(desired)) return;
        if (desired.Length == Count)
        {
            sequence.ApplyRange(0, Count, desired);
            return;
        }

        // Retained members keep their inverse links while positions and membership change.
        Exception? failure = null;
        int[] obsolete = Enumerable.Range(0, Count).Where(index => !wanted.Contains(items[index])).ToArray();
        ReciprocalEndpoint.Attempt(() => sequence.RemoveReferencePositions(obsolete), ref failure);
        var present = new HashSet<T>(items, ReferenceEqualityComparer.Instance);
        var retained = new List<T>();
        var added = new List<T>();
        var indices = new List<int>();
        for (int index = 0; index < desired.Length; index++)
        {
            T value = desired[index];
            if (present.Contains(value)) retained.Add(value);
            else
            {
                indices.Add(index);
                added.Add(value);
            }
        }
        if (!SameBuilderSequence(retained))
        {
            ReciprocalEndpoint.Attempt(() => sequence.ApplyRange(0, retained.Count, retained.ToArray()), ref failure);
        }
        ReciprocalEndpoint.Attempt(() => sequence.InsertReferencePositions(indices.ToArray(), added.ToArray()), ref failure);
        ModelObserverFailures.Rethrow(failure);
    }

    private bool SameBuilderSequence(IEnumerable<T> other) =>
        items.SequenceEqual(other, ReferenceEqualityComparer.Instance);
}
