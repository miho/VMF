using Eu.Mihosoft.Vmf.Example.Workshops;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ReadOnlyTests
{
    [Fact]
    public void ViewsAreLiveStableAndDoNotExposeMutators()
    {
        var left = new Workshop();
        var right = new Workshop();
        var station = new Station { Name = "initial" };
        left.Stations.Add(station);
        left.Favorites.Add(station);

        IReadOnlyWorkshop view = left.AsReadOnly();
        var stations = view.Stations;
        IReadOnlyStation readStation = stations[0];
        Assert.Same(view, left.AsReadOnly());
        Assert.Same(readStation, station.AsReadOnly());
        Assert.Same(view, readStation.Workshop);
        Assert.Same(readStation, Assert.Single(view.Favorites));
        Assert.False(stations is IList<IReadOnlyStation>);
        Assert.False(readStation.GetType().GetProperty("Name")?.CanWrite);
        Assert.False(readStation.GetType().GetProperty("Workshop")?.CanWrite);

        station.Name = "renamed";
        Assert.Equal("renamed", readStation.Name);
        station.Workshop = right;
        Assert.Empty(view.Stations);
        Assert.Empty(stations);
        Assert.Same(readStation, Assert.Single(view.Favorites));
        Assert.Same(right.AsReadOnly(), readStation.Workshop);

        var site = new WorkshopSite();
        site.Workshops.Add(left);
        site.Workshops.Add(right);
        var restored = ModelSnapshot.Read<WorkshopSite>(ModelSnapshot.Write(site.AsReadOnly()));
        Assert.Same(restored.Workshops[1].Stations[0], Assert.Single(restored.Workshops[0].Favorites));
    }

    [Fact]
    public void GenericViewRetainsTheConcreteReadOnlyType()
    {
        var catalog = new Catalog();
        var thermometer = new Thermometer { Sku = "T-1", Unit = "C" };
        catalog.Products.Add(thermometer);
        catalog.Featured = thermometer;

        IReadOnlyShelf<IReadOnlyInstrument> view = catalog.AsReadOnly();
        IReadOnlyInstrument featured = Assert.IsAssignableFrom<IReadOnlyInstrument>(view.Featured);
        Assert.Same(featured, view.Products[0]);
        Assert.IsAssignableFrom<IReadOnlyThermometer>(featured);
        thermometer.Sku = "T-2";
        Assert.Equal("T-2", view.Products[0].Sku);
    }
}
