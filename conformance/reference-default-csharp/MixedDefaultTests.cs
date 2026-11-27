using System.Collections;
using Eu.Mihosoft.Vmf.Conformance.Referencedefaults;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class MixedDefaultTests
{
    [Fact]
    public void MutableProvidersFollowJavaConstructionAndCopyOrder()
    {
        MixedDefaultBindings.Events.Clear();
        var model = new MixedDefaultPicks();
        Calls("caption", "favorites", "numbers", "options");
        var original = Assert.Single(model.Favorites);
        var reference = Field(model, "favorites");
        var originalDefault = reference.DefaultValue;
        Assert.False(reference.IsSet);
        Assert.Same(original, Assert.Single(Members(reference)));
        Assert.Same(original.AsReadOnly(), Assert.Single(Members(Field(model.AsReadOnly(), "favorites"))));
        Calls();

        model.Caption = "source";
        Field(model, "numbers").SetDefault(new[] { 13 });
        model.Numbers.Clear();
        model.Numbers.Add(9);
        model.Options.Put("port", null);
        MixedDefaultBindings.Events.Clear();

        var deep = ModelSnapshot.Reflection.DeepCopy(model);
        Calls("caption", "numbers", "options");
        var shallow = ModelSnapshot.Reflection.ShallowCopy(model);
        Calls("caption", "numbers", "options");
        var copied = Assert.Single(deep.Favorites);
        Assert.NotSame(original, copied);
        Assert.Same(copied, Assert.Single(Members(Field(deep, "favorites"))));
        Assert.Same(original, Assert.Single(shallow.Favorites));
        Assert.Same(originalDefault, Field(shallow, "favorites").DefaultValue);
        foreach (var copy in new[] { deep, shallow })
        {
            Assert.Equal("source", copy.Caption);
            Assert.Equal(9, Assert.Single(copy.Numbers));
            Assert.Equal(13, Assert.Single(Members(Field(copy, "numbers")).Cast<int>()));
            Assert.True(copy.Options.TryGetValue("port", out var present));
            Assert.Null(present);
        }
        Calls();

        var builder = MixedDefaultPicks.NewBuilder().WithCaption(null)
            .WithFavorites([]).WithNumbers([]).WithOptions([]);
        Calls("favorites", "caption", "numbers", "options");
        var first = builder.Build();
        Calls("caption", "numbers", "options");
        var second = builder.Build();
        Calls("caption", "numbers", "options");
        Assert.Null(first.Caption);
        Assert.Empty(first.Favorites);
        Assert.Empty(first.Numbers);
        Assert.Empty(first.Options);
        Assert.Same(Field(first, "favorites").DefaultValue, Field(second, "favorites").DefaultValue);

        var draft = MixedDefaultPicks.NewBuilder();
        Calls("favorites", "caption", "numbers", "options");
        draft.AppendCollections(false).ApplyFrom(first).ApplyTo(model);
        Calls();
        Assert.Null(model.Caption);
        Assert.Empty(model.Favorites);
        Assert.Empty(model.Numbers);
        Assert.Empty(model.Options);
        Assert.True(reference.IsSet);
        Assert.Same(originalDefault, reference.DefaultValue);
        reference.Reset();
        Assert.Same(original, Assert.Single(model.Favorites));
        Calls();

        MixedDefaultBindings.DuplicateSeed = true;
        try
        {
            Assert.Throws<ArgumentException>(() => new MixedDefaultPicks());
            Calls("caption", "favorites");
            Assert.Throws<ArgumentException>(() => MixedDefaultPicks.NewBuilder());
            Calls("favorites");
        }
        finally
        {
            MixedDefaultBindings.DuplicateSeed = false;
        }
    }

    [Fact]
    public void ImmutableBuildersRetainCachedIdentitiesAndExplicitValues()
    {
        MixedDefaultBindings.Events.Clear();
        var staged = MixedFrozenPicks.NewBuilder().WithCaption(null).WithNumbers([]);
        Calls("tokens", "caption", "numbers");
        var first = staged.Build();
        var second = staged.Build();
        Calls();
        var original = Assert.Single(first.Favorites);
        var field = Field(first, "favorites");
        Assert.Null(first.Caption);
        Assert.Empty(first.Numbers);
        Assert.Same(original, Assert.Single(second.Favorites));
        Assert.Same(original, Assert.Single(Members(field)));
        Assert.False(field.IsSet);
        Assert.Same(first, first.AsReadOnly());
        Assert.Same(first, ModelSnapshot.Reflection.DeepCopy(first));
        Calls();
    }

    private static ModelProperty Field(object owner, string name) =>
        ModelSnapshot.Reflection.Reflect(owner).FindProperty(name)!;

    private static IEnumerable<object> Members(ModelProperty field) =>
        ((IEnumerable)field.DefaultValue!).Cast<object>();

    private static void Calls(params string[] expected)
    {
        Assert.Equal(expected, MixedDefaultBindings.Events);
        MixedDefaultBindings.Events.Clear();
    }
}
