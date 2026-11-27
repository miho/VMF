using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Appliedcalls;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class AppliedCallsTests
{
    [Fact]
    public void JaggedArgumentsPreserveOriginalRowsAndGeneratedModelIdentity()
    {
        var host = new JaggedCallHost();
        int[] shared = [6];
        int[]?[] numbers = [shared, null, shared, []];
        Assert.Equal(7, host.Mark(numbers));
        Assert.Equal(7, shared[0]);
        Assert.Equal(-1, host.Mark(null));

        Entry entry = new SpecialEntry { Name = "part", Rank = 7 };
        Entry?[] row = [entry, null];
        Entry?[]?[] entries = [row, null, row, []];
        Assert.Same(entry, host.First(entries));
        Assert.Null(host.First(null));
        Assert.Equal(13, host.CountRows(shared, null, shared));
    }

    [Fact]
    public void ClrAliasesPreserveDistinctReferenceAndNullableValueOverloads()
    {
        var host = new AliasCallHost();
        Assert.Equal("text:report", host.Classify("report"));
        Assert.Equal("uri:https://example.test/report", host.Classify(new Uri("https://example.test/report")));
        Assert.Equal("text:null", host.Classify((string?)null));
        Assert.Equal("uri:null", host.Classify((Uri?)null));
        Assert.Equal(107, host.ClassifyNumber(7));
        Assert.Equal(207, host.ClassifyNumber((int?)7));
        Assert.Equal(-1, host.ClassifyNumber(null));
    }

    [Fact]
    public void ClosedApplicationsPreserveTypedSlotsAndNullableArrayElements()
    {
        var entry = new SpecialEntry { Name = "part", Rank = 7 };
        var holder = new SpecialHolder { Item = entry };
        var other = new SpecialHolder();
        var counts = new Counts();
        var measurements = new Measurements { Reading = -0.0 };
        var root = new AppliedQuery("base");
        var reverse = new ReverseQuery("reverse");

        Assert.Null(typeof(AppliedQuery).GetMethod("Forward"));
        Assert.Same(reverse, reverse.AsReadOnly());
        var baseCalls = (IAppliedQueryCalls)root;
        IReverseQueryCalls reverseCalls = reverse;
        IAppliedQueryCalls reverseAsBase = reverseCalls;
        Assert.Same(holder, baseCalls.Forward(holder));
        Holder<SpecialEntry>? forwarded = reverseAsBase.Forward(holder);
        Assert.Same(entry, forwarded?.Item);
        Assert.Equal(7, forwarded?.Item?.Rank);
        Series<int?>? boxed = reverseAsBase.Boxed(counts);
        Assert.Same(counts, boxed);
        Assert.Null(boxed?.Value);
        counts.Value = 7;
        Assert.Equal(7, reverseAsBase.Boxed(counts)?.Value);
        Numbered<double>? numeric = reverseAsBase.Numeric(measurements);
        Assert.Same(measurements, numeric);
        Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(numeric?.Reading ?? 1.0));
        var pair = new RankedCount { Item = entry };
        Pair<SpecialEntry, int?>? paired = reverseAsBase.Pair(pair);
        Assert.Same(pair, paired);
        Assert.Same(entry, paired?.Item);
        Assert.Null(paired?.Value);
        pair.Value = 12;
        Assert.Equal(12, baseCalls.Pair(pair)?.Value);
        Assert.Null(reverseAsBase.Forward(null));
        var endpoint = new Uri("https://example.test/report");
        var id = Guid.Parse("980e13c6-84a0-49d6-821d-737269635aef");
        Assert.Same(endpoint, reverseAsBase.EchoUri(endpoint));
        Assert.Equal(id, reverseAsBase.EchoId(id));
        Assert.Null(reverseAsBase.EchoUri(null));
        Assert.Null(reverseAsBase.EchoId(null));

        Holder<SpecialEntry>?[] original = [holder, null, other];
        Assert.Same(original, reverseAsBase.Batch(original));
        Assert.Null(reverseAsBase.Batch(null));
        Assert.Same(original, baseCalls.Collect(original));
        var reordered = Assert.IsType<Holder<SpecialEntry>[]>(reverseAsBase.Collect(holder, null, other));
        Assert.Collection(reordered, value => Assert.Same(other, value), value => Assert.Null(value),
            value => Assert.Same(holder, value));

        Projector projector = new UpdatedProjector();
        Assert.Same(holder, projector.View(holder));
        Assert.Null(new Projector().View(holder));
    }

    [Fact]
    public void AppliedBehaviorDoesNotChangeSnapshotWireOrRestoredDispatch()
    {
        string wire = ModelSnapshot.WriteWithoutReferences(new QueryEnvelope { Query = new ReverseQuery("reverse") });
        const string Expected = """
            {"query":{"@vmf-type":"eu.mihosoft.vmf.conformance.appliedcalls.ReverseQuery","caption":"reverse"}}
            """;
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(Expected), JsonNode.Parse(wire)));

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_APPLIED_CALLS");
        if (exchange is not null)
        {
            Directory.CreateDirectory(exchange);
            File.WriteAllText(Path.Combine(exchange, "csharp-query.json"), wire);
            wire = File.ReadAllText(Path.Combine(exchange, "java-query.json"));
        }

        var restored = Assert.IsType<ReverseQuery>(ModelSnapshot.ReadWithoutReferences<QueryEnvelope>(wire).Query);
        Assert.Equal("reverse", restored.Caption);
        IAppliedQueryCalls calls = (IReverseQueryCalls)restored;
        var holder = new SpecialHolder();
        Assert.Same(holder, calls.Forward(holder));
        var reordered = Assert.IsType<Holder<SpecialEntry>[]>(calls.Collect(holder, null));
        Assert.Collection(reordered, value => Assert.Null(value), value => Assert.Same(holder, value));
    }
}
