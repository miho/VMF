using System.Text.Json;

namespace Vmf.Runtime;

/// <summary>Selects the native JSON snapshot representation described by a Draft-07 schema.</summary>
public enum SnapshotSchemaProfile
{
    Plain,
    TypedValues,
    References,
}

/// <summary>Supplies schema metadata that cannot be inferred from a native snapshot accessor.</summary>
public sealed class SnapshotSchemaProperty
{
    private readonly Lazy<JsonElement>? defaultValue;
    private readonly Lazy<JsonElement>? annotations;
    private readonly Lazy<JsonElement>? assertions;

    public SnapshotSchemaProperty(int lowerBound = 0, int? upperBound = null, string? keyPropertyName = null,
        bool restoresOpposite = false, bool? requiresPresence = null, string? defaultJson = null,
        string? annotationsJson = null, string? assertionsJson = null)
        : this(false, lowerBound, upperBound, keyPropertyName, restoresOpposite, requiresPresence,
            defaultJson, annotationsJson, assertionsJson)
    {
    }

    public SnapshotSchemaProperty(bool executableDefault, int lowerBound = 0, int? upperBound = null,
        string? keyPropertyName = null, bool restoresOpposite = false, bool? requiresPresence = null,
        string? defaultJson = null, string? annotationsJson = null, string? assertionsJson = null)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(lowerBound);
        if (upperBound is int upper && (upper < -1 || (upper >= 0 && upper < lowerBound)))
        {
            throw new ArgumentOutOfRangeException(nameof(upperBound));
        }

        LowerBound = lowerBound;
        UpperBound = upperBound;
        KeyPropertyName = keyPropertyName;
        RestoresOpposite = restoresOpposite;
        RequiresPresence = requiresPresence;
        HasExecutableDefault = executableDefault;
        defaultValue = ParseOnDemand(defaultJson, nameof(defaultJson), requireObject: false);
        annotations = ParseOnDemand(annotationsJson, nameof(annotationsJson), requireObject: true);
        assertions = ParseOnDemand(assertionsJson, nameof(assertionsJson), requireObject: true);
    }

    internal static SnapshotSchemaProperty DefaultMetadata { get; } = new();
    internal int LowerBound { get; }
    internal int? UpperBound { get; }
    internal string? KeyPropertyName { get; }
    internal bool RestoresOpposite { get; }
    internal bool? RequiresPresence { get; }
    internal bool HasExecutableDefault { get; }
    internal JsonElement? Default => defaultValue?.Value;
    internal JsonElement? Annotations => annotations?.Value;
    internal JsonElement? Assertions => assertions?.Value;

    private static Lazy<JsonElement>? ParseOnDemand(string? json, string parameter, bool requireObject) =>
        json is null ? null : new Lazy<JsonElement>(() => Parse(json, parameter, requireObject));

    private static JsonElement Parse(string json, string parameter, bool requireObject)
    {
        using var document = JsonDocument.Parse(json);
        if (requireObject && document.RootElement.ValueKind != JsonValueKind.Object)
        {
            throw new ArgumentException("Expected a JSON object containing Draft-07 keywords.", parameter);
        }
        return document.RootElement.Clone();
    }
}
