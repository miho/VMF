using System.Reflection;
using System.Text.Json;
using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Covariance;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class CovarianceTests
{
    [Fact]
    public void ImportsCovariantReferencesThroughAnAncestorGetter()
    {
        var classified = new ClassifiedItem { Code = "picked", Category = "type" };
        PickTrait compatible = new ExactPick { Current = classified };
        var staged = NarrowPick.NewBuilder().ApplyFrom(compatible);
        Assert.Same(classified, staged.Build().Current);

        var target = new NarrowPick();
        var view = target.AsReadOnly();
        staged.ApplyTo(target);
        Assert.Same(classified, target.Current);
        Assert.Same(classified.AsReadOnly(), view.Current);

        PickTrait incompatible = new ExactPick { Current = new PickableItem { Code = "broad" } };
        Assert.Throws<InvalidCastException>(() => staged.ApplyFrom(incompatible));
        Assert.Same(classified, staged.Build().Current);

        staged.ApplyFrom((PickTrait)new ExactPick()).ApplyTo(target);
        Assert.Null(target.Current);
        Assert.Null(view.Current);
        Assert.Same(classified, ExactPick.NewBuilder().ApplyFrom(compatible).Build().Current);
    }

    [Fact]
    public void NarrowMutableAndReadOnlyContractsShareOneReferenceSlot()
    {
        var first = new ClassifiedItem { Code = "first", Category = "classified" };
        var broader = new PickableItem { Code = "broad" };
        var narrow = NarrowPick.NewBuilder().WithCurrent(first).Build();
        PickTrait basePick = narrow;
        IReadOnlyPickTrait baseView = narrow.AsReadOnly();
        IReadOnlyClassifiedItem? narrowReadOnly = narrow.AsReadOnly().Current;
        Assert.Same(first, basePick.Current);
        Assert.Same(first.AsReadOnly(), narrowReadOnly);
        Assert.Same(narrowReadOnly, baseView.Current);
        Assert.Same(narrowReadOnly, ((IReadOnlyPickTrait)basePick).Current);

        const BindingFlags declared = BindingFlags.Instance | BindingFlags.Public | BindingFlags.DeclaredOnly;
        var broadProperty = typeof(PickTrait).GetProperty(nameof(PickTrait.Current), declared)!;
        Assert.False(broadProperty.SetMethod?.IsPublic == true);
        Assert.Equal(typeof(ClassifiedItem), typeof(NarrowPick).GetProperty(nameof(NarrowPick.Current), declared)!.PropertyType);
        var field = Assert.Single(ModelSnapshot.Reflection.Reflect(basePick).Properties, candidate => candidate.Name == "current");
        var viewed = ModelSnapshot.Reflection.Reflect(baseView).FindProperty("current")!;
        Assert.Equal(typeof(ClassifiedItem), field.ValueType);
        Assert.Equal("preferred", field.WireName);
        Assert.False(viewed.CanWrite);

        var seen = new List<ModelChange>();
        using var listener = narrow.VmfChanges.AddPropertyListener("current", seen.Add);
        Assert.Throws<InvalidCastException>(() => field.Set(broader));
        Assert.Empty(seen);
        Assert.Same(first, narrow.Current);
        narrow.Current = null;
        Assert.Null(baseView.Current);
        field.Set(first);
        Assert.Equal(new object?[] { null, first }, seen.Select(change => change.NewValue));
        Assert.Same(first, basePick.Current);
        Assert.Same(first.AsReadOnly(), viewed.Get());

        var exact = new ExactPick { Current = broader };
        Assert.Same(broader, ((PickTrait)exact).Current);
        Assert.Same(broader.AsReadOnly(), exact.AsReadOnly().Current);

        var reaffirmed = new ReaffirmedPick { Current = first };
        ClassifiedPickTrait narrowedTrait = reaffirmed;
        Assert.False(typeof(ClassifiedPickTrait).GetProperty(nameof(ClassifiedPickTrait.Current), declared)!.SetMethod?.IsPublic == true);
        Assert.Same(first, narrowedTrait.Current);
        Assert.Same(first, ((PickTrait)narrowedTrait).Current);
        Assert.Same(first.AsReadOnly(), ((IReadOnlyPickTrait)reaffirmed.AsReadOnly()).Current);
    }

    [Fact]
    public void ImmutableCovariantGetterReadsThePrimaryBaseStorage()
    {
        var item = new ColorTag(color: "green", title: "active");
        var narrow = new FrozenColorChoice(current: item);
        FrozenChoice wide = narrow;
        ColorTag? typed = narrow.Current;
        Assert.Same(typed, wide.Current);
        Assert.Same(item, typed);
        Assert.Same(narrow, narrow.AsReadOnly());
        var field = Assert.Single(ModelSnapshot.Reflection.Reflect(wide).Properties, candidate => candidate.Name == "current");
        Assert.Equal(typeof(ColorTag), field.ValueType);
        Assert.Equal("color_tag", field.WireName);
        Assert.False(field.CanWrite);
        Assert.Same(item, field.Get());
        var immutableTrait = new FrozenTraitChoice(current: item);
        IReadOnlyFrozenPickTrait getterOnlyTrait = immutableTrait;
        Assert.Same(item, immutableTrait.Current);
        Assert.Same(item, getterOnlyTrait.Current);
        Assert.Same(immutableTrait, immutableTrait.AsReadOnly());
    }

    [Fact]
    public void MutableAndImmutableCovariantReferencesUseEffectiveWireContracts()
    {
        var broad = new PickableItem { Code = "broad" };
        var narrow = new ClassifiedItem { Code = "narrow", Category = "special" };
        var tag = new ColorTag(color: "green", title: "status");
        var showcase = ReferenceShowcase.NewBuilder().WithItems([broad, narrow]).WithExact(new ExactPick { Current = broad })
            .WithNarrow(new NarrowPick { Current = narrow })
            .WithReaffirmed(new ReaffirmedPick { Current = narrow })
            .WithFrozen(new FrozenColorChoice(current: tag)).WithTraitFrozen(new FrozenTraitChoice(current: tag)).Build();
        string native = ModelSnapshot.Write(showcase);
        VerifyWire(native);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_COVARIANCE");
        if (exchange is null) return;
        string java = File.ReadAllText(Path.Combine(exchange, "java-covariance.json"));
        VerifyWire(java);
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(native)));
        File.WriteAllText(Path.Combine(exchange, "csharp-covariance.json"), native);
    }

    [Fact]
    public void MergeAcceptsTheConcreteValueOfABroaderReference()
    {
        var original = new ClassifiedItem { Code = "original", Category = "kept" };
        var incoming = new ClassifiedItem { Code = "incoming", Category = "replacement" };
        var template = new NarrowPick { Current = original };
        var overlay = new ExactPick { Current = incoming };
        var reflection = ModelSnapshot.Reflection;

        var merged = Assert.IsType<NarrowPick>(reflection.Merge<PickTrait>(template, overlay));
        Assert.NotSame(template, merged);
        Assert.NotSame(incoming, merged.Current);
        Assert.Equal("incoming", merged.Current!.Code);
        Assert.Equal("replacement", merged.Current.Category);
        Assert.Same(merged.Current.AsReadOnly(), merged.AsReadOnly().Current);
        Assert.Same(original, template.Current);
        Assert.Same(incoming, overlay.Current);

        reflection.Reflect(overlay).FindProperty("current")!.SetDefault(incoming);
        overlay.Current = null;
        Assert.Null(Assert.IsType<NarrowPick>(reflection.Merge<PickTrait>(template, overlay)).Current);
        Assert.Same(original, template.Current);

        var incompatible = new PickableItem { Code = "too broad" };
        overlay.Current = incompatible;
        Assert.Throws<ArgumentException>(() => reflection.Merge<PickTrait>(template, overlay));
        Assert.Same(original, template.Current);
        Assert.Same(incompatible, overlay.Current);
    }

    private static void VerifyWire(string wire)
    {
        var tree = JsonNode.Parse(wire)!;
        Assert.True(tree["exact"]!.AsObject().ContainsKey("old_pick"));
        Assert.True(tree["narrow"]!.AsObject().ContainsKey("preferred"));
        Assert.False(tree["narrow"]!.AsObject().ContainsKey("old_pick"));
        Assert.True(tree["frozen"]!.AsObject().ContainsKey("color_tag"));
        Assert.False(tree["frozen"]!.AsObject().ContainsKey("old_tag"));
        Assert.True(tree["reaffirmed"]!.AsObject().ContainsKey("reaffirmed_pick"));
        Assert.True(tree["traitFrozen"]!.AsObject().ContainsKey("trait_color"));

        var restored = ModelSnapshot.Read<ReferenceShowcase>(wire);
        Assert.Same(restored.Items[0], restored.Exact!.Current);
        var narrow = restored.Narrow!;
        Assert.Same(restored.Items[1], narrow.Current);
        Assert.Equal("special", narrow.Current!.Category);
        IReadOnlyPickTrait wideView = narrow.AsReadOnly();
        Assert.Same(narrow.Current.AsReadOnly(), wideView.Current);
        Assert.Same(narrow.Current, restored.Reaffirmed!.Current);
        var frozen = Assert.IsType<FrozenColorChoice>(restored.Frozen);
        Assert.Equal("green", frozen.Current!.Color);
        Assert.Same(frozen.Current, ((FrozenChoice)frozen).Current);
        Assert.Same(frozen.Current, restored.TraitFrozen!.Current);

        tree["narrow"]!["preferred"] = tree["items"]![0]!["@vmf-id"]!.DeepClone();
        Assert.Throws<JsonException>(() => ModelSnapshot.Read<ReferenceShowcase>(tree.ToJsonString()));
    }
}
