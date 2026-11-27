using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Immutables;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class CreationCollectionJournalTests
{
    private static readonly double NegativeZero = BitConverter.Int64BitsToDouble(long.MinValue);

    [Theory]
    [InlineData("mapProperties", "text", "entries")]
    [InlineData("typedMapProperties", "typed", "entries")]
    [InlineData("setProperties", "set", "values")]
    public void NamedCreationCollectionsPreserveExplicitEmptyFields(string field, string name, string members)
    {
        const string model = "creation-test";
        var creation = new JournalCreation("child", "Payload", new Dictionary<string, IReadOnlyList<JournalValue>>(),
            mapProperties: field == "mapProperties" ? new Dictionary<string, IReadOnlyList<JournalMapEntry>> { [name] = [] } : null,
            typedMapProperties: field == "typedMapProperties" ? new Dictionary<string, IReadOnlyList<JournalMapEntry>> { [name] = [] } : null,
            setProperties: field == "setProperties" ? new Dictionary<string, IReadOnlyList<JournalValue>> { [name] = [] } : null);
        var journal = new ChangeJournal(model, 1, "root", "start", 0,
            [new JournalFrame(1, [new JournalSet("root", "child", JournalValue.FromScalar(null),
                JournalValue.Reference("child"), [creation])])]);
        var wire = JournalJson.Write(journal);
        var loaded = JournalJson.Read(wire);
        Assert.Equal(1, loaded.WireVersion);
        var node = JsonNode.Parse(wire)!;
        var value = Assert.Single(node["transactions"]![0]!["operations"]![0]!["created"]![0]![field]!.AsArray());
        Assert.Equal(name, value!["name"]!.GetValue<string>());
        Assert.Empty(value![members]!.AsArray());
        Assert.True(JsonNode.DeepEquals(node, JsonNode.Parse(JournalJson.Write(loaded))));
    }

    [Fact]
    public void MutableCreationReplacesDefaultMapsInOrderAndComparesSetsByMembership()
    {
        var registry = new SnapshotRegistry([
            new SnapshotType("Holder", typeof(Holder), () => new Holder(), [
                new("children", SnapshotPropertyKind.ContainmentList, typeof(Payload), owner => ((Holder)owner).Children,
                    add: (owner, value) => ((Holder)owner).Children.Add((Payload)value)),
            ]),
            new SnapshotType("Payload", typeof(Payload), () => new Payload(), [
                new("id", SnapshotPropertyKind.Scalar, typeof(string), owner => ((Payload)owner).Id,
                    set: (owner, value) => ((Payload)owner).Id = (string?)value),
                new("labels", SnapshotPropertyKind.ScalarMap, typeof(long?), owner => ((Payload)owner).Labels,
                    keyType: typeof(string), put: (owner, key, value) => ((Payload)owner).Labels.Put((string)key, (long?)value)),
                new("markers", SnapshotPropertyKind.ScalarMap, typeof(char?), owner => ((Payload)owner).Markers,
                    keyType: typeof(double), put: (owner, key, value) => ((Payload)owner).Markers.Put((double)key, (char?)value)),
                new("levels", SnapshotPropertyKind.ScalarSet, typeof(double), owner => ((Payload)owner).Levels,
                    add: (owner, value) => ((Payload)owner).Levels.Add((double)value)),
            ]),
        ]);
        var binding = new JournalBinding("creation-test", 1, value => value is Payload payload ? payload.Id! : "root");
        var working = new Holder();
        var original = new Holder();
        var child = new Payload { Id = "child" };
        child.Labels.Remove("first");
        child.Labels.Put("first", null);
        child.Markers.Put(NegativeZero, '-');
        child.Markers.Put(+0.0, null);
        child.Markers.Put(double.NaN, 'n');
        child.Levels.Clear();
        child.Levels.AddRange([4.0, 3.0]);

        var journal = JournalRecorder<Holder>.RecordFrame(working, registry, binding, "start", 0,
            root => root.Children.Add(child));
        Assert.Equal(1, journal.WireVersion);
        var creation = Assert.Single(Assert.Single(Assert.Single(journal.Transactions).Operations).Created);
        Assert.Equal(["second", "first"], creation.MapProperties["labels"].Select(entry => entry.Key.Text));
        Assert.Equal(["-0x0.0p0", "0x0.0p0", "NaN"], creation.TypedMapProperties["markers"].Select(entry => entry.Key.Text));
        Assert.Equal(["0x1.0p2", "0x1.8p1"], creation.SetProperties["levels"].Select(member => member.Text));

        var replayed = JournalReplayer.Replay(original, binding, "start", JournalJson.Read(JournalJson.Write(journal)), registry);
        var restored = Assert.Single(replayed.Children);
        Assert.Equal("child", restored.Id);
        Assert.Equal(["second", "first"], restored.Labels.Keys);
        Assert.Null(restored.Labels["first"]);
        Assert.Equal('-', restored.Markers[NegativeZero]);
        Assert.Null(restored.Markers[+0.0]);
        Assert.Equal('n', restored.Markers[double.NaN]);
        Assert.Equal(2, restored.Levels.Count);
        Assert.True(restored.Levels.Contains(3.0) && restored.Levels.Contains(4.0));
        Assert.Empty(original.Children);
    }

    [Fact]
    public void ImmutableCreationPreservesManagedCollectionsAndSharedIdentity()
    {
        var portfolio = ModelSnapshot.Read<ExtendedPortfolio>("""
            {
              "portfolio_status": null,
              "aliases": ["a", null, "a"],
              "levels": [0.0, -0.0, "NaN"],
              "limits": {"high": 9223372036854775807, "pending": null},
              "markers": [{"key": -0.0, "value": "-"}, {"key": 0.0, "value": null}],
              "current": {"@vmf-id": "badge", "text": "badge"},
              "trail": ["badge", null, "badge"],
              "preferred": ["badge"],
              "pool": ["badge"]
            }
            """);
        var binding = new JournalBinding("portfolio-creation", 1, value => value switch
        {
            PortfolioSelection => "selection",
            ExtendedPortfolio => "portfolio",
            ImmutableBadge badge => badge.Text!,
            _ => throw new ArgumentException("Unknown model type."),
        });
        var baseline = new PortfolioSelection();
        var working = new PortfolioSelection();
        var journal = JournalRecorder<PortfolioSelection>.RecordFrame(working, ModelSnapshot.Registry, binding, "start", 0,
            model => model.Portfolio = portfolio);
        Assert.Equal(1, journal.WireVersion);
        var operation = Assert.Single(Assert.Single(journal.Transactions).Operations);
        Assert.Equal(["badge", "portfolio"], operation.Created.Select(item => item.Id));
        var creation = operation.Created[1];
        Assert.Single(creation.MapProperties);
        Assert.Single(creation.TypedMapProperties);
        Assert.Single(creation.SetProperties);

        string wire = JournalJson.Write(journal);
        VerifyPortfolio(baseline, binding, JournalJson.Read(wire));
        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_COLLECTION_CREATION");
        if (interchange is not null)
        {
            string javaWire = File.ReadAllText(Path.Combine(interchange, "java-journal.json"));
            var javaBaseline = ModelSnapshot.Read<PortfolioSelection>(
                File.ReadAllText(Path.Combine(interchange, "java-checkpoint.json")));
            VerifyPortfolio(javaBaseline, binding, JournalJson.Read(javaWire));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(javaWire), JsonNode.Parse(wire)));
            File.WriteAllText(Path.Combine(interchange, "csharp-checkpoint.json"), ModelSnapshot.Write(baseline));
            File.WriteAllText(Path.Combine(interchange, "csharp-journal.json"), wire);
        }
    }

    private static void VerifyPortfolio(PortfolioSelection baseline, JournalBinding binding, ChangeJournal journal)
    {
        var replayed = JournalReplayer.Replay(baseline, binding, "start", journal, ModelSnapshot.Registry);
        var restored = Assert.IsType<ExtendedPortfolio>(replayed.Portfolio);
        Assert.Null(restored.Status);
        Assert.Equal(new string?[] { "a", null, "a" }, restored.Aliases);
        Assert.Equal(3, restored.Levels.Count);
        Assert.Equal(["high", "pending"], restored.Limits.Keys);
        Assert.Equal(long.MaxValue, restored.Limits["high"]);
        Assert.Null(restored.Limits["pending"]);
        Assert.Equal('-', restored.Markers[NegativeZero]);
        Assert.Null(restored.Markers[+0.0]);
        Assert.Same(restored.Current, restored.Trail[0]);
        Assert.Null(restored.Trail[1]);
        Assert.Same(restored.Current, restored.Trail[2]);
        Assert.Same(restored.Current, Assert.Single(restored.Preferred));
        Assert.Same(restored.Current, Assert.Single(restored.Pool));
        Assert.Null(baseline.Portfolio);
    }

    private sealed class Holder
    {
        public Holder()
        {
            var link = new ContainmentLink();
            Children = new(this, link, child => child.Link, new ModelChangeSource(this, link, "children"));
        }

        public ContainmentList<Holder, Payload> Children { get; }
    }

    private sealed class Payload
    {
        public Payload()
        {
            Labels.Put("first", 1);
            Labels.Put("second", 2);
            Levels.AddRange([3.0, 4.0]);
        }

        public ContainmentLink Link { get; } = new();
        public string? Id { get; set; }
        public ScalarMap<string, long?> Labels { get; } = new();
        public ScalarMap<double, char?> Markers { get; } = new();
        public ScalarSet<double> Levels { get; } = new();
    }
}
