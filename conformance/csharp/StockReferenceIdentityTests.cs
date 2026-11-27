using Eu.Mihosoft.Vmftest.Referenceends;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class StockReferenceIdentityTests
{
    [Fact]
    public void HeldViewAddressesTheSameMemberInWritableOwningAndNonOwningSets()
    {
        var workshop = new Workshop();
        var first = new Station { Name = "Inspection" };
        var second = new Station { Name = "Inspection" };
        var absent = new Station { Name = "Inspection" };
        Assert.Equal(first, second);
        workshop.Stations.AddRange([first, second]);
        workshop.Favorites.AddRange([first, second]);
        var alias = second.AsReadOnly();
        IContainmentCollection<Station> writableStations = workshop.Stations;
        IReadOnlyReferenceSet<Station> writableFavorites = workshop.Favorites;
        Assert.True(writableFavorites.Contains(alias));
        Assert.True(workshop.Favorites.Contains(alias));
        Assert.True(writableStations.Contains(alias));
        Assert.Equal(1, workshop.Stations.IndexOf(alias));
        Assert.False(workshop.Stations.Contains(absent.AsReadOnly()));
        Assert.False(workshop.Favorites.Contains(absent.AsReadOnly()));
        Assert.False(workshop.Favorites.Remove(absent.AsReadOnly()));
        Assert.True(workshop.AsReadOnly().Favorites.Contains(second));

        var favorites = new List<ModelChange>();
        using var watchingFavorites = workshop.VmfChanges.AddPropertyListener("favorites", favorites.Add);
        Assert.True(workshop.Favorites.Remove(alias));
        Assert.False(workshop.Favorites.Remove(alias));
        Assert.Same(first, Assert.Single(workshop.Favorites));
        Assert.Same(workshop, second.Workshop);
        var favoriteEdit = Assert.IsType<ModelCollectionChange>(Assert.Single(favorites));
        Assert.Same(second, Assert.Single(favoriteEdit.Removed).Value);

        workshop.Stations.Move(1, 0);
        second.Name = "Calibration";
        Assert.Equal(0, workshop.Stations.IndexOf(alias));
        var ownerEdits = new List<ModelChange>();
        var inverseEdits = new List<ModelChange>();
        using var watchingOwner = workshop.VmfChanges.AddPropertyListener("stations", ownerEdits.Add);
        using var watchingInverse = second.VmfChanges.AddPropertyListener("workshop", inverseEdits.Add);
        Assert.True(writableStations.Remove(alias));
        Assert.False(workshop.Stations.Remove(alias));
        Assert.Null(second.Workshop);
        Assert.Same(first, Assert.Single(workshop.Stations));
        Assert.Same(first.AsReadOnly(), Assert.Single(workshop.AsReadOnly().Stations));
        var ownerEdit = Assert.IsType<ModelCollectionChange>(Assert.Single(ownerEdits));
        Assert.Same(second, Assert.Single(ownerEdit.Removed).Value);
        Assert.Null(Assert.Single(inverseEdits).NewValue);
    }

    [Fact]
    public void OrderedReciprocalSetRemovesByReadOnlyAliasAndClearsTheInverse()
    {
        var panel = new Panel();
        var link = new StationLink();
        Assert.True(panel.Links.Add(link));
        var alias = link.AsReadOnly();
        IReadOnlyOrderedReferenceSet<StationLink> ordered = panel.Links;
        Assert.True(ordered.Contains(alias));
        Assert.Equal(0, ordered.IndexOf(alias));
        Assert.True(panel.Links.Remove(alias));
        Assert.Empty(panel.Links);
        Assert.Empty(link.Panels);
        Assert.Equal(-1, ordered.IndexOf(alias));
    }

    [Fact]
    public void OrdinaryOwningListRemovesTheFirstEqualValueThroughAHeldView()
    {
        var group = new MixedGroup();
        var first = new Station { Name = "same" };
        var second = new Station { Name = "same" };
        group.Members.AddRange([first, second]);
        var alias = second.AsReadOnly();
        Assert.Equal(first, second);
        Assert.True(group.Members.Contains(alias));
        Assert.Equal(0, group.Members.IndexOf(alias));
        Assert.True(group.Members.Remove(alias));
        Assert.Same(second, Assert.Single(group.Members));
        Assert.Same(alias, Assert.Single(group.AsReadOnly().Members));
    }

    [Fact]
    public void OrdinaryReciprocalListUsesAReadOnlyAliasToClearItsOpposite()
    {
        var team = new Team();
        var member = new Member();
        member.Teams.Add(team);
        var view = team.AsReadOnly();
        Assert.True(member.Teams.Contains(view));
        Assert.Equal(0, member.Teams.IndexOf(view));
        Assert.True(member.Teams.Remove(view));
        Assert.Empty(member.Teams);
        Assert.Empty(team.Members);
    }
}
