using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Generickeys;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class GenericKeyTests
{
    private const string CheckpointId = "base";
    private static readonly JournalBinding Binding = new("conformance.generic-key", 1,
        model => model is IntegerTable ? "table" : throw new ArgumentException("Unknown journal model type."));

    [Fact]
    public void SameTypeParameterRetainsOptionalValuesAndRejectsNullAtManagedPositions()
    {
        KeyIndex<int?> index = new IntegerIndex();
        index.Entries.Put(3, "kept");
        Assert.False(index.Entries.TryGetValue(null, out _));
        Assert.False(index.Entries.ContainsKey(null));
        Assert.False(index.Entries.Remove(null));
        Assert.Throws<ArgumentNullException>(() => index.Entries.Put(null, "invalid"));
        Assert.Throws<ArgumentNullException>(() => index.Entries.PutAll([new(9, "first"), new(null, "invalid")]));
        Assert.Equal(3, Assert.Single(index.Entries).Key);

        var concrete = Specimen();
        ValueTable<int?> table = concrete;
        IReadOnlyValueTable<int?> view = table.AsReadOnly();
        Assert.Same(concrete.Paired, table.Paired);
        Assert.Same(concrete.Checked, table.Checked);
        Assert.Null(view.Selected);
        Assert.Null(view.History[0]);
        Assert.True(view.Paired.ContainsKey(1));
        Assert.Null(view.Paired[1]);
        Assert.Throws<ArgumentNullException>(() => table.Checked.Add(null));
        Assert.Throws<ArgumentNullException>(() => table.Known.Add(null));
        Assert.Throws<ArgumentNullException>(() => table.Priority.Add(null));
        Assert.Throws<ArgumentException>(() => table.Checked.AddRange([8, 9]));
        Assert.Equal(1, Assert.Single(view.Checked));
        Assert.Equal(1, Assert.Single(view.Known));
        Assert.False(table.Priority.Add(1));
        Assert.Equal(1, Assert.Single(view.Priority));
        table.Selected = 0;
        Assert.Equal(0, view.Selected);
        var map = ModelSnapshot.Reflection.Reflect(table).FindProperty("paired")!;
        Assert.Equal(typeof(int), map.KeyType);
        Assert.Equal(typeof(int?), map.ValueType);

        var floats = new FloatTable();
        NumberedTable<double> numeric = floats;
        IReadOnlyValueTable<double?> numericView = numeric.AsReadOnly();
        floats.Paired.Put(+0.0, null);
        floats.Paired.Put(-0.0, 1.0);
        floats.Paired.Put(double.NaN, 3.0);
        Assert.Equal(3, numericView.Paired.Count);
        var restored = ModelSnapshot.ReadPlain<FloatTable>(ModelSnapshot.WritePlain(floats));
        Assert.True(restored.Paired.ContainsKey(+0.0));
        Assert.Null(restored.Paired[+0.0]);
        Assert.Equal(1.0, restored.Paired[-0.0]);
        Assert.Equal(3.0, restored.Paired[BitConverter.Int64BitsToDouble(0x7ff8000000000007L)]);
    }

    [Fact]
    public void BothRuntimesExchangeTheSameTypedKeyJournalAndCheckpoint()
    {
        var model = Specimen();
        string checkpoint = ModelSnapshot.WritePlain(model);
        string journal = JournalJson.Write(JournalRecorder<IntegerTable>.RecordFrame(model, ModelSnapshot.Registry,
            Binding, CheckpointId, 0, table =>
            {
                table.Selected = 0;
                table.Paired.Put(1, 8);
                table.Paired.Put(2, null);
                table.Checked.Add(2);
                table.Known.Add(2);
                table.Priority.Add(2);
            }));
        Verify(checkpoint, journal);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_GENERIC_KEYS") is { } directory)
        {
            string javaCheckpoint = File.ReadAllText(Path.Combine(directory, "java-checkpoint.json"));
            string javaJournal = File.ReadAllText(Path.Combine(directory, "java-journal.json"));
            Verify(javaCheckpoint, javaJournal);
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(javaCheckpoint), JsonNode.Parse(checkpoint)));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(javaJournal), JsonNode.Parse(journal)));
            File.WriteAllText(Path.Combine(directory, "csharp-checkpoint.json"), checkpoint);
            File.WriteAllText(Path.Combine(directory, "csharp-journal.json"), journal);
        }
    }

    [Fact]
    public void TypeNameCollisionKeepsTheInheritedMapAndLogicalNames()
    {
        var catalog = new PriceCatalog();
        PricesModel<int?, long?> typed = catalog;
        IReadOnlyPricesModel<int?, long?> view = typed.AsReadOnly();
        typed.Prices.Put(2, 20L);
        typed.Prices.Put(3, 30L);
        Assert.Equal(20L, view.Prices[2]);
        Assert.Throws<ArgumentException>(() => typed.Prices.Put(4, 40L));
        var field = ModelSnapshot.Reflection.Reflect(catalog).FindProperty("prices")!;
        Assert.Same(typed.Prices, field.Get());
        var baseType = ModelSnapshot.TypeCatalog.Find(typeof(PricesModel<,>))!;
        Assert.Equal("eu.mihosoft.vmf.conformance.generickeys.Prices", baseType.Name);
        Assert.Equal(baseType.Name, baseType.WireName);

        string document = ModelSnapshot.WritePlain(catalog);
        Assert.NotNull(JsonNode.Parse(document)!["prices"]);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_GENERIC_KEYS") is { } directory)
        {
            string java = File.ReadAllText(Path.Combine(directory, "java-prices.json"));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(document)));
            var restored = ModelSnapshot.ReadPlain<PriceCatalog>(java);
            Assert.Equal([2, 3], restored.Prices.Select(entry => entry.Key));
            Assert.Equal(30L, restored.Prices[3]);
            File.WriteAllText(Path.Combine(directory, "csharp-prices.json"), document);
        }
    }

    private static IntegerTable Specimen() => IntegerTable.NewBuilder().WithChecked([1]).WithKnown([1])
        .WithPriority([1]).WithHistory([null]).WithPaired([new(1, null)]).Build();

    private static void Verify(string checkpointDocument, string journalDocument)
    {
        var checkpoint = ModelSnapshot.ReadPlain<IntegerTable>(checkpointDocument);
        var journal = JournalJson.Read(journalDocument);
        Assert.Equal(1, journal.WireVersion);
        var result = JournalReplayer.Replay(checkpoint, Binding, CheckpointId, journal, ModelSnapshot.Registry);
        Assert.Null(checkpoint.Selected);
        Assert.Null(checkpoint.Paired[1]);
        Assert.False(checkpoint.Paired.ContainsKey(2));
        Assert.Equal(0, result.Selected);
        Assert.Equal(8, result.Paired[1]);
        Assert.True(result.Paired.ContainsKey(2));
        Assert.Null(result.Paired[2]);
        Assert.Equal(new int?[] { 1, 2 }, result.Checked);
        Assert.Equal(2, result.Known.Count);
        Assert.Equal(new int?[] { 1, 2 }, result.Priority);
    }
}
