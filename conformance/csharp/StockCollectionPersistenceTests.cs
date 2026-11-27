using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Jackson.Test.Collectionpersistence;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class StockCollectionPersistenceTests
{
    private static readonly double NegativeZero = BitConverter.Int64BitsToDouble(long.MinValue);
    private static readonly JournalBinding Binding = new("catalog", 1, model => ((Identified)model).Id!);

    [Fact]
    public void SecondaryMapExposesSharedTypedStorageWithManagedCapacity()
    {
        var catalog = new Catalog();
        IIndex<double?, string?> index = catalog;
        IReadOnlyIndex<double?, string?> view = index.AsReadOnly();
        Assert.Same(catalog.Ratings, index.Ratings);
        Assert.Empty(view.Ratings);
        Assert.Empty(catalog.Notes);
        Assert.Empty(catalog.Priority);
        Assert.Empty(catalog.Tags);

        index.Ratings.Put(NegativeZero, null);
        index.Ratings.Put(+0.0, "positive");
        index.Ratings.Put(double.NaN, "missing");
        index.Ratings.Put(2.5, "last");
        Assert.ThrowsAny<ArgumentException>(() => index.Ratings.Put(5.0, "over"));
        Assert.ThrowsAny<ArgumentException>(() => index.Ratings.Put(null, "null key"));
        index.Ratings.Put(2.5, "updated");

        Assert.Equal(4, view.Ratings.Count);
        Assert.True(view.Ratings.ContainsKey(NegativeZero));
        Assert.Null(view.Ratings[NegativeZero]);
        Assert.Equal("positive", view.Ratings[+0.0]);
        Assert.Equal("missing", view.Ratings[double.NaN]);
        Assert.Equal("updated", view.Ratings[2.5]);
        Assert.Empty(new Catalog().Ratings);

        var fields = JsonNode.Parse(ModelSnapshot.GenerateJsonSchema<Catalog>())!["properties"]!;
        Assert.Equal(4, fields["ratings"]!["maxItems"]!.GetValue<int>());
        Assert.Equal(3, fields["priority"]!["maxItems"]!.GetValue<int>());
        Assert.Equal(2, fields["notes"]!["maxProperties"]!.GetValue<int>());
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void StockManagedCollectionsPreserveTypedKeysAndGraphIdentity(string format)
    {
        (Func<Catalog, string> Write, Func<string, Catalog> Read) snapshots = format switch
        {
            "json" => (ModelSnapshot.Write, ModelSnapshot.Read<Catalog>),
            "yaml" => (ModelSnapshot.WriteYaml, ModelSnapshot.ReadYaml<Catalog>),
            "xml" => (ModelSnapshot.WriteXml, ModelSnapshot.ReadXml<Catalog>),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
        (Func<ChangeJournal, string> Write, Func<string, ChangeJournal> Read) journals = format switch
        {
            "json" => (JournalJson.Write, JournalJson.Read),
            "yaml" => (JournalYaml.Write, JournalYaml.Read),
            "xml" => (JournalXml.Write, JournalXml.Read),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };
        var source = Checkpoint();
        source.Tags.AddRange(["", "a\r\u0001", "other"]);
        source.Priority.AddRange(["b", "a"]);
        source.Ratings.Put(NegativeZero, null);
        source.Ratings.Put(+0.0, "zero");
        source.Ratings.Put(double.NaN, "missing");
        var item = new Item { Id = "id-1", Code = "first" };
        source.Items.Add(item);
        var entry = new Entry { Id = "entry", Target = item };
        entry.Flags.AddRange([2, 5]);
        entry.Bits.Put(2, null);
        source.Entries.Add(entry);

        var restored = snapshots.Read(snapshots.Write(source));
        Assert.Equal(["b", "a"], restored.Priority);
        Assert.True(new HashSet<string>(restored.Tags).SetEquals(["original", "", "a\r\u0001", "other"]));
        Assert.True(restored.Ratings.ContainsKey(NegativeZero));
        Assert.Null(restored.Ratings[NegativeZero]);
        Assert.Equal("zero", restored.Ratings[+0.0]);
        Assert.Equal("missing", restored.Ratings[double.NaN]);
        VerifyIdentity(restored, "first");
        var restoredEntry = Assert.Single(restored.Entries)!;
        Assert.True(new HashSet<int>(restoredEntry.Flags).SetEquals([2, 5]));
        Assert.True(restoredEntry.Bits.ContainsKey(2));
        Assert.Null(restoredEntry.Bits[2]);

        var baseline = snapshots.Read(snapshots.Write(Checkpoint()));
        var recorded = JournalRecorder<Catalog>.RecordFrame(Checkpoint(), ModelSnapshot.Registry, Binding, "initial", 0,
            current =>
            {
                current.Tags.Remove("original");
                current.Tags.Add("new");
                current.Ratings.Put(NegativeZero, null);
                current.Ratings.Put(+0.0, "positive");
                var trackedItem = new Item { Id = "item", Code = "old" };
                current.Items.Add(trackedItem);
                var trackedEntry = new Entry { Id = "entry", Target = trackedItem };
                trackedEntry.Flags.Add(12);
                trackedEntry.Bits.Put(7, true);
                current.Entries.Add(trackedEntry);
                trackedItem.Code = "new";
                trackedEntry.Bits.Put(7, false);
                current.Priority.Add("high");
            });
        var edits = journals.Read(journals.Write(recorded));
        Assert.Equal(1, edits.WireVersion);
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(JournalJson.Write(recorded)), JsonNode.Parse(JournalJson.Write(edits))));
        var replayed = JournalReplayer.Replay(baseline, Binding, "initial", edits, ModelSnapshot.Registry);
        Assert.Equal("original", Assert.Single(baseline.Tags));
        Assert.Empty(baseline.Items);
        Assert.Equal("new", Assert.Single(replayed.Tags));
        Assert.Equal("high", Assert.Single(replayed.Priority));
        Assert.True(replayed.Ratings.ContainsKey(NegativeZero));
        Assert.Null(replayed.Ratings[NegativeZero]);
        Assert.Equal("positive", replayed.Ratings[+0.0]);
        VerifyIdentity(replayed, "new");
        var replayedEntry = Assert.Single(replayed.Entries)!;
        Assert.Equal(12, Assert.Single(replayedEntry.Flags));
        Assert.False(replayedEntry.Bits[7]);
    }

    private static Catalog Checkpoint()
    {
        var result = new Catalog { Id = "catalog" };
        result.Tags.Add("original");
        return result;
    }

    private static void VerifyIdentity(Catalog catalog, string code)
    {
        var item = Assert.Single(catalog.Items)!;
        var entry = Assert.Single(catalog.Entries)!;
        Assert.Same(item, entry.Target);
        Assert.Same(item, catalog.FindItemsByCode(code));
        Assert.Same(entry, catalog.FindEntriesByTarget(item));
        Assert.Same(entry.AsReadOnly(), catalog.AsReadOnly().FindEntriesByTarget(item.AsReadOnly()));
    }
}
