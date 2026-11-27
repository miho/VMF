namespace Vmf.Runtime;

/// <summary>Locally registered codecs for immutable external journal values.</summary>
public sealed class JournalValueCodecs
{
    public const string UuidCodecId = "java.uuid/v1";

    public static JournalValueCodecs Empty { get; } = new(new(), new(StringComparer.Ordinal));

    /// <summary>The standard registry supports System.Guid using Java VMF's UUID wire codec ID.</summary>
    public static JournalValueCodecs Standard { get; } = Empty.WithImmutable<Guid>(
        UuidCodecId, static value => value.ToString("D"), static text => Guid.ParseExact(text, "D"));

    private readonly Dictionary<Type, Codec> byType;
    private readonly Dictionary<string, Codec> byId;

    private JournalValueCodecs(Dictionary<Type, Codec> byType, Dictionary<string, Codec> byId)
    {
        this.byType = byType;
        this.byId = byId;
    }

    /// <summary>
    /// Returns a registry with a deterministic canonical text codec for the exact immutable value type.
    /// The existing registry remains available for previously configured bindings.
    /// </summary>
    public JournalValueCodecs WithImmutable<T>(string id, Func<T, string> encode, Func<string, T> decode)
        where T : notnull
    {
        ChangeJournal.CheckName(id);
        ArgumentNullException.ThrowIfNull(encode);
        ArgumentNullException.ThrowIfNull(decode);
        Type type = typeof(T);
        if (type.IsPrimitive || type.IsAbstract || type.IsArray || type == typeof(string)
            || Nullable.GetUnderlyingType(type) is not null || typeof(IModelGraphAccess).IsAssignableFrom(type))
        {
            throw new ArgumentException($"'{type}' is not a concrete external journal value type.");
        }
        if (byType.ContainsKey(type) || byId.ContainsKey(id))
        {
            throw new ArgumentException($"External journal codec ID or type already registered for '{id}'.", nameof(id));
        }

        var codec = new Codec(id, type, value => encode((T)value), text => decode(text));
        return new JournalValueCodecs(new Dictionary<Type, Codec>(byType) { [type] = codec },
            new Dictionary<string, Codec>(byId, StringComparer.Ordinal) { [id] = codec });
    }

    internal JournalValue Encode(object? value) => value is not null && byType.TryGetValue(value.GetType(), out var codec)
        ? JournalValue.External(codec.Id, codec.Encode(value))
        : JournalValue.FromScalar(value);

    internal static bool RequiresCodec(Type type) =>
        !type.IsPrimitive && type != typeof(string) && Nullable.GetUnderlyingType(type)?.IsPrimitive != true;

    internal object Decode(JournalValue value)
    {
        if (!byId.TryGetValue(value.CodecId!, out var codec))
        {
            throw new InvalidOperationException($"Unknown external journal codec '{value.CodecId}'.");
        }

        object? decoded;
        try
        {
            decoded = codec.Decode(value.Text!);
        }
        catch (Exception error) when (error is ArgumentException or FormatException or OverflowException)
        {
            throw new InvalidOperationException($"Invalid external journal value for '{codec.Id}'.", error);
        }
        if (decoded is null || decoded.GetType() != codec.Type
            || !string.Equals(value.Text, codec.Encode(decoded), StringComparison.Ordinal))
        {
            throw new InvalidOperationException($"Invalid or noncanonical external journal value for '{codec.Id}'.");
        }
        return decoded;
    }

    private sealed record Codec(string Id, Type Type, Func<object, string> Encode, Func<string, object?> Decode);
}
