using Eu.Mihosoft.Vmf.Conformance.Numeric;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class NumericGenericTests
{
    private static readonly double NegativeZero = BitConverter.Int64BitsToDouble(long.MinValue);
    private const long BeyondJavaScriptSafeInteger = 9_007_199_254_740_993L;

    [Fact]
    public void ClosedNumericSlotsExposeNullableValuesAndTypedKeys()
    {
        var original = NewDouble();
        NumericSeries<double> generic = original;
        IReadOnlyNumericSeries<double> view = original.AsReadOnly();
        Assert.Null(generic.Current);
        Assert.Null(view.Current);
        generic.Current = NegativeZero;
        Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(view.Current!.Value));
        VerifyDouble(original, expectedCurrent: NegativeZero);
        generic.Current = null;
        Assert.Null(view.Current);

        generic.Threshold = 0.2d;
        Assert.Contains(original.Validate(), issue => issue.ModelCode == "vmf.range" && issue.ModelPath == "/threshold");
        generic.Threshold = 0.1d;
        Assert.Empty(original.Validate());
    }

    [Fact]
    public void JavaAndCSharpExchangeClosedNumericModelsWithoutLoss()
    {
        string doubles = ModelSnapshot.WritePlain(NewDouble());
        string longs = ModelSnapshot.WritePlain(NewLong());
        VerifyDouble(ModelSnapshot.ReadPlain<DoubleSeries>(doubles), expectedCurrent: null);
        VerifyLong(ModelSnapshot.ReadPlain<LongSeries>(longs));
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_NUMERIC") is { } interchange)
        {
            VerifyDouble(ModelSnapshot.ReadPlain<DoubleSeries>(File.ReadAllText(Path.Combine(interchange, "java-double.json"))),
                expectedCurrent: null);
            VerifyLong(ModelSnapshot.ReadPlain<LongSeries>(File.ReadAllText(Path.Combine(interchange, "java-long.json"))));
            File.WriteAllText(Path.Combine(interchange, "csharp-double.json"), doubles);
            File.WriteAllText(Path.Combine(interchange, "csharp-long.json"), longs);
        }
    }

    private static DoubleSeries NewDouble() =>
        DoubleSeries.NewBuilder().WithSamples([1.0d, null, 1.0d])
            .WithBounded([1.0d, 2.0d])
            .WithByName([new("empty", null), new("limit", NegativeZero)])
            .WithByNumber([new(+0.0d, 7L), new(NegativeZero, null), new(double.NaN, long.MaxValue)])
            .WithThreshold(0.1d).Build();

    private static LongSeries NewLong() =>
        LongSeries.NewBuilder().WithCurrent(BeyondJavaScriptSafeInteger)
            .WithByNumber([new(BeyondJavaScriptSafeInteger, 3L)]).Build();

    private static void VerifyDouble(DoubleSeries value, double? expectedCurrent)
    {
        Assert.Equal(expectedCurrent, value.Current);
        Assert.Equal(new double?[] { 1.0d, null, 1.0d }, value.Samples);
        Assert.Equal(new[] { 1.0d, 2.0d }, value.Bounded);
        Assert.True(value.ByName.ContainsKey("empty"));
        Assert.Null(value.ByName["empty"]);
        Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(value.ByName["limit"]!.Value));
        Assert.Equal(7L, value.ByNumber[+0.0d]);
        Assert.True(value.ByNumber.ContainsKey(NegativeZero));
        Assert.Null(value.ByNumber[NegativeZero]);
        Assert.Equal(long.MaxValue, value.ByNumber[double.NaN]);
        Assert.Empty(value.Validate());
        var reflected = ModelSnapshot.Reflection.Reflect(value);
        Assert.Equal(typeof(double?), reflected.FindProperty("current")!.ValueType);
        Assert.Equal(typeof(double), reflected.FindProperty("byNumber")!.KeyType);
    }

    private static void VerifyLong(LongSeries value)
    {
        Assert.Equal(BeyondJavaScriptSafeInteger, value.Current);
        Assert.Equal(3L, value.ByNumber[BeyondJavaScriptSafeInteger]);
        Assert.Equal(typeof(long?), ModelSnapshot.Reflection.Reflect(value).FindProperty("current")!.ValueType);
    }
}
