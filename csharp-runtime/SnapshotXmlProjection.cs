using System.Text.Json;
using System.Xml;
using System.Xml.Linq;

namespace Vmf.Runtime;

public static partial class SnapshotXml
{
    private sealed partial class Projection(SnapshotRegistry registry, Profile profile)
    {
        private const string XmlType = "vmf-type";
        private const string XmlId = "vmf-id";
        private const string Xsi = "http://www.w3.org/2001/XMLSchema-instance";
        private static readonly XName Nil = XName.Get("nil", Xsi);
        private readonly Dictionary<SnapshotType, Dictionary<string, SnapshotProperty>> fieldMaps = new();

        private int outputDepth;

        internal bool HasCustomXmlContent { get; private set; }

        internal void WriteRoot(XmlWriter xml, string name, JsonElement data, SnapshotType type)
        {
            StartElement(xml, name);
            WriteObject(xml, data, type);
            EndElement(xml);
        }

        private void StartElement(XmlWriter xml, string name)
        {
            if (outputDepth == MaxElementDepth) throw OutputDepthExceeded();
            xml.WriteStartElement(name);
            outputDepth++;
        }

        private void EndElement(XmlWriter xml, bool full = false)
        {
            if (full) xml.WriteFullEndElement();
            else xml.WriteEndElement();
            outputDepth--;
        }

        private void WriteElementString(XmlWriter xml, string name, string? value)
        {
            if (outputDepth == MaxElementDepth) throw OutputDepthExceeded();
            xml.WriteElementString(name, value);
        }

        private void WriteObject(XmlWriter xml, JsonElement data, SnapshotType type)
        {
            var properties = Properties(type);
            foreach (var field in data.EnumerateObject())
            {
                if (field.Name is SnapshotJson.TypeField or SnapshotJson.IdField)
                {
                    string tag = field.Name == SnapshotJson.TypeField ? XmlType : XmlId;
                    WriteElementString(xml, tag, field.Value.GetString());
                    continue;
                }

                var property = properties[field.Name];
                StartElement(xml, XmlConvert.VerifyNCName(field.Name));
                if (property.IsMap)
                {
                    WriteMap(xml, field.Value, property);
                }
                else if (property.Kind == SnapshotPropertyKind.ScalarSet)
                {
                    WriteSet(xml, field.Value, property);
                }
                else if (property.IsCollection)
                {
                    foreach (var item in field.Value.EnumerateArray())
                    {
                        StartElement(xml, "item");
                        WriteValue(xml, item, property);
                        EndValueElement(xml, item);
                    }
                }
                else
                {
                    WriteValue(xml, field.Value, property);
                }
                EndValueElement(xml, field.Value);
            }
        }

        private void EndValueElement(XmlWriter xml, JsonElement value) =>
            EndElement(xml, full: value.ValueKind == JsonValueKind.String && value.GetRawText() == "\"\"");

        private void WriteValue(XmlWriter xml, JsonElement data, SnapshotProperty property)
        {
            if (property.ExternalType is not null && data.ValueKind != JsonValueKind.Null)
            {
                string path = property.WireName;
                var codec = registry.ExternalValues.Require(property, path).RequireXml(path);
                try
                {
                    codec.WriteContent(xml, data.Clone());
                    // An application codec may write its own elements, including raw XML.
                    HasCustomXmlContent |= !ReferenceEquals(codec, ExternalSnapshotXmlCodec.Text);
                }
                catch (Exception error) when (error is ArgumentException or InvalidOperationException or FormatException or JsonException or OverflowException)
                {
                    throw new XmlException($"Could not project external snapshot field '{path}' to XML.", error);
                }
                return;
            }
            if (property.IsUntypedObject && data.ValueKind != JsonValueKind.Null)
            {
                if (property.IsScalarCollection && data.ValueKind is JsonValueKind.Array or JsonValueKind.Object)
                    throw new XmlException($"Untyped list field '{property.WireName}' retains only scalar XML elements.");
                WriteUntypedContent(xml, data);
                return;
            }
            switch (data.ValueKind)
            {
                case JsonValueKind.Null:
                    xml.WriteAttributeString("xsi", "nil", Xsi, "true");
                    break;
                case JsonValueKind.Object:
                    var type = data.TryGetProperty(SnapshotJson.TypeField, out var discriminator)
                        ? registry.TypeForName(discriminator.GetString()!, property.ValueType, property.WireName)
                        : registry.TypeForClass(property.ValueType, property.WireName);
                    WriteObject(xml, data, type);
                    break;
                case JsonValueKind.String:
                    try
                    {
                        xml.WriteString(data.GetString());
                    }
                    catch (Exception error) when (error is ArgumentException or InvalidOperationException)
                    {
                        throw new XmlException($"Field '{property.WireName}' contains text outside XML 1.0.", error);
                    }
                    break;
                default:
                    xml.WriteString(data.GetRawText());
                    break;
            }
        }

        internal void ReadObject(XElement node, Utf8JsonWriter json, Type expected, int depth)
        {
            if (depth >= 1000) throw Invalid(node, "VMF snapshot exceeds maximum nesting depth");
            RequireElement(node);
            RequireStructured(node);
            var (discriminator, id) = ReadMetadata(node);
            if (profile == Profile.Plain && (discriminator ?? id) is { } metadata)
            {
                throw Invalid(metadata, "Plain XML cannot contain VMF graph metadata");
            }
            if (profile == Profile.TypedValues && id is not null)
            {
                throw Invalid(id, "VMF XML by value cannot contain a model ID");
            }

            string? typeName = discriminator is null ? null : RequiredText(discriminator);
            var model = typeName is null ? registry.TypeForClass(expected, Path(node))
                : registry.TypeForName(typeName, expected, Path(discriminator!));
            if (depth == 0 && !registry.MatchesName(model, node.Name.LocalName))
            {
                throw Invalid(node, $"Expected the registered root element '{model.WireName}'");
            }

            var properties = Properties(model);
            var knownFields = new HashSet<string>(StringComparer.Ordinal);
            json.WriteStartObject();
            if (typeName is not null) json.WriteString(SnapshotJson.TypeField, typeName);
            if (id is not null) json.WriteString(SnapshotJson.IdField, RequiredText(id));
            foreach (var field in node.Elements())
            {
                string name = field.Name.LocalName;
                if (name is XmlType or XmlId) continue;
                if (!properties.TryGetValue(name, out var property))
                {
                    CheckIgnoredField(field);
                    continue;
                }
                if (!knownFields.Add(name)) throw Invalid(field, "Duplicate field");

                json.WritePropertyName(property.WireName);
                if (property.IsMap)
                {
                    ReadMap(field, json, property);
                }
                else if (property.Kind == SnapshotPropertyKind.ScalarSet)
                {
                    ReadSet(field, json, property);
                }
                else if (property.IsCollection)
                {
                    RequireCollectionWrapper(field);
                    json.WriteStartArray();
                    foreach (var item in field.Elements())
                    {
                        if (item.Name != "item") throw Invalid(item, "Expected collection item");
                        ReadValue(item, json, property, depth + 1);
                    }
                    json.WriteEndArray();
                }
                else
                {
                    ReadValue(field, json, property, depth + 1);
                }
            }
            json.WriteEndObject();
        }

        private void CheckIgnoredField(XElement field)
        {
            var pending = new Stack<(XElement Element, int Depth)>();
            pending.Push((field, field.Ancestors().Count() + 1));
            while (pending.TryPop(out var entry))
            {
                var (element, depth) = entry;
                if (depth > MaxElementDepth) throw Invalid(element, "VMF snapshot exceeds maximum element depth");
                if (profile == Profile.Plain && (element.Name == XmlType || element.Name == XmlId))
                    throw Invalid(element, "Plain XML cannot contain VMF graph metadata");
                foreach (var child in element.Elements()) pending.Push((child, depth + 1));
            }
        }

        private void ReadValue(XElement node, Utf8JsonWriter json, SnapshotProperty property, int depth)
        {
            bool nil = ReadElementAttributes(node, allowNil: true, out var hostAttribute);
            if (hostAttribute is not null && (nil || property.ExternalType is null))
                throw Invalid(node, $"Unexpected attribute '{hostAttribute.Name}'");
            // Jackson reads a bare string element as empty text. For other nullable scalars
            // retain the legacy bare-null form, and let external codecs receive their own elements.
            bool bareNullSlot = property.IsScalarCollection && property.AllowNullElements
                && property.ValueType != typeof(string) && property.ExternalType is null
                && node.IsEmpty && node.Attribute(Nil) is null;
            if (nil || bareNullSlot)
            {
                RequireNilContentIsEmpty(node);
                json.WriteNullValue();
            }
            else if (property.ExternalType is not null)
            {
                string path = Path(node);
                var codec = registry.ExternalValues.Require(property, path).RequireXml(path);
                if (hostAttribute is not null && !codec.AllowsElementAttributes)
                    throw Invalid(node, $"Unexpected attribute '{hostAttribute.Name}'");
                try
                {
                    // Project the opaque host element back to precisely typed JSON before decoding the CLR value.
                    ExternalSnapshotCodecs.Codec.Detach(codec.ReadContent(node), path).WriteTo(json);
                }
                catch (Exception error) when (error is ArgumentException or InvalidOperationException or FormatException or JsonException or OverflowException)
                {
                    throw new XmlException($"Could not project external XML field '{path}' to a JSON value.", error);
                }
            }
            else if (property.IsUntypedObject)
            {
                if (property.IsScalarCollection && node.HasElements)
                    throw Invalid(node, "Untyped list XML elements must contain only text");
                ReadUntypedContent(node, json, depth);
            }
            else if (property.Kind == SnapshotPropertyKind.Scalar || property.IsScalarCollection)
            {
                WriteJsonScalar(node, json, property.ValueType);
            }
            else if (property.IsReference && !node.HasElements && !string.IsNullOrEmpty(node.Value))
            {
                if (profile != Profile.References) throw Invalid(node, "VMF XML without references cannot contain a model ID");
                json.WriteStringValue(RequiredText(node));
            }
            else
            {
                ReadObject(node, json, property.ValueType, depth);
            }
        }

        private static void WriteJsonScalar(XElement node, Utf8JsonWriter json, Type declared)
        {
            if (node.HasElements) throw Invalid(node, "A scalar field must contain only text");
            WriteJsonScalar(node, node.Value, json, declared, useXmlIntegerLexemes: true);
        }

        private static void WriteJsonScalar(XElement node, string value, Utf8JsonWriter json, Type declared,
            bool useXmlIntegerLexemes = false)
        {
            Type type = Nullable.GetUnderlyingType(declared) ?? declared;
            if (type == typeof(char) && value.Length == 1 && char.IsSurrogate(value[0]))
            {
                json.WriteRawValue($"\"\\u{(int)value[0]:X4}\"");
                return;
            }
            if (type == typeof(string) || type == typeof(char) || type == typeof(Guid))
            {
                RequireProperUtf16(node, value);
                json.WriteStringValue(value);
                return;
            }

            string text = value.Trim();
            if (type == typeof(bool))
            {
                if (text is not ("true" or "false")) throw Invalid(node, "Expected a boolean");
                json.WriteBooleanValue(text == "true");
                return;
            }
            if ((type == typeof(float) || type == typeof(double)) && text is ("NaN" or "Infinity" or "-Infinity"))
            {
                json.WriteStringValue(text);
                return;
            }

            // Element text follows XML integer syntax. Managed membership records have their own grammar.
            if (useXmlIntegerLexemes && (type == typeof(sbyte) || type == typeof(short)
                || type == typeof(int) || type == typeof(long)))
            {
                try
                {
                    json.WriteNumberValue(XmlConvert.ToInt64(text));
                }
                catch (Exception error) when (error is FormatException or OverflowException)
                {
                    throw Invalid(node, "Expected a signed decimal integer");
                }
                return;
            }

            using var number = JsonDocument.Parse(text);
            if (number.RootElement.ValueKind != JsonValueKind.Number) throw Invalid(node, "Expected a scalar number");
            number.RootElement.WriteTo(json);
        }

        private Dictionary<string, SnapshotProperty> Properties(SnapshotType model)
        {
            if (fieldMaps.TryGetValue(model, out var known)) return known;

            var result = new Dictionary<string, SnapshotProperty>(StringComparer.Ordinal);
            foreach (var property in model.Properties)
            {
                if (property.WireName is XmlType or XmlId)
                {
                    throw new XmlException($"Field '{property.WireName}' is reserved for VMF XML metadata.");
                }
                result.Add(property.WireName, property);
            }
            fieldMaps.Add(model, result);
            return result;
        }

        private static (XElement? Discriminator, XElement? Id) ReadMetadata(XElement model)
        {
            XElement? discriminator = null;
            XElement? id = null;
            foreach (var field in model.Elements())
            {
                RequireElementName(field);
                switch (field.Name.LocalName)
                {
                    case XmlType when discriminator is null: discriminator = field; break;
                    case XmlId when id is null: id = field; break;
                    case XmlType or XmlId: throw Invalid(field, "Duplicate field");
                }
            }
            return (discriminator, id);
        }

        private static Dictionary<string, XElement> ReadFields(XElement model)
        {
            RequireStructured(model);
            var result = new Dictionary<string, XElement>(StringComparer.Ordinal);
            foreach (var field in model.Elements())
            {
                RequireElementName(field);
                if (!result.TryAdd(field.Name.LocalName, field)) throw Invalid(field, "Duplicate field");
            }
            return result;
        }

        private static bool RequireElement(XElement element, bool allowNil = false)
        {
            bool nil = ReadElementAttributes(element, allowNil, out var unexpected);
            if (unexpected is not null) throw Invalid(element, $"Unexpected attribute '{unexpected.Name}'");
            return nil;
        }

        private static void RequireCollectionWrapper(XElement element)
        {
            if (RequireElement(element, allowNil: true)) RequireNilContentIsEmpty(element);
            else RequireStructured(element);
        }

        private static void RequireNilContentIsEmpty(XElement element)
        {
            if (element.HasElements || !string.IsNullOrWhiteSpace(element.Value))
                throw Invalid(element, "An xsi:nil element must be empty");
        }

        private static void RequireElementName(XElement element)
        {
            if (element.Name.Namespace != XNamespace.None) throw Invalid(element, "Unexpected element namespace");
        }

        private static bool ReadElementAttributes(XElement element, bool allowNil, out XAttribute? hostAttribute)
        {
            RequireElementName(element);
            hostAttribute = null;
            bool nil = false;
            foreach (var attribute in element.Attributes())
            {
                if (attribute.IsNamespaceDeclaration) continue;
                if (attribute.Name != Nil)
                {
                    hostAttribute ??= attribute;
                    continue;
                }
                if (!allowNil || attribute.Value is not ("true" or "false" or "1" or "0"))
                {
                    throw Invalid(element, $"Unexpected attribute '{attribute.Name}'");
                }
                nil = attribute.Value is "true" or "1";
            }
            return nil;
        }

        private static void RequireStructured(XElement element)
        {
            if (element.Nodes().OfType<XText>().Any(text => !string.IsNullOrWhiteSpace(text.Value)))
            {
                throw Invalid(element, "Unexpected text in a structured element");
            }
        }

        private static string RequiredText(XElement element)
        {
            RequireElement(element);
            if (element.HasElements || string.IsNullOrWhiteSpace(element.Value))
            {
                throw Invalid(element, "Expected non-empty scalar text");
            }
            return element.Value;
        }

        private static string Path(XElement element) =>
            "/" + string.Join("/", element.AncestorsAndSelf().Reverse().Select(node => node.Name.LocalName));

        private static XmlException Invalid(XElement element, string message)
        {
            var line = (IXmlLineInfo)element;
            return new XmlException($"{message} at {Path(element)}.", null, line.LineNumber, line.LinePosition);
        }
    }
}
