using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Hierarchy;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class DefaultPrecedenceTests
{
    [Fact]
    public void MutableAndImmutableParentsShareTheFirstLiteralThroughSnapshots()
    {
        var rate = new LiteralRate();
        var reflected = OnlyField(rate, "rate", 23);
        ILeftRate left = rate;
        IRightRate right = rate;
        IReadOnlyLeftRate leftView = left.AsReadOnly();
        IReadOnlyRightRate rightView = right.AsReadOnly();
        Assert.Same(leftView, rightView);
        Assert.Equal(23, left.Rate);
        Assert.Equal(23, right.Rate);
        Assert.Equal(23, leftView.Rate);
        Assert.Equal(23, rightView.Rate);
        OnlyField(leftView, "rate", 23);

        right.Rate = 19;
        Assert.True(reflected.IsSet);
        Assert.Equal(19, left.Rate);
        Assert.Equal(19, rightView.Rate);
        reflected.Reset();
        Assert.False(reflected.IsSet);
        Assert.Equal(23, left.Rate);
        Assert.Equal(37, LiteralRate.NewBuilder().WithRate(37).Build().Rate);
        Assert.Equal(23, LiteralRate.NewBuilder().Build().Rate);
        Assert.Equal(42, ReversedLiteralRate.NewBuilder().Build().Rate);

        var rank = LiteralRank.NewBuilder().Build();
        ILeftRank leftRank = rank;
        IRightRank rightRank = rank;
        Assert.Equal(5, leftRank.Rank);
        Assert.Equal(5, rightRank.Rank);
        Assert.Same(rank, rank.AsReadOnly());
        OnlyField(rank, "rank", 5);
        Assert.Equal(17, LiteralRank.NewBuilder().WithRank(17).Build().Rank);
        Assert.Equal(11, ReversedLiteralRank.NewBuilder().Build().Rank);

        string rateWire = ModelSnapshot.WritePlain(rate);
        string rankWire = ModelSnapshot.WritePlain(rank);
        VerifyWire(rateWire, rankWire);
        Assert.Equal(23, ModelSnapshot.ReadPlain<LiteralRate>("{}").Rate);
        Assert.Equal(42, ModelSnapshot.ReadPlain<ReversedLiteralRate>("{}").Rate);
        Assert.Equal(5, ModelSnapshot.ReadPlain<LiteralRank>("{}").Rank);
        Assert.Equal(11, ModelSnapshot.ReadPlain<ReversedLiteralRank>("{}").Rank);

        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_HIERARCHY") is { } exchange)
        {
            VerifyWire(File.ReadAllText(Path.Combine(exchange, "java-literal-rate.json")),
                File.ReadAllText(Path.Combine(exchange, "java-literal-rank.json")));
            File.WriteAllText(Path.Combine(exchange, "csharp-literal-rate.json"), rateWire);
            File.WriteAllText(Path.Combine(exchange, "csharp-literal-rank.json"), rankWire);
        }
    }

    [Fact]
    public void ImplicitAndLocalDefaultsApplyToSharedInheritedProperties()
    {
        var implicitRate = new ImplicitRate();
        var implicitField = OnlyField(implicitRate, "rate", 0);
        ILeftRate alias = implicitRate;
        var implicitView = alias.AsReadOnly();
        Assert.Equal(0, alias.Rate);
        Assert.Equal(0, ImplicitRate.NewBuilder().Build().Rate);
        Assert.Equal(23, new InheritedLiteralRate().Rate);
        alias.Rate = 12;
        Assert.Equal(12, implicitView.Rate);
        implicitField.Reset();
        Assert.Equal(0, implicitView.Rate);

        var localRate = LocalLiteralRate.NewBuilder().Build();
        var localField = OnlyField(localRate, "rate", 71);
        IRightRate localAlias = localRate;
        Assert.Equal(71, localAlias.Rate);
        Assert.Equal(71, new LocalLiteralRate().Rate);
        Assert.Equal(10, LocalLiteralRate.NewBuilder().WithRate(10).Build().Rate);
        localAlias.Rate = 98;
        localField.Reset();
        Assert.Equal(71, localRate.AsReadOnly().Rate);

        var localRank = LocalLiteralRank.NewBuilder().Build();
        ILeftRank rankAlias = localRank;
        Assert.Equal(17, rankAlias.Rank);
        OnlyField(localRank, "rank", 17);
        Assert.Equal(39, LocalLiteralRank.NewBuilder().WithRank(39).Build().Rank);
        Assert.Equal(0, ImplicitRank.NewBuilder().Build().Rank);
        Assert.Equal(5, InheritedLiteralRank.NewBuilder().Build().Rank);

        Assert.Equal(0, ModelSnapshot.ReadPlain<ImplicitRate>("{}").Rate);
        Assert.Equal(71, ModelSnapshot.ReadPlain<LocalLiteralRate>("{}").Rate);
        Assert.Equal(0, ModelSnapshot.ReadPlain<ImplicitRank>("{}").Rank);
        Assert.Equal(17, ModelSnapshot.ReadPlain<LocalLiteralRank>("{}").Rank);

        string rateWire = ModelSnapshot.WritePlain(localRate);
        string rankWire = ModelSnapshot.WritePlain(localRank);
        VerifyLocalWire(rateWire, rankWire);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_HIERARCHY") is { } exchange)
        {
            VerifyLocalWire(File.ReadAllText(Path.Combine(exchange, "java-literal-local-rate.json")),
                File.ReadAllText(Path.Combine(exchange, "java-literal-local-rank.json")));
            var explicitRate = ModelSnapshot.ReadPlain<LocalLiteralRate>(
                File.ReadAllText(Path.Combine(exchange, "java-literal-rate.json")));
            Assert.Equal(23, explicitRate.Rate);
            OnlyField(explicitRate, "rate", 71).Reset();
            Assert.Equal(71, explicitRate.Rate);
            Assert.Equal(5, ModelSnapshot.ReadPlain<LocalLiteralRank>(
                File.ReadAllText(Path.Combine(exchange, "java-literal-rank.json"))).Rank);
            File.WriteAllText(Path.Combine(exchange, "csharp-literal-local-rate.json"), rateWire);
            File.WriteAllText(Path.Combine(exchange, "csharp-literal-local-rank.json"), rankWire);
        }
    }

    private static void VerifyLocalWire(string rateWire, string rankWire)
    {
        AssertWireField(rateWire, "chosen_rate", 71);
        AssertWireField(rankWire, "chosen_rank", 17);
        Assert.Equal(71, ModelSnapshot.ReadPlain<LocalLiteralRate>(rateWire).Rate);
        Assert.Equal(17, ModelSnapshot.ReadPlain<LocalLiteralRank>(rankWire).Rank);
    }

    private static void VerifyWire(string rateWire, string rankWire)
    {
        AssertWireField(rateWire, "chosen_rate", 23);
        AssertWireField(rankWire, "chosen_rank", 5);
        Assert.Equal(23, ModelSnapshot.ReadPlain<LiteralRate>(rateWire).Rate);
        Assert.Equal(5, ModelSnapshot.ReadPlain<LiteralRank>(rankWire).Rank);
        var reversed = ModelSnapshot.ReadPlain<ReversedLiteralRate>(rateWire);
        Assert.Equal(23, reversed.Rate);
        OnlyField(reversed, "rate", 42).Reset();
        Assert.Equal(42, reversed.Rate);
    }

    private static void AssertWireField(string wire, string name, int value)
    {
        using var document = JsonDocument.Parse(wire);
        var member = Assert.Single(document.RootElement.EnumerateObject());
        Assert.Equal(name, member.Name);
        Assert.Equal(value, member.Value.GetInt32());
    }

    private static ModelProperty OnlyField(object owner, string name, int expectedDefault)
    {
        var property = Assert.Single(ModelSnapshot.Reflection.Reflect(owner).Properties);
        Assert.Equal(name, property.Name);
        Assert.Equal(expectedDefault, Assert.IsType<int>(property.DefaultValue));
        return property;
    }
}
