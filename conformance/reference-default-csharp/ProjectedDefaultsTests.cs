using Eu.Mihosoft.Vmf.Conformance.Referencedefaults;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed partial class ProjectedDefaultsTests
{
    private static readonly string[] CopyOrder = ["accent", "ahead", "before", "marks", "numbers", "path", "prunes", "summary", "zulu"];

    [Fact]
    public void SecondaryDefaultProvidersBindOnceAndEachConcreteHostCapturesItsOwnMembership()
    {
        ProjectedDefaultBindings.Events.Clear();
        var primary = new ProjectionPrimary();
        Calls("before", "numbers", "path");
        var queueRole = new ProjectedQueueRole();
        Calls("queue", "summary", "zulu");
        var favoriteRole = new ProjectedFavoriteRole();
        Calls("accent", "favorites");

        var calibration = new ProjectedCalibrationRole();
        Calls("marks", "prunes");

        var owner = new ProjectedDefaultsLeaf();
        Calls("accent", "ahead", "before", "favorites", "marks", "numbers", "path", "prunes", "queue", "summary", "zulu");
        IProjectedQueueRoot queues = owner;
        IProjectedFavoriteRoot favorites = owner;
        var queueSeed = queues.Queue[0];
        var favoriteSeed = Assert.Single(favorites.Favorites);
        Assert.Same(queueSeed, Captured(queues, "queue")[0]);
        Assert.Same(favoriteSeed, Assert.Single(Captured(favorites, "favorites")));
        Assert.Same(queueSeed.AsReadOnly(), Captured(queues.AsReadOnly(), "queue")[0]);
        Assert.Same(favoriteSeed.AsReadOnly(), Assert.Single(Captured(favorites.AsReadOnly(), "favorites")));
        Assert.NotSame(Captured(queueRole, "queue")[0], queueSeed);
        Assert.NotSame(Assert.Single(Captured(favoriteRole, "favorites")), favoriteSeed);
        Assert.Equal(primary.Numbers, owner.Numbers);
        Assert.Equal([4, 5], owner.Summary);
        Assert.Equal(calibration.Marks, owner.Marks);
        Assert.Equal(calibration.Prunes, owner.Prunes);
        Assert.Equal("before", owner.Before);
        Assert.Equal("path", owner.Path);
        Assert.Equal("concrete", owner.Label);
        Assert.Equal("ahead", owner.Ahead);

        var builder = ProjectedDefaultsLeaf.NewBuilder();
        Calls("favorites", "queue", "accent", "ahead", "before", "marks", "numbers", "path", "prunes", "summary", "zulu");
        var populated = builder.Build();
        Calls(CopyOrder);
        var empty = builder.WithQueue([]).WithFavorites([]).WithNotes("configured").WithRank(18).Build();
        Calls(CopyOrder);
        Assert.Empty(empty.Queue);
        Assert.Empty(empty.Favorites);
        Assert.Same(Captured(populated, "queue"), Captured(empty, "queue"));
        Assert.Same(Captured(populated, "favorites"), Captured(empty, "favorites"));
        Assert.Equal("configured", empty.Notes);
        Assert.Equal(18, empty.Rank);
        Field(empty, "favorites").Reset();
        Assert.Same(Assert.Single(populated.Favorites), Assert.Single(empty.Favorites));
        Calls();

        var deep = ModelSnapshot.Reflection.DeepCopy(owner);
        Calls(CopyOrder);
        var shallow = ModelSnapshot.Reflection.ShallowCopy(owner);
        Calls(CopyOrder);
        Assert.NotSame(queueSeed, deep.Queue[0]);
        Assert.NotSame(favoriteSeed, Assert.Single(deep.Favorites));
        Assert.Same(deep.Queue[0], Captured(deep, "queue")[0]);
        Assert.Same(Assert.Single(deep.Favorites), Assert.Single(Captured(deep, "favorites")));
        Assert.Same(queueSeed, shallow.Queue[0]);
        Assert.Same(favoriteSeed, Assert.Single(shallow.Favorites));
        Assert.Same(Captured(owner, "queue"), Captured(shallow, "queue"));
        Assert.Same(Captured(owner, "favorites"), Captured(shallow, "favorites"));
    }

    private static ModelProperty Field(object owner, string name) =>
        ModelSnapshot.Reflection.Reflect(owner).FindProperty(name)!;

    private static IReadOnlyList<object> Captured(object owner, string name) =>
        Assert.IsAssignableFrom<IReadOnlyList<object>>(Field(owner, name).DefaultValue);

    private static void Calls(params string[] expected)
    {
        Assert.Equal(expected, ProjectedDefaultBindings.Events);
        ProjectedDefaultBindings.Events.Clear();
    }
}
