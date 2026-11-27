using Eu.Mihosoft.Vmftest.Genericseries;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class StockSeriesBuilderTests
{
    [Fact]
    public void AppendAndReplaceWorkAcrossInheritedAndConcreteInputs()
    {
        var source = TemperatureSeries.NewBuilder().WithSamples([2.0]).Build();
        SampleSeries<double?> inherited = source;
        var builder = TemperatureSeries.NewBuilder().WithSamples([1.0]);

        builder.ApplyFrom(inherited).ApplyFrom(source);
        Assert.Equal(new double?[] { 1.0, 2.0, 2.0 }, builder.Build().Samples);
        builder.AppendCollections(false).ApplyFrom(source);
        Assert.Equal(new double?[] { 2.0 }, builder.Build().Samples);

        var targetList = source.Samples;
        TemperatureSeries.NewBuilder().ApplyTo(source);
        Assert.Equal(new double?[] { 2.0 }, targetList);
        TemperatureSeries.NewBuilder().AppendCollections(false).ApplyTo(source);
        Assert.Empty(targetList);
        Assert.Same(targetList, source.Samples);
    }

    [Fact]
    public void ImportCapturesNullableMembersAndLeavesStagedValuesIndependent()
    {
        var source = TemperatureSeries.NewBuilder().WithLatest(1.5).WithSamples([null, 2.5]).Build();
        var builder = TemperatureSeries.NewBuilder().ApplyFrom((NumericSeries<double>)source);
        source.Samples.Clear();
        source.Latest = 7.5;

        var built = builder.Build();
        Assert.Equal(1.5, built.Latest);
        Assert.Equal(new double?[] { null, 2.5 }, built.Samples);
        builder.WithSamples([3.5]).ApplyTo(source);
        Assert.Equal(1.5, source.Latest);
        Assert.Equal(new double?[] { 3.5 }, source.Samples);
        Assert.Equal(new double?[] { null, 2.5 }, built.Samples);
    }
}
