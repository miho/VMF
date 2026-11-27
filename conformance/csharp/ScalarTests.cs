using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ScalarTests
{
    private static readonly string Fixtures = Path.Combine(AppContext.BaseDirectory, "fixtures");

    [Fact]
    public void NativeScalarDefaultsMatchJava()
    {
        var sample = ModelSnapshot.ReadPlain<ScalarSample>("{}");
        Assert.False(sample.Active);
        Assert.Equal((sbyte)0, sample.ByteValue);
        Assert.Equal((short)0, sample.ShortValue);
        Assert.Equal(0, sample.IntValue);
        Assert.Equal(0L, sample.LongValue);
        Assert.Equal('\0', sample.CharValue);
        Assert.Equal(0f, sample.FloatValue);
        Assert.Equal(0d, sample.DoubleValue);
        Assert.Null(sample.OptionalActive);
        Assert.Null(sample.OptionalByte);
        Assert.Null(sample.OptionalShort);
        Assert.Null(sample.OptionalInt);
        Assert.Null(sample.OptionalLong);
        Assert.Null(sample.OptionalChar);
        Assert.Null(sample.OptionalFloat);
        Assert.Null(sample.OptionalDouble);
        Assert.Null(sample.Description);
    }

    [Fact]
    public void ExtremeScalarValuesRoundTripInBothProfiles()
    {
        var sample = ModelSnapshot.ReadPlain<ScalarSample>(
            File.ReadAllText(Path.Combine(Fixtures, "scalar-extremes.json")));
        Verify(sample);
        var plain = ModelSnapshot.WritePlain(sample.AsReadOnly());
        var references = ModelSnapshot.Write(sample.AsReadOnly());
        Verify(ModelSnapshot.ReadPlain<ScalarSample>(plain));
        Verify(ModelSnapshot.Read<ScalarSample>(references));
        using var tree = JsonDocument.Parse(plain);
        var root = tree.RootElement;
        Assert.False(root.TryGetProperty("@vmf-type", out _));
        Assert.False(root.TryGetProperty("@vmf-id", out _));
        Assert.Equal(long.MinValue, root.GetProperty("longValue").GetInt64());
        Assert.Equal(long.MaxValue, root.GetProperty("optionalLong").GetInt64());
        Assert.Equal("Infinity", root.GetProperty("optionalFloat").GetString());
        Assert.Equal("NaN", root.GetProperty("optionalDouble").GetString());

        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_SCALARS");
        if (interchange is not null)
        {
            Verify(ModelSnapshot.ReadPlain<ScalarSample>(
                File.ReadAllText(Path.Combine(interchange, "scalar-java-plain.json"))));
            Verify(ModelSnapshot.Read<ScalarSample>(
                File.ReadAllText(Path.Combine(interchange, "scalar-java-references.json"))));
            File.WriteAllText(Path.Combine(interchange, "scalar-csharp-plain.json"), plain);
            File.WriteAllText(Path.Combine(interchange, "scalar-csharp-references.json"), references);
        }
    }

    public static IEnumerable<object[]> ValidationCases()
    {
        using var cases = JsonDocument.Parse(File.ReadAllText(Path.Combine(Fixtures, "scalar-validation.json")));
        foreach (var testCase in cases.RootElement.EnumerateArray())
        {
            yield return
            [
                testCase.GetProperty("name").GetString()!,
                testCase.GetProperty("model").GetRawText(),
                testCase.GetProperty("issues").GetRawText()
            ];
        }
    }

    [Theory]
    [MemberData(nameof(ValidationCases))]
    public void DeclaredConstraintsMatchTheSharedOracle(string name, string model, string issues)
    {
        var batch = ModelSnapshot.ReadPlain<ScalarBatch>(model);
        using var expectedIssues = JsonDocument.Parse(issues);
        var expected = expectedIssues.RootElement.EnumerateArray()
            .Select(issue => string.Join("|", issue.GetProperty("path").GetString(),
                issue.GetProperty("code").GetString(), issue.GetProperty("severity").GetString()))
            .Order(StringComparer.Ordinal)
            .ToArray();
        Assert.Equal(expected, Describe(batch.Validate()));
        Assert.Equal(expected, Describe(batch.AsReadOnly().Validate()));
        Assert.False(string.IsNullOrWhiteSpace(name));
    }

    [Theory]
    [InlineData("{\"byteValue\":128}")]
    [InlineData("{\"shortValue\":-32769}")]
    [InlineData("{\"longValue\":9223372036854775808}")]
    [InlineData("{\"charValue\":\"two\"}")]
    [InlineData("{\"charValue\":\"🛠\"}")]
    [InlineData("{\"doubleValue\":\"infinity\"}")]
    public void NonCanonicalScalarsAreRejected(string json)
    {
        Assert.Throws<JsonException>(() => ModelSnapshot.ReadPlain<ScalarSample>(json));
    }

    private static IEnumerable<string> Describe(IEnumerable<ValidationIssue> issues) =>
        issues.Select(issue => $"{issue.ModelPath}|{issue.ModelCode}|{issue.Severity.ToString().ToUpperInvariant()}")
            .Order(StringComparer.Ordinal);

    private static void Verify(ScalarSample sample)
    {
        Assert.True(sample.Active);
        Assert.False(sample.OptionalActive);
        Assert.Equal(sbyte.MinValue, sample.ByteValue);
        Assert.Equal(sbyte.MaxValue, sample.OptionalByte);
        Assert.Equal(short.MinValue, sample.ShortValue);
        Assert.Equal(short.MaxValue, sample.OptionalShort);
        Assert.Equal(int.MinValue, sample.IntValue);
        Assert.Equal(int.MaxValue, sample.OptionalInt);
        Assert.Equal(long.MinValue, sample.LongValue);
        Assert.Equal(long.MaxValue, sample.OptionalLong);
        Assert.Equal('中', sample.CharValue);
        Assert.Equal('\0', sample.OptionalChar);
        Assert.Equal(0.1f, sample.FloatValue);
        Assert.Equal(float.PositiveInfinity, sample.OptionalFloat);
        Assert.Equal(-1.2345678901234567e200, sample.DoubleValue);
        Assert.Equal(double.NaN, sample.OptionalDouble);
        Assert.Equal("Probe 🛠", sample.Description);
        Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(Assert.IsType<double>(sample.SignedZero)));
        Assert.Equal(float.Epsilon, sample.TinyFloat);
        Assert.Equal(double.Epsilon, sample.TinyDouble);
        Assert.Equal(double.NegativeInfinity, sample.NegativeInfinity);
        Assert.Equal('\uD800', sample.SurrogateUnit);
    }
}
