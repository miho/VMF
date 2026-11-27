using Eu.Mihosoft.Vmf.Conformance.Referencedefaults;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed partial class InheritedMixedDefaultsTests
{
    [Fact]
    public void PassiveConcreteRoleSharesTheReferenceDefaultOwnersLifecycle()
    {
        InheritedMixedDefaultBindings.Events.Clear();
        var owner = new ComposedDefaultSelectionLeaf();
        Calls("ahead", "queue", "summary", "zulu");
        IPassiveConcreteRole role = owner;
        DefaultPrimary primary = owner;
        var view = owner.AsReadOnly();
        Assert.Equal("north", role.Region);
        Assert.Equal("early", owner.Ahead);
        Assert.Equal([4, 5], owner.Summary);
        Assert.Same(owner.Queue[0], Members(Field(primary, "queue"))[0]);
        Assert.Same(owner.Queue[0].AsReadOnly(), Members(Field(view, "queue"))[0]);
        Assert.Same(view, role.AsReadOnly());
        role.Region = "south";
        Assert.Equal("south", view.Region);

        var builder = ComposedDefaultSelectionLeaf.NewBuilder();
        Calls("queue", "ahead", "summary", "zulu");
        var built = builder.Build();
        Calls("ahead", "summary", "zulu");
        var empty = builder.WithQueue([]).WithRank(18).Build();
        Calls("ahead", "summary", "zulu");
        Assert.Empty(empty.Queue);
        Assert.Same(Field(built, "queue").DefaultValue, Field(empty, "queue").DefaultValue);
        Assert.Equal(18, empty.Rank);
        Assert.Equal("north", empty.Region);

        var deep = ModelSnapshot.Reflection.DeepCopy(owner);
        Calls("ahead", "summary", "zulu");
        var shallow = ModelSnapshot.Reflection.ShallowCopy(owner);
        Calls("ahead", "summary", "zulu");
        Assert.Equal("south", deep.Region);
        Assert.Equal("south", shallow.Region);
        Assert.Same(deep.Queue[0], Members(Field(deep, "queue"))[0]);
        Assert.NotSame(owner.Queue[0], deep.Queue[0]);
        Assert.Same(Field(primary, "queue").DefaultValue, Field(shallow, "queue").DefaultValue);
        Assert.Same(owner.Queue[0], shallow.Queue[0]);
    }
}
