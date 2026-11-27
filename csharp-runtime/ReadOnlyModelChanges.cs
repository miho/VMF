using System.Runtime.CompilerServices;

namespace Vmf.Runtime;

/// <summary>A registered model view with validation and non-editable change observation.</summary>
public interface IReadOnlyModelView : IModelValidationView
{
    ReadOnlyModelChanges VmfChanges { get; }
}

/// <summary>A live, independently controlled change stream for a read-only model view.</summary>
/// <remarks>Like Java VMF's generated view, it observes only the wrapped object even for recursive subscriptions.</remarks>
public sealed class ReadOnlyModelChanges
{
    private readonly ModelChanges stream;
    private readonly ModelReflectionRegistry registry;
    private readonly ConditionalWeakTable<ModelChange, ReadOnlyModelChange> changes = new();
    private readonly ConditionalWeakTable<ModelTransaction, ReadOnlyModelTransaction> transactions = new();

    internal ReadOnlyModelChanges(ModelReflectionRegistry.ModelBinding binding, ModelReflectionRegistry registry)
    {
        this.registry = registry;
        stream = ModelChanges.CreateReadOnlyStream(binding);
        stream.History.AddListener(change => Mirror(History, change, Project));
        stream.Transactions.AddListener(change => Mirror(Transactions, change, ProjectTransaction));
    }

    public ModelHistory<ReadOnlyModelChange> History { get; } = new();
    public ModelHistory<ReadOnlyModelTransaction> Transactions { get; } = new();
    public ModelVersion ModelVersion => stream.ModelVersion;
    public bool IsModelVersioningEnabled => stream.IsModelVersioningEnabled;
    public bool IsRecording => stream.IsRecording;

    public void Start() => stream.Start();
    public void Stop() => stream.Stop();
    public void Clear() => stream.Clear();
    public void StartTransaction() => stream.StartTransaction();
    public void EnableModelVersioning() => stream.EnableModelVersioning();
    public void DisableModelVersioning() => stream.DisableModelVersioning();

    public ReadOnlyModelTransaction? PublishTransaction() => stream.PublishTransaction() is { } transaction
        ? ProjectTransaction(transaction) : null;

    public IDisposable AddListener(Action<ReadOnlyModelChange> listener, ModelChangeScope scope = ModelChangeScope.Recursive)
    {
        ArgumentNullException.ThrowIfNull(listener);
        return stream.AddListener(change => listener(Project(change)), scope);
    }

    public IDisposable AddLocalListener(Action<ReadOnlyModelChange> listener) => AddListener(listener, ModelChangeScope.Local);
    public IDisposable AddRecursiveListener(Action<ReadOnlyModelChange> listener) => AddListener(listener, ModelChangeScope.Recursive);

    public IDisposable AddPropertyListener(string propertyName, Action<ReadOnlyModelChange> listener,
        ModelChangeScope scope = ModelChangeScope.Local)
    {
        ArgumentNullException.ThrowIfNull(listener);
        return stream.AddPropertyListener(propertyName, change => listener(Project(change)), scope);
    }

    /// <summary>Observes direct property assignments with projected source and reference values.</summary>
    public IDisposable AddDirectPropertyListener(Action<ModelPropertyNotification> listener) =>
        stream.AddDirectPropertyListener(listener);

    /// <summary>Observes one named property directly with projected source and reference values.</summary>
    public IDisposable AddDirectPropertyListener(string propertyName, Action<ModelPropertyNotification> listener) =>
        stream.AddDirectPropertyListener(propertyName, listener);

    public IDisposable AddFilteredListener(Predicate<ReadOnlyModelChange> filter, Action<ReadOnlyModelChange> listener,
        ModelChangeScope scope = ModelChangeScope.Local)
    {
        ArgumentNullException.ThrowIfNull(filter);
        ArgumentNullException.ThrowIfNull(listener);
        return AddListener(change =>
        {
            if (filter(change)) listener(change);
        }, scope);
    }

    private ReadOnlyModelChange Project(ModelChange change) => changes.GetValue(change, source => new(source, registry));

    private ReadOnlyModelTransaction ProjectTransaction(ModelTransaction transaction) => transactions.GetValue(transaction,
        source => new ReadOnlyModelTransaction(source.Changes.Select(Project)));

    private static void Mirror<TSource, TView>(ModelHistory<TView> items, ModelHistoryChange<TSource> change,
        Func<TSource, TView> project)
    {
        if (change.Removed.Count > 0) ModelObserverFailures.Rethrow(items.RemoveAll()?.Notify(null));
        foreach (var added in change.Added) ModelObserverFailures.Rethrow(items.Append(project(added))?.Notify(null));
    }
}

/// <summary>An observed edit with projected model values.</summary>
public sealed class ReadOnlyModelChange
{
    private readonly ModelChange change;

    internal ReadOnlyModelChange(ModelChange change, ModelReflectionRegistry registry)
    {
        this.change = change;
        Source = registry.ProjectReadOnly(change.Source);
        PropertyName = change.PropertyName;
        Kind = change.Kind;
        Timestamp = change.Timestamp;
        IsModelReference = change.IsModelReference;
        IsContainment = change.IsContainment;
        IsInverse = change.IsInverse;
        OldValue = ProjectValue(change.OldValue);
        NewValue = ProjectValue(change.NewValue);
        if (change is ModelCollectionChange collection)
        {
            Removed = ProjectEntries(collection.Removed);
            Added = ProjectEntries(collection.Added);
        }

        object? ProjectValue(object? value) => registry.ProjectNotificationValue(value, IsModelReference);

        IReadOnlyList<ModelCollectionEntry> ProjectEntries(IReadOnlyList<ModelCollectionEntry> entries) => !IsModelReference
            ? entries : Array.AsReadOnly(entries.Select(entry => entry with { Value = ProjectValue(entry.Value) }).ToArray());
    }

    public object Source { get; }
    public string PropertyName { get; }
    public ModelChangeKind Kind { get; }
    public long Timestamp { get; }
    public bool IsModelReference { get; }
    public bool IsContainment { get; }
    public bool IsInverse { get; }
    public object? OldValue { get; }
    public object? NewValue { get; }
    public IReadOnlyList<ModelCollectionEntry> Removed { get; } = Array.Empty<ModelCollectionEntry>();
    public IReadOnlyList<ModelCollectionEntry> Added { get; } = Array.Empty<ModelCollectionEntry>();

    /// <summary>Applies an edit with no model payload to an explicitly supplied writable target.</summary>
    /// <remarks>Ordinary list edits containing only null model slots can also be applied.</remarks>
    public void Apply(object target)
    {
        ArgumentNullException.ThrowIfNull(target);
        bool isReadOnlyView = target is IReadOnlyModelView and not IModelReadOnlyProjection;
        if (isReadOnlyView || LegacyImmutableModel.IsImmutable(target))
        {
            throw new ArgumentException("A projected change requires a writable target.", nameof(target));
        }
        bool cannotDispatch = change is ModelCollectionChange collection
            ? !collection.CanApplyToExplicitTarget
            : IsModelReference;
        if (IsContainment || cannotDispatch || IsModelValue(OldValue) || IsModelValue(NewValue)
            || Removed.Any(CarriesModel) || Added.Any(CarriesModel))
        {
            throw new ArgumentException("This projected change cannot be applied to a writable target.", nameof(target));
        }

        change.Apply(target);
    }

    private static bool CarriesModel(ModelCollectionEntry entry) =>
        IsModelValue(entry.Value) || IsModelValue(entry.Key);

    private static bool IsModelValue(object? value) =>
        value is IReadOnlyModelView or IModelReadOnlyProjection || LegacyImmutableModel.IsImmutable(value);
}

/// <summary>A published group of projected edits with no replay operations.</summary>
public sealed class ReadOnlyModelTransaction
{
    internal ReadOnlyModelTransaction(IEnumerable<ReadOnlyModelChange> changes) => Changes = Array.AsReadOnly(changes.ToArray());

    public IReadOnlyList<ReadOnlyModelChange> Changes { get; }
}

public sealed partial class ModelReflectionRegistry
{
    private readonly ConditionalWeakTable<object, ReadOnlyModelChanges> readOnlyChanges = new();

    /// <summary>Returns the independent stream attached to an exact read-only model view or immutable value.</summary>
    public ReadOnlyModelChanges ChangesForReadOnlyView(object model)
    {
        var binding = Bind(model);
        if (!binding.IsReadOnly) throw new ArgumentException("Expected a read-only model view or immutable value.", nameof(model));
        return readOnlyChanges.GetValue(model, _ => new ReadOnlyModelChanges(binding, this));
    }
}
