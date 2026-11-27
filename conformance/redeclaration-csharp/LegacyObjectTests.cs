using System.Collections;
using System.Numerics;
using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Redeclarations;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class LegacyObjectTests
{
    private static readonly JournalBinding Binding = new("legacy-object", 23, _ => "root");

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void WritableObjectFollowsTheRecordedJavaShape(string format)
    {
        (Func<object, string> Write, Func<string, PayloadRecord> Read) snapshots = format switch
        {
            "json" => (ModelSnapshot.WriteWithoutReferences, ModelSnapshot.ReadWithoutReferences<PayloadRecord>),
            "yaml" => (ModelSnapshot.WriteYamlWithoutReferences, ModelSnapshot.ReadYamlWithoutReferences<PayloadRecord>),
            "xml" => (ModelSnapshot.WriteXmlWithoutReferences, ModelSnapshot.ReadXmlWithoutReferences<PayloadRecord>),
            _ => throw new ArgumentOutOfRangeException(nameof(format))
        };
        (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) journals = format switch
        {
            "json" => (JournalJson.Write, JournalJson.Read),
            "yaml" => (JournalYaml.Write, JournalYaml.Read),
            "xml" => (JournalXml.Write, JournalXml.Read),
            _ => throw new ArgumentOutOfRangeException(nameof(format))
        };
        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_REDECLARATIONS");
        foreach (var (name, initial) in Values())
        {
            var original = new PayloadRecord { Payload = initial };
            string wire = snapshots.Write(original);
            var own = snapshots.Read(wire);
            var received = own;
            string? java = directory is null ? null : Path.Combine(directory, $"java-legacy-object-{name}");
            string? csharp = directory is null ? null : Path.Combine(directory, $"csharp-legacy-object-{name}");
            if (java is not null && csharp is not null)
            {
                received = snapshots.Read(File.ReadAllText(java + "." + format));
                File.WriteAllText(csharp + "." + format, wire);
                if (name == "exponent" && format == "xml")
                {
                    Assert.Contains(Assert.IsType<string>(received.Payload), new[] { "9.999999999999999E22", "1.0E23" });
                    Assert.Equal("1.0E23", own.Payload);
                }
                else Assert.True(TreeEquals(received.Payload, own.Payload), $"{name}.{format}: Java and C# decoded values");
            }

            if (initial is IDictionary or IList or BigInteger)
            {
                Assert.Throws<ArgumentException>(() => JournalRecorder<PayloadRecord>.RecordFrame(original,
                    ModelSnapshot.Registry, Binding, "initial", 0, node => node.Payload = "next"));
                continue;
            }

            var journal = JournalRecorder<PayloadRecord>.RecordFrame(new PayloadRecord { Payload = initial },
                ModelSnapshot.Registry, Binding, "initial", 0, node => node.Payload = "next");
            string written = journals.Write(journal);
            var loaded = journals.Read(java is null ? written : File.ReadAllText(java + ".journal." + format));
            Assert.Equal(1, loaded.WireVersion);
            Assert.Equal(JournalJson.Write(journal), JournalJson.Write(loaded));
            Assert.Equal("next", JournalReplayer.Replay(original, Binding, "initial", loaded, ModelSnapshot.Registry).Payload);
            Assert.True(TreeEquals(initial, original.Payload));

            if (csharp is not null) File.WriteAllText(csharp + ".journal." + format, written);
            if (TreeEquals(initial, received.Payload))
                Assert.Equal("next", JournalReplayer.Replay(received, Binding, "initial", loaded, ModelSnapshot.Registry).Payload);
            else
                Assert.Throws<InvalidOperationException>(() =>
                    JournalReplayer.Replay(received, Binding, "initial", loaded, ModelSnapshot.Registry));
        }
    }

    [Fact]
    public void PayloadUsesTheOrdinaryScalarSlotAndRejectsUnsupportedHostObjects()
    {
        var record = new PayloadRecord { Payload = "value" };
        var field = Assert.Single(ModelSnapshot.Reflection.Reflect(record).Properties, item => item.Name == "payload");
        Assert.Equal(typeof(object), field.ValueType);
        Assert.Equal("value", field.Get());
        var cyclic = new List<object>();
        cyclic.Add(cyclic);
        foreach (object unsupported in new object[] { new Uri("https://example.test"), new PayloadRecord(),
            new Dictionary<int, object?> { [1] = null }, cyclic })
        {
            record.Payload = unsupported;
            Assert.Throws<JsonException>(() => ModelSnapshot.WriteWithoutReferences(record));
        }
    }

    private static Dictionary<string, object?> Values() => new()
    {
        ["nil"] = null, ["string"] = "true", ["boolean"] = true, ["char"] = 'A', ["byte"] = (sbyte)2,
        ["short"] = (short)-3, ["int"] = 42, ["narrow-long"] = 2L, ["wide-long"] = 4000000000L,
        ["float"] = 3.5f, ["double"] = 1.0, ["negative-zero"] = -0.0, ["nan"] = double.NaN, ["exponent"] = 1e23,
        ["wide-integer"] = BigInteger.Parse("9223372036854775809"),
        ["map"] = new Dictionary<string, object?>
        {
            ["empty"] = "", ["nil"] = null, ["floats"] = new object?[] { 1.0, null, "" },
            ["nested"] = new Dictionary<string, object?> { ["enabled"] = true },
            ["emptyArray"] = Array.Empty<object>(), ["emptyMap"] = new Dictionary<string, object?>(),
        },
        ["list"] = new object[] { new[] { 1, 2 }, new[] { 3 } },
        ["singleton-list"] = new[] { true },
    };

    private static bool TreeEquals(object? expected, object? actual)
    {
        if (expected is IDictionary<string, object?> map && actual is IDictionary<string, object?> otherMap)
            return map.Count == otherMap.Count && map.All(pair => otherMap.TryGetValue(pair.Key, out var value) && TreeEquals(pair.Value, value));
        if (expected is IList source && actual is IList other)
            return source.Count == other.Count && Enumerable.Range(0, source.Count).All(index => TreeEquals(source[index], other[index]));
        if (expected is double d && actual is double otherDouble)
            return double.IsNaN(d) ? double.IsNaN(otherDouble) : BitConverter.DoubleToInt64Bits(d) == BitConverter.DoubleToInt64Bits(otherDouble);
        return Equals(expected, actual);
    }
}
