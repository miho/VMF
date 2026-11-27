using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Traits;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class GetterOnlyTraitTests
{
    [Fact]
    public void MutableTemplateUsesSetImmutableOverlayFieldsAndPreservesItsSharedIdentities()
    {
        var amber = ColorToken.NewBuilder().WithCode("amber").Build();
        var blue = ColorToken.NewBuilder().WithCode("blue").Build();
        var template = MutableBadge.NewBuilder().WithName("editable").WithOrigin("lab").WithTags(["existing"])
            .WithAccent(amber).WithSwatches([amber]).Build();
        var overlay = FixedBadge.NewBuilder().WithCategory("overlay-only").WithName("fixed").WithTags(["append"])
            .WithAccent(blue).WithSwatches([blue]).Build();
        var reflection = ModelSnapshot.Reflection;

        var merged = Assert.IsType<MutableBadge>(reflection.Merge<IReadOnlyBadgeTraits>(template, overlay));
        Assert.NotSame(template, merged);
        Assert.Equal("fixed", merged.Name);
        Assert.Equal("lab", merged.Origin);
        Assert.Equal(["existing", "append"], merged.Tags);
        Assert.Same(blue, merged.Accent);
        Assert.Contains(merged.Swatches, token => ReferenceEquals(token, blue));
        Assert.Contains(merged.Swatches, token => ReferenceEquals(token, amber));
        Assert.Equal(2, merged.Swatches.Count);
        Assert.Null(reflection.Reflect(merged).FindProperty("category"));
        Assert.Equal("editable", template.Name);
        Assert.Same(amber, template.Accent);
        Assert.Equal("existing", Assert.Single(template.Tags));
        Assert.Equal("append", Assert.Single(overlay.Tags));

        var clearsOrigin = FixedBadge.NewBuilder().WithOrigin(null).Build();
        var cleared = Assert.IsType<MutableBadge>(reflection.Merge<IReadOnlyBadgeTraits>(template, clearsOrigin));
        Assert.Equal("editable", cleared.Name);
        Assert.Null(cleared.Origin);
    }

    [Fact]
    public void ImmutableTemplateKeepsItsIdentityOnlyWhenTheOverlayMakesNoWrites()
    {
        var token = ColorToken.NewBuilder().WithCode("held").Build();
        var template = FixedBadge.NewBuilder().WithName("fixed").WithScores([new("held", 7)]).WithSwatches([token]).Build();
        var reflection = ModelSnapshot.Reflection;

        Assert.Same(template, reflection.Merge<IReadOnlyBadgeTraits>(template, new MutableBadge()));
        Assert.Same(template, reflection.Merge(template, FixedBadge.NewBuilder().Build()));
        Assert.Throws<ArgumentException>(() => reflection.Merge(template, template));
        Assert.Throws<ArgumentException>(() => reflection.Merge<IReadOnlyBadgeTraits>(template,
            MutableBadge.NewBuilder().WithName("fixed").Build()));
        Assert.Throws<ArgumentException>(() => reflection.Merge<IReadOnlyBadgeTraits>(template,
            MutableBadge.NewBuilder().WithTags(["appended"]).Build()));
        Assert.Throws<ArgumentException>(() => reflection.Merge<IReadOnlyBadgeTraits>(template,
            MutableBadge.NewBuilder().WithSwatches([token]).Build()));
        Assert.Throws<ArgumentException>(() => reflection.Merge<IReadOnlyBadgeTraits>(template,
            MutableBadge.NewBuilder().WithScores([new("held", 7)]).Build()));
        Assert.Empty(template.Tags);
        Assert.Same(token, Assert.Single(template.Swatches));
        Assert.Equal(7L, template.Scores["held"]);
    }

    [Fact]
    public void MutableAndImmutableModelsMeetOneGetterContract()
    {
        var token = ColorToken.NewBuilder().WithCode("blue").Build();
        var fixedBadge = CreateFixed(token);
        var mutable = CreateMutable(token);
        IReadOnlyBadgeTraits fixedFields = fixedBadge;
        IReadOnlyBadgeTraits liveFields = mutable;
        IReadOnlySourceTraits fixedSource = fixedBadge;
        IReadOnlySourceTraits liveSource = mutable;
        Assert.Equal("draft", fixedFields.Name);
        Assert.Null(fixedSource.Origin);
        Assert.Equal("reflected", liveFields.Name);
        Assert.Equal("lab", liveSource.Origin);
        Assert.Same(token, fixedFields.Accent);
        Assert.Same(token, liveFields.Accent);
        Assert.False(fixedFields.Tags is ICollection<string?> fixedList && !fixedList.IsReadOnly);
        Assert.False(liveFields.Tags is ICollection<string?> liveList && !liveList.IsReadOnly);

        var name = ModelSnapshot.Reflection.Reflect(liveFields).FindProperty("name")!;
        Assert.True(name.CanWrite);
        name.Set("updated");
        mutable.Tags.Add("update");
        Assert.Equal("updated", liveFields.Name);
        Assert.Equal("update", liveFields.Tags[^1]);
        Assert.Equal("draft", fixedFields.Name);
        Assert.False(ModelSnapshot.Reflection.Reflect(fixedFields).FindProperty("name")!.CanWrite);
        Assert.False(ModelSnapshot.Reflection.Reflect(mutable.AsReadOnly()).FindProperty("name")!.CanWrite);
        Assert.Same(fixedBadge, ModelSnapshot.Reflection.ProjectReadOnly(fixedFields));
        Assert.Same(mutable.AsReadOnly(), ModelSnapshot.Reflection.ProjectReadOnly(liveFields));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void MixedGetterRoleImportsIntoExistingObservableStorage(bool append)
    {
        var accent = ColorToken.NewBuilder().WithCode("blue").Build();
        var former = ColorToken.NewBuilder().WithCode("amber").Build();
        IReadOnlyBadgeTraits source = FixedBadge.NewBuilder().WithName(null).WithAccent(accent).WithSwatches([accent])
            .WithMeasures([3, 4]).WithTags(["source", null, "source"]).WithScores([new("shared", 9L), new("empty", null)]).Build();
        var target = MutableBadge.NewBuilder().WithName("old").WithOrigin("target").WithAccent(former).WithSwatches([former])
            .WithMeasures([2]).WithTags(["previous"]).WithScores([new("old", 1L), new("shared", 2L)]).Build();
        var view = target.AsReadOnly();
        var changes = new List<string>();
        using var watch = target.VmfChanges.AddLocalListener(change => changes.Add(change.PropertyName));

        var staged = MutableBadge.NewBuilder().WithOrigin("staged").AppendCollections(append).ApplyFrom(source);
        Assert.Empty(changes);
        Assert.Equal("old", view.Name);
        Assert.Same(former, view.Accent);
        var built = staged.Build();
        Assert.Null(built.Name);
        Assert.Equal("staged", built.Origin);
        Assert.Same(accent, built.Accent);
        Assert.Same(accent, Assert.Single(built.Swatches));
        Assert.Equal(source.Tags, built.Tags);
        Assert.Equal(source.Measures, built.Measures);
        Assert.Equal(source.Scores, built.Scores);

        staged.ApplyTo(target);
        Assert.Null(view.Name);
        Assert.Equal("staged", view.Origin);
        Assert.Same(accent, view.Accent);
        Assert.Contains(view.Swatches, token => ReferenceEquals(accent, token));
        Assert.Equal(append, view.Swatches.Contains(former));
        Assert.Equal(append ? [2, 3, 4] : [3, 4], view.Measures);
        Assert.Equal(append ? ["previous", "source", null, "source"] : ["source", null, "source"], view.Tags);
        Assert.Equal(9L, view.Scores["shared"]);
        Assert.Null(view.Scores["empty"]);
        Assert.Equal(append, view.Scores.ContainsKey("old"));
        Assert.Equal(append ? ["accent", "measures", "name", "origin", "scores", "swatches", "tags"]
            : ["accent", "measures", "measures", "name", "origin", "scores", "scores", "swatches", "swatches", "tags", "tags"],
            changes);
    }

    [Fact]
    public void MixedGetterOnlyTypesCrossLanguageReferenceAndValueSnapshots()
    {
        var token = ColorToken.NewBuilder().WithCode("blue").Build();
        var fixedBadge = CreateFixed(token);
        var mutable = CreateMutable(token);
        var graph = BadgeShowcase.NewBuilder().WithOwned([mutable]).WithSelected(fixedBadge)
            .WithChoices([fixedBadge, null, mutable, fixedBadge]).Build();
        var values = BadgeShowcase.NewBuilder().WithSelected(fixedBadge).WithChoices([fixedBadge, null, fixedBadge]).Build();
        string references = ModelSnapshot.Write(graph);
        string byValue = ModelSnapshot.WriteWithoutReferences(values);
        VerifyReferences(references);
        VerifyValues(byValue);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_TRAITS") is { } directory)
        {
            VerifyReferences(File.ReadAllText(Path.Combine(directory, "java-reference.json")));
            VerifyValues(File.ReadAllText(Path.Combine(directory, "java-values.json")));
            File.WriteAllText(Path.Combine(directory, "csharp-reference.json"), references);
            File.WriteAllText(Path.Combine(directory, "csharp-values.json"), byValue);
        }
    }

    private static FixedBadge CreateFixed(ColorToken token) =>
        FixedBadge.NewBuilder().WithCategory("archive").WithOrigin(null).WithTags(["fixed", null, "fixed"])
            .WithMeasures([3, 4]).WithScores([new("high", 9), new("empty", null)])
            .WithAccent(token).WithSwatches([token]).Build();

    private static MutableBadge CreateMutable(ColorToken token) =>
        MutableBadge.NewBuilder().WithName("reflected").WithOrigin("lab").WithTags(["draft", null, "draft", "live"])
            .WithMeasures([1, 2]).WithScores([new("high", 9), new("empty", null), new("later", 5)])
            .WithAccent(token).WithSwatches([token]).Build();

    private static void VerifyReferences(string json)
    {
        var model = ModelSnapshot.Read<BadgeShowcase>(json);
        var fixedBadge = Assert.IsType<FixedBadge>(model.Selected);
        var mutable = Assert.Single(model.Owned);
        VerifyFixed(fixedBadge);
        Assert.Equal("reflected", mutable.Name);
        Assert.Equal("lab", mutable.Origin);
        Assert.Equal(new string?[] { "draft", null, "draft", "live" }, mutable.Tags);
        Assert.Equal(new[] { 1, 2 }, mutable.Measures);
        Assert.Equal(5L, mutable.Scores["later"]);
        Assert.Same(fixedBadge.Accent, mutable.Accent);
        Assert.Same(fixedBadge.Accent, Assert.Single(fixedBadge.Swatches));
        Assert.Same(mutable.Accent, Assert.Single(mutable.Swatches));
        Assert.Same(fixedBadge, model.Choices[0]);
        Assert.Null(model.Choices[1]);
        Assert.Same(mutable, model.Choices[2]);
        Assert.Same(fixedBadge, model.Choices[3]);
        Assert.Equal(typeof(IReadOnlyBadgeTraits), ModelSnapshot.Reflection.Reflect(model).FindProperty("selected")!.ValueType);
        var view = model.AsReadOnly();
        Assert.Same(fixedBadge, view.Selected);
        Assert.Same(fixedBadge, view.Choices[0]);
        Assert.Same(view.Owned[0], view.Choices[2]);
    }

    private static void VerifyValues(string json)
    {
        using var wire = JsonDocument.Parse(json);
        Assert.Equal("fixed-badge", wire.RootElement.GetProperty("selected").GetProperty("@vmf-type").GetString());
        Assert.False(wire.RootElement.GetProperty("selected").TryGetProperty("@vmf-id", out _));
        var model = ModelSnapshot.ReadWithoutReferences<BadgeShowcase>(json);
        var item = Assert.IsType<FixedBadge>(model.Selected);
        VerifyFixed(item);
        Assert.Empty(model.Owned);
        Assert.NotSame(item, model.Choices[0]);
        Assert.Null(model.Choices[1]);
        Assert.NotSame(model.Choices[0], model.Choices[2]);
        Assert.NotSame(item.Accent, Assert.Single(item.Swatches));
    }

    private static void VerifyFixed(FixedBadge model)
    {
        Assert.Equal("archive", model.Category);
        Assert.Equal("draft", model.Name);
        Assert.Null(model.Origin);
        Assert.Equal(new string?[] { "fixed", null, "fixed" }, model.Tags);
        Assert.Equal(new[] { 3, 4 }, model.Measures);
        Assert.Equal(9L, model.Scores["high"]);
        Assert.True(model.Scores.ContainsKey("empty"));
        Assert.Null(model.Scores["empty"]);
        Assert.Equal("blue", model.Accent?.Code);
        Assert.Equal("blue", Assert.Single(model.Swatches).Code);
    }
}
