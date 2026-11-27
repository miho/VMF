using System.Collections;
using System.Globalization;
using System.Text;

namespace Vmf.Runtime;

public sealed partial class ModelReflectionField
{
    /// <summary>Excludes this property from the generated model's diagnostic summary.</summary>
    public bool IgnoreToString { get; init; }
}

public sealed partial class ModelReflectionRegistry
{
    /// <summary>Formats a model or a read-only view in the VMF diagnostic summary layout.</summary>
    /// <remarks>External values supply their own text and unordered collections retain their iteration order.
    /// The layout is intended for diagnostics and is independent of snapshot serialization.</remarks>
    public string FormatSummary(object model) => new SummaryWriter(this).Format(Bind(model));

    private sealed class SummaryWriter(ModelReflectionRegistry registry)
    {
        private readonly StringBuilder text = new();
        private readonly Stack<Action> pending = new();
        private readonly HashSet<object> seenMutable = new(ReferenceEqualityComparer.Instance);
        private readonly HashSet<object> activeImmutable = new(ReferenceEqualityComparer.Instance);

        internal string Format(ModelBinding root)
        {
            AppendModel(root);
            while (pending.TryPop(out var part)) part();
            return text.ToString();
        }

        private void AppendModel(ModelBinding node)
        {
            bool immutable = node.Schema.Snapshot.IsImmutable;
            if (!(immutable ? activeImmutable : seenMutable).Add(node.Identity))
            {
                text.Append("{skipping recursion}");
                return;
            }

            string name = node.Schema.Snapshot.WireName;
            int generic = name.IndexOf('<');
            var declaration = generic < 0 ? name.AsSpan() : name.AsSpan(0, generic);
            text.Append("{\"@type\":\"").Append(declaration[(declaration.LastIndexOf('.') + 1)..]).Append('"');
            pending.Push(() =>
            {
                text.Append('}');
                if (immutable) activeImmutable.Remove(node.Identity);
            });
            for (int index = node.Schema.Fields.Count - 1; index >= 0; index--)
            {
                var field = node.Schema.Fields[index];
                if (field.Snapshot is not null && !field.Field.IgnoreToString)
                {
                    pending.Push(() => AppendField(node.Identity, field));
                }
            }
        }

        private void AppendField(object owner, PropertySchema field)
        {
            var property = field.Snapshot!;
            object? value = field.MutableGet(owner);
            text.Append(", \"").Append(property.ModelName).Append("\": ");
            if (property.IsMap)
            {
                AppendMap(value as ISnapshotScalarMap);
            }
            else if (property.IsCollection)
            {
                AppendCollection(property, value as IEnumerable);
            }
            else
            {
                text.Append('"');
                pending.Push(() => text.Append('"'));
                AppendValue(value, property.IsContainment || property.IsReference);
            }
        }

        private void AppendCollection(SnapshotProperty property, IEnumerable? source)
        {
            bool quote = property.Kind is SnapshotPropertyKind.OrderedScalarSet or SnapshotPropertyKind.ScalarSet
                || (property.Kind == SnapshotPropertyKind.ScalarList && property.UpperBound >= 0);
            if (quote) text.Append('"');
            text.Append('[');
            if (property.IsScalarCollection)
            {
                bool first = true;
                foreach (var member in source ?? Array.Empty<object>())
                {
                    if (!first) text.Append(", ");
                    AppendScalar(member);
                    first = false;
                }
                text.Append(']');
                if (quote) text.Append('"');
                return;
            }

            var members = source?.Cast<object?>().ToArray() ?? [];
            pending.Push(() => text.Append(']'));
            for (int index = members.Length - 1; index >= 0; index--)
            {
                if (index != members.Length - 1) pending.Push(() => text.Append(", "));
                object? member = members[index];
                pending.Push(() => AppendValue(member, modelValue: true));
            }
        }

        private void AppendMap(ISnapshotScalarMap? map)
        {
            text.Append("\"{");
            bool first = true;
            foreach (var (key, value) in map?.Entries ?? [])
            {
                if (!first) text.Append(", ");
                AppendScalar(key);
                text.Append('=');
                AppendScalar(value);
                first = false;
            }
            text.Append("}\"");
        }

        private void AppendValue(object? value, bool modelValue)
        {
            if (modelValue && value is not null) AppendModel(registry.Bind(value));
            else AppendScalar(value);
        }

        private void AppendScalar(object? value)
        {
            switch (value)
            {
                case null: text.Append("null"); break;
                case bool flag: text.Append(flag ? "true" : "false"); break;
                case float number: AppendFloatingPoint(number, number.ToString("R", CultureInfo.InvariantCulture), single: true); break;
                case double number: AppendFloatingPoint(number, number.ToString("R", CultureInfo.InvariantCulture), single: false); break;
                case sbyte or short or int or long: text.Append(Convert.ToString(value, CultureInfo.InvariantCulture)); break;
                default: text.Append(value); break;
            }
        }

        private void AppendFloatingPoint(double value, string shortest, bool single)
        {
            if (!double.IsFinite(value))
            {
                text.Append(shortest);
                return;
            }
            if (double.IsNegative(value)) text.Append('-');
            if (value == 0)
            {
                text.Append("0.0");
                return;
            }

            var (digits, position) = FloatingDigits(shortest);
            if (digits.Length == 1)
            {
                // Java chooses the closest of the one- and two-digit round-tripping decimals.
                var twoDigits = value.ToString("E1", CultureInfo.InvariantCulture);
                double rounded = single ? float.Parse(twoDigits, CultureInfo.InvariantCulture)
                    : double.Parse(twoDigits, CultureInfo.InvariantCulture);
                if (value == rounded) (digits, position) = FloatingDigits(twoDigits);
            }

            if (position is < -2 or > 7)
            {
                text.Append(digits[0]).Append('.');
                if (digits.Length == 1) text.Append('0');
                else text.Append(digits.AsSpan(1));
                text.Append('E').Append((position - 1).ToString(CultureInfo.InvariantCulture));
            }
            else if (position <= 0)
            {
                text.Append("0.").Append('0', -position).Append(digits);
            }
            else if (position >= digits.Length)
            {
                text.Append(digits).Append('0', position - digits.Length).Append(".0");
            }
            else
            {
                text.Append(digits.AsSpan(0, position)).Append('.').Append(digits.AsSpan(position));
            }
        }

        private static (string Digits, int Position) FloatingDigits(string source)
        {
            var number = source[0] == '-' ? source.AsSpan(1) : source.AsSpan();
            int e = number.IndexOf('E');
            int exponent = e < 0 ? 0 : int.Parse(number[(e + 1)..], CultureInfo.InvariantCulture);
            var mantissa = e < 0 ? number : number[..e];
            int dot = mantissa.IndexOf('.');
            int position = exponent + (dot < 0 ? mantissa.Length : dot);
            string untrimmed = mantissa.ToString().Replace(".", "", StringComparison.Ordinal);
            string digits = untrimmed.TrimStart('0');
            return (digits.TrimEnd('0'), position - (untrimmed.Length - digits.Length));
        }
    }
}
