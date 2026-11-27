namespace Vmf.Runtime;

/// <summary>A fixed group of edits already applied to the live model.</summary>
public sealed class ModelTransaction
{
    internal ModelTransaction(IEnumerable<ModelChange> changes)
    {
        Changes = Array.AsReadOnly(changes.ToArray());
    }

    public IReadOnlyList<ModelChange> Changes { get; }

    /// <summary>Checks the most recent edit of each affected property against the live model.</summary>
    public bool CanUndo => CanReplay(undo: true);

    /// <summary>Checks the earliest edit of each affected property against the live model.</summary>
    public bool CanRedo => CanReplay(undo: false);

    /// <summary>Undoes edits in reverse order. Rechecks and skips stale edits, returning false if any were skipped.</summary>
    /// <remarks>Unrecorded edits and observers can cause a partial undo.</remarks>
    public bool Undo() => Replay(undo: true);

    /// <summary>Reapplies edits in recording order. Rechecks and skips stale edits, returning false if any were skipped.</summary>
    public bool Redo() => Replay(undo: false);

    private bool Replay(bool undo)
    {
        if (!CanReplay(undo)) return false;
        bool completed = true;
        foreach (var change in ReplayOrder(undo))
        {
            if (undo ? !change.Undo() : !change.Redo()) completed = false;
        }
        return completed;
    }

    private bool CanReplay(bool undo)
    {
        // Only the first encountered edit of each property should match its current value.
        var seen = new Dictionary<object, HashSet<string>>(ReferenceEqualityComparer.Instance);
        foreach (var change in ReplayOrder(undo))
        {
            if (!seen.TryGetValue(change.Source, out var properties))
            {
                properties = new HashSet<string>(StringComparer.Ordinal);
                seen.Add(change.Source, properties);
            }
            if (properties.Add(change.PropertyName) && !change.IsCurrent(undo)
                && (undo || change is not ReciprocalReferenceChange reciprocal
                    || !reciprocal.IsCurrentAfterPrecedingRemoval(Changes))) return false;
        }
        return true;
    }

    private IEnumerable<ModelChange> ReplayOrder(bool undo)
    {
        if (undo)
        {
            for (int index = Changes.Count - 1; index >= 0; index--) yield return Changes[index];
        }
        else
        {
            foreach (var change in Changes) yield return change;
        }
    }
}
