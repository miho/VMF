using System.Text.Json;
using System.Xml;
using System.Xml.Linq;

namespace Vmf.Runtime;

public static partial class SnapshotXml
{
    private sealed partial class Projection
    {
        private void WriteUntypedContent(XmlWriter xml, JsonElement value)
        {
            switch (value.ValueKind)
            {
                case JsonValueKind.Object:
                    foreach (var field in value.EnumerateObject()) WriteUntypedMember(xml, field.Name, field.Value);
                    break;
                case JsonValueKind.Array:
                    foreach (var member in value.EnumerateArray()) WriteUntypedMember(xml, "item", member);
                    break;
                case JsonValueKind.String:
                    xml.WriteString(value.GetString());
                    break;
                case JsonValueKind.Null:
                    break;
                default:
                    xml.WriteString(value.GetRawText());
                    break;
            }
        }

        private void WriteUntypedMember(XmlWriter xml, string name, JsonElement value)
        {
            XmlConvert.VerifyNCName(name);
            if (value.ValueKind == JsonValueKind.Array)
            {
                foreach (var member in value.EnumerateArray()) WriteUntypedMember(xml, name, member);
                return;
            }

            StartElement(xml, name);
            WriteUntypedContent(xml, value);
            EndValueElement(xml, value);
        }

        private static void ReadUntypedContent(XElement node, Utf8JsonWriter json, int depth)
        {
            if (depth >= 1000) throw Invalid(node, "Untyped Object value exceeds maximum nesting depth");
            bool nil = RequireElement(node, allowNil: true);
            if (nil)
            {
                if (node.HasElements || !string.IsNullOrWhiteSpace(node.Value)) throw Invalid(node, "An xsi:nil element must be empty");
                json.WriteNullValue();
                return;
            }
            if (!node.HasElements)
            {
                json.WriteStringValue(node.Value);
                return;
            }

            RequireStructured(node);
            json.WriteStartObject();
            foreach (var group in node.Elements().GroupBy(child => child.Name))
            {
                var entries = group.ToArray();
                json.WritePropertyName(group.Key.LocalName);
                if (entries.Length == 1)
                {
                    ReadUntypedContent(entries[0], json, depth + 1);
                }
                else
                {
                    json.WriteStartArray();
                    foreach (var entry in entries) ReadUntypedContent(entry, json, depth + 1);
                    json.WriteEndArray();
                }
            }
            json.WriteEndObject();
        }
    }
}
