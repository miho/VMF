using System.Text.Json;
using System.Text.Json.Nodes;
using System.Xml;
using System.Xml.Linq;
using Eu.Mihosoft.Vmf.Conformance.Hosts;
using Vmf.Runtime;

namespace Eu.Mihosoft.Vmf.Conformance.Hosts
{
    public sealed class JavaMap : Dictionary<string, JsonNode?>, IEquatable<JavaMap>
    {
        public JavaMap() : base(StringComparer.Ordinal) { }

        public bool Equals(JavaMap? other) => other is not null && Count == other.Count
            && this.All(entry => other.TryGetValue(entry.Key, out var value) && JsonNode.DeepEquals(entry.Value, value));

        public override bool Equals(object? other) => other is JavaMap map && Equals(map);

        public override int GetHashCode()
        {
            var hash = new HashCode();
            foreach (var key in Keys.Order(StringComparer.Ordinal)) hash.Add(key, StringComparer.Ordinal);
            return hash.ToHashCode();
        }
    }

    internal static class JavaMapSnapshots
    {
        internal static ExternalSnapshotCodecs Create() => ExternalSnapshotCodecs.Empty.WithValue<JavaMap>("java.util.Map",
            static map => JsonSerializer.SerializeToElement(map), Read,
            JsonSerializer.SerializeToElement(new { type = "object" }),
            new ExternalSnapshotXmlCodec(WriteXmlMembers, static element => JsonSerializer.SerializeToElement(ReadXmlMembers(element))));

        private static JavaMap Read(JsonElement data)
        {
            if (data.ValueKind != JsonValueKind.Object) throw new JsonException("Expected a plain map object.");
            var map = new JavaMap();
            foreach (var field in data.EnumerateObject()) map.Add(field.Name, JsonNode.Parse(field.Value.GetRawText()));
            return map;
        }

        private static void WriteXmlMembers(XmlWriter writer, JsonElement map)
        {
            foreach (var field in map.EnumerateObject()) WriteXmlField(writer, field.Name, field.Value);
        }

        private static void WriteXmlField(XmlWriter writer, string name, JsonElement value)
        {
            if (value.ValueKind == JsonValueKind.Array)
            {
                foreach (var member in value.EnumerateArray()) WriteXmlField(writer, name, member);
                return;
            }

            writer.WriteStartElement(XmlConvert.VerifyNCName(name));
            if (value.ValueKind == JsonValueKind.Object) WriteXmlMembers(writer, value);
            else if (value.ValueKind != JsonValueKind.Null)
                writer.WriteString(value.ValueKind == JsonValueKind.String ? value.GetString() : value.GetRawText());
            writer.WriteEndElement();
        }

        private static JsonObject ReadXmlMembers(XElement element)
        {
            var map = new JsonObject();
            foreach (var group in element.Elements().GroupBy(member => member.Name.LocalName, StringComparer.Ordinal))
            {
                var values = group.Select(member => member.HasElements ? (JsonNode?)ReadXmlMembers(member)
                    : JsonValue.Create(member.Value)).ToArray();
                map.Add(group.Key, values.Length == 1 ? values[0] : new JsonArray(values));
            }
            return map;
        }
    }
}

namespace Eu.Mihosoft.Vmf.Jackson.Test.External_types02
{
    public sealed partial class ExternalTypeModel02
    {
        private static partial JavaMap? CreateDefaultStringMap() => new();

        private static partial JavaMap? CreateDefaultObjectMap() => new();
    }

    public static partial class ModelSnapshot
    {
        private static partial ExternalSnapshotCodecs CreateExternalValueCodecs() => JavaMapSnapshots.Create();
    }
}

namespace Eu.Mihosoft.Vmf.Jackson.Test.Mapjournal
{
    public sealed partial class Labels
    {
        private static partial JavaMap? CreateDefaultExternal() => new();
    }

    public static partial class ModelSnapshot
    {
        private static partial ExternalSnapshotCodecs CreateExternalValueCodecs() => JavaMapSnapshots.Create();
    }
}
