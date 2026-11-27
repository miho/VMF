using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Immutables;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Eu.Mihosoft.Vmf.Example.Workshops;
using Vmf.Runtime;
using Xunit;
using YamlDotNet.Core;
using Immutables = Eu.Mihosoft.Vmf.Conformance.Immutables.ModelSnapshot;
using Values = Eu.Mihosoft.Vmf.Conformance.Values.ModelSnapshot;
using Workshops = Eu.Mihosoft.Vmf.Example.Workshops.ModelSnapshot;

namespace Vmf.CSharp.Conformance;

public sealed class YamlTests
{
    private static readonly string Fixtures = Path.Combine(AppContext.BaseDirectory, "fixtures");
    private static readonly double NegativeZero = BitConverter.Int64BitsToDouble(long.MinValue);
    private const string Description = "🚀 \"\\ud800\" and null";

    [Fact]
    public void PlainInventoryPreservesExternalNamesAndTypedZeroValues()
    {
        var inventory = ExternalInventory.NewBuilder().WithWarehouseId("BER-1").WithItems(
            [StockItem.NewBuilder().WithSku("A-42").WithQuantity(0).WithActive(false).Build(),
             StockItem.NewBuilder().WithSku("B-10").WithQuantity(8).WithActive(true).Build()]).Build();
        Exchange("inventory", Workshops.WritePlainYaml(inventory.AsReadOnly()), yaml =>
        {
            Assert.Contains("stock_items", yaml);
            Assert.Contains("warehouse_id", yaml);
            Assert.DoesNotContain("@vmf-", yaml);
            var restored = Workshops.ReadPlainYaml<ExternalInventory>(yaml);
            Assert.Equal("BER-1", restored.WarehouseId);
            Assert.Collection(restored.Items,
                first => { Assert.Equal("A-42", first.Sku); Assert.Equal(0, first.Quantity); Assert.False(first.Active); },
                second => { Assert.Equal("B-10", second.Sku); Assert.Equal(8, second.Quantity); Assert.True(second.Active); });
        });
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void BothCatalogProfilesRetainSubtypes(bool references)
    {
        var thermometer = Thermometer.NewBuilder().WithSku("T-1").WithUnit("C").Build();
        var instrument = Instrument.NewBuilder().WithSku("I-1").Build();
        var catalog = Catalog.NewBuilder().WithProducts([thermometer, instrument]).Build();
        if (references) catalog.Featured = thermometer;
        var view = catalog.AsReadOnly();
        var output = references ? Workshops.WriteYaml(view) : Workshops.WriteYamlWithoutReferences(view);
        Exchange(references ? "catalog" : "catalog-value", output, yaml =>
        {
            Assert.Contains("@vmf-type", yaml);
            Assert.Equal(references, yaml.Contains("@vmf-id", StringComparison.Ordinal));
            var restored = references ? Workshops.ReadYaml<Catalog>(yaml) : Workshops.ReadYamlWithoutReferences<Catalog>(yaml);
            var probe = Assert.IsType<Thermometer>(restored.Products[0]);
            Assert.Equal("T-1", probe.Sku);
            Assert.Equal("C", probe.Unit);
            Assert.Equal("I-1", Assert.IsType<Instrument>(restored.Products[1]).Sku);
            Assert.Same(references ? probe : null, restored.Featured);
        });
    }

    [Fact]
    public void ReferenceArchiveUsesVmfIdsForNullableRepeatedAndSelfReferences()
    {
        var model = Values.Read<ArchiveRoot>(File.ReadAllText(Path.Combine(Fixtures, "archive-references.json")));
        Exchange("archive", Values.WriteYaml(model), yaml =>
        {
            var restored = Values.ReadYaml<ArchiveRoot>(yaml);
            var head = Assert.IsType<ArchiveNode>(restored.Head);
            var left = Assert.Single(head.Children);
            var right = Assert.IsType<ArchiveNode>(head.Inset);
            Assert.Same(head, left.Parent);
            Assert.Same(head, right.InsetOf);
            Assert.Collection(head.Trail, item => Assert.Same(right, item), Assert.Null,
                item => Assert.Same(left, item), item => Assert.Same(right, item), item => Assert.Same(head, item));
            Assert.Collection(head.Featured, item => Assert.Same(left, item), item => Assert.Same(right, item));
            Assert.Collection(restored.Review, item => Assert.Same(left, item), Assert.Null, item => Assert.Same(right, item));
        });
    }

    [Fact]
    public void PlainCollectionsPreserveTypedKeysPresentNullAndSignedZero()
    {
        var model = Values.ReadPlain<CollectionProfile>(File.ReadAllText(Path.Combine(Fixtures, "scalar-collections.json")));
        model.Messages.Add("");
        model.Options.Add("<<", null);
        model.Markers.Add(double.Epsilon, '\ud800');
        Exchange("collections", Values.WritePlainYaml(model.AsReadOnly()), yaml =>
        {
            var restored = Values.ReadPlainYaml<CollectionProfile>(yaml);
            Assert.Equal(new string?[] { "ready", null, "ready", "" }, restored.Messages);
            Assert.Equal(3, restored.Levels.Count);
            Assert.True(restored.Levels.Contains(+0.0) && restored.Levels.Contains(NegativeZero) && restored.Levels.Contains(double.NaN));
            Assert.True(restored.Options["@vmf-id"]);
            Assert.False(restored.Options["@vmf-type"]);
            Assert.True(restored.Options.TryGetValue("<<", out var mergeKey));
            Assert.Null(mergeKey);
            Assert.Equal(long.MaxValue, restored.Limits["limit"]);
            Assert.True(restored.Limits.TryGetValue("optional", out var optional));
            Assert.Null(optional);
            Assert.Equal('+', restored.Markers[+0.0]);
            Assert.Equal('-', restored.Markers[NegativeZero]);
            Assert.Equal('\ud800', restored.Markers[double.Epsilon]);
            Assert.True(restored.Markers.TryGetValue(double.NaN, out var marker));
            Assert.Null(marker);
        });
    }

    [Fact]
    public void PlainObjectsDistinguishExplicitNullFromDefaultsAndEmptyStrings()
    {
        var model = DefaultSettings.NewBuilder().WithName(null).WithLimit(null)
            .WithStages(["one", ""]).WithEntries([DefaultEntry.NewBuilder().Build()]).Build();
        Exchange("defaults", Values.WritePlainYaml(model), yaml =>
        {
            var restored = Values.ReadPlainYaml<DefaultSettings>(yaml);
            Assert.Null(restored.Name);
            Assert.Null(restored.Limit);
            Assert.Equal(["one", ""], restored.Stages);
            Assert.Equal("new", Assert.Single(restored.Entries).Label);
            Assert.Equal(9_007_199_254_740_993L, restored.Sequence);
            Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(restored.Baseline));
        });
        var missing = Values.ReadPlainYaml<DefaultSettings>("{}\n");
        Assert.Equal("idle\nready", missing.Name);
        Assert.Equal(7, missing.Limit);
    }

    [Fact]
    public void ScalarRoundTripPreservesTextFullWidthNumbersAndUtf16CodeUnits()
    {
        var model = new ScalarSample
        {
            Description = Description,
            OptionalLong = 9_007_199_254_740_993L,
            OptionalDouble = NegativeZero,
            OptionalFloat = float.NaN,
            DoubleValue = double.NegativeInfinity,
            SurrogateUnit = '\ud800',
        };
        Exchange("scalars", Values.WritePlainYaml(model), yaml =>
        {
            var restored = Values.ReadPlainYaml<ScalarSample>(yaml);
            Assert.Equal(Description, restored.Description);
            Assert.Equal(9_007_199_254_740_993L, restored.OptionalLong);
            Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(restored.OptionalDouble!.Value));
            Assert.True(float.IsNaN(restored.OptionalFloat!.Value));
            Assert.Equal(double.NegativeInfinity, restored.DoubleValue);
            Assert.Equal('\0', restored.CharValue);
            Assert.Equal('\ud800', restored.SurrogateUnit);
        });
    }

    [Fact]
    public void JournalRoundTripsThroughYamlAndReplaysSharedImmutableCollections()
    {
        var badge = new ImmutableBadge(text: "badge");
        var portfolio = new ExtendedPortfolio(status: new OptionalValue<string?>(null), aliases: ["a", null, "a"],
            levels: [+0.0, NegativeZero, double.NaN], limits: new Dictionary<string, long?> { ["high"] = long.MaxValue, ["pending"] = null },
            markers: new KeyValuePair<double, char?>[] { new(NegativeZero, '-'), new(+0.0, null) },
            current: badge, trail: [badge, null, badge], preferred: [badge], pool: [badge]);
        var binding = new JournalBinding("portfolio-creation", 1, value => value switch
        {
            PortfolioSelection => "selection",
            ExtendedPortfolio => "portfolio",
            ImmutableBadge item => item.Text!,
            _ => throw new ArgumentException("Unknown YAML journal model type."),
        });
        var baseline = new PortfolioSelection();
        var journal = JournalRecorder<PortfolioSelection>.RecordFrame(new PortfolioSelection(), Immutables.Registry,
            binding, "start", 0, model => model.Portfolio = portfolio);
        var checkpoint = Immutables.WriteYaml(baseline);
        var output = JournalYaml.Write(journal);
        VerifyJournal(checkpoint, output, binding, journal);

        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_YAML");
        if (directory is null) return;
        VerifyJournal(File.ReadAllText(Path.Combine(directory, "java-journal-checkpoint.yaml")),
            File.ReadAllText(Path.Combine(directory, "java-journal.yaml")), binding, journal);
        File.WriteAllText(Path.Combine(directory, "csharp-journal-checkpoint.yaml"), checkpoint);
        File.WriteAllText(Path.Combine(directory, "csharp-journal.yaml"), output);
    }

    [Theory]
    [InlineData("description: &id original\n")]
    [InlineData("description: *id\n")]
    [InlineData("description: !private value\n")]
    [InlineData("description: !!str value\n")]
    [InlineData("<<: { description: hidden }\n")]
    [InlineData("description: first\n'description': second\n")]
    [InlineData("---\ndescription: first\n---\ndescription: second\n")]
    [InlineData("? [first, second]\n: complex\n")]
    [InlineData("description: \"\\ud800\"\n")]
    public void RejectsYamlOutsideTheVmfDataProfile(string yaml) =>
        Assert.ThrowsAny<YamlException>(() => Values.ReadPlainYaml<ScalarSample>(yaml));

    [Fact]
    public void QuotedAndLiteralTextNeverBecomesAnImplicitScalar()
    {
        const string yaml = """
            description: |-
              🚀 "\ud800"
            surrogateUnit: "\ud800"
            optionalLong: 9007199254740993
            """;
        var model = Values.ReadPlainYaml<ScalarSample>(yaml);
        Assert.Equal("🚀 \"\\ud800\"", model.Description);
        Assert.Equal('\ud800', model.SurrogateUnit);
        Assert.Equal(9_007_199_254_740_993L, model.OptionalLong);
        Assert.Equal("true", Values.ReadPlainYaml<ScalarSample>("description: 'true'").Description);
        Assert.Equal("001", Values.ReadPlainYaml<ScalarSample>("description: \"001\"").Description);
        Assert.Null(Values.ReadPlainYaml<ScalarSample>("description: null").Description);
    }

    [Fact]
    public void AQuotedMergeSpellingRemainsAnOrdinaryStructuredProperty()
    {
        var registry = new SnapshotRegistry([
            new SnapshotType("ArchiveRoot", typeof(ArchiveRoot), () => new ArchiveRoot(), [
                new("<<", SnapshotPropertyKind.Containment, typeof(ArchiveNode), owner => ((ArchiveRoot)owner).Head,
                    set: (owner, value) => ((ArchiveRoot)owner).Head = (ArchiveNode?)value),
            ]),
            new SnapshotType("ArchiveNode", typeof(ArchiveNode), () => new ArchiveNode(), [
                new("name", SnapshotPropertyKind.Scalar, typeof(string), owner => ((ArchiveNode)owner).Name,
                    set: (owner, value) => ((ArchiveNode)owner).Name = (string?)value),
            ]),
        ]);
        var restored = SnapshotYaml.ReadPlain<ArchiveRoot>("'<<': { name: child }\n", registry);
        var output = SnapshotYaml.WritePlain(restored, registry);
        Assert.Contains("\"<<\"", output);
        Assert.Equal("child", SnapshotYaml.ReadPlain<ArchiveRoot>(output, registry).Head?.Name);
    }

    [Fact]
    public void VmfReferenceValidationStillRejectsDanglingAndUnknownTypes()
    {
        Assert.ThrowsAny<YamlException>(() => Values.ReadYaml<ArchiveRoot>("review: [missing]\n"));
        Assert.ThrowsAny<YamlException>(() => Workshops.ReadYaml<Catalog>("items:\n- '@vmf-type': unknown\n"));
    }

    private static void VerifyJournal(string checkpoint, string wire, JournalBinding binding, ChangeJournal recorded)
    {
        var baseline = Immutables.ReadYaml<PortfolioSelection>(checkpoint);
        var journal = JournalYaml.Read(wire);
        Assert.Equal(1, journal.WireVersion);
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(recorded)), JsonNode.Parse(JournalJson.Write(journal))));
        var replayed = JournalReplayer.Replay(baseline, binding, "start", journal, Immutables.Registry);
        var restored = Assert.IsType<ExtendedPortfolio>(replayed.Portfolio);
        Assert.Null(restored.Status);
        Assert.Equal(new string?[] { "a", null, "a" }, restored.Aliases);
        Assert.Equal(3, restored.Levels.Count);
        Assert.Equal(long.MaxValue, restored.Limits["high"]);
        Assert.Null(restored.Limits["pending"]);
        Assert.Equal('-', restored.Markers[NegativeZero]);
        Assert.Null(restored.Markers[+0.0]);
        Assert.Same(restored.Current, restored.Trail[0]);
        Assert.Null(restored.Trail[1]);
        Assert.Same(restored.Current, restored.Trail[2]);
        Assert.Same(restored.Current, Assert.Single(restored.Preferred));
        Assert.Same(restored.Current, Assert.Single(restored.Pool));
        Assert.Null(baseline.Portfolio);
    }

    private static void Exchange(string name, string output, Action<string> verify)
    {
        verify(output);
        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_YAML");
        if (directory is null) return;
        verify(File.ReadAllText(Path.Combine(directory, "java-" + name + ".yaml")));
        File.WriteAllText(Path.Combine(directory, "csharp-" + name + ".yaml"), output);
    }
}
