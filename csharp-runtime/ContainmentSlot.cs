namespace Vmf.Runtime;

/// <summary>Owns at most one child and participates in automatic reparenting.</summary>
public sealed class ContainmentSlot<TParent, TChild> : IContainment, IContainmentCursorSlot
    where TParent : class
    where TChild : class
{
    private readonly ContainmentLink ownerLink;
    private readonly Func<TChild, ContainmentLink> getLink;
    private readonly ModelChangeSource changeSource;
    private readonly string? oppositeName;
    private TChild? child;
    private bool updating;
    private long cursorVersion;

    long IModelCursorRevision.Version => cursorVersion;

    public ContainmentSlot(TParent owner, ContainmentLink ownerLink, Func<TChild, ContainmentLink> getLink)
        : this(owner, ownerLink, getLink, default)
    {
    }

    public ContainmentSlot(TParent owner, ContainmentLink ownerLink, Func<TChild, ContainmentLink> getLink,
        ModelChangeSource changeSource, string? oppositeName = null)
    {
        ArgumentNullException.ThrowIfNull(owner);
        ArgumentNullException.ThrowIfNull(ownerLink);
        ArgumentNullException.ThrowIfNull(getLink);
        Owner = owner;
        this.ownerLink = ownerLink;
        this.getLink = getLink;
        this.changeSource = changeSource;
        this.oppositeName = oppositeName;
        ModelChanges.RegisterContainment(owner, this);
    }

    public TParent Owner { get; }

    public TChild? Value
    {
        get => child;
        set => Replace(value);
    }

    private void Replace(TChild? value)
    {
        if (ReferenceEquals(child, value)) return;
        RequireWritable();

        TChild? previousChild = child;
        ContainmentLink? outgoing = previousChild is null ? null : getLink(previousChild);
        if (outgoing is not null)
        {
            outgoing.RequireAvailable();
            if (!ReferenceEquals(outgoing.CurrentCollection, this)) throw Inconsistent();
        }

        ContainmentLink? incoming = value is null ? null : getLink(value);
        if (incoming is not null)
        {
            incoming.RequireAvailable();
            incoming.RequireAcyclicAttachmentTo(ownerLink);
            var previous = incoming.CurrentCollection;
            if (previous is not null)
            {
                previous.RequireWritable();
                if (previous.FindChild(value!) < 0) throw Inconsistent();
            }
        }

        Exception? failure = null;
        updating = true;
        outgoing?.Reserve(destination: null);
        incoming?.Reserve(this);
        try
        {
            outgoing?.Unregister(previousChild!, releaseBeforeInverse: true, ref failure);
            if (incoming is not null)
            {
                incoming.Unregister(value!, releaseBeforeInverse: false, ref failure);
                incoming.AssignOwner(value!, this, ref failure);
            }
            child = value;
            if (value is not null) cursorVersion++;
        }
        finally
        {
            outgoing?.Release();
            incoming?.Release();
            updating = false;
        }
        if (value is not null) Publish(null, value, ref failure);
        ModelObserverFailures.Rethrow(failure);
    }

    private void RequireWritable()
    {
        if (updating)
        {
            throw new ArgumentException("A containment edit for this slot is already in progress.");
        }
    }

    private void Publish(TChild? oldValue, TChild? newValue, ref Exception? failure)
    {
        var deliver = changeSource.CapturePropertyDelivery(this);
        if (deliver is null)
        {
            if (!changeSource.IsTracked)
            {
                ModelChanges.ReconcileUntrackedContainment(this, oldValue is null ? [] : [oldValue],
                    newValue is null ? [] : [newValue], useMemberIndex: false);
            }
            return;
        }

        var change = changeSource.CreateContainment(oldValue, newValue, () => Value, value => Value = (TChild?)value);
        ContainmentEdits.Notify(deliver, change, ref failure);
    }

    object IContainment.OwnerObject => Owner;

    ContainmentLink IContainment.OwnerLink => ownerLink;
    string? IContainment.OppositeName => oppositeName;
    IEnumerable<object> IContainment.Children => child is null ? [] : [child];
    int IContainment.SlotCount => 1;
    object? IContainment.ChildAt(int slot) => child;
    ContainmentLink IContainment.GetChildLink(object value) => getLink((TChild)value);

    int IContainment.FindChild(object item) => child is not null && ReferenceEquals(child, item) ? 0 : -1;

    void IContainment.RequireWritable() => RequireWritable();

    void IContainment.AttachChild(object value) => Value = (TChild)value;

    void IContainment.RemoveForTransfer(object item, ref Exception? failure)
    {
        TChild? previous = child;
        if (previous is null || !ReferenceEquals(previous, item)) throw Inconsistent();

        child = null;
        cursorVersion++;
        Publish(previous, null, ref failure);
    }

    private static InvalidOperationException Inconsistent() => new("The child and its containment are inconsistent.");
}
