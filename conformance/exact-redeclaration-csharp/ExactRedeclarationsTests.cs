using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Exactredeclarations;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ExactRedeclarationsTests
{
    [Fact]
    public void ReaffirmedMembersUseSingleStorageAcrossPhysicalAndCompanionContracts()
    {
        var value = CombinedRecord.NewBuilder().Build();
        FirstRecord first = value;
        ISecondRecord second = value;
        IWithMarkers markers = value;
        Assert.Equal("record", first.Caption);
        Assert.Equal(first.Caption, second.Caption);

        var asset = new Asset { Id = "asset" };
        var observed = new List<ModelChange>();
        using var watch = value.VmfChanges.AddLocalListener(observed.Add);
        second.Caption = "changed";
        second.Assets.Add(asset);
        markers.Markers.AddRange(["first", null, "first"]);

        Assert.Equal("changed", first.Caption);
        Assert.Same(first.Assets, second.Assets);
        Assert.Same(asset, Assert.Single(first.Assets));
        Assert.Same(markers.Markers, value.Markers);
        Assert.Equal(new string?[] { "first", null, "first" }, value.Markers);
        Assert.Same(value.AsReadOnly(), second.AsReadOnly());
        Assert.Equal(["caption", "assets", "markers"], observed.Select(change => change.PropertyName));
        Assert.Equal(ModelChangeKind.List, observed[2].Kind);

        var reflection = ModelSnapshot.Reflection.Reflect(value);
        Assert.Equal(3, reflection.Properties.Count);
        Assert.Equal("captionText", reflection.FindProperty("caption")?.WireName);
        Assert.False(reflection.FindProperty("markers")?.IsContainment);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void LocalScalarWireNameUsesOneSlotAcrossAgreeingTraits(bool references)
    {
        var value = new LocallyNamedRecord();
        WithLegacyName first = value;
        IWithLegacyRank second = value;
        first.Name = "first";
        Assert.Equal("first", second.Name);
        second.Name = "winner";
        value.Rank = 3;
        Assert.Equal("winner", first.Name);
        Assert.Equal("winner", value.AsReadOnly().Name);
        var reflection = ModelSnapshot.Reflection.Reflect(value);
        Assert.Equal(2, reflection.Properties.Count);
        Assert.Equal("name", reflection.FindProperty("name")?.WireName);

        string Write<T>(T root) where T : class => references ? ModelSnapshot.Write(root) : ModelSnapshot.WritePlain(root);
        var sibling = JsonNode.Parse(Write(new LegacyNamedRecord { Name = "sibling" }))!;
        Assert.Equal("sibling", sibling["label"]?.GetValue<string>());
        Assert.Null(sibling["name"]);

        string wire = Write(value);
        var json = JsonNode.Parse(wire)!;
        Assert.Equal("winner", json["name"]?.GetValue<string>());
        Assert.Null(json["label"]);
        Verify(wire);

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXACT_REDECLARATIONS");
        if (exchange is null) return;
        string file = $"local-name-{references.ToString().ToLowerInvariant()}.json";
        string fromJava = File.ReadAllText(Path.Combine(exchange, "java-" + file));
        Assert.True(JsonNode.DeepEquals(json, JsonNode.Parse(fromJava)));
        Verify(fromJava);
        File.WriteAllText(Path.Combine(exchange, "csharp-" + file), wire);

        void Verify(string snapshot)
        {
            var loaded = references ? ModelSnapshot.Read<LocallyNamedRecord>(snapshot)
                : ModelSnapshot.ReadPlain<LocallyNamedRecord>(snapshot);
            Assert.Equal("winner", loaded.Name);
            Assert.Equal(3, loaded.Rank);
        }
    }

    [Fact]
    public void SecondaryTypedFieldUsesEffectiveWireNamesAndRestoresTheComposedValue()
    {
        var value = CombinedRecord.NewBuilder().WithCaption("changed").WithAssets([new Asset { Id = "asset" }])
            .WithMarkers(["first", null, "first"]).Build();
        string wire = ModelSnapshot.WriteWithoutReferences(new RecordEnvelope { Record = value });
        const string Expected = """
            {"record":{"@vmf-type":"eu.mihosoft.vmf.conformance.exactredeclarations.CombinedRecord","assets":[{"id":"asset"}],
              "captionText":"changed","markers":["first",null,"first"]}}
            """;
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(Expected), JsonNode.Parse(wire)));
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXACT_REDECLARATIONS");
        if (exchange is not null)
        {
            Directory.CreateDirectory(exchange);
            File.WriteAllText(Path.Combine(exchange, "csharp-record.json"), wire);
            wire = File.ReadAllText(Path.Combine(exchange, "java-record.json"));
        }

        var restored = Assert.IsType<CombinedRecord>(ModelSnapshot.ReadWithoutReferences<RecordEnvelope>(wire).Record);
        Assert.Equal("changed", restored.Caption);
        Assert.Equal("asset", Assert.Single(restored.Assets)?.Id);
        Assert.Equal(new string?[] { "first", null, "first" }, ((IWithMarkers)restored).Markers);
    }
}
