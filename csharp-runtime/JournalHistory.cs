namespace Vmf.Runtime;

/// <summary>Navigates the registered checkpoints and journal segments of one model timeline.</summary>
public sealed class JournalHistory<T> where T : class
{
    private readonly JournalBinding binding;
    private readonly SnapshotRegistry registry;
    private readonly object gate = new();
    private readonly SortedList<long, Segment> segments = new();

    public JournalHistory(JournalBinding binding, SnapshotRegistry registry)
    {
        this.binding = binding ?? throw new ArgumentNullException(nameof(binding));
        this.registry = registry ?? throw new ArgumentNullException(nameof(registry));
    }

    /// <summary>
    /// Registers an independent checkpoint at the journal's base sequence.
    /// The caller supplies its verified identifier and segments from the same timeline.
    /// </summary>
    public void AddSegment(string checkpointId, T checkpoint, ChangeJournal journal)
    {
        ArgumentNullException.ThrowIfNull(checkpoint);
        ArgumentNullException.ThrowIfNull(journal);
        lock (gate)
        {
            if (segments.ContainsKey(journal.BaseSequence))
            {
                throw new ArgumentException($"History already has a checkpoint at sequence {journal.BaseSequence}.", nameof(journal));
            }
            if (segments.Count != 0 && segments.Values[0].Journal.Root != journal.Root)
            {
                throw new ArgumentException("History segment has a different model root.", nameof(journal));
            }

            var independent = JournalReplayer.Replay(checkpoint, binding, checkpointId, Prefix(journal, 0), registry);
            segments.Add(journal.BaseSequence, new Segment(independent, journal));
        }
    }

    /// <summary>
    /// Reconstructs a fresh model from the latest checkpoint whose journal covers the sequence.
    /// An empty journal covers its base sequence only. Replay validates the requested prefix.
    /// </summary>
    public T Seek(long sequence)
    {
        lock (gate)
        {
            int first = 0;
            int last = segments.Count;
            while (first < last)
            {
                int midpoint = first + (last - first) / 2;
                if (segments.Keys[midpoint] <= sequence) first = midpoint + 1;
                else last = midpoint;
            }

            for (int index = first - 1; index >= 0; index--)
            {
                var segment = segments.Values[index];
                var journal = segment.Journal;
                long count = sequence - journal.BaseSequence;
                if (count <= journal.Transactions.Count)
                {
                    return JournalReplayer.Replay(segment.Checkpoint, binding, journal.Checkpoint, Prefix(journal, (int)count), registry);
                }
            }
            throw new ArgumentOutOfRangeException(nameof(sequence), sequence, "No history segment covers the sequence.");
        }
    }

    private static ChangeJournal Prefix(ChangeJournal journal, int count) => count == journal.Transactions.Count
        ? journal
        : new ChangeJournal(journal.Model, journal.ModelVersion, journal.Root, journal.Checkpoint,
            journal.BaseSequence, journal.Transactions.Take(count));

    private sealed record Segment(T Checkpoint, ChangeJournal Journal);
}
