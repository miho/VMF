using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Eu.Mihosoft.Vmf.Example.Workshops;
using Xunit;
using Snapshot = Eu.Mihosoft.Vmf.Conformance.Values.ModelSnapshot;

namespace Vmf.CSharp.Conformance;

public class DefaultTests
{
    [Fact]
    public void ConstructorsBuildersAndMissingSnapshotFieldsUseTheDeclaredDefaults()
    {
        VerifyDefaults(new DefaultSettings());
        VerifyDefaults(DefaultSettings.NewBuilder().Build());
        VerifyDefaults(Snapshot.ReadPlain<DefaultSettings>("{}"));
        VerifyDefaults(Snapshot.Read<DefaultSettings>("{}"));
        Assert.Equal("idle\nready", DefaultBase.NewBuilder().Build().Name);
    }

    [Fact]
    public void BuildersCopyCollectionInputsAndKeepPreviousStateAfterRejectedEdits()
    {
        var stages = new List<string> { "check", "publish" };
        var builder = DefaultSettings.NewBuilder().WithName(null).WithLimit(null).WithStages(stages);
        stages[1] = "external edit";
        Assert.ThrowsAny<ArgumentException>(() => builder.WithStages(["check", "check"]));
        Assert.ThrowsAny<ArgumentException>(() => builder.WithStages(["a", "b", "c", "d"]));

        var first = builder.Build();
        first.Stages.Move(1, 0);
        var second = builder.Build();
        Assert.NotSame(first, second);
        Assert.Equal(["publish", "check"], first.Stages);
        Assert.Equal(["check", "publish"], second.Stages);
        Assert.Null(second.Name);
        Assert.Null(second.Limit);
        Assert.Equal("new", DefaultEntry.NewBuilder().Build().Label);
    }

    [Fact]
    public void BuilderPreservesTheConcreteGenericRelationshipTypes()
    {
        var item = Thermometer.NewBuilder().WithSku("T-1").WithUnit("C").Build();
        var catalog = Catalog.NewBuilder().WithProducts([item]).WithFeatured(item).Build();
        Assert.Same(item, Assert.Single(catalog.Products));
        Assert.Same(item, catalog.Featured);
        Assert.Equal("T-1", Assert.IsType<Thermometer>(catalog.Featured).Sku);
    }

    [Fact]
    public void ExplicitNullOverridesDefaultInBothJsonProfilesAndLanguages()
    {
        var model = DefaultSettings.NewBuilder().WithName(null).WithLimit(null)
            .WithStages(["check", "publish"]).WithEntries([DefaultEntry.NewBuilder().Build()]).Build();
        string plain = Snapshot.WritePlain(model);
        string references = Snapshot.Write(model);
        VerifySnapshot(plain, references: false);
        VerifySnapshot(references, references: true);

        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_DEFAULTS");
        if (interchange is not null)
        {
            VerifySnapshot(File.ReadAllText(Path.Combine(interchange, "java-plain.json")), references: false);
            VerifySnapshot(File.ReadAllText(Path.Combine(interchange, "java-references.json")), references: true);
            File.WriteAllText(Path.Combine(interchange, "csharp-plain.json"), plain);
            File.WriteAllText(Path.Combine(interchange, "csharp-references.json"), references);
        }
    }

    private static void VerifyDefaults(DefaultSettings model)
    {
        Assert.Equal("idle\nready", model.Name);
        Assert.Equal(7, model.Limit);
        Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(model.Baseline));
        Assert.Equal(0.3125f, model.Ratio);
        Assert.Equal('中', model.Mode);
        Assert.Equal(9_007_199_254_740_993L, model.Sequence);
        Assert.True(model.Flag);
        Assert.Null(model.UnsetValue);
        Assert.Null(model.Note);
    }

    private static void VerifySnapshot(string json, bool references)
    {
        using var document = JsonDocument.Parse(json);
        Assert.Equal(JsonValueKind.Null, document.RootElement.GetProperty("display_name").ValueKind);
        Assert.Equal(JsonValueKind.Null, document.RootElement.GetProperty("limit").ValueKind);
        Assert.False(document.RootElement.TryGetProperty("note", out _));
        var model = references ? Snapshot.Read<DefaultSettings>(json) : Snapshot.ReadPlain<DefaultSettings>(json);
        Assert.Null(model.Name);
        Assert.Null(model.Limit);
        Assert.Equal(["check", "publish"], model.Stages);
        Assert.Equal("new", Assert.Single(model.Entries).Label);
        Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(model.Baseline));
    }
}
