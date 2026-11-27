using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Redeclarations;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class RedeclarationTests
{
    [Fact]
    public void MixedTraitPromotesOnlyTheConcreteSetterAndKeepsItsCollectionLive()
    {
        var concrete = new Settings();
        MixedSettings trait = concrete;
        Assert.False(typeof(MixedSettings).GetProperty(nameof(trait.Name))?.SetMethod?.IsPublic == true);
        Assert.True(typeof(MixedSettings).GetProperty(nameof(trait.Level))?.SetMethod?.IsPublic == true);
        Assert.True(typeof(Settings).GetProperty(nameof(concrete.Name))?.SetMethod?.IsPublic == true);
        trait.Level = 3;
        trait.Measures.Add(7);
        Assert.Equal(3, concrete.Level);
        Assert.Equal([7], concrete.Measures);

        var view = concrete.AsReadOnly();
        var name = ModelSnapshot.Reflection.Reflect(concrete).FindProperty("name")!;
        var readOnlyName = ModelSnapshot.Reflection.Reflect(view).FindProperty("name")!;
        Assert.True(name.CanWrite);
        Assert.False(readOnlyName.CanWrite);
        Assert.Equal("display_name", name.WireName);
        Assert.Equal("origin", name.DefaultValue);
        var edits = new List<object?>();
        using var subscription = concrete.VmfChanges.AddPropertyListener("name", change => edits.Add(change.NewValue));
        concrete.Name = "assigned";
        name.Set("reflected");
        Assert.True(name.IsSet);
        Assert.Equal("reflected", trait.Name);
        Assert.Equal("reflected", view.Name);
        Assert.Throws<InvalidOperationException>(() => readOnlyName.Set("blocked"));
        name.Reset();
        Assert.False(name.IsSet);
        Assert.Equal("origin", view.Name);
        Assert.Equal(new[] { "assigned", "reflected", "origin" }, edits);
    }

    [Fact]
    public void RedeclarationLayersDefaultsAndValidationOverOnePhysicalSlot()
    {
        var derived = Reconfigured.NewBuilder().WithName("from builder").WithLevel(6).Build();
        Settings asBase = derived;
        MixedSettings asTrait = derived;
        Assert.Equal("from builder", asBase.Name);
        Assert.Equal(6, asTrait.Level);
        asBase.Name = "via base";
        asBase.Level = 8;
        Assert.Equal("via base", derived.Name);
        Assert.Equal(8, derived.Level);

        var fields = ModelSnapshot.Reflection.Reflect(derived);
        var name = Assert.Single(fields.Properties, property => property.Name == "name");
        var level = Assert.Single(fields.Properties, property => property.Name == "level");
        Assert.Equal("name", name.WireName);
        Assert.EndsWith(".Reconfigured", name.DeclaringType);
        Assert.Equal("reconfigured", name.DefaultValue);
        Assert.Equal(4, level.DefaultValue);
        name.Reset();
        level.Reset();
        Assert.Equal("reconfigured", asTrait.Name);
        Assert.Equal(4, asBase.Level);
        Assert.False(name.IsSet);
        Assert.False(level.IsSet);
        asBase.Level = 1;
        Assert.Single(derived.Validate(), issue => issue.ModelCode == "vmf.range" && issue.ModelPath == "/level");
        asBase.Level = 0;
        var issues = derived.Validate();
        Assert.Equal(2, issues.Count);
        Assert.All(issues, issue =>
        {
            Assert.Equal("vmf.range", issue.ModelCode);
            Assert.Equal("/level", issue.ModelPath);
        });

        var implicitDefaults = new Unconfigured();
        Assert.Null(implicitDefaults.Name);
        Assert.Equal(0, implicitDefaults.Level);
        var explicitNull = new ExplicitNull();
        Assert.Null(explicitNull.Name);
        var explicitField = ModelSnapshot.Reflection.Reflect(explicitNull).FindProperty("name")!;
        Assert.Equal("optional_name", explicitField.WireName);
        explicitField.Set("present");
        explicitField.Reset();
        Assert.Null(explicitNull.Name);
    }

    [Fact]
    public void ImmutableRedeclarationsPassTheEffectiveValueToTheirBaseConstructor()
    {
        FrozenSetting original = FrozenSetting.NewBuilder().Build();
        FrozenSetting revised = RevisedSetting.NewBuilder().Build();
        Assert.Equal("original", original.Label);
        Assert.Equal(0, original.Limit);
        Assert.Null(revised.Label);
        Assert.Equal(6, revised.Limit);
        var label = Assert.Single(ModelSnapshot.Reflection.Reflect(revised).Properties, field => field.Name == "label");
        Assert.Equal("caption", label.WireName);
        Assert.Null(label.DefaultValue);
        Assert.False(label.CanWrite);
        Assert.Equal(0, new RevisedSetting(limit: 0).Limit);
        Assert.Null(new FrozenSetting(label: new OptionalValue<string?>(null)).Label);
    }

    [Fact]
    public void SchemaPreservesBothRangesOnARedeclaredScalar()
    {
        var schema = JsonNode.Parse(ModelSnapshot.GenerateJsonSchema<Reconfigured>(SnapshotSchemaProfile.Plain));
        var level = schema!["properties"]!["level"]!;
        var declared = Assert.Single(level["allOf"]!.AsArray())!;
        Assert.Equal(2, declared["minimum"]!.GetValue<int>());
        Assert.Equal(8, declared["maximum"]!.GetValue<int>());
        var inherited = Assert.Single(declared["allOf"]!.AsArray())!;
        Assert.Equal(1, inherited["minimum"]!.GetValue<int>());
        Assert.Equal(9, inherited["maximum"]!.GetValue<int>());
    }

    [Fact]
    public void TypedSnapshotsUseEffectiveFieldAliasesAndExplicitNullsAcrossRuntimes()
    {
        var specimen = SettingCatalog.NewBuilder().WithEntries([
            Settings.NewBuilder().WithMeasures([7]).Build(),
            Reconfigured.NewBuilder().WithMeasures([5, 6]).Build(),
            Reconfigured.NewBuilder().WithName(null).Build(),
            new Unconfigured(),
            ExplicitNull.NewBuilder().WithName("visible").Build()
        ]).WithFrozen(RevisedSetting.NewBuilder().WithLabel("retained").Build()).Build();
        string native = ModelSnapshot.WriteWithoutReferences(specimen);
        VerifyWire(native);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_REDECLARATIONS");
        if (exchange is null) return;
        string java = File.ReadAllText(Path.Combine(exchange, "java-redeclarations.json"));
        VerifyWire(java);
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(native)));
        File.WriteAllText(Path.Combine(exchange, "csharp-redeclarations.json"), native);
    }

    private static void VerifyWire(string wire)
    {
        var snapshot = JsonNode.Parse(wire)!;
        var document = snapshot["entries"]!.AsArray();
        Assert.Equal(5, document.Count);
        Assert.Equal("origin", document[0]!["display_name"]!.GetValue<string>());
        Assert.False(document[0]!.AsObject().ContainsKey("name"));
        Assert.Equal("reconfigured", document[1]!["name"]!.GetValue<string>());
        Assert.False(document[1]!.AsObject().ContainsKey("display_name"));
        Assert.True(document[2]!.AsObject().ContainsKey("name"));
        Assert.Null(document[2]!["name"]);
        Assert.False(document[3]!.AsObject().ContainsKey("name"));
        Assert.Equal("visible", document[4]!["optional_name"]!.GetValue<string>());

        Assert.Equal("retained", snapshot["frozen"]!["caption"]!.GetValue<string>());
        Assert.False(snapshot["frozen"]!.AsObject().ContainsKey("label"));
        var restored = ModelSnapshot.ReadWithoutReferences<SettingCatalog>(wire);
        var entries = restored.Entries;
        VerifyEntry(Assert.IsType<Settings>(entries[0]), "origin", "origin", 2);
        VerifyEntry(Assert.IsType<Reconfigured>(entries[1]), "reconfigured", "reconfigured", 4);
        VerifyEntry(Assert.IsType<Reconfigured>(entries[2]), null, "reconfigured", 4);
        VerifyEntry(Assert.IsType<Unconfigured>(entries[3]), null, null, 0);
        VerifyEntry(Assert.IsType<ExplicitNull>(entries[4]), "visible", null, 0);
        Assert.Equal([7], entries[0].Measures);
        Assert.Equal([5, 6], entries[1].Measures);
        var frozen = Assert.IsType<RevisedSetting>(restored.Frozen);
        Assert.Equal("retained", frozen.Label);
        Assert.Equal(6, frozen.Limit);
    }

    private static void VerifyEntry(Settings entry, string? expected, string? declared, int levelDefault)
    {
        var fields = ModelSnapshot.Reflection.Reflect(entry);
        var name = Assert.Single(fields.Properties, property => property.Name == "name");
        var level = Assert.Single(fields.Properties, property => property.Name == "level");
        Assert.Equal(expected, entry.Name);
        Assert.Equal(declared, name.DefaultValue);
        Assert.Equal(expected != declared, name.IsSet);
        Assert.Equal(levelDefault, entry.Level);
        Assert.Equal(levelDefault, level.DefaultValue);
    }
}
