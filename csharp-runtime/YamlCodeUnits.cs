using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using YamlDotNet.Core;
using YamlDotNet.Core.Events;

namespace Vmf.Runtime;

// Jackson represents isolated Java Character values with whole-scalar \u escapes.
// YamlDotNet validates Unicode scalars, so pass these code units through temporarily.
internal static class YamlCodeUnits
{
    private const string EscapePrefix = "\"\\u";
    private static readonly UTF8Encoding StrictUtf8 = new(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);

    internal static (string Text, IReadOnlyDictionary<long, char>? CodeUnits) PrepareInput(string source)
    {
        Dictionary<long, char>? positions = null;
        char[]? prepared = null;
        for (int index = source.IndexOf(EscapePrefix, StringComparison.Ordinal); index >= 0;
            index = source.IndexOf(EscapePrefix, index + EscapePrefix.Length, StringComparison.Ordinal))
        {
            if (!TryCodeUnit(source.AsSpan(index), out char value)) continue;
            positions ??= new Dictionary<long, char>();
            prepared ??= source.ToCharArray();
            positions.Add(index, value);
            "e000".AsSpan().CopyTo(prepared.AsSpan(index + EscapePrefix.Length, 4));
        }
        if (positions is null) return (source, null);

        // Let the parser identify actual complete quoted scalars. Identical text in
        // comments, block strings, or escaped content must retain its original spelling.
        var confirmed = new Dictionary<long, char>();
        using (var text = new StringReader(new string(prepared!)))
        {
            var parser = new Parser(text);
            while (parser.MoveNext())
            {
                if (parser.Current is Scalar { Style: ScalarStyle.DoubleQuoted, Value: "\uE000" } scalar
                    && scalar.End.Index - scalar.Start.Index == 8
                    && positions.TryGetValue(scalar.Start.Index, out char value))
                {
                    confirmed.Add(scalar.Start.Index, value);
                }
            }
        }
        if (confirmed.Count == 0) return (source, null);
        foreach (long index in positions.Keys)
        {
            if (!confirmed.ContainsKey(index))
            {
                int offset = (int)index + EscapePrefix.Length;
                source.AsSpan(offset, 4).CopyTo(prepared!.AsSpan(offset, 4));
            }
        }
        return (new string(prepared!), confirmed);
    }

    internal static void RequireUnicode(Scalar scalar)
    {
        try
        {
            StrictUtf8.GetByteCount(scalar.Value);
        }
        catch (EncoderFallbackException error)
        {
            throw new YamlException(scalar.Start, scalar.End, "Invalid Unicode in a VMF YAML string.", error);
        }
    }

    private static bool TryCodeUnit(ReadOnlySpan<char> text, out char codeUnit)
    {
        codeUnit = default;
        if (text.Length < 8 || text[7] != '"' || !text.StartsWith(EscapePrefix, StringComparison.Ordinal)
            || !ushort.TryParse(text.Slice(EscapePrefix.Length, 4), NumberStyles.AllowHexSpecifier,
                CultureInfo.InvariantCulture, out ushort value) || !char.IsSurrogate((char)value))
        {
            return false;
        }
        codeUnit = (char)value;
        return true;
    }

    internal sealed class Output(string json)
    {
        private readonly Dictionary<string, char> codeUnits = new(StringComparer.Ordinal);
        private string? prefix;

        internal string GetScalar(JsonElement scalar)
        {
            string raw = scalar.GetRawText();
            if (raw.Length != 8 || !TryCodeUnit(raw, out char codeUnit))
            {
                return scalar.GetString()!;
            }

            if (prefix is null)
            {
                do
                {
                    prefix = "VmfYamlChar_" + Guid.NewGuid().ToString("N") + "_";
                }
                while (json.Contains(prefix, StringComparison.Ordinal));
            }
            string marker = prefix + codeUnits.Count.ToString(CultureInfo.InvariantCulture) + "_";
            codeUnits.Add(marker, codeUnit);
            return marker;
        }

        internal string Complete(string yaml)
        {
            if (prefix is null) return yaml;

            int replaced = 0;
            var result = Regex.Replace(yaml, "\"(" + Regex.Escape(prefix) + "[0-9]+_)\"", match =>
            {
                replaced++;
                return $"\"\\u{(int)codeUnits[match.Groups[1].Value]:x4}\"";
            }, RegexOptions.CultureInvariant);
            if (replaced != codeUnits.Count)
            {
                throw new YamlException("Could not emit a VMF character code unit.");
            }
            return result;
        }
    }
}
