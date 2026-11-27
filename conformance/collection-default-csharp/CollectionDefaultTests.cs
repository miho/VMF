using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Collectiondefaults;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

[Collection(CollectionDefaultBindings.TestCollection)]
public class CollectionDefaultTests
{
    [Fact]
    public void BuilderCopyAppendsAndReplacesManagedCollectionsWithStagedDefaults()
    {
        var source = new ManagedSamples();
        source.Numbers.Clear();
        source.Numbers.Add(7);
        source.Labels.Clear();
        source.Labels.Add("published");
        source.Codes.Clear();
        source.Codes.Add(19L);
        source.Options.Clear();
        source.Options.Add("known", 12);

        var target = new ManagedSamples();
        var numbers = target.Numbers;
        numbers.Clear();
        target.Labels.Clear();
        target.Labels.Add("existing");
        target.Codes.Clear();
        target.Codes.Add(31L);
        target.Options.Clear();
        target.Options.Add("existing", null);
        target.Options.Add("known", 42);
        int before = CollectionDefaultBindings.NumberCalls;
        var builder = ManagedSamples.NewBuilder();
        Assert.Equal(before + 1, CollectionDefaultBindings.NumberCalls);
        CollectionDefaultBindings.Numbers[0] = 8;
        try
        {
            Assert.Same(builder, builder.ApplyFrom(source).ApplyTo(target));
            Assert.Same(numbers, target.Numbers);
            Assert.Equal([3, 5, 7], numbers);
            Assert.Equal(["existing", "draft", "published"], target.Labels);
            Assert.Equal([19L, 23L, 31L], target.Codes.Order());
            Assert.Equal(["existing", "known", "present"], target.Options.Keys);
            Assert.Equal(12, target.Options["known"]);
            Assert.Null(target.Options["existing"]);
            Assert.Null(target.Options["present"]);

            source.Numbers.Clear();
            source.Labels.Clear();
            source.Codes.Clear();
            source.Options.Clear();
            builder.AppendCollections(false).ApplyFrom(source);
            source.Numbers.Add(9);
            builder.ApplyTo(target);
            Assert.Same(numbers, target.Numbers);
            Assert.Empty(numbers);
            Assert.Empty(target.Labels);
            Assert.Empty(target.Codes);
            Assert.Empty(target.Options);
            Assert.Equal(before + 1, CollectionDefaultBindings.NumberCalls);

            var result = builder.Build();
            Assert.Empty(result.Numbers);
            Assert.Empty(result.Options);
            Assert.Equal(before + 2, CollectionDefaultBindings.NumberCalls);
        }
        finally
        {
            CollectionDefaultBindings.Numbers[0] = 3;
        }
    }

    [Fact]
    public void ManagedCollectionDefaultsAreDefensivelyCapturedAndReflectedFreshly()
    {
        int before = CollectionDefaultBindings.NumberCalls;
        var model = new ManagedSamples();
        Assert.Equal(before + 1, CollectionDefaultBindings.NumberCalls);
        var storage = model.Numbers;
        Assert.Equal([3, 5], storage);
        Assert.Equal(["draft", "published"], model.Labels);
        Assert.True(model.Codes.Contains(19));
        Assert.True(model.Options.ContainsKey("present"));
        Assert.Null(model.Options["present"]);

        var field = Field(model, "numbers");
        var oldDefault = Assert.IsAssignableFrom<IReadOnlyList<object?>>(field.DefaultValue);
        Assert.NotSame(oldDefault, field.DefaultValue);
        Assert.Throws<NotSupportedException>(() => ((ICollection<object?>)oldDefault).Clear());
        var changes = new List<ModelChange>();
        using var watch = model.VmfChanges.AddPropertyListener("numbers", changes.Add);
        CollectionDefaultBindings.Numbers[0] = 8;
        try
        {
            Assert.Equal([3, 5], storage);
            Assert.True(field.IsSet);
            Assert.Equal(new object?[] { 8, 5 }, Assert.IsAssignableFrom<IReadOnlyList<object?>>(Field(model.AsReadOnly(), "numbers").DefaultValue));
            field.Reset();
            Assert.Same(storage, model.Numbers);
            Assert.Equal([8, 5], storage);
            Assert.Single(changes);
            Assert.False(field.IsSet);
        }
        finally
        {
            CollectionDefaultBindings.Numbers[0] = 3;
        }

        CollectionDefaultBindings.Labels.Add("draft");
        try
        {
            Assert.Throws<ArgumentException>(() => Field(model, "labels").Reset());
            Assert.Equal(["draft", "published"], model.Labels);
        }
        finally
        {
            CollectionDefaultBindings.Labels.RemoveAt(2);
        }

        model.Options.Clear();
        model.Options.Add("known", 11);
        model.Options.Add("present", null);
        field.SetDefault(new[] { 13 });
        before = CollectionDefaultBindings.NumberCalls;
        var deep = ModelSnapshot.Reflection.DeepCopy(model);
        var shallow = ModelSnapshot.Reflection.ShallowCopy(model);
        Assert.Equal(before + 2, CollectionDefaultBindings.NumberCalls);
        foreach (var copy in new[] { deep, shallow })
        {
            Assert.NotSame(storage, copy.Numbers);
            Assert.Equal([8, 5], copy.Numbers);
            Assert.Equal(["known", "present"], copy.Options.Keys);
            var copyDefault = Assert.IsAssignableFrom<IReadOnlyList<object?>>(Field(copy, "numbers").DefaultValue);
            Assert.Equal(new object?[] { 13 }, copyDefault);
        }
        var restored = ModelSnapshot.ReadPlain<ManagedSamples>(ModelSnapshot.WritePlain(model));
        Assert.Equal([8, 5], restored.Numbers);
        Assert.Equal(new object?[] { 3, 5 }, Assert.IsAssignableFrom<IReadOnlyList<object?>>(Field(restored, "numbers").DefaultValue));
    }

    [Fact]
    public void MutableAndImmutableBuildersCaptureDefaultsAndApplyExplicitValues()
    {
        int before = CollectionDefaultBindings.NumberCalls;
        var builder = ManagedSamples.NewBuilder();
        Assert.Equal(before + 1, CollectionDefaultBindings.NumberCalls);
        var mutable = builder.WithNumbers([9]).WithOptions([]).Build();
        Assert.Equal(before + 2, CollectionDefaultBindings.NumberCalls);
        Assert.Equal([9], mutable.Numbers);
        Assert.Empty(mutable.Options);

        before = CollectionDefaultBindings.NumberCalls;
        var candidate = new List<int> { 8 };
        var frozenBuilder = FrozenSamples.NewBuilder().WithNumbers(candidate);
        candidate.Add(7);
        Assert.Equal(before + 1, CollectionDefaultBindings.NumberCalls);
        var frozen = frozenBuilder.Build();
        Assert.Equal(before + 1, CollectionDefaultBindings.NumberCalls);
        Assert.Equal([8], frozen.Numbers);
        Assert.False(frozen.Numbers is ICollection<int> numbers && !numbers.IsReadOnly);
        Assert.False(Field(frozen, "numbers").CanSetDefault);
        Assert.Equal([3, 5], new FrozenSamples().Numbers);
        Assert.Empty(new FrozenSamples(numbers: []).Numbers);
    }

    [Fact]
    public void JavaAndCSharpSnapshotsPreserveExplicitEmptyAndOmittedCollections()
    {
        var specimen = new DefaultCollections
        {
            Mutable = ManagedSamples.NewBuilder().WithNumbers([17]).WithLabels([]).WithCodes([31L]).WithOptions([]).Build(),
            Frozen = FrozenSamples.NewBuilder().WithNumbers([]).WithLabels(["closed"]).WithCodes([]).Build()
        };
        string native = ModelSnapshot.WritePlain(specimen);
        VerifyWire(native);
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_COLLECTION_DEFAULTS");
        if (exchange is null) return;
        VerifyWire(File.ReadAllText(Path.Combine(exchange, "java-collection-defaults.json")));
        File.WriteAllText(Path.Combine(exchange, "csharp-collection-defaults.json"), native);
    }

    [Fact]
    public void PortableLiteralFactoriesPreserveEscapesAndExplicitEmptyOnBothTargets()
    {
        var seed = ModelSnapshot.ReadPlain<LiteralSamples>("{}");
        Assert.Equal(["a,b", "c\\d", "e\"f"], seed.Stages);
        Assert.Equal(80, seed.Ports["port"]);
        string document = ModelSnapshot.WritePlain(LiteralSamples.NewBuilder().WithStages([]).Build());
        Assert.Empty(ModelSnapshot.ReadPlain<LiteralSamples>(document).Stages);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_COLLECTION_DEFAULTS") is { } directory)
        {
            var java = File.ReadAllText(Path.Combine(directory, "java-literal-defaults.json"));
            var restored = ModelSnapshot.ReadPlain<LiteralSamples>(java);
            Assert.Empty(restored.Stages);
            Assert.Equal(80, restored.Ports["port"]);
            File.WriteAllText(Path.Combine(directory, "csharp-literal-defaults.json"), document);
        }
    }

    [Fact]
    public void SchemaUsesLiteralCollectionDefaultsToDetermineRequiredFields()
    {
        string schema = ModelSnapshot.GenerateJsonSchema<SchemaLiteralSamples>(SnapshotSchemaProfile.Plain);
        Verify(schema);
        var value = ModelSnapshot.ReadPlain<SchemaLiteralSamples>("""{"needsWire":[5]}""");
        Assert.Empty(value.Validate());
        Assert.Equal(new[] { 7, 11 }, value.Values);
        Assert.Equal(2L, Assert.Single(value.Codes));
        Assert.Equal("ready", value.Choices[4]);

        var immutable = JsonNode.Parse(ModelSnapshot.GenerateJsonSchema<LiteralSamples>(SnapshotSchemaProfile.Plain))!;
        Assert.Equal(80, immutable["properties"]!["ports"]!["default"]!["port"]!.GetValue<int>());
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_COLLECTION_DEFAULTS") is { } directory)
        {
            Verify(File.ReadAllText(Path.Combine(directory, "java-literal-defaults-schema.json")));
            File.WriteAllText(Path.Combine(directory, "csharp-literal-defaults-schema.json"), schema);
        }

        static void Verify(string schemaDocument)
        {
            var node = JsonNode.Parse(schemaDocument)!;
            Assert.Equal("needsWire", Assert.Single(node["required"]!.AsArray())!.GetValue<string>());
            var props = node["properties"]!;
            Assert.True(JsonNode.DeepEquals(props["values"]!["default"], JsonNode.Parse("[7,11]")));
            Assert.True(JsonNode.DeepEquals(props["codes"]!["default"], JsonNode.Parse("[2]")));
            Assert.True(JsonNode.DeepEquals(props["choices"]!["default"], JsonNode.Parse("""[{"key":4,"value":"ready"}]""")));
            Assert.Empty(props["needsWire"]!["default"]!.AsArray());
        }
    }

    [Fact]
    public void CollectionResetsReplayAgainstAnExplicitlyClearedCheckpoint()
    {
        const string checkpoint = "collection-defaults";
        var binding = new JournalBinding("conformance.collection-defaults", 1, _ => "root");
        var current = ManagedSamples.NewBuilder().WithNumbers([17]).WithCodes([]).WithOptions([]).Build();
        string recorded = ModelSnapshot.WritePlain(current);
        using var recorder = JournalRecorder<ManagedSamples>.Start(current, ModelSnapshot.Registry, binding, checkpoint, 0);
        Field(current, "numbers").Reset();
        Field(current, "codes").Reset();
        Field(current, "options").Reset();
        var journal = JournalJson.Read(JournalJson.Write(recorder.Finish()));

        var fresh = ModelSnapshot.ReadPlain<ManagedSamples>(recorded);
        Assert.Equal([17], fresh.Numbers);
        Assert.Empty(fresh.Codes);
        Assert.Empty(fresh.Options);
        var restored = JournalReplayer.Replay(fresh, binding, checkpoint, journal, ModelSnapshot.Registry);
        Assert.Equal(current.Numbers.ToArray(), restored.Numbers.ToArray());
        Assert.Equal(current.Codes.Order().ToArray(), restored.Codes.Order().ToArray());
        Assert.Equal(current.Options.ToArray(), restored.Options.ToArray());
    }

    [Fact]
    public void MapResetCompletesAndRecordsItsOrderingWhenAnObserverThrows()
    {
        const string checkpoint = "ordered-collection-default";
        var binding = new JournalBinding("conformance.ordered-collection-default", 1, _ => "root");
        var model = ManagedSamples.NewBuilder().WithOptions(new Dictionary<string, int?>
        {
            ["discard-first"] = 1,
            ["known"] = 3,
            ["discard-last"] = null,
        }).Build();
        string before = ModelSnapshot.WritePlain(model);
        var failure = new InvalidOperationException("Observer rejected a removal.");
        int notifications = 0;
        using var recorder = JournalRecorder<ManagedSamples>.Start(model, ModelSnapshot.Registry, binding, checkpoint, 0);
        using var watch = model.VmfChanges.AddPropertyListener("options", _ =>
        {
            if (++notifications == 1) throw failure;
        });

        Assert.Same(failure, Assert.Throws<InvalidOperationException>(() => Field(model, "options").Reset()));
        Assert.Equal(2, notifications);
        Assert.Equal(["present", "known"], model.Options.Keys);
        Assert.Null(model.Options["present"]);
        Assert.Equal(11, model.Options["known"]);

        var journal = JournalJson.Read(JournalJson.Write(recorder.Finish()));
        Assert.Equal(1, journal.WireVersion);
        var fresh = ModelSnapshot.ReadPlain<ManagedSamples>(before);
        var replayed = JournalReplayer.Replay(fresh, binding, checkpoint, journal, ModelSnapshot.Registry);
        Assert.Equal(model.Options.ToArray(), replayed.Options.ToArray());
    }

    private static void VerifyWire(string wire)
    {
        var document = ModelSnapshot.ReadPlain<DefaultCollections>(wire);
        var mutable = Assert.IsType<ManagedSamples>(document.Mutable);
        var frozen = Assert.IsType<FrozenSamples>(document.Frozen);
        Assert.Equal([17], mutable.Numbers);
        Assert.Empty(mutable.Labels);
        Assert.Equal([31L], mutable.Codes);
        Assert.Empty(mutable.Options);
        Assert.Empty(frozen.Numbers);
        Assert.Equal(["closed"], frozen.Labels);
        Assert.Empty(frozen.Codes);
        Assert.True(frozen.Options.ContainsKey("present"));
        Assert.Null(frozen.Options["present"]);
        Assert.Equal([3, 5], ModelSnapshot.ReadPlain<ManagedSamples>("{}").Numbers);
        Assert.Equal([3, 5], ModelSnapshot.ReadPlain<FrozenSamples>("{}").Numbers);
    }

    private static ModelProperty Field(object owner, string name) => ModelSnapshot.Reflection.Reflect(owner).FindProperty(name)!;
}
