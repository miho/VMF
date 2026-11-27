using System.Buffers;
using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using YamlDotNet.Core;
using YamlDotNet.Core.Events;

namespace Vmf.Runtime;

internal static class YamlWire
{
    private const int MaxDepth = 1000;
    private static readonly Regex JsonNumber = new(
        @"\A-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?\z",
        RegexOptions.CultureInvariant | RegexOptions.NonBacktracking);

    internal static T Decode<T>(string yaml, Func<string, T> readJson)
    {
        ArgumentNullException.ThrowIfNull(yaml);
        try
        {
            var compatible = YamlCodeUnits.PrepareInput(yaml);
            using var input = new StringReader(compatible.Text);
            var parser = new Parser(input);
            parser.Consume<StreamStart>();
            parser.Consume<DocumentStart>();
            var buffer = new ArrayBufferWriter<byte>();
            using (var json = new Utf8JsonWriter(buffer, new JsonWriterOptions { MaxDepth = MaxDepth }))
            {
                ReadNode(parser, json, compatible.CodeUnits, depth: 0);
            }
            parser.Consume<DocumentEnd>();
            parser.Consume<StreamEnd>();
            return readJson(Encoding.UTF8.GetString(buffer.WrittenSpan));
        }
        catch (JsonException error)
        {
            throw new YamlException("Invalid VMF YAML document: " + error.Message, error);
        }
    }

    private static void ReadNode(IParser parser, Utf8JsonWriter json, IReadOnlyDictionary<long, char>? codeUnits, int depth)
    {
        // Count open containers. A scalar leaf does not increase nesting depth.
        if (depth >= MaxDepth && parser.Current is (MappingStart or SequenceStart))
        {
            throw new YamlException("VMF YAML document exceeds the supported nesting depth.");
        }

        switch (parser.Current)
        {
            case MappingStart:
                CheckNode(parser.Consume<MappingStart>());
                json.WriteStartObject();
                var keys = new HashSet<string>(StringComparer.Ordinal);
                while (!parser.TryConsume<MappingEnd>(out _))
                {
                    var key = parser.Consume<Scalar>();
                    CheckNode(key);
                    // Jackson also writes a literal string-map key "<<" unquoted.
                    // VMF scalar maps can carry it, but never apply a YAML merge.
                    if (key.Style == ScalarStyle.Plain && key.Value == "<<" && parser.Current is MappingStart or SequenceStart)
                    {
                        throw Error(key, "YAML merge keys are unavailable in VMF documents.");
                    }
                    if (codeUnits?.ContainsKey(key.Start.Index) == true)
                    {
                        throw Error(key, "A YAML mapping key must be valid Unicode.");
                    }
                    YamlCodeUnits.RequireUnicode(key);
                    if (!keys.Add(key.Value)) throw Error(key, $"Duplicate YAML key '{key.Value}'.");
                    json.WritePropertyName(key.Value);
                    ReadNode(parser, json, codeUnits, depth + 1);
                }
                json.WriteEndObject();
                break;
            case SequenceStart:
                CheckNode(parser.Consume<SequenceStart>());
                json.WriteStartArray();
                while (!parser.TryConsume<SequenceEnd>(out _))
                {
                    ReadNode(parser, json, codeUnits, depth + 1);
                }
                json.WriteEndArray();
                break;
            case Scalar:
                var scalar = parser.Consume<Scalar>();
                CheckNode(scalar);
                if (codeUnits is not null && codeUnits.TryGetValue(scalar.Start.Index, out char codeUnit))
                {
                    json.WriteRawValue($"\"\\u{(int)codeUnit:x4}\"");
                }
                else
                {
                    ReadScalar(scalar, json);
                }
                break;
            case AnchorAlias alias:
                throw Error(alias, "YAML aliases are unavailable in VMF documents.");
            case ParsingEvent unexpected:
                throw Error(unexpected, "Expected a YAML mapping, sequence, or scalar.");
            default:
                throw new YamlException("Unexpected end of VMF YAML document.");
        }
    }

    private static void ReadScalar(Scalar scalar, Utf8JsonWriter json)
    {
        string value = scalar.Value;
        if (scalar.Style == ScalarStyle.Plain)
        {
            switch (value)
            {
                case "" or "null" or "Null" or "NULL" or "~":
                    json.WriteNullValue();
                    return;
                case "true" or "True" or "TRUE":
                    json.WriteBooleanValue(true);
                    return;
                case "false" or "False" or "FALSE":
                    json.WriteBooleanValue(false);
                    return;
            }
            if (JsonNumber.IsMatch(value))
            {
                json.WriteRawValue(value);
                return;
            }
        }
        YamlCodeUnits.RequireUnicode(scalar);
        json.WriteStringValue(value);
    }

    private static void CheckNode(NodeEvent node)
    {
        if (!node.Anchor.IsEmpty) throw Error(node, "YAML anchors are unavailable in VMF documents.");
        if (!node.Tag.IsEmpty) throw Error(node, "Explicit YAML tags are unavailable in VMF documents.");
    }

    internal static string Encode(Func<string> writeJson)
    {
        try
        {
            string json = writeJson();
            using var document = JsonDocument.Parse(json, new JsonDocumentOptions { MaxDepth = MaxDepth });
            using var output = new StringWriter(CultureInfo.InvariantCulture);
            var emitter = new Emitter(output);
            var codeUnits = new YamlCodeUnits.Output(json);
            emitter.Emit(new StreamStart());
            emitter.Emit(new DocumentStart());
            WriteNode(emitter, document.RootElement, codeUnits);
            emitter.Emit(new DocumentEnd(isImplicit: true));
            emitter.Emit(new StreamEnd());
            return codeUnits.Complete(output.ToString());
        }
        catch (JsonException error)
        {
            throw new YamlException("Invalid VMF model for a YAML document: " + error.Message, error);
        }
    }

    private static void WriteNode(IEmitter emitter, JsonElement value, YamlCodeUnits.Output codeUnits)
    {
        switch (value.ValueKind)
        {
            case JsonValueKind.Object:
                emitter.Emit(new MappingStart());
                foreach (var property in value.EnumerateObject())
                {
                    WriteScalar(emitter, property.Name, ScalarStyle.DoubleQuoted);
                    WriteNode(emitter, property.Value, codeUnits);
                }
                emitter.Emit(new MappingEnd());
                break;
            case JsonValueKind.Array:
                emitter.Emit(new SequenceStart(AnchorName.Empty, TagName.Empty, isImplicit: true, SequenceStyle.Block));
                foreach (var member in value.EnumerateArray())
                {
                    WriteNode(emitter, member, codeUnits);
                }
                emitter.Emit(new SequenceEnd());
                break;
            case JsonValueKind.String:
                WriteScalar(emitter, codeUnits.GetScalar(value), ScalarStyle.DoubleQuoted);
                break;
            default:
                WriteScalar(emitter, value.GetRawText(), ScalarStyle.Plain);
                break;
        }
    }

    private static void WriteScalar(IEmitter emitter, string value, ScalarStyle style) =>
        emitter.Emit(new Scalar(AnchorName.Empty, TagName.Empty, value, style, isPlainImplicit: true, isQuotedImplicit: true));

    private static YamlException Error(ParsingEvent item, string message) => new(item.Start, item.End, message);
}
