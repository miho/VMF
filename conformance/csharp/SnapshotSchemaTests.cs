using System.Text.Json;
using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.External;
using Eu.Mihosoft.Vmf.Conformance.Immutables;
using Eu.Mihosoft.Vmf.Conformance.Numeric;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Eu.Mihosoft.Vmf.Example.Workshops;
using Vmf.Runtime;
using Xunit;
using AlarmInstrument = Eu.Mihosoft.Vmf.Jackson.Test.Readvalidation.Instrument;
using AlarmGroup = Eu.Mihosoft.Vmf.Jackson.Test.Readvalidation.InstrumentGroup;
using AlarmModels = Eu.Mihosoft.Vmf.Jackson.Test.Readvalidation.ModelSnapshot;
using ExternalModels = Eu.Mihosoft.Vmf.Conformance.External.ModelSnapshot;
using ImmutableModels = Eu.Mihosoft.Vmf.Conformance.Immutables.ModelSnapshot;
using NumericModels = Eu.Mihosoft.Vmf.Conformance.Numeric.ModelSnapshot;
using ValueModels = Eu.Mihosoft.Vmf.Conformance.Values.ModelSnapshot;
using WorkshopModels = Eu.Mihosoft.Vmf.Example.Workshops.ModelSnapshot;

namespace Vmf.CSharp.Conformance;

public sealed class SnapshotSchemaTests
{
    private const string Draft = "http://json-schema.org/draft-07/schema#";
    private static readonly string Fixtures = Path.Combine(AppContext.BaseDirectory, "fixtures");

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void StockAlarmPolicyAgreesWithTheConfiguredReaderAndWireSchema(bool references)
    {
        using var samples = JsonDocument.Parse(Fixture("stock-alarm-policy"));
        string Sample(string name) => samples.RootElement.GetProperty(name).GetRawText();
        var read = references
            ? (Func<string, ModelValidator, AlarmGroup>)AlarmModels.Read<AlarmGroup>
            : AlarmModels.ReadWithoutReferences<AlarmGroup>;
        const string code = "instrument.alarm-limit.required";
        var standard = ModelValidator.NewBuilder(AlarmModels.Reflection).Build();
        var configured = ModelValidator.NewBuilder(AlarmModels.Reflection).WithRule<AlarmInstrument>((instrument, context) =>
        {
            if (instrument.AlarmEnabled && instrument.AlarmLimit is null)
                context.Report(ValidationSeverity.Error, code, "alarmLimit", "An enabled alarm must have a limit");
        }).Build();

        var loaded = read(Sample("missing"), standard);
        Assert.True(standard.Validate(loaded).IsValid);
        var issue = Assert.Single(configured.Validate(loaded).Issues);
        Assert.Equal(code, issue.Code);
        Assert.Equal("/instruments/0/alarmLimit", issue.Path);
        Assert.Equal("alarmLimit", issue.Property?.Name);
        var rejected = Assert.Throws<ModelValidationException>(() => read(Sample("missing"), configured));
        Assert.Equal(issue.ToString(), Assert.Single(rejected.Report.Issues).ToString());

        var repaired = read(Sample("configured"), configured);
        Assert.Equal(8.0, Assert.Single(repaired.Instruments)!.AlarmLimit);
        Assert.True(configured.Validate(repaired).IsValid);
        Assert.True(configured.Validate(read(Sample("inactive"), configured)).IsValid);

        using var condition = JsonDocument.Parse("""
            {"if":{"required":["alarm-enabled"],"properties":{"alarm-enabled":{"const":true}}},
             "then":{"required":["alarm-limit"]}}
            """);
        var assertions = new Dictionary<Type, IReadOnlyList<JsonElement>> { [typeof(AlarmInstrument)] = [condition.RootElement] };
        var profile = references ? SnapshotSchemaProfile.References : SnapshotSchemaProfile.TypedValues;
        string standardSchema = SnapshotSchema.Generate<AlarmGroup>(AlarmModels.Registry, profile);
        string configuredSchema = SnapshotSchema.Generate<AlarmGroup>(AlarmModels.Registry, profile, assertions);
        Assert.NotEqual(standardSchema, configuredSchema);

        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_SCHEMA");
        if (interchange is null) return;
        string name = references ? "references" : "values";
        File.WriteAllText(Path.Combine(interchange, $"csharp-stock-alarm-{name}-default-schema.json"), standardSchema);
        File.WriteAllText(Path.Combine(interchange, $"csharp-stock-alarm-{name}-configured-schema.json"), configuredSchema);
    }

    [Fact]
    public void PlainInventoryKeepsExternalFieldNamesAndConcreteContainment()
    {
        var inventory = WorkshopModels.ReadPlain<ExternalInventory>("""
            {"warehouse_id":"BER-1","stock_items":[{"part_no":"A-42","available_qty":0,"active":false}]}
            """);
        Compare<ExternalInventory>("inventory", WorkshopModels.Registry, SnapshotSchemaProfile.Plain,
            WorkshopModels.WritePlain(inventory), schema =>
            {
                var properties = Properties(schema);
                Assert.Equal(0, properties["stock_items"]!["propertyOrder"]!.GetValue<int>());
                Assert.Equal("#/definitions/eu.mihosoft.vmf.example.workshops.StockItem",
                    properties["stock_items"]!["items"]!["$ref"]!.GetValue<string>());
                Assert.NotNull(properties["warehouse_id"]);
                Assert.Null(properties["@vmf-id"]);
                Assert.Null(properties["@vmf-type"]);
            }, generatedSchema: WorkshopModels.GenerateJsonSchema<ExternalInventory>(SnapshotSchemaProfile.Plain));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void CatalogUsesAliasDiscriminatorsAndReferenceProfileControlsIds(bool references)
    {
        var probe = new Thermometer { Sku = "T-1", Unit = "C" };
        var catalog = new Catalog();
        catalog.Products.AddRange([probe, new Instrument { Sku = "I-1" }]);
        if (references) catalog.Featured = probe;
        var profile = references ? SnapshotSchemaProfile.References : SnapshotSchemaProfile.TypedValues;
        Compare<Catalog>(references ? "catalog-references" : "catalog-values", WorkshopModels.Registry, profile,
            references ? WorkshopModels.Write(catalog) : WorkshopModels.WriteWithoutReferences(catalog), schema =>
            {
                var properties = Properties(schema);
                var definitions = Assert.IsType<JsonObject>(schema["definitions"]);
                var variants = Assert.IsType<JsonArray>(properties["items"]!["items"]!["oneOf"]);
                Assert.Equal(["thermometer", "instrument"], variants.Select(variant => variant!["title"]!.GetValue<string>()));
                foreach (var (variant, name) in variants.Zip(new[] { "thermometer", "instrument" }))
                {
                    Assert.NotNull(definitions[name]);
                    var branches = Assert.IsType<JsonArray>(variant!["allOf"]);
                    Assert.Equal("#/definitions/" + name, branches[0]!["$ref"]!.GetValue<string>());
                    if (name == "thermometer")
                        Assert.Equal("@vmf-type", Assert.Single(Assert.IsType<JsonArray>(branches[1]!["required"]))!.GetValue<string>());
                    else
                        Assert.Null(branches[1]!["required"]);
                    var discriminator = Assert.IsType<JsonArray>(Properties(branches[1]!)["@vmf-type"]!["enum"]);
                    var type = name == "thermometer" ? typeof(Thermometer) : typeof(Instrument);
                    Assert.Equal([name, "eu.mihosoft.vmf.example.workshops." + type.Name],
                        discriminator.Select(alias => alias!.GetValue<string>()));
                }
                Assert.Equal(references, properties.ContainsKey("featured"));
                Assert.Equal(references, properties.ContainsKey("@vmf-id"));
                if (references) AssertTypes(properties["featured"]!, "string", "integer", "null");
            });
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void CatalogReadersAndSchemasKeepPriorTypeNames(bool references)
    {
        const string canonical = "eu.mihosoft.vmf.example.workshops.Thermometer";
        const string previous = "thermometer/old~name";
        const string preferred = "sensor";
        const string discriminator = "@vmf-type";
        var registry = WorkshopModels.Registry.WithTypeAliases(new Dictionary<string, string>
        {
            [previous] = canonical,
            [preferred] = canonical,
        });
        var sensor = new Thermometer { Sku = "T-1", Unit = "C" };
        var catalog = new Catalog();
        catalog.Products.Add(sensor);
        if (references) catalog.Featured = sensor;
        string native = references ? SnapshotJson.Write(catalog, registry) : SnapshotJson.WriteWithoutReferences(catalog, registry);
        Verify(native, preferred);
        foreach (string alias in new[] { previous, canonical, "thermometer" })
        {
            var alternate = JsonNode.Parse(native)!;
            alternate["items"]![0]![discriminator] = alias;
            Verify(alternate.ToJsonString(), alias);
        }
        var invalid = JsonNode.Parse(native)!;
        invalid["items"]![0]![discriminator] = "eu.mihosoft.vmf.example.workshops.Catalog";
        Assert.Throws<JsonException>(() => Read(invalid.ToJsonString()));
        string untagged = references
            ? """{"items":[{"@vmf-id":"legacy","sku":"B-1"}],"featured":"legacy"}"""
            : """{"items":[{"sku":"B-1"}]}""";
        VerifyFallback(untagged);

        var profile = references ? SnapshotSchemaProfile.References : SnapshotSchemaProfile.TypedValues;
        string name = "catalog-renamed-" + (references ? "references" : "values");
        Compare<Catalog>(name, registry, profile, native, schema =>
        {
            var variants = Assert.IsType<JsonArray>(Properties(schema)["items"]!["items"]!["oneOf"]);
            var option = Assert.Single(variants, item => item!["title"]!.GetValue<string>() == preferred);
            var branch = option!["allOf"]![1]!;
            var names = Assert.IsType<JsonArray>(Properties(branch)[discriminator]!["enum"]);
            Assert.Equal([preferred, canonical, "thermometer", previous], names.Select(alias => alias!.GetValue<string>()));
        });

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_SCHEMA");
        if (exchange is null) return;
        Verify(File.ReadAllText(Path.Combine(exchange, $"java-{name}-snapshot.json")), preferred);
        Verify(File.ReadAllText(Path.Combine(exchange, $"java-{name}-canonical.json")), canonical);
        Verify(File.ReadAllText(Path.Combine(exchange, $"java-{name}-previous.json")), previous);
        VerifyFallback(File.ReadAllText(Path.Combine(exchange, $"java-{name}-untagged.json")));
        File.WriteAllText(Path.Combine(exchange, $"csharp-{name}-untagged.json"), untagged);

        void VerifyFallback(string document)
        {
            var fallback = Read(document);
            var item = Assert.IsType<Instrument>(Assert.Single(fallback.Products));
            Assert.Equal("B-1", item.Sku);
            if (references) Assert.Same(item, fallback.Featured);
            else Assert.Null(fallback.Featured);
        }

        Catalog Read(string document) => references ? SnapshotJson.Read<Catalog>(document, registry)
            : SnapshotJson.ReadWithoutReferences<Catalog>(document, registry);

        void Verify(string document, string alias)
        {
            Assert.Equal(alias, JsonNode.Parse(document)!["items"]![0]![discriminator]!.GetValue<string>());
            var restored = Read(document);
            var item = Assert.IsType<Thermometer>(Assert.Single(restored.Products));
            Assert.Equal("C", item.Unit);
            Assert.Equal("T-1", item.Sku);
            if (references) Assert.Same(item, restored.Featured);
            else Assert.Null(restored.Featured);
        }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void ConcreteRootSchemasSelectTheSamePolymorphicTypesAsSnapshotReaders(bool references)
    {
        string name = "instrument-root-" + (references ? "references" : "values");
        var profile = references ? SnapshotSchemaProfile.References : SnapshotSchemaProfile.TypedValues;
        var basis = new Instrument { Sku = "B-1" };
        var subtype = new Thermometer { Sku = "T-1", Unit = "C" };
        string Write(Instrument value) => references ? WorkshopModels.Write(value) : WorkshopModels.WriteWithoutReferences(value);
        Instrument Read(string wire) => references ? WorkshopModels.Read<Instrument>(wire)
            : WorkshopModels.ReadWithoutReferences<Instrument>(wire);

        string baseSnapshot = Write(basis);
        string subtypeSnapshot = Write(subtype);
        Verify(baseSnapshot, subtypeSnapshot);
        Assert.Equal("legacy", Assert.IsType<Instrument>(Read("""{"sku":"legacy"}""")).Sku);
        Assert.Throws<JsonException>(() => Read("""{"@vmf-type":"not-registered"}"""));
        Assert.Throws<JsonException>(() => Read("""{"@vmf-type":"thermometer","unit":{}}"""));

        Compare<Instrument>(name, WorkshopModels.Registry, profile, subtypeSnapshot,
            schema => Assert.Equal(2, Assert.IsType<JsonArray>(schema["oneOf"]).Count),
            generatedSchema: WorkshopModels.GenerateJsonSchema<Instrument>(profile));

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_SCHEMA");
        if (exchange is null) return;
        Verify(File.ReadAllText(Path.Combine(exchange, $"java-{name}-base.json")),
            File.ReadAllText(Path.Combine(exchange, $"java-{name}-snapshot.json")));
        File.WriteAllText(Path.Combine(exchange, $"csharp-{name}-base.json"), baseSnapshot);

        void Verify(string baseWire, string subtypeWire)
        {
            Assert.Equal("B-1", Assert.IsType<Instrument>(Read(baseWire)).Sku);
            Assert.Equal("C", Assert.IsType<Thermometer>(Read(subtypeWire)).Unit);
        }
    }

    [Theory]
    [InlineData(false, "json")]
    [InlineData(false, "yaml")]
    [InlineData(false, "xml")]
    [InlineData(true, "json")]
    [InlineData(true, "yaml")]
    [InlineData(true, "xml")]
    public void InterfaceOnlyRootsRoundTripThroughTheirConcreteTypes(bool references, string format)
    {
        var sensor = new Thermometer { Sku = "P-1", Unit = "C" };
        var catalog = new Catalog();
        catalog.Products.Add(sensor);
        if (references) catalog.Featured = sensor;
        string suffix = references ? "references" : "values";
        string productCase = "role-product-" + suffix;
        string shelfCase = "role-shelf-" + suffix;
        string product = Write<Product>(sensor);
        string shelf = Write<Shelf<Instrument>>(catalog);
        Verify(product, shelf);

        if (format == "json")
        {
            var profile = references ? SnapshotSchemaProfile.References : SnapshotSchemaProfile.TypedValues;
            Compare<Product>(productCase, WorkshopModels.Registry, profile, product,
                schema => Assert.Equal(2, Assert.IsType<JsonArray>(schema["oneOf"]).Count),
                generatedSchema: WorkshopModels.GenerateJsonSchema<Product>(profile));
            Compare<Shelf<Instrument>>(shelfCase, WorkshopModels.Registry, profile, shelf,
                schema => Assert.Single(Assert.IsType<JsonArray>(schema["oneOf"])),
                generatedSchema: WorkshopModels.GenerateJsonSchema<Shelf<Instrument>>(profile));
            Assert.Throws<JsonException>(() => Read<Product>("""{"sku":"P-1"}"""));
            Assert.Throws<JsonException>(() => Read<Product>(
                """{"@vmf-type":"eu.mihosoft.vmf.example.workshops.Catalog"}"""));
            Assert.Throws<JsonException>(() => Read<Product>("""{"@vmf-type":"unknown"}"""));
            Assert.Throws<JsonException>(() => WorkshopModels.GenerateJsonSchema<Product>(SnapshotSchemaProfile.Plain));
            Assert.Throws<JsonException>(() => WorkshopModels.GenerateJsonSchema<Shelf<Thermometer>>(profile));
            Assert.ThrowsAny<JsonException>(() => SnapshotJson.Write(catalog, typeof(Product), WorkshopModels.Registry));
        }

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_SCHEMA");
        if (exchange is null) return;
        Verify(File.ReadAllText(Path.Combine(exchange, $"java-{productCase}-snapshot.{format}")),
            File.ReadAllText(Path.Combine(exchange, $"java-{shelfCase}-snapshot.{format}")));
        if (format != "json")
        {
            File.WriteAllText(Path.Combine(exchange, $"csharp-{productCase}-snapshot.{format}"), product);
            File.WriteAllText(Path.Combine(exchange, $"csharp-{shelfCase}-snapshot.{format}"), shelf);
        }

        string Write<T>(T value) where T : class => (references, format) switch
        {
            (true, "json") => WorkshopModels.WriteAs(value),
            (false, "json") => WorkshopModels.WriteWithoutReferencesAs(value),
            (true, "yaml") => WorkshopModels.WriteYamlAs(value),
            (false, "yaml") => WorkshopModels.WriteYamlWithoutReferencesAs(value),
            (true, "xml") => WorkshopModels.WriteXmlAs(value),
            (false, "xml") => WorkshopModels.WriteXmlWithoutReferencesAs(value),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };

        T Read<T>(string document) where T : class => (references, format) switch
        {
            (true, "json") => WorkshopModels.Read<T>(document),
            (false, "json") => WorkshopModels.ReadWithoutReferences<T>(document),
            (true, "yaml") => WorkshopModels.ReadYaml<T>(document),
            (false, "yaml") => WorkshopModels.ReadYamlWithoutReferences<T>(document),
            (true, "xml") => WorkshopModels.ReadXml<T>(document),
            (false, "xml") => WorkshopModels.ReadXmlWithoutReferences<T>(document),
            _ => throw new ArgumentOutOfRangeException(nameof(format)),
        };

        void Verify(string productDocument, string shelfDocument)
        {
            var restoredProduct = Assert.IsType<Thermometer>(Read<Product>(productDocument));
            Assert.Equal("P-1", restoredProduct.Sku);
            Assert.Equal("C", restoredProduct.Unit);
            var restoredShelf = Assert.IsType<Catalog>(Read<Shelf<Instrument>>(shelfDocument));
            var item = Assert.IsType<Thermometer>(Assert.Single(restoredShelf.Products));
            Assert.Equal("P-1", item.Sku);
            Assert.Equal("C", item.Unit);
            if (references) Assert.Same(item, restoredShelf.Featured);
            else Assert.Null(restoredShelf.Featured);
        }
    }

    [Fact]
    public void StrictPlainPolymorphicRootSchemaKeepsTheExactDeclaredType()
    {
        var basis = new Instrument { Sku = "B-1" };
        string document = WorkshopModels.WritePlain(basis);
        Assert.Equal("B-1", WorkshopModels.ReadPlain<Instrument>(document).Sku);
        var subtype = new Thermometer { Unit = "C" };
        string subtypeSnapshot = WorkshopModels.WriteWithoutReferences(subtype);
        Assert.Throws<JsonException>(() => WorkshopModels.ReadPlain<Instrument>(subtypeSnapshot));
        Assert.Throws<JsonException>(() => WorkshopModels.WritePlain<Instrument>(subtype));
        string schema = WorkshopModels.GenerateJsonSchema<Instrument>(SnapshotSchemaProfile.Plain);
        Assert.Null(JsonNode.Parse(schema)!["oneOf"]);

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_SCHEMA");
        if (exchange is null) return;
        File.WriteAllText(Path.Combine(exchange, "csharp-instrument-root-plain-schema.json"), schema);
        File.WriteAllText(Path.Combine(exchange, "csharp-instrument-root-plain-snapshot.json"), document);
    }

    [Fact]
    public void ContainmentKeysApplyOnlyToEntriesAndUseWireNames()
    {
        var inventory = ValueModels.Read<KeyedInventory>(Fixture("keyed-inventory"));
        Compare<KeyedInventory>("keyed", ValueModels.Registry, SnapshotSchemaProfile.References, ValueModels.Write(inventory), schema =>
        {
            var properties = Properties(schema);
            AssertKey(properties["items"]!, "sku_code");
            AssertKey(properties["assignments"]!, "part");
            Assert.Null(schema["definitions"]!["eu.mihosoft.vmf.conformance.values.KeyedItem"]!["required"]);
        });
    }

    [Fact]
    public void ScalarCollectionsDistinguishSetsNullableSlotsAndTypedMapEntries()
    {
        var model = ValueModels.ReadPlain<CollectionProfile>(Fixture("scalar-collections"));
        Compare<CollectionProfile>("collections", ValueModels.Registry, SnapshotSchemaProfile.Plain,
            ValueModels.WritePlain(model), schema =>
            {
                var field = Properties(schema);
                Assert.Equal(3, field["counts"]!["maxItems"]!.GetValue<int>());
                Assert.True(field["phases"]!["uniqueItems"]!.GetValue<bool>());
                Assert.Equal(1, field["levels"]!["minItems"]!.GetValue<int>());
                Assert.Equal(4, field["levels"]!["maxItems"]!.GetValue<int>());
                Assert.Null(field["levels"]!["uniqueItems"]);
                Assert.Equal(1, field["limits"]!["minProperties"]!.GetValue<int>());
                Assert.Equal(3, field["limits"]!["maxProperties"]!.GetValue<int>());
                AssertTypes(field["limits"]!["additionalProperties"]!, "integer", "null");
                AssertTypes(field["options"]!["additionalProperties"]!, "boolean", "null");
                var entry = field["markers"]!["items"]!;
                Assert.False(entry["additionalProperties"]!.GetValue<bool>());
                Assert.Equal(["key", "value"], Assert.IsType<JsonArray>(entry["required"]).Select(node => node!.GetValue<string>()));
                Assert.NotNull(Properties(entry)["key"]!["anyOf"]);
                AssertTypes(Properties(entry)["value"]!, "string", "null");
            }, native => AssertTypes(Properties(native)["messages"]!["items"]!, "string", "null"));
    }

    [Fact]
    public void ReferenceArchiveDistinguishesNullablePositionsFromIdentitySets()
    {
        var archive = ValueModels.Read<ArchiveRoot>(Fixture("archive-references"));
        Compare<ArchiveRoot>("archive", ValueModels.Registry, SnapshotSchemaProfile.References, ValueModels.Write(archive), schema =>
        {
            Assert.Contains(Assert.IsType<JsonArray>(schema["required"]), item => item?.GetValue<string>() == "head");
            AssertTypes(Properties(schema)["review"]!["items"]!, "string", "integer", "null");
            var node = Properties(schema["definitions"]!["eu.mihosoft.vmf.conformance.values.ArchiveNode"]!);
            AssertTypes(node["trail"]!["items"]!, "string", "integer", "null");
            Assert.True(node["featured"]!["uniqueItems"]!.GetValue<bool>());
            Assert.Null(node["parent"]);
            Assert.Null(node["insetOf"]);
        }, native =>
        {
            Assert.Contains(Assert.IsType<JsonArray>(native["required"]), item => item?.GetValue<string>() == "review");
            Assert.Equal(2, Properties(native)["review"]!["minItems"]!.GetValue<int>());
            Assert.Equal("null", Properties(native)["review"]!["contains"]!["not"]!["type"]!.GetValue<string>());
        });
    }

    [Fact]
    public void ImmutableReferenceIncludesBothIdAndAllowlistedPolymorphicValue()
    {
        var badge = new ImmutableBadge("shared");
        var portfolio = new ExtendedPortfolio(aliases: ["first", "second"], current: badge, trail: [badge, null, badge],
            preferred: [badge], pool: [badge], limits: new Dictionary<string, long?> { ["exact"] = 9_007_199_254_740_993L, ["unknown"] = null });
        var model = new PortfolioSelection { Portfolio = portfolio };
        Compare<PortfolioSelection>("immutable", ImmutableModels.Registry, SnapshotSchemaProfile.References,
            ImmutableModels.Write(model), schema =>
            {
                var options = Assert.IsType<JsonArray>(Properties(schema)["portfolio"]!["oneOf"]);
                AssertTypes(options[0]!, "string", "integer", "null");
                Assert.Contains(options, option => option?["title"]?.GetValue<string>() is
                    "eu.mihosoft.vmf.conformance.immutables.ExtendedPortfolio");
                var subtype = Properties(schema["definitions"]!["eu.mihosoft.vmf.conformance.immutables.ExtendedPortfolio"]!);
                Assert.Null(subtype["preferred"]!["uniqueItems"]);
                Assert.NotNull(subtype["trail"]!["items"]!["oneOf"]);
            });
    }

    [Fact]
    public void DefaultSchemaIncludesInheritedValuesAndPermitsExplicitNullClears()
    {
        var model = DefaultSettings.NewBuilder().WithName(null).WithLimit(null).WithStages(["one", ""])
            .WithEntries([new DefaultEntry()]).Build();
        Compare<DefaultSettings>("defaults", ValueModels.Registry, SnapshotSchemaProfile.Plain, ValueModels.WritePlain(model), schema =>
        {
            var properties = Properties(schema);
            Assert.Equal("idle\nready", properties["display_name"]!["default"]!.GetValue<string>());
            Assert.Equal(7, properties["limit"]!["default"]!.GetValue<int>());
            Assert.Equal(9_007_199_254_740_993L, properties["sequence"]!["default"]!.GetValue<long>());
            Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(properties["baseline"]!["default"]!.GetValue<double>()));
            AssertTypes(properties["display_name"]!, "string", "null");
            AssertTypes(properties["limit"]!, "integer", "null");
        });
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void ClearedOptionalScalarsSatisfySchemaEvenWithTypedConstraints(bool references)
    {
        var model = new CheckedOptionalDefaults { Label = null, Limit = null, Enabled = null };
        var validator = ModelValidator.NewBuilder(ValueModels.Reflection).Build();
        string snapshot = references ? ValueModels.Write(model) : ValueModels.WritePlain(model);
        var loaded = references
            ? ValueModels.Read<CheckedOptionalDefaults>(snapshot, validator)
            : ValueModels.ReadPlain<CheckedOptionalDefaults>(snapshot, validator);
        Assert.Null(loaded.Label);
        Assert.Null(loaded.Limit);
        Assert.Null(loaded.Enabled);
        Assert.True(validator.Validate(loaded).IsValid);
        loaded.Label = "";
        loaded.Limit = 11;
        Assert.Equal(["/label", "/limit"], validator.Validate(loaded).Issues.Select(issue => issue.Path));

        Compare<CheckedOptionalDefaults>(references ? "checked-defaults-references" : "checked-defaults-values",
            ValueModels.Registry, references ? SnapshotSchemaProfile.References : SnapshotSchemaProfile.Plain, snapshot, schema =>
            {
                var properties = Properties(schema);
                AssertTypes(properties["label"]!, "string", "null");
                AssertTypes(properties["limit"]!, "integer", "null");
                AssertTypes(properties["enabled"]!, "boolean", "null");
                Assert.Equal("integer", properties["primitive"]!["type"]!.GetValue<string>());
                Assert.Equal("boolean", properties["mandatory"]!["type"]!.GetValue<string>());
                Assert.Equal(1, properties["label"]!["minLength"]!.GetValue<int>());
            });
    }

    [Fact]
    public void ScalarsExposeExactNativeWidthsAndSpecialFloatingTokens()
    {
        var model = new ScalarSample
        {
            ByteValue = sbyte.MinValue, ShortValue = short.MaxValue, IntValue = int.MinValue, LongValue = long.MaxValue,
            CharValue = '中', DoubleValue = double.NegativeInfinity, OptionalFloat = float.NaN,
        };
        Compare<ScalarSample>("scalars", ValueModels.Registry, SnapshotSchemaProfile.Plain, ValueModels.WritePlain(model), schema =>
        {
            var properties = Properties(schema);
            Assert.Equal(0, properties["intValue"]!["default"]!.GetValue<int>());
        }, native =>
        {
            var properties = Properties(native);
            AssertBounds(properties["byteValue"]!, sbyte.MinValue, sbyte.MaxValue);
            AssertBounds(properties["shortValue"]!, short.MinValue, short.MaxValue);
            AssertBounds(properties["intValue"]!, int.MinValue, int.MaxValue);
            AssertBounds(properties["longValue"]!, long.MinValue, long.MaxValue);
            Assert.Equal(1, properties["charValue"]!["maxLength"]!.GetValue<int>());
            Assert.Equal(["NaN", "Infinity", "-Infinity"], Assert.IsType<JsonArray>(properties["doubleValue"]!["anyOf"]![1]!["enum"])
                .Select(node => node!.GetValue<string>()));
        });
    }

    [Fact]
    public void NamedConstraintsPreserveLexicalBoundsAndTypeAssertions()
    {
        using var rule = JsonDocument.Parse("""
            {"if":{"properties":{"status_label":{"const":"A-10"}},"required":["status_label"]},"then":{"required":["sequence"]}}
            """);
        var rules = new Dictionary<Type, IReadOnlyList<JsonElement>> { [typeof(ScalarEntry)] = [rule.RootElement] };
        using var fixture = JsonDocument.Parse(Fixture("scalar-validation"));
        var batch = ValueModels.ReadPlain<ScalarBatch>(fixture.RootElement[0].GetProperty("model").GetRawText());
        Compare<ScalarBatch>("constraints", ValueModels.Registry, SnapshotSchemaProfile.Plain, ValueModels.WritePlain(batch), schema =>
        {
            Assert.Equal(1, Properties(schema)["title"]!["maxLength"]!.GetValue<int>());
            Assert.Equal("title", Assert.Single(Assert.IsType<JsonArray>(schema["required"]))!.GetValue<string>());
            var entry = schema["definitions"]!["eu.mihosoft.vmf.conformance.values.ScalarEntry"]!;
            var fields = Properties(entry);
            Assert.Equal("status_label", Assert.Single(Assert.IsType<JsonArray>(entry["required"]))!.GetValue<string>());
            Assert.Equal("[A-Z]-\\d{2}", fields["status_label"]!["pattern"]!.GetValue<string>());
            Assert.Equal("^[\\w]+(?![\\s\\S])", fields["notes"]!["pattern"]!.GetValue<string>());
            Assert.Contains(Assert.IsType<JsonArray>(entry["allOf"]), item => JsonNode.DeepEquals(item, JsonNode.Parse(rule.RootElement.GetRawText())));
            Assert.Contains("9007199254740993", fields["sequence"]!.ToJsonString());
            Assert.Equal("0.5", fields["ratio"]!["exclusiveMaximum"]!.ToJsonString());
            Assert.Matches("1[eE]\\+?400", fields["aboveFiniteDouble"]!.ToJsonString());
        }, typeConstraints: rules);
        var root = JsonNode.Parse(SnapshotSchema.Generate<ScalarEntry>(ValueModels.Registry, SnapshotSchemaProfile.Plain, rules))!;
        Assert.Single(Assert.IsType<JsonArray>(root["allOf"]));
    }

    [Fact]
    public void ClosedGenericsUseResolvedValueAndMapKeyShapes()
    {
        var integers = JsonNode.Parse(SnapshotSchema.Generate<LongSeries>(NumericModels.Registry, SnapshotSchemaProfile.Plain))!;
        var floating = JsonNode.Parse(SnapshotSchema.Generate<DoubleSeries>(NumericModels.Registry, SnapshotSchemaProfile.Plain))!;
        AssertBounds(Properties(integers)["current"]!, long.MinValue, long.MaxValue);
        Assert.NotNull(Properties(floating)["current"]!["anyOf"]);
        AssertBounds(Properties(Properties(integers)["byNumber"]!["items"]!)["key"]!, long.MinValue, long.MaxValue);
        Assert.NotNull(Properties(Properties(floating)["byNumber"]!["items"]!)["key"]!["anyOf"]);
    }

    [Fact]
    public void GeneratedExternalUuidSchemaUsesStringsAndRetainsNullableListSlots()
    {
        var properties = Properties(JsonNode.Parse(ExternalModels.GenerateJsonSchema<ExternalValueEntry>(SnapshotSchemaProfile.Plain))!);
        var source = properties["sourceId"]!;
        var related = properties["relatedIds"]!;
        var members = related["items"]!;
        AssertTypes(source, "string", "null");
        Assert.Equal("array", related["type"]!.GetValue<string>());
        AssertTypes(members, "string", "null");
        Assert.Equal("uuid", source["format"]!.GetValue<string>());
        Assert.Equal("uuid", members["format"]!.GetValue<string>());
        string pattern = source["pattern"]!.GetValue<string>();
        Assert.Matches(pattern, "87fcac65-2efc-4ce8-9e64-303ef53bb7a1");
        Assert.DoesNotMatch(pattern, "87fcac652efc4ce89e64303ef53bb7a1");
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void UuidSchemasDescribeGeneratedGenericAndBoundedSnapshots(bool references)
    {
        var id = Guid.Parse("87fcac65-2efc-4ce8-9e64-303ef53bb7a1");
        var generic = GenericExternalIds.NewBuilder().WithLatest(id).WithSamples([id, null, id]).Build();
        var bounded = BoundedExternalIds.NewBuilder().WithIds([null, id]).Build();
        var profile = references ? SnapshotSchemaProfile.References : SnapshotSchemaProfile.Plain;
        string name = references ? "references" : "values";
        Compare<GenericExternalIds>($"uuid-generic-{name}", ExternalModels.Registry, profile,
            references ? ExternalModels.Write(generic) : ExternalModels.WritePlain(generic), schema =>
            {
                var fields = Properties(schema);
                AssertNullableUuid(fields["latest"]!);
                var samples = fields["samples"]!;
                AssertNullableUuid(samples["items"]!);
                Assert.Equal(1, samples["minItems"]!.GetValue<int>());
                Assert.Null(samples["uniqueItems"]);
            });
        Compare<BoundedExternalIds>($"uuid-bounded-{name}", ExternalModels.Registry, profile,
            references ? ExternalModels.Write(bounded) : ExternalModels.WritePlain(bounded), schema =>
            {
                var ids = Properties(schema)["ids"]!;
                AssertNullableUuid(ids["items"]!);
                Assert.Equal(1, ids["minItems"]!.GetValue<int>());
                Assert.Equal(2, ids["maxItems"]!.GetValue<int>());
            });

        static void AssertNullableUuid(JsonNode schema)
        {
            AssertTypes(schema, "string", "null");
            Assert.Equal("uuid", schema["format"]!.GetValue<string>());
            Assert.Null(schema["$ref"]);
        }
    }

    [Fact]
    public void RegistriesEscapeAliasPointersAndCombineSchemaAssertionsWithReferences()
    {
        var registry = new SnapshotRegistry([
            new SnapshotType("Example.Root", typeof(ExampleRoot), () => new ExampleRoot(), [
                new("child", SnapshotPropertyKind.Containment, typeof(ExampleChild), owner => ((ExampleRoot)owner).Child,
                    set: (owner, child) => ((ExampleRoot)owner).Child = (ExampleChild?)child,
                    schema: new SnapshotSchemaProperty(lowerBound: 1, annotationsJson: "{\"title\":\"Nested\"}",
                        assertionsJson: "{\"maxProperties\":1}")),
            ]),
            new SnapshotType("Example.Child", typeof(ExampleChild), () => new ExampleChild(), []),
        ], new Dictionary<string, string> { ["child/~ #"] = "Example.Child" });
        var schema = JsonNode.Parse(SnapshotSchema.Generate<ExampleRoot>(registry))!;
        var child = Properties(schema)["child"]!;
        Assert.NotNull(schema["definitions"]!["child/~ #"]);
        Assert.Equal("#/definitions/child~1~0%20%23", child["allOf"]![0]!["$ref"]!.GetValue<string>());
        Assert.Equal("Nested", child["title"]!.GetValue<string>());
        Assert.Equal(1, child["maxProperties"]!.GetValue<int>());
        Assert.Equal("child", Assert.Single(Assert.IsType<JsonArray>(schema["required"]))!.GetValue<string>());
    }

    [Theory]
    [InlineData("{broken default", null, null)]
    [InlineData(null, "{broken annotation", null)]
    [InlineData(null, null, "{broken assertion")]
    public void InvalidSchemaMetadataOnlyFailsWhenSchemaIsRequested(string? defaultJson, string? annotationsJson,
        string? assertionsJson)
    {
        var registry = new SnapshotRegistry([
            new SnapshotType("Example.Root", typeof(ExampleRoot), () => new ExampleRoot(), [
                new("child", SnapshotPropertyKind.Containment, typeof(ExampleChild), owner => ((ExampleRoot)owner).Child,
                    set: (owner, child) => ((ExampleRoot)owner).Child = (ExampleChild?)child,
                    schema: new SnapshotSchemaProperty(defaultJson: defaultJson, annotationsJson: annotationsJson,
                        assertionsJson: assertionsJson)),
            ]),
            new SnapshotType("Example.Child", typeof(ExampleChild), () => new ExampleChild(), []),
        ]);
        Assert.Null(SnapshotJson.ReadPlain<ExampleRoot>("{}", registry).Child);
        Assert.ThrowsAny<JsonException>(() => SnapshotSchema.Generate<ExampleRoot>(registry));
    }

    private static void Compare<T>(string name, SnapshotRegistry registry, SnapshotSchemaProfile profile, string snapshot,
        Action<JsonNode> common, Action<JsonNode>? nativeOnly = null,
        IReadOnlyDictionary<Type, IReadOnlyList<JsonElement>>? typeConstraints = null, string? generatedSchema = null) where T : class
    {
        string generated = generatedSchema ?? SnapshotSchema.Generate<T>(registry, profile, typeConstraints);
        Assert.Equal(generated, SnapshotSchema.Generate(typeof(T), registry, profile, typeConstraints));
        var schema = JsonNode.Parse(generated)!;
        Assert.Equal(Draft, schema["$schema"]!.GetValue<string>());
        Assert.Equal(typeof(T).Name.Split('`', 2)[0], schema["title"]!.GetValue<string>());
        common(schema);
        nativeOnly?.Invoke(schema);

        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_SCHEMA");
        if (interchange is null) return;
        common(JsonNode.Parse(File.ReadAllText(Path.Combine(interchange, "java-" + name + "-schema.json")))!);
        File.WriteAllText(Path.Combine(interchange, "csharp-" + name + "-schema.json"), generated);
        File.WriteAllText(Path.Combine(interchange, "csharp-" + name + "-snapshot.json"), snapshot);
    }

    private static JsonObject Properties(JsonNode schema) => Assert.IsType<JsonObject>(schema["properties"]);

    private static void AssertTypes(JsonNode node, params string[] expected) =>
        Assert.Equal(expected, Assert.IsType<JsonArray>(node["type"]).Select(type => type!.GetValue<string>()));

    private static void AssertKey(JsonNode array, string wireName)
    {
        var assertion = array["items"]!["allOf"]![1]!;
        Assert.Equal(wireName, Assert.Single(Assert.IsType<JsonArray>(assertion["required"]))!.GetValue<string>());
        Assert.Equal("null", Properties(assertion)[wireName]!["not"]!["type"]!.GetValue<string>());
    }

    private static void AssertBounds(JsonNode schema, long minimum, long maximum)
    {
        Assert.Equal(minimum, schema["minimum"]!.GetValue<long>());
        Assert.Equal(maximum, schema["maximum"]!.GetValue<long>());
    }

    private static string Fixture(string name) => File.ReadAllText(Path.Combine(Fixtures, name + ".json"));

    private sealed class ExampleRoot
    {
        public ExampleChild? Child { get; set; }
    }

    private sealed class ExampleChild;
}
