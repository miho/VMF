namespace Vmf.Runtime;

/// <summary>Copies and compares collections used by immutable VMF values.</summary>
public static class ImmutableCollections
{
    public static IReadOnlyScalarList<T> CopyScalarList<T>(IEnumerable<T>? values, int upperBound = -1,
        bool unique = false, bool allowNull = false)
    {
        var copy = new ScalarList<T>(upperBound, unique, allowNull);
        foreach (var value in values ?? Enumerable.Empty<T>())
        {
            if (!copy.Add(value)) throw new ArgumentException("Duplicate member in the immutable ordered scalar set.", nameof(values));
        }
        return copy.AsReadOnly();
    }

    public static IReadOnlyScalarSet<T> CopyScalarSet<T>(IEnumerable<T>? values, int upperBound = -1)
    {
        var copy = new ScalarSet<T>(upperBound);
        if (values is not null) copy.AddRange(values);
        return copy.AsReadOnly();
    }

    public static IReadOnlyDictionary<TKey, TValue> CopyScalarMap<TKey, TValue>(
        IEnumerable<KeyValuePair<TKey, TValue>>? values, int upperBound = -1) where TKey : notnull
    {
        var copy = new ScalarMap<TKey, TValue>(upperBound);
        foreach (var (key, value) in values ?? Enumerable.Empty<KeyValuePair<TKey, TValue>>()) copy.Add(key, value);
        return copy.AsReadOnly();
    }

    public static IReadOnlyScalarList<T> AppendOrderedScalarSet<T>(
        IEnumerable<T> staged, IEnumerable<T> incoming, int upperBound)
    {
        var copy = new ScalarList<T>(upperBound, unique: true);
        copy.AddRange(staged);
        copy.AddRange(incoming);
        return copy.AsReadOnly();
    }

    public static IReadOnlyDictionary<TKey, TValue> AppendScalarMap<TKey, TValue>(
        IEnumerable<KeyValuePair<TKey, TValue>> staged,
        IEnumerable<KeyValuePair<TKey, TValue>> incoming, int upperBound) where TKey : notnull
    {
        var copy = new ScalarMap<TKey, TValue>(upperBound);
        copy.PutAll(staged);
        copy.PutAll(incoming);
        return copy.AsReadOnly();
    }

    public static IReadOnlyList<T?> CopyReferenceList<T>(IEnumerable<T?>? values) where T : class =>
        Array.AsReadOnly(values?.ToArray() ?? []);

    public static IReadOnlyOrderedReferenceSet<T> CopyOrderedReferenceSet<T>(IEnumerable<T>? values) where T : class
    {
        var copy = new OrderedReferenceSet<T>();
        foreach (var value in values ?? Enumerable.Empty<T>())
        {
            if (!copy.Add(value)) throw new ArgumentException("Duplicate identity in the immutable ordered reference set.", nameof(values));
        }
        return new ReadOnlyOrderedReferenceSet<T, T>(copy, static item => item);
    }

    public static IReadOnlyReferenceSet<T> CopyReferenceSet<T>(IEnumerable<T>? values) where T : class
    {
        var copy = new ReferenceSet<T>();
        foreach (var value in values ?? Enumerable.Empty<T>())
        {
            if (!copy.Add(value)) throw new ArgumentException("Duplicate identity in the immutable reference set.", nameof(values));
        }
        return new ReadOnlyReferenceSet<T, T>(copy, static item => item);
    }

    public static bool OrderedScalarEquals<T>(IEnumerable<T> left, IEnumerable<T> right) =>
        left.SequenceEqual(right, ScalarEquality<T>.Instance);

    public static int OrderedScalarHash<T>(IEnumerable<T> values) => OrderedHash(values, ScalarHash<T>);

    public static bool ScalarSetEquals<T>(IEnumerable<T> left, IEnumerable<T> right) =>
        new HashSet<T>(left, ScalarEquality<T>.Instance).SetEquals(right);

    public static int ScalarSetHash<T>(IEnumerable<T> values) => UnorderedHash(values, ScalarHash<T>);

    public static bool ScalarMapEquals<TKey, TValue>(IReadOnlyDictionary<TKey, TValue> left,
        IReadOnlyDictionary<TKey, TValue> right) where TKey : notnull
    {
        if (left.Count != right.Count) return false;
        var rightValues = new Dictionary<TKey, TValue>(right.Count, ScalarEquality<TKey>.Instance);
        foreach (var (key, value) in right)
        {
            if (!rightValues.TryAdd(key, value)) return false;
        }
        var values = ScalarEquality<TValue>.Instance;
        foreach (var (key, value) in left)
        {
            if (!rightValues.TryGetValue(key, out var other) || !values.Equals(value, other)) return false;
        }
        return true;
    }

    public static int ScalarMapHash<TKey, TValue>(IReadOnlyDictionary<TKey, TValue> values) where TKey : notnull =>
        UnorderedHash(values, static entry => ScalarHash(entry.Key) ^ ScalarHash(entry.Value));

    public static bool OrderedReferenceEquals<T>(IReadOnlyList<T?> left, IReadOnlyList<T?> right,
        Func<T, T, bool> equals) where T : class
    {
        if (left.Count != right.Count) return false;
        for (int index = 0; index < left.Count; index++)
        {
            var first = left[index];
            var second = right[index];
            if (!ReferenceEquals(first, second) && (first is null || second is null || !equals(first, second)))
            {
                return false;
            }
        }
        return true;
    }

    public static int OrderedReferenceHash<T>(IEnumerable<T?> values, Func<T, int> hash) where T : class =>
        OrderedHash(values, value => value is null ? 0 : hash(value));

    public static bool UnorderedReferenceEquals<T>(IReadOnlyCollection<T> left, IReadOnlyCollection<T> right,
        Func<T, T, bool> equals) where T : class
    {
        if (left.Count != right.Count) return false;
        // Identity-distinct members can have equal contents. Each match consumes one member.
        var unmatched = right.ToList();
        foreach (var value in left)
        {
            int index = unmatched.FindIndex(other => ReferenceEquals(value, other) || equals(value, other));
            if (index < 0) return false;
            unmatched.RemoveAt(index);
        }
        return true;
    }

    public static int UnorderedReferenceHash<T>(IEnumerable<T> values, Func<T, int> hash) where T : class =>
        UnorderedHash(values, hash);

    private static int ScalarHash<T>(T value) => value is null ? 0 : ScalarEquality<T>.Instance.GetHashCode(value);

    private static int OrderedHash<T>(IEnumerable<T> values, Func<T, int> hash)
    {
        var result = new HashCode();
        foreach (var value in values) result.Add(hash(value));
        return result.ToHashCode();
    }

    private static int UnorderedHash<T>(IEnumerable<T> values, Func<T, int> hash)
    {
        int result = 0;
        foreach (var value in values) result = unchecked(result + hash(value));
        return result;
    }
}
