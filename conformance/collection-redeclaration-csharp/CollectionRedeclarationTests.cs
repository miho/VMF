using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Collectionredeclarations;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class CollectionRedeclarationTests
{
    private static readonly JournalBinding ScalarRelay = new("conformance.collection-relay", 1, model => model switch
    {
        CollectionCatalog => "root",
        CollectionBasis => "basis",
        _ => "child",
    });

    [Fact]
    public void MutableRedeclarationsReuseTheBaseCollectionAndRetainTheirContracts()
    {
        var variant = CreateVariant();
        CollectionBasis parent = variant;
        Assert.Same(parent.Numbers, variant.Numbers);
        Assert.Same(parent.Counters, variant.Counters);
        Assert.Same(parent.Owned, variant.Owned);
        Assert.Same(parent.Featured, variant.Featured);
        Assert.Same(parent.Trail, variant.Trail);
        Assert.Same(parent.Favorites, variant.Favorites);

        var field = Assert.Single(ModelSnapshot.Reflection.Reflect(parent).Properties, property => property.Name == "numbers");
        Assert.EndsWith(".CollectionVariant", field.DeclaringType);
        Assert.Equal("numbers_v2", field.WireName);
        Assert.Equal(1, field.LowerBound);
        Assert.Equal(3, field.UpperBound);
        Assert.Same(parent.Numbers, field.Get());
        Assert.False(field.IsUnique);

        var owned = Assert.Single(ModelSnapshot.Reflection.Reflect(variant).Properties, property => property.Name == "owned");
        Assert.Equal("owned_v2", owned.WireName);
        Assert.Equal("name", owned.KeyPropertyName);
        Assert.Equal(2, owned.UpperBound);
        Assert.True(owned.IsContainment && owned.IsOrdered && owned.IsUnique);

        var changes = new List<ModelChange>();
        using var listener = variant.VmfChanges.AddPropertyListener("numbers", changes.Add);
        var view = variant.AsReadOnly();
        parent.Numbers.Add(3);
        Assert.Equal([1, 2, 3], view.Numbers);
        Assert.ThrowsAny<ArgumentException>(() => variant.Numbers.Add(4));
        parent.Numbers.RemoveAt(2);
        Assert.Equal(2, changes.Count);
        Assert.False(parent.Labels.Add("a"));
        Assert.ThrowsAny<ArgumentException>(() => parent.Labels.Add("c"));
        Assert.True(parent.Codes.Add(20L));
        Assert.ThrowsAny<ArgumentException>(() => parent.Codes.Add(21L));
        Assert.ThrowsAny<ArgumentException>(() => parent.Counters.Add("unlisted", 18));
        Assert.True(parent.Counters.ContainsKey("empty"));
        Assert.Null(parent.Counters["empty"]);

        var first = parent.Owned[0];
        Assert.Same(first.AsReadOnly(), view.FindOwnedByName("alpha"));
        var extra = new CollectionItem { Name = "gamma" };
        Assert.True(parent.Owned.Add(extra));
        Assert.Same(variant, extra.Basis);
        Assert.Contains(variant.Validate(), issue => issue.ModelCode == "vmf.multiplicity" && issue.ModelPath == "/owned");
        variant.Owned.Remove(extra);
        Assert.Null(extra.Basis);
    }

    [Fact]
    public void ImmutableAndClosedGenericRedeclarationsKeepEffectiveTypesAndValues()
    {
        var source = new List<int> { 7, 8 };
        var derived = FrozenVariant.NewBuilder().WithNumbers(source).Build();
        source.Clear();
        FrozenBasis parent = derived;
        Assert.Same(derived.Numbers, parent.Numbers);
        Assert.Same(derived.AsReadOnly(), derived);
        Assert.Equal([7, 8], parent.Numbers);
        Assert.False(parent.Numbers is ICollection<int> collection && !collection.IsReadOnly);
        Assert.ThrowsAny<ArgumentException>(() => FrozenVariant.NewBuilder().WithNumbers([1, 2, 3, 4]));
        var property = ModelSnapshot.Reflection.Reflect(parent).FindProperty("numbers")!;
        Assert.Equal("numbers", property.WireName);
        Assert.EndsWith(".FrozenVariant", property.DeclaringType);

        var integral = CreateNumeric();
        NumericSeries<long> openBase = integral;
        Assert.Same(openBase.Readings, integral.Readings);
        Assert.Same(openBase.Marks, integral.Marks);
        Assert.Equal([long.MinValue, 0L], openBase.Readings);
        Assert.Equal(long.MaxValue, integral.Marks["limit"]);
        Assert.Null(integral.Marks["unknown"]);
    }

    [Fact]
    public void RedeclaredAliasesAndIdentitiesRoundTripThroughJavaAndCSharp()
    {
        var first = new FrozenToken(label: "first");
        var second = new FrozenToken(label: "second");
        var frozen = FrozenVariant.NewBuilder().WithNumbers([7, 8]).WithLabels(["x", "y"]).WithCodes([29L])
            .WithCounters(Counters()).WithTrail([first, null, first, second]).WithFeatured([second, first])
            .WithFavorites([first, second]).Build();
        var original = CollectionBasis.NewBuilder().WithNumbers([9]).Build();
        var specimen = CollectionCatalog.NewBuilder().WithExamples([original, CreateVariant()])
            .WithFrozen(frozen).WithNumeric(CreateNumeric()).Build();
        string native = ModelSnapshot.Write(specimen);
        VerifyWire(native);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_COLLECTION_REDECLARATIONS");
        if (exchange is null) return;
        VerifyWire(File.ReadAllText(Path.Combine(exchange, "java-collection-redeclarations.json")));
        File.WriteAllText(Path.Combine(exchange, "csharp-collection-redeclarations.json"), native);
    }

    [Theory]
    [InlineData("numbers", false)]
    [InlineData("numbers", true)]
    [InlineData("labels", false)]
    [InlineData("labels", true)]
    [InlineData("counters", false)]
    [InlineData("counters", true)]
    [InlineData("codes", false)]
    [InlineData("codes", true)]
    public void ScalarCollectionHistoryFollowsRecordingRelayAndV1FollowsCommitOrder(string property, bool ancestorFirst)
    {
        var root = ScalarRelayCheckpoint();
        var basis = Assert.Single(root.Examples);
        var recording = root.VmfChanges;
        var trace = new List<string>();
        var visible = new List<string>();
        var before = ancestorFirst ? Start() : null;
        using var early = ancestorFirst ? ObserveAncestor() : null;
        using var local = basis.VmfChanges.AddPropertyListener(property, _ =>
        {
            trace.Add($"local {property}");
            visible.AddRange(recording.History.Select(edit => edit.PropertyName));
            basis.Owned[0].Name = "nested";
        });
        using var recorder = before ?? Start();
        using var late = ancestorFirst ? null : ObserveAncestor();

        switch (property)
        {
            case "numbers": basis.Numbers.Add(2); break;
            case "labels": basis.Labels.Add("added"); break;
            case "counters": basis.Counters.Put("added", 2); break;
            case "codes": basis.Codes.Add(2L); break;
        }

        // The basis' own listener runs before the recording ancestor (portable contract E8), and the nested edit is
        // delivered at once (E9) but recorded after its cause (H4).
        Assert.Empty(visible);
        Assert.Equal([$"local {property}", "ancestor name", $"ancestor {property}"], trace);
        Assert.Equal([property, "name"], recording.History.Select(edit => edit.PropertyName));
        local.Dispose();
        early?.Dispose();
        late?.Dispose();

        var journal = recorder.Finish();
        var transaction = Assert.Single(recording.Transactions);
        Assert.True(transaction.CanUndo);
        Assert.True(transaction.Undo());
        CheckRelayState(property, root, edited: false);

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_COLLECTION_REDECLARATIONS");
        string category = property is "counters" or "codes" ? "membership" : "list";
        bool readJava = exchange is not null && (category != "membership" || PreparedMembershipEnabled(exchange));
        string suffix = $"-scalar-{category}-relay-{property}-{(ancestorFirst ? "ancestor-first" : "local-first")}";
        VerifyFormat("json", JournalJson.Write, JournalJson.Read);
        VerifyFormat("yaml", JournalYaml.Write, JournalYaml.Read);
        VerifyFormat("xml", JournalXml.Write, JournalXml.Read);

        JournalRecorder<CollectionCatalog> Start() => JournalRecorder<CollectionCatalog>.Start(
            root, ModelSnapshot.Registry, ScalarRelay, "initial", 0);

        IDisposable ObserveAncestor() => recording.AddRecursiveListener(edit => trace.Add($"ancestor {edit.PropertyName}"));

        void VerifyFormat(string extension, Func<ChangeJournal, string> write, Func<string, ChangeJournal> read)
        {
            string native = write(journal);
            VerifyScalarRelayJournal(property, read(native));
            if (exchange is null) return;
            if (readJava)
                VerifyScalarRelayJournal(property, read(File.ReadAllText(Path.Combine(exchange, $"java{suffix}.{extension}"))));
            File.WriteAllText(Path.Combine(exchange, $"csharp{suffix}.{extension}"), native);
        }
    }

    [Theory]
    [InlineData("counters")]
    [InlineData("codes")]
    public void ReentrantEditsOfTheSameCollectionReplayInCommitOrder(string property)
    {
        var root = ScalarRelayCheckpoint();
        var basis = Assert.Single(root.Examples);
        bool map = property == "counters";
        int callbacks = 0;
        void EditAgain()
        {
            if (++callbacks != 1) return;
            if (map) basis.Counters.Put("added", 3);
            else basis.Codes.Add(3L);
        }

        using var direct = map ? basis.Counters.AddListener(_ => EditAgain()) : basis.Codes.AddListener(_ => EditAgain());
        using var recorder = JournalRecorder<CollectionCatalog>.Start(root, ModelSnapshot.Registry, ScalarRelay, "initial", 0);
        if (map) basis.Counters.Put("added", 2);
        else basis.Codes.Add(2L);
        Assert.Equal(2, callbacks);
        var journal = recorder.Finish();
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_COLLECTION_REDECLARATIONS");
        bool readJava = exchange is not null && PreparedMembershipEnabled(exchange);

        VerifyFormat("json", JournalJson.Write, JournalJson.Read);
        VerifyFormat("yaml", JournalYaml.Write, JournalYaml.Read);
        VerifyFormat("xml", JournalXml.Write, JournalXml.Read);

        void VerifyFormat(string extension, Func<ChangeJournal, string> write, Func<string, ChangeJournal> read)
        {
            string native = write(journal);
            Verify(read(native));
            if (exchange is null) return;
            if (readJava)
                Verify(read(File.ReadAllText(Path.Combine(exchange, $"java-reentrant-{property}.{extension}"))));
            File.WriteAllText(Path.Combine(exchange, $"csharp-reentrant-{property}.{extension}"), native);
        }

        void Verify(ChangeJournal recorded)
        {
            Assert.Equal(1, recorded.WireVersion);
            var edits = Assert.Single(recorded.Transactions).Operations;
            Assert.Equal([property, property], edits.Select(edit => edit.Property));
            if (map)
            {
                var outer = Assert.IsType<JournalMapEdit>(edits[0]);
                var inner = Assert.IsType<JournalMapEdit>(edits[1]);
                Assert.Empty(outer.Before);
                var first = Assert.Single(outer.After);
                Assert.Equal("added", first.Key.Text);
                Assert.Equal("2", first.Value.Text);
                var previous = Assert.Single(inner.Before);
                Assert.Equal(first.Key.Text, previous.Key.Text);
                Assert.Equal(first.Value.Text, previous.Value.Text);
                Assert.Equal("3", Assert.Single(inner.After).Value.Text);
            }
            else
            {
                var outer = Assert.IsType<JournalMembershipEdit>(edits[0]);
                var inner = Assert.IsType<JournalMembershipEdit>(edits[1]);
                Assert.Empty(outer.Removed);
                Assert.Empty(inner.Removed);
                Assert.Equal("2", Assert.Single(outer.Added).Text);
                Assert.Equal("3", Assert.Single(inner.Added).Text);
            }
            var checkpoint = ScalarRelayCheckpoint();
            var original = Assert.Single(checkpoint.Examples);
            var result = Assert.Single(JournalReplayer.Replay(checkpoint, ScalarRelay, "initial", recorded, ModelSnapshot.Registry).Examples);
            Assert.Empty(original.Counters);
            Assert.Empty(original.Codes);
            if (map) Assert.Equal(3, result.Counters["added"]);
            else Assert.Equal(new[] { 2L, 3L }, result.Codes.Order());
        }
    }

    private static bool PreparedMembershipEnabled(string exchange)
    {
        string marker = File.ReadAllText(Path.Combine(exchange, "java-prepared-membership.txt"));
        return marker switch
        {
            "prepared" => true,
            "legacy" when Environment.GetEnvironmentVariable("VMF_CONFORMANCE_PREPARED_MEMBERSHIP") == "1" =>
                throw new InvalidOperationException("VMF_CONFORMANCE_PREPARED_MEMBERSHIP=1 requires Java to link the VCollections preparation hooks."),
            "legacy" => false,
            _ => throw new InvalidDataException("Unknown Java prepared membership capability. Run the Java exchange writer.")
        };
    }

    private static void VerifyScalarRelayJournal(string property, ChangeJournal journal)
    {
        Assert.Equal(1, journal.WireVersion);
        Assert.Collection(Assert.Single(journal.Transactions).Operations,
            edit =>
            {
                Assert.Equal(property, edit.Property);
                switch (property)
                {
                    case "counters": Assert.False(Assert.IsType<JournalMapEdit>(edit).TypedKeys); break;
                    case "codes": Assert.False(Assert.IsType<JournalMembershipEdit>(edit).References); break;
                    default: Assert.Equal(JournalListEditKind.Add, Assert.IsType<JournalListEdit>(edit).Kind); break;
                }
            },
            edit => Assert.Equal("name", Assert.IsType<JournalSet>(edit).Property));
        var checkpoint = ScalarRelayCheckpoint();
        var replayed = JournalReplayer.Replay(checkpoint, ScalarRelay, "initial", journal, ModelSnapshot.Registry);
        CheckRelayState(property, checkpoint, edited: false);
        CheckRelayState(property, replayed, edited: true);
    }

    private static CollectionCatalog ScalarRelayCheckpoint()
    {
        var basis = CollectionBasis.NewBuilder().WithNumbers([1]).WithOwned([new CollectionItem { Name = "initial" }]).Build();
        return CollectionCatalog.NewBuilder().WithExamples([basis]).Build();
    }

    private static void CheckRelayState(string property, CollectionCatalog root, bool edited)
    {
        var basis = Assert.Single(root.Examples);
        Assert.Equal(edited && property == "numbers" ? [1, 2] : [1], basis.Numbers);
        Assert.Equal(edited && property == "labels" ? ["added"] : [], basis.Labels);
        Assert.Equal(edited && property == "codes" ? [2L] : [], basis.Codes);
        if (edited && property == "counters") Assert.Equal(2, Assert.Single(basis.Counters).Value);
        else Assert.Empty(basis.Counters);
        Assert.Equal(edited ? "nested" : "initial", Assert.Single(basis.Owned).Name);
    }

    private static CollectionVariant CreateVariant()
    {
        var first = new CollectionItem { Name = "alpha" };
        var second = new CollectionItem { Name = "beta" };
        return CollectionVariant.NewBuilder().WithNumbers([1, 2]).WithLabels(["a", "b"]).WithCodes([19L])
            .WithCounters(Counters()).WithOwned([first, second]).WithFeatured([second, first])
            .WithTrail([first, null, first, second]).WithFavorites([first, second]).Build();
    }

    private static Dictionary<string, int?> Counters() => new() { ["known"] = 5, ["empty"] = null };

    private static IntegralSeries CreateNumeric() => IntegralSeries.NewBuilder().WithReadings([long.MinValue, 0L])
        .WithMarks(new Dictionary<string, long?> { ["limit"] = long.MaxValue, ["unknown"] = null }).Build();

    private static void VerifyWire(string wire)
    {
        var parsed = JsonNode.Parse(wire)!;
        var examples = parsed["examples"]!.AsArray();
        Assert.Equal(9, examples[0]!["base_numbers"]![0]!.GetValue<int>());
        Assert.False(examples[0]!.AsObject().ContainsKey("numbers_v2"));
        var fields = examples[1]!.AsObject();
        Assert.Equal(2, fields["numbers_v2"]!.AsArray().Count);
        Assert.Equal(2, fields["owned_v2"]!.AsArray().Count);
        Assert.False(fields.ContainsKey("base_numbers"));
        Assert.False(fields.ContainsKey("owned"));
        var frozenFields = parsed["frozen"]!.AsObject();
        Assert.Equal(2, frozenFields["numbers"]!.AsArray().Count);
        Assert.False(frozenFields.ContainsKey("previous_numbers"));

        var restored = ModelSnapshot.Read<CollectionCatalog>(wire);
        Assert.IsType<CollectionBasis>(restored.Examples[0]);
        var variant = Assert.IsType<CollectionVariant>(restored.Examples[1]);
        Assert.Equal([1, 2], variant.Numbers);
        Assert.Equal(["a", "b"], variant.Labels);
        Assert.Contains(19L, variant.Codes);
        Assert.True(variant.Counters.ContainsKey("empty"));
        Assert.Null(variant.Counters["empty"]);
        var first = variant.Owned[0];
        var second = variant.Owned[1];
        Assert.Same(first, variant.FindOwnedByName("alpha"));
        Assert.Same(variant, first.Basis);
        Assert.Same(variant, second.Basis);
        Assert.Same(variant, first.SelectedBy);
        Assert.Same(variant, second.SelectedBy);
        Assert.Same(second, variant.Featured[0]);
        Assert.Same(first, variant.Featured[1]);
        Assert.Null(variant.Trail[1]);
        Assert.Same(first, variant.Trail[0]);
        Assert.Same(first, variant.Trail[2]);
        Assert.Same(second, variant.Trail[3]);
        Assert.True(variant.Favorites.Contains(second));

        var frozen = Assert.IsType<FrozenVariant>(restored.Frozen);
        Assert.Equal([7, 8], frozen.Numbers);
        Assert.Equal(["x", "y"], frozen.Labels);
        Assert.Contains(29L, frozen.Codes);
        Assert.Null(frozen.Counters["empty"]);
        Assert.Null(frozen.Trail[1]);
        Assert.Same(frozen.Trail[0], frozen.Trail[2]);
        Assert.Same(frozen.Trail[3], frozen.Featured[0]);
        Assert.Same(frozen.Trail[0], frozen.Featured[1]);
        Assert.True(frozen.Favorites.Contains(frozen.Featured[0]));
        Assert.True(frozen.Favorites.Contains(frozen.Featured[1]));

        var series = Assert.IsType<IntegralSeries>(restored.Numeric);
        Assert.Equal([long.MinValue, 0L], series.Readings);
        Assert.Equal(long.MaxValue, series.Marks["limit"]);
        Assert.True(series.Marks.ContainsKey("unknown"));
        Assert.Null(series.Marks["unknown"]);
    }
}
