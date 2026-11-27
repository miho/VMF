using System.Text.Json;
using System.Text.Json.Nodes;
using System.Xml;
using System.Xml.Linq;
using Vmf.Runtime;

namespace Eu.Mihosoft.Vmftest.Complex.Devcom.Host
{
    public enum ParityBits { NO_PARITY, EVEN_PARITY, ODD_PARITY, MARK_PARITY, SPACE_PARITY }
    public enum StopBits { ONE_STOP_BIT, TWO_STOP_BITS }
    public enum State { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

    public sealed class JavaException
    {
        private static readonly string[] Fields = ["cause", "stackTrace", "message", "suppressed", "localizedMessage"];
        private static readonly XName Nil = XName.Get("nil", "http://www.w3.org/2001/XMLSchema-instance");

        internal static readonly ExternalSnapshotXmlCodec Xml = new(WriteXml, ReadXml);
        internal static JsonElement Schema { get; } = JsonSerializer.SerializeToElement(new { type = "object" });

        public JavaException(JsonElement data)
        {
            Check(data);
            Data = data.Clone();
        }

        public JsonElement Data { get; }
        public string? Message => Data.GetProperty("message").GetString();
        public JsonElement Cause => Data.GetProperty("cause");
        public int StackCount => Data.GetProperty("stackTrace").GetArrayLength();
        public int SuppressedCount => Data.GetProperty("suppressed").GetArrayLength();

        private static void Check(JsonElement data)
        {
            if (data.ValueKind != JsonValueKind.Object || !data.EnumerateObject().Select(field => field.Name)
                    .ToHashSet(StringComparer.Ordinal).SetEquals(Fields))
            {
                throw new JsonException("Expected the Jackson Throwable fields.");
            }
            foreach (string field in new[] { "message", "localizedMessage" })
                if (data.GetProperty(field).ValueKind is not (JsonValueKind.String or JsonValueKind.Null))
                    throw new JsonException($"Expected text or null for {field}.");
            var cause = data.GetProperty("cause");
            if (cause.ValueKind != JsonValueKind.Null) Check(cause);
            if (data.GetProperty("stackTrace").ValueKind != JsonValueKind.Array
                || data.GetProperty("suppressed").ValueKind != JsonValueKind.Array)
                throw new JsonException("Expected the Jackson Throwable arrays.");
            foreach (var fault in data.GetProperty("suppressed").EnumerateArray()) Check(fault);
        }

        private static void WriteXml(XmlWriter xml, JsonElement value)
        {
            if (value.GetProperty("stackTrace").GetArrayLength() != 0 || value.GetProperty("suppressed").GetArrayLength() != 0)
                throw new XmlException("The stock Java VMF XML reader rejects populated Throwable stack or suppressed arrays.");
            xml.WriteStartElement("cause");
            var cause = value.GetProperty("cause");
            if (cause.ValueKind != JsonValueKind.Null) WriteXml(xml, cause);
            xml.WriteEndElement();
            xml.WriteStartElement("stackTrace");
            xml.WriteEndElement();
            xml.WriteElementString("message", value.GetProperty("message").GetString());
            xml.WriteStartElement("suppressed");
            xml.WriteEndElement();
            xml.WriteElementString("localizedMessage", value.GetProperty("localizedMessage").GetString());
        }

        private static JsonElement ReadXml(XElement value)
        {
            if (!value.Elements().Select(field => field.Name.LocalName).ToHashSet(StringComparer.Ordinal).SetEquals(Fields)
                || value.Elements().Count() != Fields.Length)
                throw new XmlException("Expected the existing Jackson Throwable XML fields.");
            if (value.Element("stackTrace")!.HasElements || value.Element("suppressed")!.HasElements)
                throw new XmlException("The stock Java VMF XML reader rejects populated Throwable stack or suppressed arrays.");
            var cause = value.Element("cause")!;
            // Preserve the empty cause in the document. Jackson may synthesize a Throwable
            // for it while restoring Java runtime state from this legacy XML representation.
            var content = new JsonObject
            {
                ["cause"] = cause.HasElements ? JsonNode.Parse(ReadXml(cause).GetRawText()) : null,
                ["stackTrace"] = new JsonArray(),
                ["message"] = Text(value.Element("message")!),
                ["suppressed"] = new JsonArray(),
                ["localizedMessage"] = Text(value.Element("localizedMessage")!),
            };
            return JsonSerializer.SerializeToElement(content);

            static string? Text(XElement element) => element.Attribute(Nil)?.Value is "true" or "1" ? null : element.Value;
        }
    }
}

namespace Eu.Mihosoft.Vmftest.Complex.Devcom
{
    using Eu.Mihosoft.Vmftest.Complex.Devcom.Host;

    public sealed partial class PortConfig
    {
        private static partial ParityBits? CreateDefaultParityBits() => Eu.Mihosoft.Vmftest.Complex.Devcom.Host.ParityBits.NO_PARITY;
        private static partial StopBits? CreateDefaultStopBits() => Eu.Mihosoft.Vmftest.Complex.Devcom.Host.StopBits.ONE_STOP_BIT;
    }

    public static partial class ModelSnapshot
    {
        private const string JavaPackage = "eu.mihosoft.vmftest.complex.devcom.";

        private static partial ExternalSnapshotCodecs CreateExternalValueCodecs() => ExternalSnapshotCodecs.Empty
            .WithEnum<ParityBits>(JavaPackage + "ParityBits")
            .WithEnum<StopBits>(JavaPackage + "StopBits")
            .WithEnum<State>(JavaPackage + "Device.State")
            .WithValue<JavaException>("java.lang.Exception", static fault => fault.Data,
                static value => new JavaException(value), JavaException.Schema, JavaException.Xml);
    }
}
