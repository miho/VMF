using System.Buffers;
using System.Text;
using System.Text.Json;
using System.Xml;
using YamlDotNet.Core;

namespace Vmf.Runtime;

/// <summary>The transport format of a VMF document.</summary>
public enum VmfDocumentFormat
{
    Json,
    Yaml,
    Xml,
}

/// <summary>A restored owner and the features attached to that exact instance.</summary>
public sealed class FeatureBundle<T> where T : class
{
    internal FeatureBundle(T owner, FeatureAttachments attachments)
    {
        Owner = owner;
        Attachments = attachments;
    }

    public T Owner { get; }
    public FeatureAttachments Attachments { get; }
}

/// <summary>Reads and writes VMF feature bundles containing independently scoped reference-aware snapshots.</summary>
public sealed class FeatureBundleCodec
{
    private const int Version = 1;
    private const int MaxDepth = SnapshotJson.MaxDocumentDepth;
    private static readonly UTF8Encoding Utf8 = new(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);
    private readonly SnapshotRegistry registry;

    public FeatureBundleCodec(SnapshotRegistry registry)
    {
        ArgumentNullException.ThrowIfNull(registry);
        this.registry = SnapshotRegistry.CombineCanonical(registry);
    }

    public string Write(object owner, FeatureAttachments attachments, VmfDocumentFormat format = VmfDocumentFormat.Json) => format switch
    {
        VmfDocumentFormat.Json => WriteJson(owner, attachments),
        VmfDocumentFormat.Yaml => YamlWire.Encode(() => WriteJson(owner, attachments)),
        VmfDocumentFormat.Xml => FeatureBundleXml.Encode(WriteJson(owner, attachments, FeatureBundleXml.IntermediateJsonDepth)),
        _ => throw new ArgumentOutOfRangeException(nameof(format)),
    };

    public FeatureBundle<T> Read<T>(string document, VmfDocumentFormat format = VmfDocumentFormat.Json) where T : class => format switch
    {
        VmfDocumentFormat.Json => ReadJson<T>(document),
        VmfDocumentFormat.Yaml => YamlWire.Decode(document, json => ReadJson<T>(json)),
        VmfDocumentFormat.Xml => ReadJson<T>(FeatureBundleXml.Decode(document), FeatureBundleXml.IntermediateJsonDepth),
        _ => throw new ArgumentOutOfRangeException(nameof(format)),
    };

    /// <summary>Saves a complete bundle before replacing the destination with a sibling temporary file.</summary>
    public void Save(string path, object owner, FeatureAttachments attachments, VmfDocumentFormat? format = null)
    {
        string document = AsFileOperation(() => Write(owner, attachments, format ?? InferFormat(path)));
        string destination = Path.GetFullPath(path);
        string directory = Path.GetDirectoryName(destination) ?? throw new IOException("Expected a VMF feature document filename.");
        string temporary = Path.Combine(directory, ".vmf-features-" + Guid.NewGuid().ToString("N") + ".tmp");
        bool created = false;
        try
        {
            using (var stream = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                created = true;
                using var output = new StreamWriter(stream, Utf8);
                output.Write(document);
            }
            File.Move(temporary, destination, overwrite: true);
            created = false;
        }
        finally
        {
            if (created) File.Delete(temporary);
        }
    }

    /// <summary>Loads a new owner and its attached features using an explicit format or the filename extension.</summary>
    public FeatureBundle<T> Load<T>(string path, VmfDocumentFormat? format = null) where T : class
    {
        var selected = format ?? InferFormat(path);
        return AsFileOperation(() => Read<T>(File.ReadAllText(path, Utf8), selected));
    }

    private static T AsFileOperation<T>(Func<T> action)
    {
        try
        {
            return action();
        }
        catch (Exception error) when (error is JsonException or XmlException or YamlException or DecoderFallbackException)
        {
            throw new IOException(error.Message, error);
        }
    }

    private static VmfDocumentFormat InferFormat(string path)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        string name = Path.GetFileName(path);
        if (name.Length == 0) throw new IOException("Expected a VMF feature document filename.");
        int dot = name.LastIndexOf('.');
        if (dot < 0) return VmfDocumentFormat.Json;
        return name[(dot + 1)..].ToLowerInvariant() switch
        {
            "json" => VmfDocumentFormat.Json,
            "yaml" or "yml" => VmfDocumentFormat.Yaml,
            "xml" => VmfDocumentFormat.Xml,
            _ => throw new IOException($"Unsupported VMF document extension in '{name}'. Select a format explicitly."),
        };
    }

    private string WriteJson(object owner, FeatureAttachments attachments, int limit = MaxDepth)
    {
        ArgumentNullException.ThrowIfNull(owner);
        ArgumentNullException.ThrowIfNull(attachments);
        var buffer = new ArrayBufferWriter<byte>();
        using (var json = new Utf8JsonWriter(buffer, new JsonWriterOptions { Indented = true, MaxDepth = limit }))
        {
            json.WriteStartObject();
            json.WriteNumber("version", Version);
            json.WritePropertyName("owner");
            WriteEntry(json, owner, limit);
            json.WriteStartArray("features");
            foreach (var feature in attachments.All(owner)) WriteEntry(json, feature, limit);
            json.WriteEndArray();
            json.WriteEndObject();
        }
        return Utf8.GetString(buffer.WrittenSpan);
    }

    private void WriteEntry(Utf8JsonWriter json, object entry, int limit)
    {
        object value = registry.ModelIdentity(entry);
        var type = registry.TypeForClass(value.GetType(), "feature bundle entry");
        byte[] document = Utf8.GetBytes(SnapshotJson.Write(value, registry));
        json.WriteStartObject();
        json.WriteString("type", type.WireName);
        json.WritePropertyName("value");
        // A valid standalone snapshot must also fit inside the enclosing feature document.
        var reader = new Utf8JsonReader(document, new JsonReaderOptions { MaxDepth = limit - json.CurrentDepth });
        while (reader.Read()) { }
        json.WriteRawValue(document, skipInputValidation: true);
        json.WriteEndObject();
    }

    private FeatureBundle<T> ReadJson<T>(string document, int limit = MaxDepth) where T : class
    {
        ArgumentNullException.ThrowIfNull(document);
        using var parsed = JsonDocument.Parse(document, new JsonDocumentOptions { MaxDepth = limit });
        var root = parsed.RootElement;
        if (root.ValueKind != JsonValueKind.Object) throw new JsonException("Expected a VMF feature document.");
        SnapshotJson.CheckFields(root, "$", plain: false);
        if (!root.TryGetProperty("version", out var version) || version.ValueKind != JsonValueKind.Number
            || !version.TryGetInt32(out int number) || number != Version)
        {
            throw new JsonException("Unsupported or missing VMF feature document version.");
        }
        if (!root.TryGetProperty("owner", out var owner)) throw new JsonException("Expected an 'owner' entry.");
        if (!root.TryGetProperty("features", out var features) || features.ValueKind != JsonValueKind.Array)
        {
            throw new JsonException("Expected a 'features' array.");
        }

        var restored = (T)ReadEntry(owner, typeof(T), "$/owner");
        var attachments = new FeatureAttachments(registry);
        int index = 0;
        foreach (var feature in features.EnumerateArray())
        {
            var value = ReadEntry(feature, typeof(object), "$/features/" + index++);
            try
            {
                attachments.Attach(restored, value);
            }
            catch (ArgumentException error)
            {
                throw new JsonException(error.Message, error);
            }
        }
        return new(restored, attachments);
    }

    private object ReadEntry(JsonElement entry, Type expectedType, string path)
    {
        if (entry.ValueKind != JsonValueKind.Object || !entry.TryGetProperty("type", out var type)
            || type.ValueKind != JsonValueKind.String || !entry.TryGetProperty("value", out var value)
            || value.ValueKind != JsonValueKind.Object)
        {
            throw new JsonException($"Expected a VMF object with a 'type' and 'value' at {path}.");
        }
        string name = SnapshotJson.ReadText(type, path + "/type");
        if (string.IsNullOrWhiteSpace(name)) throw new JsonException($"Expected a non-empty VMF type at {path}.");
        var descriptor = registry.TypeForName(name, expectedType, path);
        if (value.TryGetProperty(SnapshotJson.TypeField, out var metadata)
            && (metadata.ValueKind != JsonValueKind.String
                || !string.Equals(SnapshotJson.ReadText(metadata, path + "/value/" + SnapshotJson.TypeField), name, StringComparison.Ordinal)))
        {
            throw new JsonException($"Payload type disagrees with registered VMF type '{name}' at {path}.");
        }
        return SnapshotJson.ReadReferenceElement(value, descriptor.RuntimeType, registry);
    }
}
