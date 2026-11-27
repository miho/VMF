using System.Text.Json;
using System.Xml;
using System.Xml.Linq;

namespace Vmf.Runtime;

/// <summary>Supplies the XML element content representation of an external snapshot value.</summary>
/// <remarks>The surrounding element belongs to the model. Its children and text can encode the external value.
/// A codec can also opt into attributes on a non-null external field or list item.</remarks>
public sealed class ExternalSnapshotXmlCodec
{
    /// <summary>Projects a JSON string to the corresponding XML text content.</summary>
    public static ExternalSnapshotXmlCodec Text { get; } = new(
        static (xml, value) => xml.WriteString(value.GetString()),
        static element => !element.HasElements ? JsonSerializer.SerializeToElement(element.Value)
            : throw new XmlException("A textual external snapshot value cannot contain child elements."));

    public ExternalSnapshotXmlCodec(Action<XmlWriter, JsonElement> writeContent, Func<XElement, JsonElement> readContent)
        : this(writeContent, readContent, allowElementAttributes: false)
    {
    }

    public ExternalSnapshotXmlCodec(Action<XmlWriter, JsonElement> writeContent, Func<XElement, JsonElement> readContent,
        bool allowElementAttributes)
    {
        ArgumentNullException.ThrowIfNull(writeContent);
        ArgumentNullException.ThrowIfNull(readContent);
        WriteContent = writeContent;
        ReadContent = readContent;
        AllowsElementAttributes = allowElementAttributes;
    }

    internal Action<XmlWriter, JsonElement> WriteContent { get; }
    internal Func<XElement, JsonElement> ReadContent { get; }
    internal bool AllowsElementAttributes { get; }
}

/// <summary>An application-scoped registry of host value representations for VMF snapshots.</summary>
public sealed class ExternalSnapshotCodecs
{
    public static ExternalSnapshotCodecs Empty { get; } = new(new(StringComparer.Ordinal));

    private readonly Dictionary<string, Codec> codecs;

    private ExternalSnapshotCodecs(Dictionary<string, Codec> codecs) => this.codecs = codecs;

    /// <summary>Registers an exact immutable host type and its whole-value JSON, optional XML, and schema representation.</summary>
    /// <remarks>Encode and decode should be deterministic. The declared external name is configuration, not wire metadata.</remarks>
    public ExternalSnapshotCodecs WithImmutable<T>(string externalType, Func<T, JsonElement> encode, Func<JsonElement, T> decode,
        JsonElement schema, ExternalSnapshotXmlCodec? xml = null) where T : notnull =>
        WithValue(externalType, encode, decode, schema, xml);

    /// <summary>Registers the snapshot representation of an exact host type, including mutable host values.</summary>
    /// <remarks>Encode captures the value at write time. Decode constructs a host value from the document.</remarks>
    public ExternalSnapshotCodecs WithValue<T>(string externalType, Func<T, JsonElement> encode, Func<JsonElement, T> decode,
        JsonElement schema, ExternalSnapshotXmlCodec? xml = null) where T : notnull
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(externalType);
        ArgumentNullException.ThrowIfNull(encode);
        ArgumentNullException.ThrowIfNull(decode);
        var type = typeof(T);
        RequireHostType(type);
        if (schema.ValueKind != JsonValueKind.Object) throw new ArgumentException("Expected a JSON Schema object.", nameof(schema));
        SnapshotJson.CheckFields(schema, "$schema", plain: false);

        var additions = new Dictionary<string, Codec>(codecs, StringComparer.Ordinal);
        if (!additions.TryAdd(externalType, new(externalType, type, value => encode((T)value), value => decode(value), schema.Clone(), xml)))
        {
            throw new ArgumentException($"External snapshot type '{externalType}' is already registered.", nameof(externalType));
        }
        return new(additions);
    }

    /// <summary>Registers an enum whose declared names are its JSON, YAML, and XML values.</summary>
    public ExternalSnapshotCodecs WithEnum<T>(string externalType) where T : struct, Enum
    {
        var names = Enum.GetNames<T>();
        var values = names.ToDictionary(name => name, name => Enum.Parse<T>(name), StringComparer.Ordinal);
        if (values.Count == 0 || values.Values.Distinct().Count() != values.Count)
        {
            throw new ArgumentException($"Enum '{typeof(T)}' must declare distinct, named snapshot values.", nameof(T));
        }
        return WithImmutable<T>(externalType,
            static value => JsonSerializer.SerializeToElement(Enum.GetName(value)
                ?? throw new ArgumentException($"'{value}' is not a declared {typeof(T)} value.")),
            value => value.ValueKind == JsonValueKind.String && values.TryGetValue(value.GetString()!, out var known)
                ? known : throw new JsonException($"Expected a declared {typeof(T)} name."),
            JsonSerializer.SerializeToElement(new { type = "string", @enum = names }), ExternalSnapshotXmlCodec.Text);
    }

    internal static void RequireHostType(Type type)
    {
        if (type.IsAbstract || type.IsArray || type.IsByRef || type.IsPointer || type.IsByRefLike || type.ContainsGenericParameters
            || Nullable.GetUnderlyingType(type) is not null || type == typeof(object)
            || typeof(IModelGraphAccess).IsAssignableFrom(type) || typeof(IReadOnlyModelView).IsAssignableFrom(type))
        {
            throw new ArgumentException($"'{type}' is not a concrete external snapshot value type.");
        }
    }

    internal static ExternalSnapshotCodecs Combine(IEnumerable<ExternalSnapshotCodecs> registries)
    {
        var result = new Dictionary<string, Codec>(StringComparer.Ordinal);
        foreach (var registry in registries)
        {
            foreach (var (name, codec) in registry.codecs)
            {
                if (!result.TryAdd(name, codec) && !ReferenceEquals(result[name], codec))
                {
                    throw new ArgumentException($"Conflicting external snapshot codecs for '{name}'.", nameof(registries));
                }
            }
        }
        return result.Count == 0 ? Empty : new(result);
    }

    internal Codec Require(SnapshotProperty property, string path)
    {
        string name = property.ExternalType!;
        if (!codecs.TryGetValue(name, out var codec)) throw new JsonException($"No external snapshot codec for '{name}' at {path}.");
        Type expected = Nullable.GetUnderlyingType(property.ValueType) ?? property.ValueType;
        if (codec.HostType != expected) throw new JsonException($"External snapshot codec '{name}' expects '{codec.HostType}', not '{expected}' at {path}.");
        return codec;
    }

    internal sealed class Codec(string name, Type hostType, Func<object, JsonElement> encode, Func<JsonElement, object?> decode,
        JsonElement schema, ExternalSnapshotXmlCodec? xml)
    {
        internal Type HostType { get; } = hostType;
        internal JsonElement Schema { get; } = schema;

        internal ExternalSnapshotXmlCodec RequireXml(string path) => xml
            ?? throw new XmlException($"No XML projection for external snapshot type '{name}' at {path}.");

        internal JsonElement Encode(object value, string path)
        {
            if (value.GetType() != HostType) throw new JsonException($"External snapshot type '{name}' requires '{HostType}' at {path}.");
            try
            {
                return Detach(encode(value), path);
            }
            catch (Exception error) when (error is ArgumentException or InvalidOperationException or FormatException or JsonException or OverflowException)
            {
                throw new JsonException($"Could not encode external snapshot type '{name}' at {path}.", error);
            }
        }

        internal object Decode(JsonElement value, string path)
        {
            try
            {
                object? result = decode(value.Clone());
                if (result is null || result.GetType() != HostType)
                {
                    throw new JsonException($"The external codec must return an instance of '{HostType}'.");
                }
                return result;
            }
            catch (Exception error) when (error is ArgumentException or InvalidOperationException or FormatException or JsonException or OverflowException)
            {
                throw new JsonException($"Could not decode external snapshot type '{name}' at {path}.", error);
            }
        }

        internal static JsonElement Detach(JsonElement value, string path)
        {
            if (value.ValueKind is JsonValueKind.Undefined or JsonValueKind.Null)
            {
                throw new JsonException($"An external snapshot codec must produce a defined non-null JSON value at {path}.");
            }
            SnapshotJson.CheckFields(value, path, plain: false);
            return value.Clone();
        }
    }
}
