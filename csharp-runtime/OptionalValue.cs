namespace Vmf.Runtime;

/// <summary>Distinguishes an omitted constructor argument from an explicitly provided value.</summary>
public readonly struct OptionalValue<T>
{
    private readonly T value;

    public OptionalValue(T value)
    {
        this.value = value;
        HasValue = true;
    }

    public bool HasValue { get; }

    public T GetValueOrDefault(T fallback) => HasValue ? value : fallback;

    public T GetValueOrCreate(Func<T> fallback)
    {
        ArgumentNullException.ThrowIfNull(fallback);
        return HasValue ? value : fallback();
    }

    public static implicit operator OptionalValue<T>(T value) => new(value);
}
