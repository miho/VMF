using System.Globalization;
using System.Text;
using System.Text.Json;

namespace Vmf.Runtime;

/// <summary>Reads and writes VMF graph snapshots and schema-known plain JSON for registered models.</summary>
public static partial class SnapshotJson
{
    internal const string TypeField = "@vmf-type";
    internal const string IdField = "@vmf-id";
    internal const int MaxDocumentDepth = 1000;
    private static readonly UTF8Encoding StrictUtf8 = new(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);

    private enum Profile { References, TypedValues, Plain }

    public static string Write(object root, SnapshotRegistry registry)
    {
        ArgumentNullException.ThrowIfNull(root);
        return Write(root, root.GetType(), registry, Profile.References);
    }

    /// <summary>Writes a reference-aware snapshot for the declared root contract.</summary>
    public static string Write(object root, Type rootType, SnapshotRegistry registry) =>
        Write(root, rootType, registry, Profile.References);

    /// <summary>Writes VMF JSON by value with type discriminators and without reference IDs.</summary>
    /// <remarks>Mutable singular references and ordinary reference sequences containing a mutable member are omitted.
    /// Mutable identity reference sets and mutable model-reference keys require a reference-aware snapshot.</remarks>
    public static string WriteWithoutReferences(object root, SnapshotRegistry registry)
    {
        ArgumentNullException.ThrowIfNull(root);
        return Write(root, root.GetType(), registry, Profile.TypedValues);
    }

    /// <summary>Writes a typed value snapshot for the declared root contract.</summary>
    public static string WriteWithoutReferences(object root, Type rootType, SnapshotRegistry registry) =>
        Write(root, rootType, registry, Profile.TypedValues);

    /// <summary>Writes plain JSON using the exact expected model types and no graph metadata.</summary>
    public static string WritePlain<T>(T root, SnapshotRegistry registry) where T : class =>
        Write(root, typeof(T), registry, Profile.Plain);

    /// <summary>Writes plain JSON when the expected root type is available at runtime.</summary>
    public static string WritePlain(object root, Type rootType, SnapshotRegistry registry)
    {
        ArgumentNullException.ThrowIfNull(rootType);
        return Write(root, rootType, registry, Profile.Plain);
    }

    private static string Write(object root, Type expectedType, SnapshotRegistry registry, Profile profile)
    {
        ArgumentNullException.ThrowIfNull(root);
        ArgumentNullException.ThrowIfNull(registry);

        ArgumentNullException.ThrowIfNull(expectedType);
        if (!expectedType.IsInstanceOfType(root))
            throw new JsonException($"Root type '{root.GetType()}' is not assignable to '{expectedType}'.");

        return new SnapshotWriter(registry, profile).Write(root, expectedType);
    }

    public static T Read<T>(string json, SnapshotRegistry registry) where T : class =>
        Read<T>(json, registry, Profile.References);

    /// <summary>Reads VMF JSON by value and resolves type discriminators through the registry.</summary>
    /// <remarks>Embedded mutable singular and ordinary sequence values are restored as separate objects.</remarks>
    public static T ReadWithoutReferences<T>(string json, SnapshotRegistry registry) where T : class =>
        Read<T>(json, registry, Profile.TypedValues);

    /// <summary>Reads plain JSON using the supplied root type and the model's declared field types.</summary>
    public static T ReadPlain<T>(string json, SnapshotRegistry registry) where T : class =>
        Read<T>(json, registry, Profile.Plain);

    internal static T ReadCheckpoint<T>(string json, SnapshotRegistry registry, Func<object, bool> isLiveInstance) where T : class
    {
        ArgumentNullException.ThrowIfNull(isLiveInstance);
        return Read<T>(json, registry, Profile.References, isLiveInstance);
    }

    private static T Read<T>(string json, SnapshotRegistry registry, Profile profile,
        Func<object, bool>? isLiveInstance = null) where T : class
    {
        ArgumentNullException.ThrowIfNull(json);
        ArgumentNullException.ThrowIfNull(registry);

        using var document = JsonDocument.Parse(json, new JsonDocumentOptions { MaxDepth = MaxDocumentDepth });
        CheckFields(document.RootElement, "$", plain: false);
        return (T)new SnapshotReader(registry, profile, isLiveInstance).Read(document.RootElement, typeof(T));
    }

    internal static object ReadReferenceElement(JsonElement value, Type expectedType, SnapshotRegistry registry) =>
        new SnapshotReader(registry, Profile.References).Read(value, expectedType);

    internal static void CheckFields(JsonElement node, string path, bool plain)
    {
        if (node.ValueKind == JsonValueKind.Object)
        {
            var names = new HashSet<string>(StringComparer.Ordinal);
            foreach (var field in node.EnumerateObject())
            {
                string fieldPath = ChildPath(path, field.Name);
                if (!names.Add(field.Name))
                {
                    throw Invalid(fieldPath, $"Duplicate field '{field.Name}'");
                }

                if (plain && (field.Name is TypeField or IdField))
                {
                    throw Invalid(fieldPath, "Plain JSON cannot contain VMF graph metadata");
                }

                CheckFields(field.Value, fieldPath, plain);
            }
        }
        else if (node.ValueKind == JsonValueKind.Array)
        {
            int index = 0;
            foreach (var entry in node.EnumerateArray())
            {
                CheckFields(entry, IndexPath(path, index++), plain);
            }
        }
    }

    private static string ReadId(JsonElement value, string path)
    {
        if (value.ValueKind == JsonValueKind.String && ReadText(value, path) is { Length: > 0 } text)
        {
            return text;
        }

        if (value.ValueKind == JsonValueKind.Number)
        {
            string number = value.GetRawText();
            if (number.AsSpan().IndexOfAny('.', 'e', 'E') == -1)
            {
                return number == "-0" ? "0" : number;
            }
        }

        throw Invalid(path, "Expected a non-empty string or integer VMF ID");
    }

    private static void RequireType(object value, Type expectedType, string path)
    {
        if (!(Nullable.GetUnderlyingType(expectedType) ?? expectedType).IsInstanceOfType(value))
        {
            throw Invalid(path, $"Expected '{expectedType}', got '{value.GetType()}'");
        }
    }

    internal static string ReadText(JsonElement value, string path)
    {
        try
        {
            return value.GetString()!;
        }
        catch (InvalidOperationException exception)
        {
            throw new JsonException($"Invalid Unicode in JSON string at {path}.", exception);
        }
    }

    private static void CheckText(string value, string path)
    {
        try
        {
            StrictUtf8.GetByteCount(value);
        }
        catch (EncoderFallbackException exception)
        {
            throw new JsonException($"Invalid Unicode in model string at {path}.", exception);
        }
    }

    private static string FormatId(int id) => id.ToString(CultureInfo.InvariantCulture);

    private static string ChildPath(string path, string field) =>
        path + "/" + field.Replace("~", "~0", StringComparison.Ordinal).Replace("/", "~1", StringComparison.Ordinal);

    private static string IndexPath(string path, int index) => path + "/" + FormatId(index);

    private static JsonException Invalid(string path, string message) => new($"{message} at {path}.");

    private static void CheckScalarCapacity(SnapshotProperty property, int count, string path)
    {
        if (property.UpperBound >= 0 && count > property.UpperBound)
        {
            throw Invalid(path, $"Scalar collection exceeds upper bound {property.UpperBound}");
        }
    }

    private sealed record ScalarMapEntry(object Key, object? Value);
}
