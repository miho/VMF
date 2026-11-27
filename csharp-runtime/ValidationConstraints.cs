using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace Vmf.Runtime;

/// <summary>Evaluates the portable VMF value constraints used by generated models.</summary>
public static class ValidationConstraints
{
    public static void CheckRange(object? value, string? min, bool minInclusive, string? max, bool maxInclusive,
        string path, string modelPath, ICollection<ValidationIssue> issues)
    {
        if (value is null)
        {
            return;
        }

        string? rendition = IntegralText(value);
        if (rendition is not null)
        {
            var number = DecimalValue.Parse(rendition);
            if ((string.IsNullOrEmpty(min) || number.CompareTo(DecimalValue.Parse(min)) >= (minInclusive ? 0 : 1))
                && (string.IsNullOrEmpty(max) || number.CompareTo(DecimalValue.Parse(max)) <= (maxInclusive ? 0 : -1)))
            {
                return;
            }
        }
        AddRangeIssue(min, minInclusive, max, maxInclusive, path, modelPath, issues);
    }

    /// <summary>Checks the inclusive IEEE-754 ranks computed from the model's decimal bounds by the generator.</summary>
    public static void CheckFloatingRange(object? value, long minRank, long maxRank,
        string? min, bool minInclusive, string? max, bool maxInclusive, string path, string modelPath,
        ICollection<ValidationIssue> issues)
    {
        if (value is null)
        {
            return;
        }

        long? rank = value switch
        {
            float number when float.IsFinite(number) => SignedRank(BitConverter.SingleToInt32Bits(number), int.MaxValue),
            double number when double.IsFinite(number) => SignedRank(BitConverter.DoubleToInt64Bits(number), long.MaxValue),
            _ => null,
        };
        if (rank is null || rank < minRank || rank > maxRank)
        {
            AddRangeIssue(min, minInclusive, max, maxInclusive, path, modelPath, issues);
        }
    }

    private static long SignedRank(long bits, long magnitudeMask) => bits < 0 ? -(bits & magnitudeMask) : bits;

    private static void AddRangeIssue(string? min, bool minInclusive, string? max, bool maxInclusive,
        string path, string modelPath, ICollection<ValidationIssue> issues)
    {
        bool hasMin = !string.IsNullOrEmpty(min);
        bool hasMax = !string.IsNullOrEmpty(max);
        string lower = hasMin ? (minInclusive ? ">= " : "> ") + min : "";
        string upper = hasMax ? (maxInclusive ? "<= " : "< ") + max : "";
        string message = "a finite number " + lower + (hasMin && hasMax ? " and " : "") + upper;
        issues.Add(new ValidationIssue("range", path, message, "vmf.range", modelPath));
    }

    public static void CheckLength(string? value, int min, int max, string path, string modelPath,
        ICollection<ValidationIssue> issues)
    {
        if (value is null)
        {
            return;
        }

        int count = 0;
        foreach (var _ in value.EnumerateRunes())
        {
            count++;
        }
        if (count < min || (max >= 0 && count > max))
        {
            string message = min.ToString(CultureInfo.InvariantCulture) + ".."
                + (max < 0 ? "*" : max.ToString(CultureInfo.InvariantCulture)) + " Unicode code points";
            issues.Add(new ValidationIssue("length", path, message, "vmf.length", modelPath));
        }
    }

    public static void CheckPattern(string? value, string portablePattern, string path, string modelPath,
        ICollection<ValidationIssue> issues)
    {
        if (value is not null && !Regex.IsMatch(value, TranslatePortablePattern(portablePattern),
            RegexOptions.CultureInvariant))
        {
            string source = portablePattern.Replace("(?![\\s\\S])", "$", StringComparison.Ordinal);
            issues.Add(new ValidationIssue("pattern", path, $"a match for /{source}/", "vmf.pattern", modelPath));
        }
    }

    /// <summary>Checks an explicitly supplied CLR counterpart to a Java-only pattern.</summary>
    public static void CheckBoundPattern(string? value, string clrPattern, string javaPattern, string path,
        string modelPath, ICollection<ValidationIssue> issues)
    {
        if (value is not null && !Regex.IsMatch(value, clrPattern, RegexOptions.CultureInvariant))
        {
            issues.Add(new ValidationIssue("pattern", path, $"a match for /{javaPattern}/", "vmf.pattern", modelPath));
        }
    }

    private static string TranslatePortablePattern(string pattern)
    {
        if (!pattern.Contains("\\w", StringComparison.Ordinal) && !pattern.Contains("\\d", StringComparison.Ordinal))
        {
            return pattern;
        }

        // Java's portable escapes are ASCII. .NET's default and ECMAScript word classes differ.
        var result = new StringBuilder(pattern.Length);
        bool inClass = false;
        for (int index = 0; index < pattern.Length; index++)
        {
            char token = pattern[index];
            if (token == '\\' && index + 1 < pattern.Length)
            {
                char escaped = pattern[++index];
                string? ascii = escaped switch
                {
                    'd' => "0-9",
                    'w' => "A-Za-z0-9_",
                    _ => null,
                };
                if (ascii is null)
                {
                    result.Append(token).Append(escaped);
                }
                else if (!inClass)
                {
                    result.Append('[').Append(ascii).Append(']');
                }
                else
                {
                    result.Append(ascii);
                    if (index + 1 < pattern.Length && pattern[index + 1] == '-')
                    {
                        // An escape standing for a set is not a range endpoint in Java.
                        result.Append("\\-");
                        index++;
                    }
                }
            }
            else
            {
                if (token is '[' or ']')
                {
                    inClass = token == '[';
                }
                result.Append(token);
            }
        }
        return result.ToString();
    }

    private static string? IntegralText(object value)
    {
        var culture = CultureInfo.InvariantCulture;
        return value switch
        {
            sbyte number => number.ToString(culture),
            short number => number.ToString(culture),
            int number => number.ToString(culture),
            long number => number.ToString(culture),
            _ => null,
        };
    }

    // Compare significant digits and exponents without rounding the model's decimal bounds.
    private readonly record struct DecimalValue(int Sign, long DecimalPosition, string Digits)
    {
        internal static DecimalValue Parse(string text)
        {
            ReadOnlySpan<char> mantissa = text.AsSpan();
            int sign = 1;
            if (!mantissa.IsEmpty && mantissa[0] is '-' or '+')
            {
                sign = mantissa[0] == '-' ? -1 : 1;
                mantissa = mantissa[1..];
            }

            long exponent = 0;
            int exponentIndex = mantissa.IndexOfAny('e', 'E');
            if (exponentIndex >= 0)
            {
                exponent = long.Parse(mantissa[(exponentIndex + 1)..], NumberStyles.AllowLeadingSign,
                    CultureInfo.InvariantCulture);
                mantissa = mantissa[..exponentIndex];
            }

            int point = mantissa.IndexOf('.');
            string digits = point < 0 ? mantissa.ToString() : string.Concat(mantissa[..point], mantissa[(point + 1)..]);
            if (digits.Length == 0 || digits.Any(character => character is < '0' or > '9'))
            {
                throw new FormatException($"Invalid decimal value '{text}'.");
            }

            int first = 0;
            while (first < digits.Length && digits[first] == '0')
            {
                first++;
            }
            if (first == digits.Length)
            {
                return new DecimalValue(0, 0, "");
            }

            long decimalPosition = checked(exponent + (point < 0 ? digits.Length : point) - first);
            return new DecimalValue(sign, decimalPosition, digits[first..].TrimEnd('0'));
        }

        internal int CompareTo(DecimalValue other)
        {
            if (Sign != other.Sign || Sign == 0)
            {
                return Sign.CompareTo(other.Sign);
            }
            int position = DecimalPosition.CompareTo(other.DecimalPosition);
            return Sign * (position != 0 ? position : Math.Sign(string.CompareOrdinal(Digits, other.Digits)));
        }
    }
}
