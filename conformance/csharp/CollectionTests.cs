using System.Text.Json;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class CollectionTests
{
    private static readonly string Fixture = Path.Combine(AppContext.BaseDirectory, "fixtures", "scalar-collections.json");
    private static readonly double NegativeZero = BitConverter.Int64BitsToDouble(long.MinValue);

    [Fact]
    public void ManagedScalarCollectionsRoundTripInBothProfiles()
    {
        var model = ReadFixture();
        Verify(model);
        string plain = ModelSnapshot.WritePlain(model.AsReadOnly());
        string references = ModelSnapshot.Write(model.AsReadOnly());
        Verify(ModelSnapshot.ReadPlain<CollectionProfile>(plain));
        Verify(ModelSnapshot.Read<CollectionProfile>(references));

        using var json = JsonDocument.Parse(plain);
        Assert.False(json.RootElement.TryGetProperty("@vmf-id", out _));
        Assert.Equal(JsonValueKind.Object, json.RootElement.GetProperty("limits").ValueKind);
        Assert.Equal(JsonValueKind.Array, json.RootElement.GetProperty("markers").ValueKind);
        Assert.Equal(long.MaxValue, json.RootElement.GetProperty("limits").GetProperty("limit").GetInt64());

        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_COLLECTIONS");
        if (interchange is not null)
        {
            Verify(ModelSnapshot.ReadPlain<CollectionProfile>(
                File.ReadAllText(Path.Combine(interchange, "collections-java-plain.json"))));
            Verify(ModelSnapshot.Read<CollectionProfile>(
                File.ReadAllText(Path.Combine(interchange, "collections-java-references.json"))));
            File.WriteAllText(Path.Combine(interchange, "collections-csharp-plain.json"), plain);
            File.WriteAllText(Path.Combine(interchange, "collections-csharp-references.json"), references);
        }
    }

    [Fact]
    public void ListsCheckCapacityBeforeApplyingBulkEdits()
    {
        var model = ReadFixture();
        var view = model.AsReadOnly();
        Assert.False(model.Phases.Add("calibrate"));
        Assert.ThrowsAny<ArgumentException>(() => model.Phases.Insert(0, "measure"));
        Assert.ThrowsAny<ArgumentException>(() => model.Phases.AddRange(["review", "publish"]));
        Assert.Equal(["calibrate", "measure"], view.Phases);
        model.Phases.AddRange(["measure", "review", "review"]);
        model.Phases.Move(2, 0);
        Assert.Equal(["review", "calibrate", "measure"], view.Phases);

        Assert.ThrowsAny<ArgumentException>(() => model.Counts.AddRange([3, 4]));
        Assert.Equal([1, 2], view.Counts);

        model.Messages.Add(null);
        model.Messages.Add("ready");
        Assert.Equal(new string?[] { "ready", null, "ready", null, "ready" }, view.Messages);
    }

    [Fact]
    public void MapBulkFailurePreservesUpdatesAndNewEntries()
    {
        var model = ReadFixture();
        var view = model.AsReadOnly();
        var edits = new KeyValuePair<string, long?>[]
        {
            new("limit", 1),
            new("new", 3),
            new("extra", 2)
        };
        Assert.ThrowsAny<ArgumentException>(() => model.Limits.PutAll(edits));
        Assert.Equal(long.MaxValue, view.Limits["limit"]);
        Assert.Equal(2, view.Limits.Count);
        Assert.False(view.Limits.ContainsKey("new"));
        model.Limits["new"] = 3;
        model.Limits["limit"] = 1;
        Assert.Equal(1, view.Limits["limit"]);
        Assert.Equal(3, view.Limits.Count);
        Assert.True(view.Limits.TryGetValue("optional", out var optional));
        Assert.Null(optional);
        Assert.False(view.Limits.TryGetValue("missing", out _));
    }

    [Fact]
    public void SetMembershipUsesVmfScalarEqualityAndLowerBoundsAreValidated()
    {
        var model = ReadFixture();
        var view = model.AsReadOnly();
        Assert.False(model.Levels.Add(BitConverter.Int64BitsToDouble(0x7ff800000000000F)));
        Assert.True(model.Levels.Add(2));
        Assert.ThrowsAny<ArgumentException>(() => model.Levels.Add(3));
        Assert.Equal(4, view.Levels.Count);
        Assert.False(view.Levels.Contains(3));
        model.Levels.Clear();
        model.Limits.Clear();
        Assert.Empty(view.Levels);
        Assert.Empty(view.Limits);
        Assert.Equal(["/levels", "/limits"], model.Validate().Select(issue => issue.ModelPath).Order());
    }

    [Theory]
    [InlineData("\"phases\": [\"calibrate\", \"measure\"]", "\"phases\": [\"calibrate\", \"calibrate\"]")]
    [InlineData("\"levels\": [0.0, -0.0, \"NaN\"]", "\"levels\": [0.0, 0.0]")]
    [InlineData("{\"key\": -0.0, \"value\": \"-\"}", "{\"key\": 0.0, \"value\": \"-\"}")]
    [InlineData("{\"key\": -0.0, \"value\": \"-\"}", "{\"key\": null, \"value\": \"-\"}")]
    public void ManagedCollectionSnapshotsRejectDuplicateMembersOrNullKeys(string valid, string invalid)
    {
        string json = File.ReadAllText(Fixture);
        Assert.Throws<JsonException>(() =>
            ModelSnapshot.ReadPlain<CollectionProfile>(json.Replace(valid, invalid, StringComparison.Ordinal)));
    }

    [Fact]
    public void ReadOnlyScalarListUsesVmfEqualityForSignedZero()
    {
        var model = ReadFixture();
        var view = model.AsReadOnly().Readings;
        Assert.Equal(0, view.IndexOf(+0.0));
        Assert.False(view.Contains(NegativeZero));
        model.Readings.Add(NegativeZero);
        Assert.Equal(1, view.IndexOf(NegativeZero));
        Assert.Equal(0, view.IndexOf(+0.0));
    }

    private static CollectionProfile ReadFixture() =>
        ModelSnapshot.ReadPlain<CollectionProfile>(File.ReadAllText(Fixture));

    private static void Verify(CollectionProfile model)
    {
        Assert.Equal(new string?[] { "ready", null, "ready" }, model.Messages);
        Assert.Equal(["calibrate", "measure"], model.Phases);
        Assert.Equal([1, 2], model.Counts);
        Assert.True(model.Options["@vmf-id"]);
        Assert.False(model.Options["@vmf-type"]);
        Assert.Equal(3, model.Levels.Count);
        Assert.True(model.Levels.Contains(+0.0));
        Assert.True(model.Levels.Contains(NegativeZero));
        Assert.True(model.Levels.Contains(double.NaN));
        Assert.Equal(long.MaxValue, model.Limits["limit"]);
        Assert.True(model.Limits.TryGetValue("optional", out var optional));
        Assert.Null(optional);
        Assert.False(model.Limits.ContainsKey("missing"));
        Assert.Equal(3, model.Markers.Count);
        Assert.Equal('+', model.Markers[+0.0]);
        Assert.Equal('-', model.Markers[NegativeZero]);
        Assert.True(model.Markers.TryGetValue(double.NaN, out var marker));
        Assert.Null(marker);
        Assert.Empty(model.Validate());
    }
}
