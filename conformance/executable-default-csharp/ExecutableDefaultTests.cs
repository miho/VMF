using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Executabledefaults;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ExecutableDefaultTests
{
    [Fact]
    public void BuilderCopyUsesStagedScalarValuesInEffectivePropertyOrder()
    {
        ExecutableDefaultValues.RestartSequence();
        var source = new JoinedDefaults();
        var target = new JoinedDefaults();
        Events("joined-hook:null/null", "alias-1", "base-2", "joined-hook:null/null", "alias-3", "base-4");
        var builder = JoinedDefaults.NewBuilder();
        Events("alias-5", "base-6");

        var applied = new List<string>();
        using var observer = target.VmfChanges.AddLocalListener(change => applied.Add(change.PropertyName));
        Assert.Same(builder, builder.ApplyTo(target));
        Assert.Equal("alias-5", target.Alias);
        Assert.Equal("base-6", target.Label);
        Assert.Equal(["alias", "label"], applied);
        Events();

        source.Alias = "imported";
        source.Label = null;
        Assert.Same(builder, builder.ApplyFrom(source));
        source.Alias = "changed after import";
        builder.ApplyTo(target);
        Assert.Equal("imported", target.Alias);
        Assert.Null(target.Label);
        Events();

        var built = builder.Build();
        Assert.Equal("imported", built.Alias);
        Assert.Null(built.Label);
        Events("joined-hook:null/null", "alias-7", "base-8");
    }

    [Fact]
    public void MutableBuilderAndReflectionEvaluateDefaultsAtTheJavaBoundaries()
    {
        var direct = new MutableDefaults();
        Assert.Equal("prepared", direct.Label);
        Assert.Equal(28, direct.Quota);
        Events("label", "quota");

        var staged = MutableDefaults.NewBuilder();
        Events("label", "quota");
        var overridden = staged.WithLabel(null).WithQuota(73).Build();
        Assert.Null(overridden.Label);
        Assert.Equal(73, overridden.Quota);
        Events("label", "quota");

        var label = Field(overridden, "label");
        var quota = Field(overridden, "quota");
        Assert.True(label.IsSet);
        Assert.True(quota.IsSet);
        Assert.Equal("prepared", label.DefaultValue);
        Assert.Equal(28, quota.DefaultValue);
        label.Reset();
        quota.Reset();
        Assert.Equal("prepared", overridden.Label);
        Assert.Equal(28, overridden.Quota);
        Events("label", "quota", "label", "quota", "label", "quota");
    }

    [Fact]
    public void ImmutableBuilderEvaluatesOnceAndRetainsBoxedSignedZero()
    {
        var staged = ImmutableDefaults.NewBuilder();
        Events("label", "scale");
        var immutable = staged.Build();
        Assert.Equal("prepared", immutable.Label);
        Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(Assert.IsType<double>(immutable.Scale)));
        Events();

        var overridden = ImmutableDefaults.NewBuilder().WithLabel(null).WithScale(+0.0).Build();
        Assert.Null(overridden.Label);
        Assert.Equal(0L, BitConverter.DoubleToInt64Bits(Assert.IsType<double>(overridden.Scale)));
        Events("label", "scale");
        var scale = Field(overridden, "scale");
        Assert.True(scale.IsSet);
        Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(Assert.IsType<double>(scale.DefaultValue)));
        Events("scale", "scale");
        Assert.Throws<InvalidOperationException>(scale.Reset);
    }

    [Fact]
    public void ClassHookRunsOnRawValuesBeforeDeclaredDefaultsAndBuilderAssignments()
    {
        var direct = new HookedDefaults();
        Assert.Equal("prepared", direct.Label);
        Assert.Equal(41, direct.Priority);
        Events("hook:null/0", "label", "priority");

        var staged = HookedDefaults.NewBuilder();
        Events("label", "priority");
        var overridden = staged.WithPriority(73).Build();
        Assert.Equal("prepared", overridden.Label);
        Assert.Equal(73, overridden.Priority);
        Events("hook:null/0", "label", "priority");
    }

    [Fact]
    public void SnapshotPreservesExplicitNullAndCurrentValues()
    {
        var changed = MutableDefaults.NewBuilder().WithLabel(null).WithQuota(73).Build();
        string wire = ModelSnapshot.WritePlain(changed);
        using var json = JsonDocument.Parse(wire);
        Assert.Equal(JsonValueKind.Null, json.RootElement.GetProperty("label").ValueKind);
        Assert.Equal(73, json.RootElement.GetProperty("quota").GetInt32());

        var restored = ModelSnapshot.ReadPlain<MutableDefaults>(wire);
        Assert.Null(restored.Label);
        Assert.Equal(73, restored.Quota);
        Assert.True(Field(restored, "quota").IsSet);
        ExecutableDefaultValues.TakeEvents();
    }

    [Fact]
    public void StaticReflectionMarksBoundDefaultsWithoutRunningProviders()
    {
        foreach (var type in new[] { typeof(MutableDefaults), typeof(ImmutableDefaults), typeof(HookedDefaults) })
        {
            var metadata = ModelSnapshot.TypeCatalog.Find(type)!;
            Assert.True(metadata.FindProperty("label")!.HasExecutableDefault);
            Assert.Null(metadata.FindProperty("label")!.DeclaredDefault);
            Assert.All(metadata.Properties, field => Assert.True(field.HasExecutableDefault));
        }

        using var schema = JsonDocument.Parse(ModelSnapshot.GenerateJsonSchema<MutableDefaults>());
        var properties = schema.RootElement.GetProperty("properties");
        Assert.False(properties.GetProperty("label").TryGetProperty("default", out _));
        Assert.False(properties.GetProperty("quota").TryGetProperty("default", out _));
        Events();
    }

    [Fact]
    public void PrimaryMutableInheritanceAppliesOnlyEffectiveProvidersInModelOrder()
    {
        ExecutableDefaultValues.RestartSequence();
        var joined = new JoinedDefaults();
        Assert.Equal("alias-1", joined.Alias);
        Assert.Equal("base-2", joined.Label);
        Events("joined-hook:null/null", "alias-1", "base-2");

        var builder = JoinedDefaults.NewBuilder();
        Events("alias-3", "base-4");
        joined = builder.Build();
        Assert.Equal("alias-3", joined.Alias);
        Assert.Equal("base-4", joined.Label);
        Events("joined-hook:null/null", "alias-5", "base-6");

        AncestorDefaults replacement = new ReplacedDefaults();
        Assert.Equal("replacement-7", replacement.Label);
        var field = Field(replacement, "label");
        Assert.Equal("short_label", field.WireName);
        Assert.Equal("replacement-8", field.DefaultValue);
        field.Reset();
        Assert.Equal("replacement-9", replacement.Label);
        Events("replacement-7", "replacement-8", "replacement-9");

        Assert.Equal("literal", new LiteralChildDefaults().Label);
        Assert.Null(new ClearedChildDefaults().Label);
        Assert.False(ModelSnapshot.TypeCatalog.Find(typeof(ClearedChildDefaults))!.FindProperty("label")!.HasExecutableDefault);
        Events();
    }

    [Fact]
    public void PrimaryImmutableInheritanceKeepsTheEffectiveStagedOrExplicitValue()
    {
        ExecutableDefaultValues.RestartSequence();
        var inherited = InheritedValueDefaults.NewBuilder();
        Events("base-value-1");
        Assert.Equal("base-value-1", inherited.Build().Label);
        Events();

        var replaced = ReplacedValueDefaults.NewBuilder();
        Events("derived-value-2");
        AncestorValueDefaults value = replaced.Build();
        Assert.Equal("derived-value-2", value.Label);
        Assert.Null(replaced.WithLabel(null).Build().Label);
        Assert.Null(ClearedValueDefaults.NewBuilder().Build().Label);
        Events();

        Assert.Equal("derived-value-3", new ReplacedValueDefaults().Label);
        Events("derived-value-3");
        Assert.Equal("derived-value-4", ModelSnapshot.ReadPlain<ReplacedValueDefaults>("{}").Label);
        Events("derived-value-4");
        var hydrated = ModelSnapshot.ReadPlain<ReplacedValueDefaults>("""{"fixed_label":null}""");
        Assert.Null(hydrated.Label);
        Events("derived-value-5");
        Assert.Contains("\"fixed_label\":null", ModelSnapshot.WritePlain(hydrated));
        Events("derived-value-6");
    }

    private static ModelProperty Field(object model, string name) => ModelSnapshot.Reflection.Reflect(model).FindProperty(name)!;

    private static void Events(params string[] expected) => Assert.Equal(expected, ExecutableDefaultValues.TakeEvents());
}
