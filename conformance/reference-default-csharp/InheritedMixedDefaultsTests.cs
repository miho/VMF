using System.Collections;
using Eu.Mihosoft.Vmf.Conformance.Referencedefaults;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed partial class InheritedMixedDefaultsTests
{
    [Fact]
    public void ConstructorsBuildersAndCopiesFollowTheEffectiveJavaProviderOrder()
    {
        InheritedMixedDefaultBindings.Events.Clear();
        var basis = new ExecutableSelectionBase();
        Calls("queue", "summary", "zulu");
        var owner = new ExecutableSelectionLeaf();
        Calls("ahead", "options", "queue", "summary", "zulu");
        var queue = Field(owner, "queue");
        var seed = owner.Queue[0];
        var fallback = queue.DefaultValue;
        Assert.Same(seed, Members(queue)[0]);
        Assert.Same(seed.AsReadOnly(), Members(Field(owner.AsReadOnly(), "queue"))[0]);
        Assert.Equal([4, 5], owner.Summary);
        Assert.Equal(17, owner.Options["port"]);

        Field(owner, "summary").SetDefault(new[] { 13 });
        owner.Summary.Clear();
        owner.Summary.Add(9);
        owner.Options.Put("port", null);
        owner.Ahead = "explicit";
        InheritedMixedDefaultBindings.Events.Clear();
        var deep = ModelSnapshot.Reflection.DeepCopy(owner);
        Calls("ahead", "options", "summary", "zulu");
        var shallow = ModelSnapshot.Reflection.ShallowCopy(owner);
        Calls("ahead", "options", "summary", "zulu");
        Assert.NotSame(seed, deep.Queue[0]);
        Assert.Same(deep.Queue[0], Members(Field(deep, "queue"))[0]);
        Assert.Same(seed, shallow.Queue[0]);
        Assert.Same(fallback, Field(shallow, "queue").DefaultValue);
        foreach (var copy in new[] { deep, shallow })
        {
            Assert.Equal("explicit", copy.Ahead);
            Assert.Equal(9, Assert.Single(copy.Summary));
            Assert.Equal(13, Assert.IsType<int>(Assert.Single(Members(Field(copy, "summary")))));
            Assert.True(copy.Options.TryGetValue("port", out int? value));
            Assert.Null(value);
        }
        Calls();

        var draft = ExecutableSelectionLeaf.NewBuilder().WithAhead(null).WithQueue([]).WithOptions([]);
        Calls("queue", "ahead", "options", "summary", "zulu");
        var first = draft.Build();
        Calls("ahead", "options", "summary", "zulu");
        var second = draft.Build();
        Calls("ahead", "options", "summary", "zulu");
        Assert.Null(first.Ahead);
        Assert.Empty(first.Queue);
        Assert.Empty(first.Options);
        Assert.Same(Field(first, "queue").DefaultValue, Field(second, "queue").DefaultValue);

        draft.AppendCollections(false).ApplyFrom(basis).ApplyTo(owner);
        Calls();
        Assert.Same(basis.Queue[0], owner.Queue[0]);
        Assert.Equal([4, 5], owner.Summary);
        Assert.Null(owner.Ahead);
        Assert.Empty(owner.Options);
        Assert.Same(fallback, queue.DefaultValue);
        queue.Reset();
        Assert.Same(seed, owner.Queue[0]);
        Calls();
    }

    [Fact]
    public void ManagedProvidersAloneAlsoRequireEffectiveConstructorOrder()
    {
        InheritedMixedDefaultBindings.Events.Clear();
        var model = new ManagedSelectionLeaf();
        Calls("summary", "queue");
        var staged = ManagedSelectionLeaf.NewBuilder();
        Calls("queue", "summary");
        var built = staged.Build();
        Calls("summary");
        var copy = ModelSnapshot.Reflection.DeepCopy(model);
        Calls("summary");
        Assert.Equal([4, 5], built.Earlier);
        Assert.NotSame(model.Queue[0], copy.Queue[0]);
    }

    private static ModelProperty Field(object owner, string name) =>
        ModelSnapshot.Reflection.Reflect(owner).FindProperty(name)!;

    private static object[] Members(ModelProperty field) => ((IEnumerable)field.DefaultValue!).Cast<object>().ToArray();

    private static void Calls(params string[] expected)
    {
        Assert.Equal(expected, InheritedMixedDefaultBindings.Events);
        InheritedMixedDefaultBindings.Events.Clear();
    }
}
