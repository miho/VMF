using System.Buffers;
using System.Collections;
using System.Globalization;
using System.Numerics;
using System.Text.Json;

namespace Vmf.Runtime;

public static partial class SnapshotJson
{
    internal const int MaxUntypedValueDepth = 1000;

    private static JsonElement CaptureUntypedValue(object value, string path)
    {
        var output = new ArrayBufferWriter<byte>();
        using (var json = new Utf8JsonWriter(output))
        {
            WriteUntypedValue(json, value, path, new(ReferenceEqualityComparer.Instance), 0);
        }
        using var document = JsonDocument.Parse(output.WrittenMemory, new JsonDocumentOptions { MaxDepth = MaxUntypedValueDepth });
        return document.RootElement.Clone();
    }

    private static void WriteUntypedValue(Utf8JsonWriter json, object? value, string path, HashSet<object> active, int depth)
    {
        if (depth >= MaxUntypedValueDepth) throw Invalid(path, "Untyped Object value exceeds maximum nesting depth");
        switch (value)
        {
            case null:
                json.WriteNullValue();
                return;
            case string text:
                CheckText(text, path);
                json.WriteStringValue(text);
                return;
            case char character when !char.IsSurrogate(character):
            case bool:
            case sbyte:
            case short:
            case int:
            case long:
                WriteScalar(json, value);
                return;
            case BigInteger integer:
                json.WriteRawValue(integer.ToString(CultureInfo.InvariantCulture));
                return;
            case float number:
                WriteUntypedFloat(json, number, number.ToString("R", CultureInfo.InvariantCulture),
                    number.ToString("G2", CultureInfo.InvariantCulture));
                return;
            case double number:
                WriteUntypedFloat(json, number, number.ToString("R", CultureInfo.InvariantCulture),
                    number.ToString("G2", CultureInfo.InvariantCulture));
                return;
        }

        if (value is not IDictionary && value is not IList || value is Array { Rank: not 1 })
            throw Invalid(path, $"CLR type '{value.GetType()}' has no equivalent untyped Object snapshot value");
        if (!active.Add(value)) throw Invalid(path, "Untyped Object values cannot contain a cycle");
        try
        {
            if (value is IDictionary map)
            {
                json.WriteStartObject();
                foreach (DictionaryEntry entry in map)
                {
                    if (entry.Key is not string name) throw Invalid(path, "Untyped Object maps need string keys");
                    CheckText(name, ChildPath(path, name));
                    json.WritePropertyName(name);
                    WriteUntypedValue(json, entry.Value, ChildPath(path, name), active, depth + 1);
                }
                json.WriteEndObject();
            }
            else
            {
                json.WriteStartArray();
                int index = 0;
                foreach (var member in (IList)value)
                {
                    WriteUntypedValue(json, member, IndexPath(path, index++), active, depth + 1);
                }
                json.WriteEndArray();
            }
        }
        finally
        {
            active.Remove(value);
        }
    }

    private static void WriteUntypedFloat(Utf8JsonWriter json, double value, string roundtrip, string twoDigits)
    {
        if (WriteSpecialFloat(json, value)) return;

        // A floating-point token must keep a decimal point or exponent for Jackson's Object decoder.
        double magnitude = Math.Abs(value);
        bool scientific = magnitude != 0 && (magnitude < 0.001 || magnitude >= 10_000_000);
        string text = scientific ? Scientific(roundtrip, twoDigits) : Plain(roundtrip);
        json.WriteRawValue(text);

        static string Scientific(string shortest, string fallback)
        {
            var (sign, digits, position) = DecimalParts(shortest);
            if (digits.Length == 1)
            {
                var nearest = DecimalParts(fallback);
                if (nearest.Digits.Length > 1) (sign, digits, position) = nearest;
            }
            return sign + digits[0] + "." + (digits.Length == 1 ? "0" : digits[1..]) + "E"
                + (position - 1).ToString(CultureInfo.InvariantCulture);
        }

        static string Plain(string shortest)
        {
            var (sign, digits, position) = DecimalParts(shortest);
            if (position <= 0) return sign + "0." + new string('0', -position) + digits;
            if (position >= digits.Length) return sign + digits + new string('0', position - digits.Length) + ".0";
            return sign + digits[..position] + "." + digits[position..];
        }

        static (string Sign, string Digits, int Position) DecimalParts(string text)
        {
            string sign = text.StartsWith('-') ? "-" : "";
            var number = text.AsSpan(sign.Length);
            int separator = number.IndexOfAny('E', 'e');
            int exponent = separator < 0 ? 0 : int.Parse(number[(separator + 1)..], CultureInfo.InvariantCulture);
            if (separator >= 0) number = number[..separator];
            int point = number.IndexOf('.');
            int position = (point < 0 ? number.Length : point) + exponent;
            string digits = number.ToString().Replace(".", "", StringComparison.Ordinal);
            int zeros = digits.Length - digits.TrimStart('0').Length;
            return zeros == digits.Length ? (sign, "0", 1) : (sign, digits[zeros..].TrimEnd('0'), position - zeros);
        }
    }

    private static object? ReadUntypedValue(JsonElement data, string path)
    {
        switch (data.ValueKind)
        {
            case JsonValueKind.Null:
                return null;
            case JsonValueKind.String:
                return ReadText(data, path);
            case JsonValueKind.True:
            case JsonValueKind.False:
                return data.GetBoolean();
            case JsonValueKind.Number:
                string text = data.GetRawText();
                if (text.AsSpan().IndexOfAny('.', 'E', 'e') >= 0) return data.GetDouble();
                if (data.TryGetInt32(out int integer)) return integer;
                if (data.TryGetInt64(out long wide)) return wide;
                return BigInteger.Parse(text, CultureInfo.InvariantCulture);
            case JsonValueKind.Array:
                return data.EnumerateArray().Select((member, index) => ReadUntypedValue(member, IndexPath(path, index))).ToList();
            case JsonValueKind.Object:
                return data.EnumerateObject().ToDictionary(field => field.Name,
                    field => ReadUntypedValue(field.Value, ChildPath(path, field.Name)), StringComparer.Ordinal);
            default:
                throw Invalid(path, "Unsupported untyped Object snapshot value");
        }
    }
}
