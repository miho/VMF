using System.Globalization;

namespace Vmf.Runtime;

internal static class JournalScalar
{
    private static readonly CultureInfo Invariant = CultureInfo.InvariantCulture;
    private const NumberStyles Integer = NumberStyles.AllowLeadingSign;

    internal static (string Kind, string? Text) Encode(object? value) => value switch
    {
        null => ("NULL", null),
        string text => ("STRING", CheckedUnicode(text)),
        bool flag => ("BOOLEAN", flag ? "true" : "false"),
        sbyte number => ("BYTE", number.ToString(Invariant)),
        short number => ("SHORT", number.ToString(Invariant)),
        int number => ("INT", number.ToString(Invariant)),
        long number => ("LONG", number.ToString(Invariant)),
        char unit => ("CHAR", ((int)unit).ToString(Invariant)),
        float number => ("FLOAT", FormatFloating(BitConverter.SingleToUInt32Bits(number), 8, 23)),
        double number => ("DOUBLE", FormatFloating(BitConverter.DoubleToUInt64Bits(number), 11, 52)),
        _ => throw new ArgumentException($"Type '{value.GetType()}' has no journal scalar representation."),
    };

    internal static object? Decode(string kind, string? text)
    {
        if (kind == "NULL" && text is null)
        {
            return null;
        }
        if (text is null)
        {
            throw new FormatException($"Missing journal text for '{kind}'.");
        }

        object value = kind switch
        {
            "STRING" => CheckedUnicode(text),
            "BOOLEAN" => bool.Parse(text),
            "BYTE" => sbyte.Parse(text, Integer, Invariant),
            "SHORT" => short.Parse(text, Integer, Invariant),
            "INT" => int.Parse(text, Integer, Invariant),
            "LONG" => long.Parse(text, Integer, Invariant),
            "CHAR" => (char)ushort.Parse(text, Integer, Invariant),
            "FLOAT" => BitConverter.UInt32BitsToSingle((uint)ParseFloating(text, 8, 23)),
            "DOUBLE" => BitConverter.UInt64BitsToDouble(ParseFloating(text, 11, 52)),
            _ => throw new FormatException($"Unsupported journal scalar kind '{kind}'."),
        };

        if (!string.Equals(Encode(value).Text, text, StringComparison.Ordinal))
        {
            throw new FormatException($"Noncanonical journal text for '{kind}'.");
        }
        return value;
    }

    internal static string CheckedUnicode(string value)
    {
        for (int index = 0; index < value.Length; index++)
        {
            if (!char.IsSurrogate(value[index]))
            {
                continue;
            }
            if (!char.IsHighSurrogate(value[index]) || ++index == value.Length || !char.IsLowSurrogate(value[index]))
            {
                throw new FormatException("Journal text contains an unpaired UTF-16 surrogate.");
            }
        }
        return value;
    }

    private static string FormatFloating(ulong bits, int exponentWidth, int fractionWidth)
    {
        ulong exponentMask = (1UL << exponentWidth) - 1;
        ulong fractionMask = (1UL << fractionWidth) - 1;
        bool negative = bits >> (exponentWidth + fractionWidth) != 0;
        ulong exponent = (bits >> fractionWidth) & exponentMask;
        ulong fraction = bits & fractionMask;
        string sign = negative ? "-" : "";

        if (exponent == exponentMask)
        {
            return fraction == 0 ? sign + "Infinity" : "NaN";
        }
        if (exponent == 0 && fraction == 0)
        {
            return sign + "0x0.0p0";
        }

        int bias = (int)(exponentMask >> 1);
        int exponentValue = exponent == 0 ? 1 - bias : (int)exponent - bias;
        int digits = (fractionWidth + 3) / 4;
        string fractionText = (fraction << (digits * 4 - fractionWidth))
            .ToString("x" + digits, Invariant).TrimEnd('0');
        if (fractionText.Length == 0)
        {
            fractionText = "0";
        }
        return $"{sign}0x{(exponent == 0 ? '0' : '1')}.{fractionText}p{exponentValue.ToString(Invariant)}";
    }

    private static ulong ParseFloating(string text, int exponentWidth, int fractionWidth)
    {
        ulong exponentMask = (1UL << exponentWidth) - 1;
        ulong sign = 1UL << (exponentWidth + fractionWidth);
        ulong infinity = exponentMask << fractionWidth;
        if (text == "NaN")
        {
            return infinity | (1UL << (fractionWidth - 1));
        }
        if (text == "Infinity")
        {
            return infinity;
        }
        if (text == "-Infinity")
        {
            return sign | infinity;
        }

        ReadOnlySpan<char> number = text.AsSpan();
        bool negative = !number.IsEmpty && number[0] == '-';
        if (negative)
        {
            number = number[1..];
        }
        if (number.Length < 7 || number[0] != '0' || number[1] != 'x'
            || (number[2] != '0' && number[2] != '1') || number[3] != '.')
        {
            throw new FormatException("Expected a hexadecimal VMF journal float.");
        }

        int exponentStart = number.IndexOf('p');
        int digitWidth = (fractionWidth + 3) / 4;
        if (exponentStart < 5 || exponentStart - 4 > digitWidth
            || !ulong.TryParse(number[4..exponentStart], NumberStyles.AllowHexSpecifier, Invariant, out ulong fraction)
            || !int.TryParse(number[(exponentStart + 1)..], Integer, Invariant, out int exponent))
        {
            throw new FormatException("Invalid hexadecimal VMF journal float.");
        }

        int padding = digitWidth * 4 - fractionWidth;
        fraction <<= 4 * (digitWidth - (exponentStart - 4));
        if ((fraction & ((1UL << padding) - 1)) != 0)
        {
            throw new FormatException("Journal float has excess precision.");
        }
        fraction >>= padding;

        int bias = (int)(exponentMask >> 1);
        long encodedExponent = 0;
        if (number[2] == '1')
        {
            encodedExponent = (long)exponent + bias;
            if (encodedExponent <= 0 || encodedExponent >= (long)exponentMask)
            {
                throw new FormatException("Journal float exponent is out of range.");
            }
        }
        else if (exponent != (fraction == 0 ? 0 : 1 - bias))
        {
            throw new FormatException("Journal float uses a noncanonical subnormal exponent.");
        }

        return (negative ? sign : 0) | ((ulong)encodedExponent << fractionWidth) | fraction;
    }
}
