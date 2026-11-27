namespace Vmf.Runtime;

public sealed partial class ContainmentList<TParent, TChild>
    where TParent : class
    where TChild : class?
{
    [System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
    public void ApplyKeyedBuilderContents(IEnumerable<TChild> children, bool append)
    {
        if (key is null || orderedSet)
        {
            throw new InvalidOperationException("Builder transfer requires an ordinary keyed containment.");
        }
        ApplyBuilderContents(children, append);
    }

    [System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
    public void ValidateOrdinaryBuilderContents(IEnumerable<TChild> children)
    {
        RequireOrdinaryBuilderList();
        ArgumentNullException.ThrowIfNull(children);
        RequireWritable();
        ValidateBuilderTransfers(children);
    }

    [System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
    public void ApplyOrdinaryBuilderContents(IEnumerable<TChild> children, bool append)
    {
        RequireOrdinaryBuilderList();
        ApplyBuilderContents(children, append);
    }

    [System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
    public void ValidateOrderedBuilderContents(IEnumerable<TChild> children, bool append)
    {
        RequireOrderedBuilderSet();
        ValidateSetBuilderContents(children, append);
    }

    internal void ValidateSetBuilderContents(IEnumerable<TChild> children, bool append)
    {
        ArgumentNullException.ThrowIfNull(children);
        RequireWritable();
        var seen = new HashSet<TChild>(ReferenceEqualityComparer.Instance);
        foreach (TChild child in children)
        {
            ArgumentNullException.ThrowIfNull(child);
            if (!seen.Add(child) && !append) throw new ArgumentException("Duplicate child identity.", nameof(children));
        }
        ValidateBuilderTransfers(seen);
        if (key is not null)
        {
            if (append) seen.ExceptWith(items);
            key.ValidateRange(items, append ? Count : 0, append ? 0 : Count, seen.ToArray());
        }
    }

    [System.ComponentModel.EditorBrowsable(System.ComponentModel.EditorBrowsableState.Never)]
    public void ApplyOrderedBuilderContents(IEnumerable<TChild> children, bool append)
    {
        ArgumentNullException.ThrowIfNull(children);
        TChild[] desired = children.ToArray();
        ValidateOrderedBuilderContents(desired, append);
        if (append)
        {
            AddRange(desired);
            return;
        }
        if (items.SequenceEqual(desired, ReferenceEqualityComparer.Instance)) return;
        if (desired.Length == Count)
        {
            ReplaceRange(0, Count, desired);
            return;
        }

        var wanted = new HashSet<TChild>(desired, ReferenceEqualityComparer.Instance);
        int[] obsolete = Enumerable.Range(0, Count).Where(index => !wanted.Contains(items[index])).ToArray();
        Exception? failure = null;
        if (obsolete.Length > 0)
        {
            ReciprocalEndpoint.Attempt(() => ReplaceRange(0, obsolete.Length, [], obsolete), ref failure);
        }
        var present = new HashSet<TChild>(items, ReferenceEqualityComparer.Instance);
        var retained = new List<TChild>();
        var added = new List<TChild>();
        var indices = new List<int>();
        for (int i = 0; i < desired.Length; i++)
        {
            if (present.Contains(desired[i])) retained.Add(desired[i]);
            else
            {
                indices.Add(i);
                added.Add(desired[i]);
            }
        }
        if (!items.SequenceEqual(retained, ReferenceEqualityComparer.Instance))
        {
            ReciprocalEndpoint.Attempt(() => ReplaceRange(0, retained.Count, retained.ToArray()), ref failure);
        }
        if (indices.Count > 0)
        {
            ReciprocalEndpoint.Attempt(() => ReplaceRange(0, 0, added.ToArray(), indices.ToArray()), ref failure);
        }
        ModelObserverFailures.Rethrow(failure);
    }

    internal void ApplyUnorderedBuilderContents(IEnumerable<TChild> children, bool append)
    {
        ArgumentNullException.ThrowIfNull(children);
        TChild[] desired = children.ToArray();
        ValidateSetBuilderContents(desired, append);
        if (append)
        {
            AddRange(desired);
            return;
        }

        var wanted = new HashSet<TChild>(desired, ReferenceEqualityComparer.Instance);
        TChild[] obsolete = items.Where(child => !wanted.Contains(child)).ToArray();
        Exception? failure = null;
        if (obsolete.Length > 0)
        {
            ReciprocalEndpoint.Attempt(() => ApplySetMembership(obsolete, []), ref failure);
        }
        ReciprocalEndpoint.Attempt(() => AddRange(desired), ref failure);
        ModelObserverFailures.Rethrow(failure);
    }

    private void RequireOrderedBuilderSet()
    {
        if (!orderedSet || unordered)
        {
            throw new InvalidOperationException("Builder transfer requires an ordered containment set.");
        }
    }

    private void ApplyBuilderContents(IEnumerable<TChild> children, bool append)
    {
        ArgumentNullException.ThrowIfNull(children);
        RequireWritable();
        var incoming = children.ToArray();
        key?.ValidateRange(items, append ? Count : 0, append ? 0 : Count, incoming);
        ValidateBuilderTransfers(incoming);

        if (!append) Clear();
        AddRange(incoming);
    }

    private void RequireOrdinaryBuilderList()
    {
        if (!IsOrdinaryList)
        {
            throw new InvalidOperationException("Builder transfer requires an ordinary containment list.");
        }
    }

    private void ValidateBuilderTransfers(IEnumerable<TChild> children)
    {
        var seen = new HashSet<TChild>(ReferenceEqualityComparer.Instance);
        foreach (var child in children)
        {
            if (child is null || !seen.Add(child)) continue;
            var link = getLink(child);
            link.RequireAvailable();
            link.RequireAcyclicAttachmentTo(ownerLink);
            if (link.CurrentCollection is not { } previous) continue;
            previous.RequireWritable();
            if (previous.FindChild(child) < 0) throw Inconsistent();
        }
    }
}
