using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Xml;
using System.Xml.Linq;

namespace Vmf.Runtime;

public static partial class SnapshotXml
{
    private sealed partial class Projection
    {
        private static readonly UTF8Encoding StrictUtf8 = new(false, true);

        private void WriteMap(XmlWriter xml, JsonElement data, SnapshotProperty property)
        {
            Type keyType = property.KeyType!;
            if (keyType == typeof(string))
            {
                foreach (var entry in data.EnumerateObject())
                {
                    WriteMapEntry(xml, entry.Name, entry.Value, property.ValueType);
                }
            }
            else
            {
                foreach (var entry in data.EnumerateArray())
                {
                    WriteMapEntry(xml, ScalarText(entry.GetProperty("key"), keyType),
                        entry.GetProperty("value"), property.ValueType);
                }
            }
        }

        private void WriteMapEntry(XmlWriter xml, string key, JsonElement value, Type valueType)
        {
            StartElement(xml, "entry");
            WriteText(xml, "key", key);
            if (value.ValueKind == JsonValueKind.Null)
            {
                WriteElementString(xml, "null", "true");
            }
            else
            {
                WriteText(xml, "value", ScalarText(value, valueType));
            }
            EndElement(xml);
        }

        private void WriteSet(XmlWriter xml, JsonElement data, SnapshotProperty property)
        {
            foreach (var member in data.EnumerateArray())
            {
                StartElement(xml, "member");
                WriteText(xml, "value", ScalarText(member, property.ValueType));
                EndElement(xml);
            }
        }

        private static void ReadMap(XElement node, Utf8JsonWriter json, SnapshotProperty property)
        {
            RequireCollectionWrapper(node);
            Type keyType = property.KeyType!;
            bool stringKey = keyType == typeof(string);
            if (stringKey) json.WriteStartObject();
            else json.WriteStartArray();

            foreach (var entry in node.Elements())
            {
                RequireElement(entry);
                if (entry.Name != "entry") throw Invalid(entry, "Expected managed scalar map entry");
                var fields = ReadFields(entry);
                if (fields.Count != 2) throw Invalid(entry, "Expected one map key and one map value");
                string key = ReadText(entry, fields, "key");
                if (stringKey)
                {
                    RequireProperUtf16(entry, key);
                    json.WritePropertyName(key);
                }
                else
                {
                    json.WriteStartObject();
                    json.WritePropertyName("key");
                    WriteKeyOrSetMember(entry, key, json, keyType);
                    json.WritePropertyName("value");
                }

                if (fields.TryGetValue("null", out var marker))
                {
                    if (RequiredText(marker) != "true") throw Invalid(marker, "Expected a true map null marker");
                    json.WriteNullValue();
                }
                else
                {
                    string value = ReadText(entry, fields, "value");
                    WriteJsonScalar(entry, value, json, property.ValueType);
                }
                if (!stringKey) json.WriteEndObject();
            }

            if (stringKey) json.WriteEndObject();
            else json.WriteEndArray();
        }

        private static void ReadSet(XElement node, Utf8JsonWriter json, SnapshotProperty property)
        {
            RequireCollectionWrapper(node);
            json.WriteStartArray();
            foreach (var member in node.Elements())
            {
                RequireElement(member);
                if (member.Name != "member") throw Invalid(member, "Expected unordered scalar set member");
                var fields = ReadFields(member);
                if (fields.Count != 1) throw Invalid(member, "Expected one set member value");
                WriteKeyOrSetMember(member, ReadText(member, fields, "value"), json, property.ValueType);
            }
            json.WriteEndArray();
        }

        private static string ScalarText(JsonElement value, Type declaredType)
        {
            Type type = Nullable.GetUnderlyingType(declaredType) ?? declaredType;
            if (value.ValueKind != JsonValueKind.String)
            {
                string literal = value.GetRawText();
                // Keep the usual Java spelling of positive zero. Other round-trip decimals may
                // differ across JVMs and .NET and are parsed at the registered floating precision.
                return literal == "0" && (type == typeof(float) || type == typeof(double)) ? "0.0" : literal;
            }

            // The snapshot JSON codec can carry a lone UTF-16 code unit as a char.
            var raw = value.GetRawText().AsSpan();
            if (type == typeof(char) && raw.Length == 8 && raw.Slice(1, 2).SequenceEqual("\\u")
                && ushort.TryParse(raw.Slice(3, 4), NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture, out ushort unit))
            {
                return ((char)unit).ToString();
            }
            return value.GetString()!;
        }

        private static void WriteKeyOrSetMember(XElement node, string text, Utf8JsonWriter json, Type type)
        {
            Type scalarType = Nullable.GetUnderlyingType(type) ?? type;
            if (scalarType != typeof(string) && scalarType != typeof(char))
            {
                bool floating = scalarType == typeof(float) || scalarType == typeof(double);
                if (text.AsSpan().Trim().Length != text.Length || (!floating && text == "-0"))
                {
                    throw Invalid(node, "Invalid managed scalar key or set member text");
                }
            }
            WriteJsonScalar(node, text, json, type);
        }

        private void WriteText(XmlWriter xml, string name, string text)
        {
            if (PreservesAsXmlText(text))
            {
                WriteElementString(xml, name, text);
                return;
            }

            // Match the Java codec's UTF-16 big-endian base64 form without replacing isolated surrogates.
            var bytes = new byte[checked(text.Length * 2)];
            for (int index = 0; index < text.Length; index++)
            {
                bytes[index * 2] = (byte)(text[index] >> 8);
                bytes[index * 2 + 1] = (byte)text[index];
            }
            WriteElementString(xml, name + "64", Convert.ToBase64String(bytes));
        }

        private static bool PreservesAsXmlText(string value)
        {
            if (value.Contains('\r')) return false;
            try
            {
                XmlConvert.VerifyXmlChars(value);
                return true;
            }
            catch (XmlException)
            {
                return false;
            }
        }

        private static string ReadText(XElement node, Dictionary<string, XElement> fields, string name)
        {
            bool hasPlain = fields.TryGetValue(name, out var plainText);
            bool hasBase64 = fields.TryGetValue(name + "64", out var base64);
            if (hasPlain == hasBase64) throw Invalid(node, $"Expected one '{name}' or '{name}64' field");
            var element = hasPlain ? plainText! : base64!;
            RequireElement(element);
            if (element.HasElements) throw Invalid(element, "Expected a text field");
            if (hasPlain) return element.Value;

            byte[] bytes;
            try
            {
                bytes = Convert.FromBase64String(element.Value);
            }
            catch (FormatException)
            {
                throw Invalid(element, "Invalid base64 text");
            }
            if ((bytes.Length & 1) != 0) throw Invalid(element, "Invalid UTF-16 byte length");
            var units = new char[bytes.Length / 2];
            for (int index = 0; index < units.Length; index++)
            {
                units[index] = (char)((bytes[index * 2] << 8) | bytes[index * 2 + 1]);
            }
            return new string(units);
        }

        private static void RequireProperUtf16(XElement element, string text)
        {
            try
            {
                StrictUtf8.GetByteCount(text);
            }
            catch (EncoderFallbackException)
            {
                throw Invalid(element, "Invalid Unicode in string value");
            }
        }
    }
}
