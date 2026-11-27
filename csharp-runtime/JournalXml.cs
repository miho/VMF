using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Xml;
using System.Xml.Linq;

namespace Vmf.Runtime;

/// <summary>Reads and writes the VMF journal XML representation with the same records as JSON.</summary>
public static class JournalXml
{
    private const string Format = "vmf-journal";
    private const int MaximumDepth = 32;
    private static readonly UTF8Encoding StrictUtf8 = new(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);
    private static readonly Dictionary<string, string> ArrayMembers = new(StringComparer.Ordinal)
    {
        ["transactions"] = "transaction",
        ["operations"] = "operation",
        ["indices"] = "index",
        ["before"] = "value",
        ["after"] = "value",
        ["created"] = "creation",
        ["values"] = "value",
        ["beforeEntries"] = "entry",
        ["afterEntries"] = "entry",
        ["entries"] = "entry",
        ["mapProperties"] = "mapProperty",
        ["typedMapProperties"] = "mapProperty",
        ["setProperties"] = "setProperty",
    };

    public static string Write(ChangeJournal journal)
    {
        using var document = JsonDocument.Parse(JournalJson.Write(journal));
        var root = WriteElement(Format, document.RootElement);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + root.ToString();
    }

    public static ChangeJournal Read(string xml)
    {
        ArgumentNullException.ThrowIfNull(xml);
        using var input = new StringReader(xml);
        using var reader = XmlReader.Create(input, new XmlReaderSettings { DtdProcessing = DtdProcessing.Prohibit, XmlResolver = null });
        try
        {
            var root = XDocument.Load(reader, LoadOptions.PreserveWhitespace).Root!;
            if (root.Name != Format)
            {
                throw Invalid(root, "expected a <vmf-journal> root");
            }
            var envelope = ReadFields(root, 1);
            envelope.Add("format", Format);
            return JournalJson.Read(envelope.ToJsonString());
        }
        catch (XmlException error)
        {
            throw new JsonException("Invalid VMF journal XML.", error);
        }
    }

    private static XElement WriteElement(string name, JsonElement value)
    {
        var element = new XElement(name);
        switch (value.ValueKind)
        {
            case JsonValueKind.Object:
                foreach (var field in value.EnumerateObject())
                {
                    if (name == "properties")
                    {
                        var property = new XElement("property");
                        var propertyName = new XElement("name");
                        WriteText(propertyName, field.Name);
                        property.Add(propertyName);
                        property.Add(WriteElement("values", field.Value));
                        element.Add(property);
                    }
                    else if (name != Format || field.Name != "format")
                    {
                        element.Add(WriteElement(field.Name, field.Value));
                    }
                }
                break;
            case JsonValueKind.Array:
                foreach (var item in value.EnumerateArray())
                {
                    element.Add(WriteElement(ArrayMembers[name], item));
                }
                break;
            case JsonValueKind.Null:
                element.SetAttributeValue("null", "true");
                break;
            case JsonValueKind.Number:
                element.Value = value.GetRawText();
                break;
            default:
                WriteText(element, value.GetString()!);
                break;
        }
        return element;
    }

    private static void WriteText(XElement element, string text)
    {
        if (text.EnumerateRunes().Any(value => value.Value is not ('\t' or '\n' or >= 0x20 and <= 0xd7ff
                or >= 0xe000 and <= 0xfffd or >= 0x10000)))
        {
            element.SetAttributeValue("encoding", "base64");
            element.Value = Convert.ToBase64String(StrictUtf8.GetBytes(text));
        }
        else
        {
            element.Value = text;
        }
    }

    private static JsonNode? ReadElement(XElement element, int depth)
    {
        if (depth > MaximumDepth || element.Name.Namespace != XNamespace.None)
        {
            throw Invalid(element, "unexpected namespace or excessive depth");
        }

        string name = element.Name.LocalName;
        if (ArrayMembers.TryGetValue(name, out string? member))
        {
            var values = new JsonArray();
            foreach (var child in Children(element))
            {
                if (child.Name != member) throw Invalid(child, $"expected <{member}>");
                values.Add(ReadElement(child, depth + 1));
            }
            return values;
        }

        return name switch
        {
            "model" or "base" or "transaction" or "operation" or "creation" or "value" or "entry" or "mapProperty"
                or "typedKey" or "setProperty" => ReadFields(element, depth),
            "properties" => ReadProperties(element, depth),
            "version" or "sequence" or "index" => ReadNumber(element),
            "id" or "root" or "checkpoint" or "kind" or "target" or "property" or "type" or "name" or "text" or "key"
                or "codec" => ReadText(element),
            _ => throw Invalid(element, "unknown field")
        };
    }

    private static JsonObject ReadFields(XElement element, int depth)
    {
        var result = new JsonObject();
        foreach (var child in Children(element))
        {
            string name = child.Name.LocalName;
            if (result.ContainsKey(name)) throw Invalid(child, "duplicate field");
            result.Add(name, ReadElement(child, depth + 1));
        }
        return result;
    }

    private static JsonObject ReadProperties(XElement element, int depth)
    {
        var result = new JsonObject();
        foreach (var child in Children(element))
        {
            if (child.Name != "property") throw Invalid(child, "expected <property>");
            var property = ReadFields(child, depth + 1);
            if (property.Count != 2 || property["name"]?.GetValue<string>() is not { } name
                || property["values"] is not JsonArray values || result.ContainsKey(name))
            {
                throw Invalid(child, "expected a unique name and a values list");
            }
            result.Add(name, values.DeepClone());
        }
        return result;
    }

    private static JsonNode ReadNumber(XElement element)
    {
        if (element.HasAttributes) throw Invalid(element, "unexpected attributes");
        string text = LeafText(element).Trim();
        ReadOnlySpan<char> digits = text.StartsWith('-') ? text.AsSpan(1) : text.AsSpan();
        if (digits.IsEmpty || digits.Length > 1 && digits[0] == '0'
            || digits.IndexOfAnyExceptInRange('0', '9') != -1
            || !long.TryParse(text, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out long value))
        {
            throw Invalid(element, "expected a decimal 64-bit integer");
        }
        return JsonValue.Create(value);
    }

    private static JsonNode? ReadText(XElement element)
    {
        string text = LeafText(element);
        if (!element.HasAttributes) return JsonValue.Create(text);
        if (element.Attributes().Skip(1).Any()) throw Invalid(element, "unexpected attributes");
        if (element.Name == "text" && (string?)element.Attribute("null") == "true" && string.IsNullOrWhiteSpace(text))
        {
            return null;
        }
        if ((string?)element.Attribute("encoding") != "base64" || text.Any(char.IsWhiteSpace))
        {
            throw Invalid(element, "expected UTF-8 base64 text");
        }

        try
        {
            if (!text.Contains('=') && text.Length % 4 is 2 or 3)
            {
                text = text.PadRight(text.Length + 4 - text.Length % 4, '=');
            }
            return JsonValue.Create(StrictUtf8.GetString(Convert.FromBase64String(text)));
        }
        catch (Exception error) when (error is FormatException or DecoderFallbackException)
        {
            throw new JsonException($"Invalid VMF journal XML at <{element.Name.LocalName}>.", error);
        }
    }

    private static IEnumerable<XElement> Children(XElement element)
    {
        if (element.HasAttributes) throw Invalid(element, "unexpected attributes");
        foreach (var node in element.Nodes())
        {
            if (node is XElement child)
            {
                yield return child;
            }
            else if (node is not XComment
                && !(node is XText text && text is not XCData && string.IsNullOrWhiteSpace(text.Value)))
            {
                throw Invalid(element, "expected only child elements");
            }
        }
    }

    private static string LeafText(XElement element)
    {
        var text = new StringBuilder();
        foreach (var node in element.Nodes())
        {
            if (node is XText part) text.Append(part.Value);
            else if (node is not XComment) throw Invalid(element, "expected only text");
        }
        return text.ToString();
    }

    private static JsonException Invalid(XElement element, string reason) =>
        new($"Invalid VMF journal XML at <{element.Name.LocalName}>. {reason}.");
}
