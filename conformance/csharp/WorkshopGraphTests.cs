using System.Text.Json;
using Eu.Mihosoft.Vmf.Example.Workshops;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class WorkshopGraphTests
{
    public static IEnumerable<object[]> OperationPrefixes()
    {
        using var json = JsonDocument.Parse(File.ReadAllText(
            Path.Combine(AppContext.BaseDirectory, "fixtures", "workshop-operations.json")));
        int index = 0;
        foreach (var step in json.RootElement.EnumerateArray())
        {
            yield return [index++, step.GetProperty("op").GetString()!];
        }
    }

    [Theory]
    [MemberData(nameof(OperationPrefixes))]
    public void SharedOperationsMatchTheGraphContract(int index, string operation)
    {
        using var json = JsonDocument.Parse(File.ReadAllText(
            Path.Combine(AppContext.BaseDirectory, "fixtures", "workshop-operations.json")));
        var workshops = new Dictionary<string, Workshop> { ["left"] = new(), ["right"] = new() };
        var stations = new[] { "a", "b", "c", "d" }
            .ToDictionary(id => id, id => new Station { Name = id });
        var ids = new Dictionary<Station, string>(ReferenceEqualityComparer.Instance);
        foreach (var (id, station) in stations)
        {
            ids.Add(station, id);
        }

        var steps = json.RootElement;
        Assert.Equal(operation, steps[index].GetProperty("op").GetString());
        for (int i = 0; i <= index; i++)
        {
            Apply(steps[i], workshops, stations);
        }

        var expected = steps[index];
        Assert.Equal(ReadIds(expected, "left"), workshops["left"].Stations.Select(item => ids[item]));
        Assert.Equal(ReadIds(expected, "right"), workshops["right"].Stations.Select(item => ids[item]));
        Assert.Equal(ReadIds(expected, "favorites"), workshops["left"].Favorites.Select(item => ids[item]).Order());
        Assert.Empty(workshops["right"].Favorites);
        Assert.Equal(expected.GetProperty("leftValid").GetBoolean(), workshops["left"].Validate().Count == 0);
        Assert.Equal(expected.GetProperty("rightValid").GetBoolean(), workshops["right"].Validate().Count == 0);
        foreach (var station in stations.Values)
        {
            var expectedParent = workshops.Values.SingleOrDefault(workshop => workshop.Stations.Contains(station));
            Assert.Same(expectedParent, station.Workshop);
        }
    }

    private static void Apply(JsonElement step, Dictionary<string, Workshop> workshops,
        Dictionary<string, Station> stations)
    {
        var shop = step.TryGetProperty("shop", out var shopId) ? workshops[shopId.GetString()!] : null;
        var station = step.TryGetProperty("item", out var itemId) ? stations[itemId.GetString()!] : null;
        switch (step.GetProperty("op").GetString())
        {
            case "add":
                Assert.Equal(step.GetProperty("result").GetBoolean(), shop!.Stations.Add(station!));
                break;
            case "remove":
                Assert.Equal(step.GetProperty("result").GetBoolean(), shop!.Stations.Remove(station!));
                break;
            case "favorite":
                Assert.Equal(step.GetProperty("result").GetBoolean(), shop!.Favorites.Add(station!));
                break;
            case "duplicate-insert":
                Assert.Throws<ArgumentException>(() => shop!.Stations.Insert(0, station!));
                break;
            case "invalid-move":
                Assert.Throws<ArgumentOutOfRangeException>(() => shop!.Stations.Move(0, shop.Stations.Count));
                break;
            case "move":
                shop!.Stations.Move(step.GetProperty("from").GetInt32(), step.GetProperty("to").GetInt32());
                break;
            case "set-parent":
                station!.Workshop = shop;
                break;
            case "rename":
                station!.Name = step.GetProperty("name").GetString();
                break;
            default:
                throw new InvalidOperationException("Unknown workshop operation.");
        }
    }

    private static IEnumerable<string> ReadIds(JsonElement expected, string name) =>
        expected.GetProperty(name).EnumerateArray().Select(id => id.GetString()!);
}
