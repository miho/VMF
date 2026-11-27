using System.Diagnostics;

namespace Vmf.Runtime;

/// <summary>A process-local version of a live instance. Its timestamp is that of the most recently delivered change.</summary>
/// <remarks>The timestamp is in stopwatch ticks. Versions are independent across instances and restored snapshots.</remarks>
public sealed record ModelVersion(long Timestamp, long VersionNumber);

public sealed partial class ModelChanges
{
    private ModelVersion modelVersion = new(Stopwatch.GetTimestamp(), 0);
    private IDisposable? versionSubscription;

    public ModelVersion ModelVersion => modelVersion;
    public bool IsModelVersioningEnabled => versionSubscription is not null;

    /// <summary>Counts recursive live notifications without requiring history recording.</summary>
    public void EnableModelVersioning()
    {
        versionSubscription?.Dispose();
        versionSubscription = AddRecursiveListener(change => modelVersion = new(change.Timestamp, modelVersion.VersionNumber + 1));
    }

    /// <summary>Stops incrementing versions. The current version survives and can resume later.</summary>
    public void DisableModelVersioning()
    {
        if (IsRecording) throw new InvalidOperationException("Stop recording before disabling model versioning.");
        versionSubscription?.Dispose();
        versionSubscription = null;
    }

    internal static ModelChanges CreateReadOnlyStream(ModelReflectionRegistry.ModelBinding binding)
    {
        Volatile.Write(ref hasStreams, 1);
        return new ModelChanges(binding.Identity, followContainment: false, readOnlyBinding: binding);
    }
}
