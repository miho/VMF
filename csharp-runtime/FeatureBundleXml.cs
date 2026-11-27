using System.Buffers;
using System.Buffers.Binary;
using System.Globalization;
using System.Numerics;
using System.Text;
using System.Text.Json;
using System.Xml;
using System.Xml.Linq;

namespace Vmf.Runtime;

internal static class FeatureBundleXml
{
    private const int MaxDepth = SnapshotJson.MaxDocumentDepth;
    // XML uses the owner/feature element itself in place of the intermediate JSON value object.
    internal const int IntermediateJsonDepth = MaxDepth + 1;
    private const string TextEncoding = "utf16be-base64";
    private static readonly CultureInfo Invariant = CultureInfo.InvariantCulture;

    internal static string Encode(string json)
    {
        using var source = JsonDocument.Parse(json, new JsonDocumentOptions { MaxDepth = IntermediateJsonDepth });
        var bundle = source.RootElement;
        var root = new XElement("vmf-features", new XAttribute("version", bundle.GetProperty("version").GetInt32()),
            WriteEntry("owner", bundle.GetProperty("owner"), depth: 2),
            new XElement("features", bundle.GetProperty("features").EnumerateArray().Select(feature => WriteEntry("feature", feature, depth: 3))));
        return root.ToString();
    }

    private static XElement WriteEntry(string tag, JsonElement entry, int depth) =>
        new(tag, new XAttribute("type", entry.GetProperty("type").GetString()!), WriteFields(entry.GetProperty("value"), depth));

    private static IEnumerable<XElement> WriteFields(JsonElement value, int depth)
    {
        foreach (var property in value.EnumerateObject())
        {
            var element = new XElement("property");
            if (PreservesXmlText(property.Name, attribute: true))
            {
                element.Add(new XAttribute("name", property.Name));
            }
            else
            {
                element.Add(new XAttribute("name", EncodeText(property.Name)), new XAttribute("name-encoding", TextEncoding));
            }
            WriteValue(element, property.Value, depth + 1);
            yield return element;
        }
    }

    private static void WriteValue(XElement element, JsonElement value, int depth)
    {
        RequireDepth(element, depth);
        string kind = value.ValueKind switch
        {
            JsonValueKind.Object => "object",
            JsonValueKind.Array => "array",
            JsonValueKind.String => "string",
            JsonValueKind.True or JsonValueKind.False => "boolean",
            JsonValueKind.Number => NumberKind(value),
            JsonValueKind.Null => "null",
            _ => throw Invalid(element, "unsupported VMF value kind"),
        };
        element.Add(new XAttribute("kind", kind));
        switch (value.ValueKind)
        {
            case JsonValueKind.Object:
                element.Add(WriteFields(value, depth));
                break;
            case JsonValueKind.Array:
                foreach (var item in value.EnumerateArray())
                {
                    var member = new XElement("item");
                    WriteValue(member, item, depth + 1);
                    element.Add(member);
                }
                break;
            case JsonValueKind.String:
                string text = JsonString(value);
                if (!PreservesXmlText(text, attribute: false))
                {
                    element.Add(new XAttribute("encoding", TextEncoding));
                    text = EncodeText(text);
                }
                element.Value = text;
                break;
            case JsonValueKind.Number:
            case JsonValueKind.True:
            case JsonValueKind.False:
                element.Value = value.GetRawText();
                break;
        }
    }

    private static string NumberKind(JsonElement value)
    {
        if (value.GetRawText().AsSpan().IndexOfAny('.', 'e', 'E') >= 0) return "double";
        if (value.TryGetInt32(out _)) return "int";
        return value.TryGetInt64(out _) ? "long" : "big-integer";
    }

    private static string JsonString(JsonElement value)
    {
        var raw = value.GetRawText().AsSpan();
        if (raw.Length == 8 && raw.Slice(1, 2).SequenceEqual("\\u")
            && ushort.TryParse(raw.Slice(3, 4), NumberStyles.AllowHexSpecifier, Invariant, out ushort unit) && char.IsSurrogate((char)unit))
        {
            return ((char)unit).ToString();
        }
        return SnapshotJson.ReadText(value, "feature XML string");
    }

    internal static string Decode(string xml)
    {
        ArgumentNullException.ThrowIfNull(xml);
        using var input = new StringReader(xml);
        using var reader = XmlReader.Create(input, new XmlReaderSettings { DtdProcessing = DtdProcessing.Prohibit, XmlResolver = null });
        var root = XDocument.Load(reader, LoadOptions.PreserveWhitespace).Root!;
        RequireName(root, "vmf-features");
        RequireAttributes(root, "version");
        if (!int.TryParse(Attribute(root, "version"), NumberStyles.AllowLeadingSign, Invariant, out int version))
        {
            throw Invalid(root, "expected an integer version");
        }
        var children = Children(root).ToArray();
        if (children.Length != 2) throw Invalid(root, "expected <owner> and <features>");
        RequireName(children[0], "owner");
        RequireName(children[1], "features");
        RequireAttributes(children[1]);

        var buffer = new ArrayBufferWriter<byte>();
        using (var json = new Utf8JsonWriter(buffer, new JsonWriterOptions { MaxDepth = IntermediateJsonDepth }))
        {
            json.WriteStartObject();
            json.WriteNumber("version", version);
            json.WritePropertyName("owner");
            ReadEntry(children[0], json, depth: 2);
            json.WriteStartArray("features");
            foreach (var feature in Children(children[1]))
            {
                RequireName(feature, "feature");
                ReadEntry(feature, json, depth: 3);
            }
            json.WriteEndArray();
            json.WriteEndObject();
        }
        return Encoding.UTF8.GetString(buffer.WrittenSpan);
    }

    private static void ReadEntry(XElement entry, Utf8JsonWriter json, int depth)
    {
        RequireAttributes(entry, "type");
        json.WriteStartObject();
        json.WriteString("type", Attribute(entry, "type"));
        json.WritePropertyName("value");
        ReadFields(entry, json, depth);
        json.WriteEndObject();
    }

    private static void ReadFields(XElement element, Utf8JsonWriter json, int depth)
    {
        json.WriteStartObject();
        var names = new HashSet<string>(StringComparer.Ordinal);
        foreach (var property in Children(element))
        {
            RequireName(property, "property");
            RequireAttributes(property, "name", "name-encoding", "kind", "encoding");
            string name = ReadText(property, Attribute(property, "name"), "name-encoding");
            if (!ValidUnicode(name)) throw Invalid(property, "property name must be valid Unicode");
            if (!names.Add(name)) throw Invalid(property, $"duplicate property '{name}'");
            json.WritePropertyName(name);
            ReadValue(property, json, depth + 1);
        }
        json.WriteEndObject();
    }

    private static void ReadValue(XElement element, Utf8JsonWriter json, int depth)
    {
        RequireDepth(element, depth);
        string kind = Attribute(element, "kind");
        if (kind != "string" && element.Attribute("encoding") is not null) throw Invalid(element, "only strings permit text encoding");
        switch (kind)
        {
            case "object":
                ReadFields(element, json, depth);
                break;
            case "array":
                json.WriteStartArray();
                foreach (var item in Children(element))
                {
                    RequireName(item, "item");
                    RequireAttributes(item, "kind", "encoding");
                    ReadValue(item, json, depth + 1);
                }
                json.WriteEndArray();
                break;
            case "string":
                string text = ReadText(element, Leaf(element), "encoding");
                if (text.Length == 1 && char.IsSurrogate(text[0])) json.WriteRawValue($"\"\\u{(int)text[0]:X4}\"");
                else if (ValidUnicode(text)) json.WriteStringValue(text);
                else throw Invalid(element, "invalid Unicode in an XML string");
                break;
            default:
                ReadScalar(element, json, kind, Leaf(element).Trim());
                break;
        }
    }

    private static void RequireDepth(XElement element, int depth)
    {
        if (depth > MaxDepth) throw Invalid(element, "maximum nesting depth exceeded");
    }

    private static void ReadScalar(XElement element, Utf8JsonWriter json, string kind, string value)
    {
        const NumberStyles Integer = NumberStyles.AllowLeadingSign;
        switch (kind)
        {
            case "null" when value.Length == 0:
                json.WriteNullValue();
                return;
            case "boolean" when value is "true" or "false":
                json.WriteBooleanValue(value == "true");
                return;
            case "int" when int.TryParse(value, Integer, Invariant, out int number):
                json.WriteNumberValue(number);
                return;
            case "long" when long.TryParse(value, Integer, Invariant, out long number):
                json.WriteNumberValue(number);
                return;
            case "big-integer" when BigInteger.TryParse(value, Integer, Invariant, out var number):
                json.WriteRawValue(number.ToString(Invariant));
                return;
            case "float" or "double" when value is "NaN" or "Infinity" or "-Infinity":
                json.WriteStringValue(value);
                return;
            case "float" when float.TryParse(value, NumberStyles.Float, Invariant, out float number) && float.IsFinite(number):
                if (number == 0 && float.IsNegative(number)) json.WriteRawValue("-0.0");
                else json.WriteNumberValue(number);
                return;
            case "double" when double.TryParse(value, NumberStyles.Float, Invariant, out double number) && double.IsFinite(number):
                if (number == 0 && double.IsNegative(number)) json.WriteRawValue("-0.0");
                else json.WriteNumberValue(number);
                return;
            case "big-decimal":
                try
                {
                    using var parsed = JsonDocument.Parse(value);
                    if (parsed.RootElement.ValueKind != JsonValueKind.Number) break;
                    json.WriteRawValue(value);
                    return;
                }
                catch (JsonException) { break; }
            case "binary":
                try
                {
                    json.WriteBase64StringValue(Convert.FromBase64String(value));
                    return;
                }
                catch (FormatException) { break; }
        }
        throw Invalid(element, $"invalid or unsupported '{kind}' value");
    }

    private static string EncodeText(string text)
    {
        var bytes = new byte[checked(text.Length * 2)];
        for (int index = 0; index < text.Length; index++) BinaryPrimitives.WriteUInt16BigEndian(bytes.AsSpan(index * 2), text[index]);
        return Convert.ToBase64String(bytes);
    }

    private static string ReadText(XElement element, string value, string encodingAttribute)
    {
        if (element.Attribute(encodingAttribute) is not { } encoding) return value;
        if (encoding.Value != TextEncoding) throw Invalid(element, "unknown text encoding");
        byte[] bytes;
        try { bytes = Convert.FromBase64String(value); }
        catch (FormatException exception) { throw new XmlException("Invalid VMF feature XML base64 text.", exception); }
        if (bytes.Length % 2 != 0) throw Invalid(element, "invalid encoded text length");
        return string.Create(bytes.Length / 2, bytes, static (text, source) =>
        {
            for (int index = 0; index < text.Length; index++) text[index] = (char)BinaryPrimitives.ReadUInt16BigEndian(source.AsSpan(index * 2));
        });
    }

    private static bool ValidUnicode(string value)
    {
        var text = value.AsSpan();
        while (!text.IsEmpty)
        {
            if (Rune.DecodeFromUtf16(text, out _, out int count) != OperationStatus.Done) return false;
            text = text[count..];
        }
        return true;
    }

    private static bool PreservesXmlText(string value, bool attribute)
    {
        if (!ValidUnicode(value)) return false;
        foreach (var rune in value.EnumerateRunes())
        {
            if (rune.Value is '\t' or '\n')
            {
                if (attribute) return false;
            }
            else if (rune.Value < 0x20 || rune.Value is 0xfffe or 0xffff) return false;
        }
        return true;
    }

    private static IEnumerable<XElement> Children(XElement element)
    {
        foreach (var node in element.Nodes())
        {
            if (node is XElement child) yield return child;
            else if (node is not XComment && (node is not XText text || text is XCData || !string.IsNullOrWhiteSpace(text.Value)))
            {
                throw Invalid(element, "expected only child elements");
            }
        }
    }

    private static string Leaf(XElement element)
    {
        if (element.Nodes().Any(node => node is not XText and not XComment)) throw Invalid(element, "expected only text");
        return element.Value;
    }

    private static string Attribute(XElement element, string name) =>
        element.Attribute(name)?.Value ?? throw Invalid(element, $"missing attribute '{name}'");

    private static void RequireName(XElement element, string name)
    {
        if (element.Name != name) throw Invalid(element, $"expected <{name}>");
    }

    private static void RequireAttributes(XElement element, params string[] allowedNames)
    {
        foreach (var attribute in element.Attributes())
        {
            if (attribute.IsNamespaceDeclaration || attribute.Name.Namespace != XNamespace.None || !allowedNames.Contains(attribute.Name.LocalName))
            {
                throw Invalid(element, $"unexpected attribute '{attribute.Name}'");
            }
        }
    }

    private static XmlException Invalid(XElement element, string details) =>
        new($"Invalid VMF feature XML at <{element.Name}>, {details}.");
}
