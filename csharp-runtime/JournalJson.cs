using System.Text;
using System.Text.Json;

namespace Vmf.Runtime;

/// <summary>Reads and writes the canonical JSON representation of portable VMF journals.</summary>
public static class JournalJson
{
    private const string Format = "vmf-journal";

    public static string Write(ChangeJournal journal)
    {
        ArgumentNullException.ThrowIfNull(journal);
        using var output = new MemoryStream();
        using (var writer = new Utf8JsonWriter(output, new JsonWriterOptions { Indented = true }))
        {
            writer.WriteStartObject();
            writer.WriteString("format", Format);
            writer.WriteNumber("version", journal.WireVersion);
            writer.WriteStartObject("model");
            writer.WriteString("id", journal.Model);
            writer.WriteNumber("version", journal.ModelVersion);
            writer.WriteEndObject();
            writer.WriteString("root", journal.Root);
            writer.WriteStartObject("base");
            writer.WriteString("checkpoint", journal.Checkpoint);
            writer.WriteNumber("sequence", journal.BaseSequence);
            writer.WriteEndObject();
            writer.WriteStartArray("transactions");
            foreach (var frame in journal.Transactions)
            {
                writer.WriteStartObject();
                writer.WriteNumber("sequence", frame.Sequence);
                writer.WriteStartArray("operations");
                foreach (var operation in frame.Operations)
                {
                    WriteOperation(writer, operation);
                }
                writer.WriteEndArray();
                writer.WriteEndObject();
            }
            writer.WriteEndArray();
            writer.WriteEndObject();
        }
        return Encoding.UTF8.GetString(output.ToArray());
    }

    public static ChangeJournal Read(string json)
    {
        ArgumentNullException.ThrowIfNull(json);
        using var document = JsonDocument.Parse(json);
        var root = document.RootElement;
        try
        {
            CheckFields(root, "format", "version", "model", "root", "base", "transactions");
            if (root.GetProperty("format").GetString() != Format
                || root.GetProperty("version").GetInt32() != ChangeJournal.Version)
            {
                throw new JsonException("Unsupported VMF journal format or version.");
            }
            var model = root.GetProperty("model");
            CheckFields(model, "id", "version");
            var baseline = root.GetProperty("base");
            CheckFields(baseline, "checkpoint", "sequence");
            return new ChangeJournal(ReadText(model, "id"), model.GetProperty("version").GetInt32(), ReadText(root, "root"),
                ReadText(baseline, "checkpoint"), baseline.GetProperty("sequence").GetInt64(),
                root.GetProperty("transactions").EnumerateArray().Select(ReadFrame));
        }
        catch (Exception error) when (error is ArgumentException or InvalidOperationException or FormatException or OverflowException)
        {
            throw new JsonException("Invalid VMF journal.", error);
        }
    }

    private static JournalFrame ReadFrame(JsonElement frame)
    {
        CheckFields(frame, "sequence", "operations");
        return new JournalFrame(frame.GetProperty("sequence").GetInt64(),
            frame.GetProperty("operations").EnumerateArray().Select(ReadOperation));
    }

    private static JournalOperation ReadOperation(JsonElement operation)
    {
        if (!operation.TryGetProperty("kind", out _))
        {
            throw new JsonException("Missing journal operation kind.");
        }
        string kind = ReadText(operation, "kind");
        if (kind is "MAP_EDIT" or "TYPED_MAP_EDIT")
        {
            CheckFields(operation, "kind", "target", "property", "beforeEntries", "afterEntries");
            bool typed = kind == "TYPED_MAP_EDIT";
            return new JournalMapEdit(ReadText(operation, "target"), ReadText(operation, "property"), typed,
                ReadMapEntries(operation.GetProperty("beforeEntries"), typed),
                ReadMapEntries(operation.GetProperty("afterEntries"), typed));
        }

        if (kind == "SET_EDIT")
        {
            CheckFields(operation, "kind", "target", "property", "before", "after");
            return new JournalMembershipEdit(ReadText(operation, "target"), ReadText(operation, "property"),
                references: false, operation.GetProperty("before").EnumerateArray().Select(ReadValue),
                operation.GetProperty("after").EnumerateArray().Select(ReadValue));
        }

        CheckFields(operation, "kind", "target", "property", "indices", "before", "after", "created");
        var created = operation.GetProperty("created").EnumerateArray().Select(ReadCreation).ToArray();
        string target = ReadText(operation, "target");
        string property = ReadText(operation, "property");
        var indices = operation.GetProperty("indices").EnumerateArray().Select(index => index.GetInt32()).ToArray();
        var before = operation.GetProperty("before").EnumerateArray().Select(ReadValue).ToArray();
        var after = operation.GetProperty("after").EnumerateArray().Select(ReadValue).ToArray();
        if (kind == "SET")
        {
            if (indices.Length != 0 || before.Length != 1 || after.Length != 1)
            {
                throw new JsonException("A scalar SET expects one value at each side of the edit and no positions.");
            }
            return new JournalSet(target, property, before[0], after[0], created);
        }
        if (kind == "REF_SET_EDIT")
        {
            if (indices.Length != 0)
            {
                throw new JsonException("A journal membership edit has no positions.");
            }
            return new JournalMembershipEdit(target, property, references: true, before, after, created);
        }
        var listKind = kind switch
        {
            "LIST_ADD" => JournalListEditKind.Add,
            "LIST_REMOVE" => JournalListEditKind.Remove,
            "LIST_SET" => JournalListEditKind.Set,
            _ => throw new JsonException($"Unsupported VMF journal operation '{kind}'."),
        };
        return new JournalListEdit(listKind, target, property, indices, before, after, created);
    }

    private static JournalCreation ReadCreation(JsonElement creation)
    {
        var fields = new List<string> { "id", "type", "properties" };
        foreach (var section in new[] { "mapProperties", "typedMapProperties", "setProperties" })
        {
            if (creation.TryGetProperty(section, out _)) fields.Add(section);
        }
        CheckFields(creation, fields.ToArray());
        var properties = new Dictionary<string, IReadOnlyList<JournalValue>>(StringComparer.Ordinal);
        foreach (var field in creation.GetProperty("properties").EnumerateObject())
        {
            if (!properties.TryAdd(field.Name, field.Value.EnumerateArray().Select(ReadValue).ToArray()))
            {
                throw new JsonException($"Duplicate creation property '{field.Name}'.");
            }
        }
        return new JournalCreation(ReadText(creation, "id"), ReadText(creation, "type"), properties,
            ReadNamedCreationValues(creation, "mapProperties", "entries", static field => ReadMapEntries(field, typed: false)),
            ReadNamedCreationValues(creation, "typedMapProperties", "entries", static field => ReadMapEntries(field, typed: true)),
            ReadNamedCreationValues(creation, "setProperties", "values", static field => field.EnumerateArray().Select(ReadValue)));
    }

    private static IReadOnlyDictionary<string, IReadOnlyList<T>>? ReadNamedCreationValues<T>(JsonElement creation,
        string section, string payload, Func<JsonElement, IEnumerable<T>> read)
    {
        if (!creation.TryGetProperty(section, out var value)) return null;
        var result = new Dictionary<string, IReadOnlyList<T>>(StringComparer.Ordinal);
        foreach (var field in value.EnumerateArray())
        {
            CheckFields(field, "name", payload);
            string name = ReadText(field, "name");
            if (!result.TryAdd(name, read(field.GetProperty(payload)).ToArray()))
            {
                throw new JsonException($"Duplicate journal creation property '{name}'.");
            }
        }
        if (result.Count == 0) throw new JsonException($"Expected at least one journal creation property in '{section}'.");
        return result;
    }

    private static JournalValue ReadValue(JsonElement value)
    {
        if (!value.TryGetProperty("kind", out _)) throw new JsonException("Missing journal value kind.");
        string kind = ReadText(value, "kind");
        if (kind == "EXTERNAL")
        {
            CheckFields(value, "kind", "text", "codec");
            return JournalValue.External(ReadText(value, "codec"), ReadText(value, "text"));
        }
        CheckFields(value, "kind", "text");
        return new JournalValue(kind, value.GetProperty("text").GetString());
    }

    private static IEnumerable<JournalMapEntry> ReadMapEntries(JsonElement entries, bool typed)
    {
        string keyName = typed ? "typedKey" : "key";
        foreach (var entry in entries.EnumerateArray())
        {
            CheckFields(entry, keyName, "value");
            var key = typed ? ReadValue(entry.GetProperty(keyName)) : JournalValue.FromScalar(ReadText(entry, keyName));
            yield return new JournalMapEntry(key, ReadValue(entry.GetProperty("value")));
        }
    }

    private static void WriteOperation(Utf8JsonWriter writer, JournalOperation operation)
    {
        writer.WriteStartObject();
        string kind;
        IReadOnlyList<int> indices;
        IReadOnlyList<JournalValue> before;
        IReadOnlyList<JournalValue> after;
        if (operation is JournalSet set)
        {
            kind = "SET";
            indices = [];
            before = [set.Before];
            after = [set.After];
        }
        else if (operation is JournalMembershipEdit membership)
        {
            if (!membership.References)
            {
                writer.WriteString("kind", "SET_EDIT");
                writer.WriteString("target", operation.Target);
                writer.WriteString("property", operation.Property);
                WriteValues(writer, "before", membership.Removed);
                WriteValues(writer, "after", membership.Added);
                writer.WriteEndObject();
                return;
            }
            kind = "REF_SET_EDIT";
            indices = [];
            before = membership.Removed;
            after = membership.Added;
        }
        else if (operation is JournalMapEdit map)
        {
            writer.WriteString("kind", map.TypedKeys ? "TYPED_MAP_EDIT" : "MAP_EDIT");
            writer.WriteString("target", operation.Target);
            writer.WriteString("property", operation.Property);
            WriteMapEntries(writer, "beforeEntries", map.Before, map.TypedKeys);
            WriteMapEntries(writer, "afterEntries", map.After, map.TypedKeys);
            writer.WriteEndObject();
            return;
        }
        else if (operation is JournalListEdit list)
        {
            kind = list.Kind switch
            {
                JournalListEditKind.Add => "LIST_ADD",
                JournalListEditKind.Remove => "LIST_REMOVE",
                JournalListEditKind.Set => "LIST_SET",
                _ => throw new InvalidOperationException("Unsupported list journal operation."),
            };
            indices = list.Indices;
            before = list.Before;
            after = list.After;
        }
        else
        {
            throw new InvalidOperationException("Unsupported journal operation.");
        }

        writer.WriteString("kind", kind);
        writer.WriteString("target", operation.Target);
        writer.WriteString("property", operation.Property);
        writer.WriteStartArray("indices");
        foreach (var index in indices)
        {
            writer.WriteNumberValue(index);
        }
        writer.WriteEndArray();
        WriteValues(writer, "before", before);
        WriteValues(writer, "after", after);
        WriteCreations(writer, operation.Created);
        writer.WriteEndObject();
    }

    private static void WriteValues(Utf8JsonWriter writer, string field, IReadOnlyList<JournalValue> values)
    {
        writer.WriteStartArray(field);
        foreach (var value in values)
        {
            WriteValue(writer, value);
        }
        writer.WriteEndArray();
    }

    private static void WriteMapEntries(Utf8JsonWriter writer, string field, IReadOnlyList<JournalMapEntry> entries,
        bool typed)
    {
        writer.WriteStartArray(field);
        foreach (var entry in entries)
        {
            writer.WriteStartObject();
            if (typed)
            {
                writer.WritePropertyName("typedKey");
                WriteValue(writer, entry.Key);
            }
            else
            {
                writer.WriteString("key", entry.Key.Text);
            }
            writer.WritePropertyName("value");
            WriteValue(writer, entry.Value);
            writer.WriteEndObject();
        }
        writer.WriteEndArray();
    }

    private static void WriteValue(Utf8JsonWriter writer, JournalValue value)
    {
        writer.WriteStartObject();
        writer.WriteString("kind", value.Kind);
        writer.WriteString("text", value.Text);
        if (value.IsExternal) writer.WriteString("codec", value.CodecId);
        writer.WriteEndObject();
    }

    private static void WriteCreations(Utf8JsonWriter writer, IReadOnlyList<JournalCreation> creations)
    {
        writer.WriteStartArray("created");
        foreach (var creation in creations)
        {
            writer.WriteStartObject();
            writer.WriteString("id", creation.Id);
            writer.WriteString("type", creation.Type);
            writer.WriteStartObject("properties");
            foreach (var field in creation.Properties)
            {
                WriteValues(writer, field.Key, field.Value);
            }
            writer.WriteEndObject();
            WriteCreationMaps(writer, "mapProperties", creation.MapProperties, typed: false);
            WriteCreationMaps(writer, "typedMapProperties", creation.TypedMapProperties, typed: true);
            if (creation.SetProperties.Count != 0)
            {
                writer.WriteStartArray("setProperties");
                foreach (var (name, values) in creation.SetProperties)
                {
                    writer.WriteStartObject();
                    writer.WriteString("name", name);
                    WriteValues(writer, "values", values);
                    writer.WriteEndObject();
                }
                writer.WriteEndArray();
            }
            writer.WriteEndObject();
        }
        writer.WriteEndArray();
    }

    private static void WriteCreationMaps(Utf8JsonWriter writer, string section,
        IReadOnlyDictionary<string, IReadOnlyList<JournalMapEntry>> maps, bool typed)
    {
        if (maps.Count == 0) return;
        writer.WriteStartArray(section);
        foreach (var (name, entries) in maps)
        {
            writer.WriteStartObject();
            writer.WriteString("name", name);
            WriteMapEntries(writer, "entries", entries, typed);
            writer.WriteEndObject();
        }
        writer.WriteEndArray();
    }

    private static string ReadText(JsonElement node, string field) =>
        node.GetProperty(field).GetString() ?? throw new JsonException($"Expected text in journal field '{field}'.");

    private static void CheckFields(JsonElement node, params string[] fields)
    {
        var expected = new HashSet<string>(fields, StringComparer.Ordinal);
        foreach (var field in node.EnumerateObject())
        {
            if (!expected.Remove(field.Name))
            {
                throw new JsonException($"Unexpected or repeated journal field '{field.Name}'.");
            }
        }
        if (expected.Count != 0)
        {
            throw new JsonException($"Missing journal field '{expected.First()}'.");
        }
    }
}
