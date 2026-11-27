using System.Buffers;
using System.Text;
using System.Text.Json;
using System.Xml;
using System.Xml.Linq;

namespace Vmf.Runtime;

/// <summary>Reads and writes the Jackson XML representation of registered VMF snapshots.</summary>
/// <remarks>Uses the model's registered field vocabulary, including scalar map entries and unordered set members.</remarks>
public static partial class SnapshotXml
{
    private const int MaxElementDepth = 1000;

    private enum Profile { References, TypedValues, Plain }

    /// <summary>Writes a reference-aware model snapshot.</summary>
    public static string Write(object root, SnapshotRegistry registry)
    {
        ArgumentNullException.ThrowIfNull(root);
        return Write(root, root.GetType(), registry, Profile.References);
    }

    /// <summary>Writes a reference-aware snapshot for the declared root contract.</summary>
    public static string Write(object root, Type rootType, SnapshotRegistry registry) =>
        Write(root, rootType, registry, Profile.References);

    /// <summary>Writes a reference-aware snapshot under the registered alias for its XML root.</summary>
    public static string Write(object root, SnapshotRegistry registry, string rootAlias)
    {
        ArgumentNullException.ThrowIfNull(root);
        ArgumentException.ThrowIfNullOrWhiteSpace(rootAlias);
        return Write(root, root.GetType(), registry, Profile.References, rootAlias);
    }

    /// <summary>Writes polymorphic VMF values with type discriminators and without graph IDs.</summary>
    public static string WriteWithoutReferences(object root, SnapshotRegistry registry)
    {
        ArgumentNullException.ThrowIfNull(root);
        return Write(root, root.GetType(), registry, Profile.TypedValues);
    }

    /// <summary>Writes a typed value snapshot for the declared root contract.</summary>
    public static string WriteWithoutReferences(object root, Type rootType, SnapshotRegistry registry) =>
        Write(root, rootType, registry, Profile.TypedValues);

    /// <summary>Writes a snapshot without graph IDs under the registered alias for its XML root.</summary>
    public static string WriteWithoutReferences(object root, SnapshotRegistry registry, string rootAlias)
    {
        ArgumentNullException.ThrowIfNull(root);
        ArgumentException.ThrowIfNullOrWhiteSpace(rootAlias);
        return Write(root, root.GetType(), registry, Profile.TypedValues, rootAlias);
    }

    /// <summary>Writes plain XML with no VMF reference metadata.</summary>
    public static string WritePlain<T>(T root, SnapshotRegistry registry) where T : class =>
        Write(root, typeof(T), registry, Profile.Plain);

    /// <summary>Writes plain XML when the expected root type is available at runtime.</summary>
    public static string WritePlain(object root, Type rootType, SnapshotRegistry registry) =>
        Write(root, rootType, registry, Profile.Plain);

    /// <summary>Writes plain XML under the registered alias for its root.</summary>
    public static string WritePlain<T>(T root, SnapshotRegistry registry, string rootAlias) where T : class =>
        WritePlain(root, typeof(T), registry, rootAlias);

    /// <summary>Writes plain XML under the registered alias when the expected root type is supplied at runtime.</summary>
    public static string WritePlain(object root, Type rootType, SnapshotRegistry registry, string rootAlias)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(rootAlias);
        return Write(root, rootType, registry, Profile.Plain, rootAlias);
    }

    /// <summary>Reads a reference-aware model snapshot.</summary>
    public static T Read<T>(string xml, SnapshotRegistry registry) where T : class =>
        Read<T>(xml, registry, Profile.References);

    /// <summary>Reads polymorphic VMF values and resolves registered type discriminators without graph IDs.</summary>
    public static T ReadWithoutReferences<T>(string xml, SnapshotRegistry registry) where T : class =>
        Read<T>(xml, registry, Profile.TypedValues);

    /// <summary>Reads plain XML with the supplied root type and its declared field types.</summary>
    public static T ReadPlain<T>(string xml, SnapshotRegistry registry) where T : class =>
        Read<T>(xml, registry, Profile.Plain);

    private static string Write(object root, Type rootType, SnapshotRegistry registry, Profile profile, string? rootAlias = null)
    {
        ArgumentNullException.ThrowIfNull(root);
        ArgumentNullException.ThrowIfNull(rootType);
        ArgumentNullException.ThrowIfNull(registry);
        try
        {
            SnapshotType type = registry.TypeForClass(root.GetType(), "/");
            if (rootAlias is not null && (rootAlias == type.WireName || !registry.MatchesName(type, rootAlias)))
            {
                throw new ArgumentException($"'{rootAlias}' is not a registered XML root alias for '{type.WireName}'.", nameof(rootAlias));
            }

            string json = profile switch
            {
                Profile.Plain => SnapshotJson.WritePlain(root, rootType, registry),
                Profile.TypedValues => SnapshotJson.WriteWithoutReferences(root, rootType, registry),
                _ => SnapshotJson.Write(root, rootType, registry)
            };
            using var data = JsonDocument.Parse(json, new JsonDocumentOptions { MaxDepth = 1000 });
            var text = new StringBuilder();
            var projection = new Projection(registry, profile);
            using (var xml = XmlWriter.Create(text, new XmlWriterSettings
            {
                OmitXmlDeclaration = true,
                NewLineHandling = NewLineHandling.Entitize,
            }))
            {
                projection.WriteRoot(xml, XmlConvert.VerifyNCName(rootAlias ?? type.WireName), data.RootElement, type);
            }
            string document = text.ToString();
            if (projection.HasCustomXmlContent) CheckOutputDepth(document);
            return document;
        }
        catch (JsonException error)
        {
            throw new XmlException("Invalid VMF model for an XML snapshot: " + error.Message, error);
        }
    }

    private static void CheckOutputDepth(string xml)
    {
        // Each element needs at least one '<'.
        if (xml.AsSpan().Count('<') <= MaxElementDepth) return;

        using var input = new StringReader(xml);
        using var reader = CreateReader(input);
        while (reader.Read())
        {
            // XmlReader reports zero-based depth, while the Jackson XML reader counts the root.
            if (reader.NodeType == XmlNodeType.Element && reader.Depth >= MaxElementDepth)
            {
                throw OutputDepthExceeded();
            }
        }
    }

    private static XmlException OutputDepthExceeded() =>
        new($"VMF XML snapshot exceeds the maximum element depth of {MaxElementDepth}.");

    private static XmlReader CreateReader(TextReader input) => XmlReader.Create(input, new XmlReaderSettings
    {
        DtdProcessing = DtdProcessing.Prohibit,
        XmlResolver = null,
    });

    private static T Read<T>(string xml, SnapshotRegistry registry, Profile profile) where T : class
    {
        ArgumentNullException.ThrowIfNull(xml);
        ArgumentNullException.ThrowIfNull(registry);
        try
        {
            using var input = new StringReader(xml);
            using var reader = CreateReader(input);
            var root = XDocument.Load(reader, LoadOptions.PreserveWhitespace | LoadOptions.SetLineInfo).Root!;
            var buffer = new ArrayBufferWriter<byte>();
            using (var json = new Utf8JsonWriter(buffer))
            {
                new Projection(registry, profile).ReadObject(root, json, typeof(T), depth: 0);
            }
            string data = Encoding.UTF8.GetString(buffer.WrittenSpan);
            return profile switch
            {
                Profile.Plain => SnapshotJson.ReadPlain<T>(data, registry),
                Profile.TypedValues => SnapshotJson.ReadWithoutReferences<T>(data, registry),
                _ => SnapshotJson.Read<T>(data, registry)
            };
        }
        catch (JsonException error)
        {
            throw new XmlException("Invalid VMF XML snapshot: " + error.Message, error);
        }
    }
}
