using System.Globalization;
using System.Numerics;
using System.Text.Json;

namespace Vmf.Runtime;

public static partial class SnapshotJson
{
    internal static bool IsSupportedScalar(Type type) =>
        type == typeof(string) || type == typeof(bool) || type == typeof(char)
        || type == typeof(sbyte) || type == typeof(short) || type == typeof(int) || type == typeof(long)
        || type == typeof(float) || type == typeof(double) || type == typeof(Guid);

    private static void WriteScalar(Utf8JsonWriter writer, object value)
    {
        switch (value)
        {
            case string text:
                writer.WriteStringValue(text);
                break;
            case Guid uuid:
                writer.WriteStringValue(uuid);
                break;
            case bool boolean:
                writer.WriteBooleanValue(boolean);
                break;
            case char character:
                if (char.IsSurrogate(character))
                {
                    // Java Character is a UTF-16 code unit, including isolated surrogates.
                    writer.WriteRawValue($"\"\\u{(int)character:X4}\"");
                }
                else
                {
                    writer.WriteStringValue(character.ToString());
                }
                break;
            case sbyte number:
                writer.WriteNumberValue(number);
                break;
            case short number:
                writer.WriteNumberValue(number);
                break;
            case int number:
                writer.WriteNumberValue(number);
                break;
            case long number:
                writer.WriteNumberValue(number);
                break;
            case float number:
                if (!WriteSpecialFloat(writer, number))
                {
                    writer.WriteNumberValue(number);
                }
                break;
            case double number:
                if (!WriteSpecialFloat(writer, number))
                {
                    writer.WriteNumberValue(number);
                }
                break;
            default:
                throw new InvalidOperationException($"Unsupported snapshot scalar type '{value.GetType()}'.");
        }
    }

    private static bool WriteSpecialFloat(Utf8JsonWriter writer, double number)
    {
        if (!double.IsFinite(number))
        {
            writer.WriteStringValue(number.ToString(CultureInfo.InvariantCulture));
            return true;
        }
        if (number == 0 && double.IsNegative(number))
        {
            // A bare -0 is an integral token in Jackson and does not retain its sign.
            writer.WriteRawValue("-0.0");
            return true;
        }
        return false;
    }

    internal static object? ReadScalar(JsonElement value, Type declaredType, string path)
    {
        Type? nullableType = Nullable.GetUnderlyingType(declaredType);
        if (value.ValueKind == JsonValueKind.Null && (!declaredType.IsValueType || nullableType is not null))
        {
            return null;
        }

        Type type = nullableType ?? declaredType;
        if (type == typeof(object)) return ReadUntypedValue(value, path);
        if (type == typeof(string) && value.ValueKind == JsonValueKind.String)
        {
            return ReadText(value, path);
        }
        if (type == typeof(char) && value.ValueKind == JsonValueKind.String)
        {
            return ReadChar(value, path);
        }
        if (type == typeof(Guid) && value.ValueKind == JsonValueKind.String && value.TryGetGuid(out Guid uuid))
        {
            return uuid;
        }
        if (type == typeof(bool) && value.ValueKind is JsonValueKind.True or JsonValueKind.False)
        {
            return value.GetBoolean();
        }
        if (type == typeof(float))
        {
            return ReadFloating<float>(value, path);
        }
        if (type == typeof(double))
        {
            return ReadFloating<double>(value, path);
        }
        if (value.ValueKind == JsonValueKind.Number)
        {
            if (type == typeof(sbyte) && value.TryGetSByte(out sbyte signedByte))
            {
                return signedByte;
            }
            if (type == typeof(short) && value.TryGetInt16(out short shortInteger))
            {
                return shortInteger;
            }
            if (type == typeof(int) && value.TryGetInt32(out int integer))
            {
                return integer;
            }
            if (type == typeof(long) && value.TryGetInt64(out long longInteger))
            {
                return longInteger;
            }
        }
        throw Invalid(path, $"Expected a JSON value of type '{declaredType}'");
    }

    private static char ReadChar(JsonElement value, string path)
    {
        var literal = value.GetRawText().AsSpan();
        if (literal.Length == 8 && literal.Slice(1, 2).SequenceEqual("\\u")
            && ushort.TryParse(literal.Slice(3, 4), NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture,
                out ushort unit) && char.IsSurrogate((char)unit))
        {
            return (char)unit;
        }

        string text = ReadText(value, path);
        if (text.Length != 1)
        {
            throw Invalid(path, "Expected a string containing one UTF-16 code unit");
        }
        return text[0];
    }

    private static T ReadFloating<T>(JsonElement value, string path) where T : IFloatingPointIeee754<T>
    {
        if (value.ValueKind == JsonValueKind.String)
        {
            return ReadText(value, path) switch
            {
                "NaN" => T.NaN,
                "Infinity" => T.PositiveInfinity,
                "-Infinity" => T.NegativeInfinity,
                _ => throw Invalid(path, $"Expected a floating-point value of type '{typeof(T)}'"),
            };
        }
        if (value.ValueKind == JsonValueKind.Number)
        {
            string literal = value.GetRawText();
            if (T.TryParse(literal, NumberStyles.Float, CultureInfo.InvariantCulture, out var result)
                && T.IsFinite(result) && (!T.IsZero(result) || HasZeroSignificand(literal)))
            {
                return result;
            }
            throw Invalid(path, $"Number is outside the range of '{typeof(T)}'");
        }
        throw Invalid(path, $"Expected a floating-point value of type '{typeof(T)}'");
    }

    private static bool HasZeroSignificand(string literal)
    {
        foreach (char character in literal)
        {
            if (character is 'e' or 'E')
            {
                break;
            }
            if (character is >= '1' and <= '9')
            {
                return false;
            }
        }
        return true;
    }
}
