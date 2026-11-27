namespace Vmf.Runtime;

/// <summary>A tagged value in the portable VMF journal format.</summary>
public sealed class JournalValue
{
    public JournalValue(string kind, string? text)
        : this(kind, text, null)
    {
    }

    private JournalValue(string kind, string? text, string? codecId)
    {
        Kind = kind;
        Text = text;
        if (kind == "EXTERNAL")
        {
            CodecId = ChangeJournal.CheckName(codecId ?? throw new ArgumentException("External journal values require a codec ID."));
            Text = JournalScalar.CheckedUnicode(text ?? throw new ArgumentNullException(nameof(text)));
            return;
        }
        Value = kind is "REFERENCE" or "IMMUTABLE_REFERENCE" ? ChangeJournal.CheckName(text ?? throw new FormatException("Missing reference ID."))
            : JournalScalar.Decode(kind, text);
    }

    public string Kind { get; }
    public string? Text { get; }
    public string? CodecId { get; }
    internal object? Value { get; }
    public bool IsReference => Kind is "REFERENCE" or "IMMUTABLE_REFERENCE";
    public bool IsImmutableReference => Kind == "IMMUTABLE_REFERENCE";
    public bool IsExternal => Kind == "EXTERNAL";

    public static JournalValue Reference(string id) => new("REFERENCE", id);
    public static JournalValue ImmutableReference(string id) => new("IMMUTABLE_REFERENCE", id);
    public static JournalValue External(string codecId, string text) => new("EXTERNAL", text, codecId);

    public static JournalValue FromScalar(object? value)
    {
        var (kind, text) = JournalScalar.Encode(value);
        return new JournalValue(kind, text);
    }
}

/// <summary>An edit addressed by checkpoint object ID and stable property key.</summary>
public abstract class JournalOperation
{
    internal JournalOperation(string target, string property, IEnumerable<JournalCreation>? created = null)
    {
        Target = ChangeJournal.CheckName(target);
        Property = ChangeJournal.CheckName(property);
        Created = Array.AsReadOnly(created?.ToArray() ?? []);
        if (Created.Any(item => item is null) || Created.Select(item => item.Id).Distinct(StringComparer.Ordinal).Count() != Created.Count)
        {
            throw new ArgumentException("Journal creations need unique object IDs.", nameof(created));
        }
    }

    public string Target { get; }
    public string Property { get; }
    public IReadOnlyList<JournalCreation> Created { get; }
}

/// <summary>A single-valued property replacement with its expected old value.</summary>
public sealed class JournalSet : JournalOperation
{
    public JournalSet(string target, string property, JournalValue before, JournalValue after)
        : this(target, property, before, after, [])
    {
    }

    public JournalSet(string target, string property, JournalValue before, JournalValue after,
        IEnumerable<JournalCreation> created)
        : base(target, property, created)
    {
        Before = before ?? throw new ArgumentNullException(nameof(before));
        After = after ?? throw new ArgumentNullException(nameof(after));
    }

    public JournalValue Before { get; }
    public JournalValue After { get; }
}

/// <summary>The positional operations in a VMF journal.</summary>
public enum JournalListEditKind
{
    Add,
    Remove,
    Set,
}

/// <summary>A positional batch on a modeled sequence or ordered identity set.</summary>
public sealed class JournalListEdit : JournalOperation
{
    public JournalListEdit(JournalListEditKind kind, string target, string property, IEnumerable<int> indices,
        IEnumerable<JournalValue> before, IEnumerable<JournalValue> after)
        : this(kind, target, property, indices, before, after, [])
    {
    }

    public JournalListEdit(JournalListEditKind kind, string target, string property, IEnumerable<int> indices,
        IEnumerable<JournalValue> before, IEnumerable<JournalValue> after, IEnumerable<JournalCreation> created)
        : base(target, property, created)
    {
        if (!Enum.IsDefined(kind))
        {
            throw new ArgumentOutOfRangeException(nameof(kind));
        }
        Kind = kind;
        Indices = Array.AsReadOnly(indices.ToArray());
        Before = Array.AsReadOnly(before.ToArray());
        After = Array.AsReadOnly(after.ToArray());

        int preceding = -1;
        foreach (int index in Indices)
        {
            if (index <= preceding)
            {
                throw new ArgumentException("Journal indices must be increasing and nonnegative.", nameof(indices));
            }
            preceding = index;
        }

        int count = Indices.Count;
        if (count == 0 || Before.Count != (kind == JournalListEditKind.Add ? 0 : count)
            || After.Count != (kind == JournalListEditKind.Remove ? 0 : count))
        {
            throw new ArgumentException("The journal list values do not match the edit's operation and positions.");
        }
    }

    public JournalListEditKind Kind { get; }
    public IReadOnlyList<int> Indices { get; }
    public IReadOnlyList<JournalValue> Before { get; }
    public IReadOnlyList<JournalValue> After { get; }
}

/// <summary>An ordered group of operations published at a single sequence number.</summary>
public sealed class JournalFrame
{
    public JournalFrame(long sequence, IEnumerable<JournalOperation> operations)
    {
        Sequence = sequence;
        Operations = Array.AsReadOnly(operations.ToArray());
        if (Operations.Count == 0)
        {
            throw new ArgumentException("A journal frame needs at least one operation.", nameof(operations));
        }
    }

    public long Sequence { get; }
    public IReadOnlyList<JournalOperation> Operations { get; }
}

/// <summary>Portable, immutable changes recorded from a VMF checkpoint.</summary>
public sealed class ChangeJournal
{
    public const int Version = 1;

    public ChangeJournal(string model, int modelVersion, string root, string checkpoint, long baseSequence,
        IEnumerable<JournalFrame> transactions)
    {
        Model = CheckName(model);
        Root = CheckName(root);
        Checkpoint = CheckName(checkpoint);
        if (modelVersion < 1)
        {
            throw new ArgumentOutOfRangeException(nameof(modelVersion));
        }
        if (baseSequence < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(baseSequence));
        }
        ModelVersion = modelVersion;
        BaseSequence = baseSequence;
        Transactions = Array.AsReadOnly(transactions.ToArray());

        long sequence = baseSequence;
        foreach (var frame in Transactions)
        {
            if (sequence == long.MaxValue || frame.Sequence != ++sequence)
            {
                throw new ArgumentException("Journal transaction sequence numbers must be consecutive.", nameof(transactions));
            }
        }
    }

    public string Model { get; }
    public int ModelVersion { get; }
    public string Root { get; }
    public string Checkpoint { get; }
    public long BaseSequence { get; }
    public IReadOnlyList<JournalFrame> Transactions { get; }

    public int WireVersion => Version;

    internal static string CheckName(string name)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(name);
        return JournalScalar.CheckedUnicode(name);
    }
}
