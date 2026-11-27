using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Immutables;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ImmutableCollectionTests
{
    private static readonly double NegativeZero = BitConverter.Int64BitsToDouble(long.MinValue);

    [Fact]
    public void ImmutableNestedBuildersCaptureTheirSlotsAndBuildEachOccurrenceLazily()
    {
        var childBuilder = ImmutableBadge.NewBuilder().WithText("staged");
        var nested = new CountingBadgeBuilder(childBuilder);
        List<IModelBuilder<ImmutableBadge>?> trail = [nested, null, nested];
        var staged = ExtendedPortfolio.NewBuilder().WithCurrentBuilder(nested).WithTrailBuilders(trail)
            .WithPreferredBuilders([nested, nested]).WithPoolBuilders([nested, nested]);
        trail.Clear();
        Assert.Equal(0, nested.Calls);
        childBuilder.WithText("first");
        var first = staged.Build();
        Assert.Equal(7, nested.Calls);
        childBuilder.WithText("second");
        IModelBuilder<ImmutablePortfolio> covariant = staged;
        var second = Assert.IsType<ExtendedPortfolio>(covariant.Build());
        Assert.Equal(14, nested.Calls);
        Verify(first, "first");
        Verify(second, "second");
        Assert.NotSame(first.Current, second.Current);

        var direct = new ImmutableBadge("direct");
        var replaced = staged.WithCurrent(direct).WithTrail([direct, null, direct])
            .WithPreferred([direct]).WithPool([direct]).Build();
        AssertDirect(replaced, direct);
        var imported = staged.WithCurrentBuilder(nested).WithTrailBuilders([nested])
            .WithPreferredBuilders([nested]).WithPoolBuilders([nested]).ApplyFrom(replaced).Build();
        AssertDirect(imported, direct);
        Assert.Equal(14, nested.Calls);
        Assert.Null(staged.WithCurrentBuilder(null).Build().Current);

        static void Verify(ExtendedPortfolio value, string text)
        {
            Assert.Equal(text, value.Current!.Text);
            Assert.Equal(3, value.Trail.Count);
            Assert.Null(value.Trail[1]);
            Assert.NotSame(value.Current, value.Trail[0]);
            Assert.NotSame(value.Trail[0], value.Trail[2]);
            Assert.Equal(text, value.Trail[0]!.Text);
            Assert.Equal(text, value.Trail[2]!.Text);
            Assert.Equal(2, value.Preferred.Count);
            Assert.Equal(2, value.Pool.Count);
            Assert.All(value.Preferred, item => Assert.Equal(text, item.Text));
            Assert.All(value.Pool, item => Assert.Equal(text, item.Text));
        }

        static void AssertDirect(ExtendedPortfolio value, ImmutableBadge badge)
        {
            Assert.Same(badge, value.Current);
            Assert.Equal(3, value.Trail.Count);
            Assert.Same(badge, value.Trail[0]);
            Assert.Null(value.Trail[1]);
            Assert.Same(badge, value.Trail[2]);
            Assert.Same(badge, Assert.Single(value.Preferred));
            Assert.Same(badge, Assert.Single(value.Pool));
        }
    }

    private sealed class CountingBadgeBuilder(ImmutableBadge.Builder source) : IModelBuilder<ImmutableBadge>
    {
        public int Calls { get; private set; }

        public ImmutableBadge Build()
        {
            Calls++;
            return source.Build();
        }
    }

    [Fact]
    public void ImmutableBuilderImportsAppendOrReplaceCollections()
    {
        var firstBadge = new ImmutableBadge("same");
        var secondBadge = new ImmutableBadge("same");
        var first = ExtendedPortfolio.NewBuilder()
            .WithStatus("first").WithAliases(["first"]).WithCounts([1, 2])
            .WithSteps(["one", "shared"]).WithLimits(new Dictionary<string, long?> { ["shared"] = 1, ["retained"] = 2 })
            .WithCurrent(firstBadge).WithTrail([firstBadge]).WithPreferred([firstBadge]).WithPool([firstBadge]).Build();
        var second = ExtendedPortfolio.NewBuilder()
            .WithStatus(null).WithDefaultProbe(null).WithAliases([null, "second"]).WithCounts([3])
            .WithSteps(["shared", "two"]).WithLimits(new Dictionary<string, long?> { ["shared"] = 3, ["new"] = 4 })
            .WithCurrent(secondBadge).WithTrail([firstBadge, secondBadge])
            .WithPreferred([firstBadge, secondBadge]).WithPool([firstBadge, secondBadge]).Build();

        var staged = ExtendedPortfolio.NewBuilder().ApplyFrom(first).ApplyFrom(second);
        var merged = staged.Build();
        Assert.Null(merged.Status);
        Assert.Null(merged.DefaultProbe);
        Assert.Equal(["first", null, "second"], merged.Aliases);
        Assert.Equal([1, 2, 3], merged.Counts);
        Assert.Equal(["one", "shared", "two"], merged.Steps);
        Assert.Equal(3, merged.Limits.Count);
        Assert.Equal(3L, merged.Limits["shared"]);
        Assert.Equal(2L, merged.Limits["retained"]);
        Assert.Equal(4L, merged.Limits["new"]);
        Assert.Same(secondBadge, merged.Current);
        Assert.Same(firstBadge, merged.Trail[0]);
        Assert.Same(firstBadge, merged.Trail[1]);
        Assert.Same(secondBadge, merged.Trail[2]);
        Assert.Same(firstBadge, merged.Preferred[0]);
        Assert.Same(secondBadge, merged.Preferred[1]);
        Assert.Equal(2, merged.Pool.Count);

        Assert.ThrowsAny<ArgumentException>(() => staged.ApplyFrom(second));
        Assert.Equal([1, 2, 3], staged.Build().Counts);

        var replaced = staged.AppendCollections(false).ApplyFrom((ImmutablePortfolio)second).Build();
        Assert.Equal([null, "second"], replaced.Aliases);
        Assert.Equal([3], replaced.Counts);
        Assert.Equal(["shared", "two"], replaced.Steps);
        Assert.Equal(2, replaced.Limits.Count);
        Assert.Equal(3L, replaced.Limits["shared"]);
        Assert.Equal(4L, replaced.Limits["new"]);
        Assert.Same(secondBadge, replaced.Current);
        Assert.Equal(3, replaced.Trail.Count);
        Assert.Equal(2, replaced.Preferred.Count);
        Assert.Equal([1, 2, 3], merged.Counts);
    }

    [Fact]
    public void ImmutableCollectionInputsAreCopiedAndUseModeledMembership()
    {
        var input = new List<string?> { "tag", null, "tag" };
        var map = new Dictionary<string, long?> { ["known"] = 9, ["empty"] = null };
        var badge = new ImmutableBadge("same");
        var sameValue = new ImmutableBadge("same");
        var source = new List<ImmutableBadge?> { badge, null, badge, sameValue };
        var builder = ExtendedPortfolio.NewBuilder().WithAliases(input).WithLimits(map)
            .WithTrail(source).WithLevels([0.0d, NegativeZero, double.NaN, double.NaN])
            .WithPreferred([badge, sameValue]).WithPool([sameValue, badge]);
        var constructorValue = new ExtendedPortfolio(aliases: input, limits: map, trail: source);

        input.Clear();
        map["empty"] = 4;
        source.Clear();
        var value = builder.Build();
        Assert.Equal(["tag", null, "tag"], value.Aliases);
        Assert.Equal(value.Aliases, constructorValue.Aliases);
        Assert.Null(value.Limits["empty"]);
        Assert.Null(constructorValue.Limits["empty"]);
        Assert.Same(badge, value.Trail[2]);
        Assert.Same(badge, constructorValue.Trail[2]);
        Assert.Same(sameValue, value.Trail[3]);
        Assert.Equal(3, value.Levels.Count);
        Assert.Contains(double.NaN, value.Levels);
        Assert.True(value.Preferred.Contains(badge));
        Assert.True(value.Pool.Contains(sameValue));
        Assert.False(value.Pool.Contains(new ImmutableBadge("same")));
        Assert.False(value.Aliases is ICollection<string?> collection && !collection.IsReadOnly);
        Assert.False(value.Limits is IDictionary<string, long?> dictionary && !dictionary.IsReadOnly);

        Assert.Throws<ArgumentException>(() => ExtendedPortfolio.NewBuilder().WithSteps(["pick", "pick"]));
        Assert.Throws<ArgumentException>(() => ExtendedPortfolio.NewBuilder().WithPreferred([badge, badge]));
        Assert.Throws<ArgumentException>(() => ExtendedPortfolio.NewBuilder().WithPool([badge, badge]));
        Assert.ThrowsAny<ArgumentException>(() => ExtendedPortfolio.NewBuilder().WithCounts([1, 2, 3, 4]));
    }

    [Fact]
    public void ImmutableInheritancePreservesDeclaredEqualityAndDefaults()
    {
        var left = Create();
        var right = Create();
        Assert.Same(left, left.AsReadOnly());
        Assert.Equal(left, right);
        Assert.Equal(left.GetHashCode(), right.GetHashCode());
        Assert.Equal(left.ContentHashCode(), right.ContentHashCode());
        Assert.NotSame(left.Current, right.Current);
        Assert.True(left.ContentEquals(right));
        ImmutablePortfolio asBase = left;
        Assert.True(asBase.ContentEquals(right));
        Assert.False(asBase.ContentEquals(new ImmutablePortfolio()));
        Assert.False(new ImmutablePortfolio().ContentEquals(asBase));

        var identityLeft = new IdentityPortfolio();
        var identityRight = new IdentityPortfolio();
        Assert.True(identityLeft.ContentEquals(identityRight));
        Assert.False(identityLeft.Equals((object)identityRight));
        Assert.False(((IEquatable<ImmutablePortfolio>)identityLeft).Equals(identityRight));

        var omitted = new ExtendedPortfolio();
        Assert.Equal("ready", omitted.Status);
        Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(omitted.DefaultProbe!.Value));
        var explicitNull = ExtendedPortfolio.NewBuilder().WithStatus(null).WithDefaultProbe(null).Build();
        Assert.Null(explicitNull.Status);
        Assert.Null(explicitNull.DefaultProbe);
        var nullRoundTrip = ModelSnapshot.ReadPlain<ExtendedPortfolio>(ModelSnapshot.WritePlain(explicitNull));
        Assert.Null(nullRoundTrip.Status);
        Assert.Null(nullRoundTrip.DefaultProbe);
        var missing = ModelSnapshot.ReadPlain<ExtendedPortfolio>("{}");
        Assert.Equal("ready", missing.Status);
        Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(missing.DefaultProbe!.Value));
    }

    [Fact]
    public void ImmutableCollectionsAndAliasesCrossLanguageSnapshots()
    {
        var model = Create();
        var holder = new PortfolioSelection { Portfolio = model };
        string reference = ModelSnapshot.Write(holder);
        string values = ModelSnapshot.WriteWithoutReferences(holder);
        string plain = ModelSnapshot.WritePlain(model);
        VerifyReference(reference);
        VerifyValues(values);
        Verify(ModelSnapshot.ReadPlain<ExtendedPortfolio>(plain));
        Assert.Throws<JsonException>(() => ModelSnapshot.ReadPlain<PortfolioSelection>(values));

        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_IMMUTABLE_COLLECTIONS") is { } exchange)
        {
            VerifyReference(File.ReadAllText(Path.Combine(exchange, "java-reference.json")));
            VerifyValues(File.ReadAllText(Path.Combine(exchange, "java-values.json")));
            File.WriteAllText(Path.Combine(exchange, "csharp-reference.json"), reference);
            File.WriteAllText(Path.Combine(exchange, "csharp-values.json"), values);
            File.WriteAllText(Path.Combine(exchange, "csharp-plain.json"), plain);
        }
    }

    [Fact]
    public void DuplicateOrOutOfBoundsImmutableWireMembersAreRejected()
    {
        Assert.Throws<JsonException>(() => ModelSnapshot.ReadPlain<ExtendedPortfolio>("""{"steps":["same","same"]}"""));
        Assert.Throws<JsonException>(() => ModelSnapshot.ReadPlain<ExtendedPortfolio>("""{"levels":[1,1]}"""));
        Assert.Throws<JsonException>(() => ModelSnapshot.ReadPlain<ExtendedPortfolio>("""{"counts":[1,2,3,4]}"""));
    }

    [Fact]
    public void JsonByValueChecksTypeNamesAndRejectsGraphIds()
    {
        Assert.Throws<JsonException>(() => ModelSnapshot.ReadWithoutReferences<ImmutablePortfolio>(
            """{"@vmf-type":"arbitrary.Type"}"""));
        Assert.Throws<JsonException>(() => ModelSnapshot.ReadWithoutReferences<ExtendedPortfolio>(
            """{"current":{"@vmf-id":"1","text":"tag"},"trail":["1"]}"""));
        Assert.Throws<JsonException>(() => ModelSnapshot.ReadWithoutReferences<ExtendedPortfolio>(
            """{"trail":["1"]}"""));
    }

    internal static ExtendedPortfolio Create()
    {
        var badge = new ImmutableBadge("same");
        var equivalent = new ImmutableBadge("same");
        return ExtendedPortfolio.NewBuilder()
            .WithAliases(["tag", null, "tag"])
            .WithCounts([1, 2])
            .WithSteps(["pick", "place"])
            .WithLevels([0.0d, NegativeZero, double.NaN])
            .WithLimits([new("known", 9), new("empty", null)])
            .WithMarkers([new(0.0d, '+'), new(NegativeZero, '-')])
            .WithCurrent(badge)
            .WithTrail([badge, null, badge, equivalent])
            .WithPreferred([badge, equivalent])
            .WithPool([equivalent, badge])
            .Build();
    }

    private static void VerifyReference(string json)
    {
        var model = Assert.IsType<ExtendedPortfolio>(ModelSnapshot.Read<PortfolioSelection>(json).Portfolio);
        Verify(model);
        Assert.Same(model.Current, model.Trail[0]);
        Assert.Same(model.Current, model.Trail[2]);
        Assert.Same(model.Current, model.Preferred[0]);
        Assert.Same(model.Trail[3], model.Preferred[1]);
        Assert.NotSame(model.Current, model.Trail[3]);
        Assert.True(model.Pool.Contains(model.Current!));
        Assert.True(model.Pool.Contains(model.Trail[3]!));
    }

    private static void VerifyValues(string json)
    {
        var model = Assert.IsType<ExtendedPortfolio>(ModelSnapshot.ReadWithoutReferences<PortfolioSelection>(json).Portfolio);
        Verify(model);
        Assert.NotSame(model.Current, model.Trail[0]);
        Assert.NotSame(model.Trail[0], model.Trail[2]);
        Assert.DoesNotContain("\"@vmf-id\"", json, StringComparison.Ordinal);
    }

    internal static void Verify(ExtendedPortfolio model)
    {
        Assert.Equal("ready", model.Status);
        Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(model.DefaultProbe!.Value));
        Assert.Equal(["tag", null, "tag"], model.Aliases);
        Assert.Equal([1, 2], model.Counts);
        Assert.Equal(["pick", "place"], model.Steps);
        Assert.Contains(model.Levels, value => BitConverter.DoubleToInt64Bits(value) == 0);
        Assert.Contains(model.Levels, value => BitConverter.DoubleToInt64Bits(value) == long.MinValue);
        Assert.Contains(double.NaN, model.Levels);
        Assert.Equal(9, model.Limits["known"]);
        Assert.Null(model.Limits["empty"]);
        Assert.Equal('+', model.Markers[0.0d]);
        Assert.Equal('-', model.Markers[NegativeZero]);
        Assert.Equal("same", model.Current?.Text);
        Assert.Equal(["same", null, "same", "same"], model.Trail.Select(value => value?.Text));
        Assert.Equal(2, model.Preferred.Count);
        Assert.Equal(2, model.Pool.Count);
    }
}
