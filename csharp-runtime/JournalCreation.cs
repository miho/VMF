using System.Collections.ObjectModel;

namespace Vmf.Runtime;

/// <summary>The initial properties of an object first introduced by a journal edit.</summary>
public sealed class JournalCreation
{
    public JournalCreation(string id, string type, IReadOnlyDictionary<string, IReadOnlyList<JournalValue>> properties)
        : this(id, type, properties, null, null, null)
    {
    }

    public JournalCreation(string id, string type, IReadOnlyDictionary<string, IReadOnlyList<JournalValue>> properties,
        IReadOnlyDictionary<string, IReadOnlyList<JournalMapEntry>>? mapProperties = null,
        IReadOnlyDictionary<string, IReadOnlyList<JournalMapEntry>>? typedMapProperties = null,
        IReadOnlyDictionary<string, IReadOnlyList<JournalValue>>? setProperties = null)
    {
        Id = ChangeJournal.CheckName(id);
        Type = ChangeJournal.CheckName(type);
        ArgumentNullException.ThrowIfNull(properties);
        var names = new HashSet<string>(StringComparer.Ordinal);
        Properties = CopyNamed(properties, names, static values =>
        {
            if (values.Any(value => value is null))
            {
                throw new ArgumentException("Creation properties must contain tagged journal values.");
            }
        });
        MapProperties = CopyNamed(mapProperties, names, static entries => ValidateMap(entries, typed: false));
        TypedMapProperties = CopyNamed(typedMapProperties, names, static entries => ValidateMap(entries, typed: true));
        SetProperties = CopyNamed(setProperties, names, ValidateSet);
    }

    private static IReadOnlyDictionary<string, IReadOnlyList<T>> CopyNamed<T>(
        IReadOnlyDictionary<string, IReadOnlyList<T>>? source, HashSet<string> names, Action<T[]> validate)
    {
        var copy = new SortedDictionary<string, IReadOnlyList<T>>(StringComparer.Ordinal);
        if (source is not null)
        {
            foreach (var (name, items) in source)
            {
                ChangeJournal.CheckName(name);
                if (!names.Add(name)) throw new ArgumentException($"Duplicate journal creation property '{name}'.");
                ArgumentNullException.ThrowIfNull(items);
                var array = items.ToArray();
                validate(array);
                copy.Add(name, Array.AsReadOnly(array));
            }
        }
        return new ReadOnlyDictionary<string, IReadOnlyList<T>>(copy);
    }

    private static void ValidateMap(JournalMapEntry[] entries, bool typed)
    {
        if (entries.Length == 0) return;
        string? kind = entries[0]?.Key?.Kind;
        if (kind is null or "NULL" || typed == (kind == "STRING")
            || entries.Any(entry => entry?.Key is null || entry.Value is null
                || entry.Key.IsReference || entry.Key.IsExternal || entry.Key.Kind != kind
                || entry.Value.IsReference || entry.Value.IsExternal)
            || entries.Select(entry => entry.Key.Text).Distinct(StringComparer.Ordinal).Count() != entries.Length)
        {
            throw new ArgumentException("Creation maps need one scalar key kind, distinct non-null keys, and scalar values.");
        }
    }

    private static void ValidateSet(JournalValue[] values)
    {
        if (values.Length == 0) return;
        string? kind = values[0]?.Kind;
        if (kind is null or "NULL" || values.Any(value => value is null || value.IsReference || value.IsExternal || value.Kind != kind)
            || values.Select(value => value.Text).Distinct(StringComparer.Ordinal).Count() != values.Length)
        {
            throw new ArgumentException("Creation sets need distinct non-null members of one scalar kind.");
        }
    }

    public string Id { get; }
    public string Type { get; }
    public IReadOnlyDictionary<string, IReadOnlyList<JournalValue>> Properties { get; }
    public IReadOnlyDictionary<string, IReadOnlyList<JournalMapEntry>> MapProperties { get; }
    public IReadOnlyDictionary<string, IReadOnlyList<JournalMapEntry>> TypedMapProperties { get; }
    public IReadOnlyDictionary<string, IReadOnlyList<JournalValue>> SetProperties { get; }
}
