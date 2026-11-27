using System.Text.Json;
using System.Xml;
using System.Xml.Linq;
using Eu.Mihosoft.Vmf.Jackson.Test.External_types01;
using Eu.Mihosoft.Vmf.Jackson.Test.Type_formats01;
using Eu.Mihosoft.Vmftest.Complex.Supplier;
using Vmf.Runtime;
using Xunit;
using EnumSnapshot = Eu.Mihosoft.Vmf.Jackson.Test.External_types01.ModelSnapshot;
using FormatSnapshot = Eu.Mihosoft.Vmf.Jackson.Test.Type_formats01.ModelSnapshot;
using SupplierSnapshot = Eu.Mihosoft.Vmftest.Complex.Supplier.ModelSnapshot;

namespace Vmf.CSharp.Conformance;

public sealed class StockNamedHostTests
{
    private const long OrderDate = -2208988800123L;
    private const long ShipDate = long.MaxValue - 1;

    [Fact]
    public void NamedEnumAndItsExistingXmlRootAliasArePortable()
    {
        var model = ExternalTypeModel01.NewBuilder().WithName("Model 01").WithNameValue("Model 01 Value")
            .WithEnumValue(ExternalEnum.SECOND).WithTags(["tag1", "tag2", "tag3"]).WithValues([1, 2, 3]).Build();
        Exchange("stock-named-enum", model, static restored =>
        {
            Assert.Equal(ExternalEnum.SECOND, restored.EnumValue);
            Assert.Equal(new[] { "tag1", "tag2", "tag3" }, restored.Tags);
            Assert.Equal(new int?[] { 1, 2, 3 }, restored.Values);
        }, [
            ("json", EnumSnapshot.WriteWithoutReferences, EnumSnapshot.ReadWithoutReferences<ExternalTypeModel01>),
            ("xml", static value => EnumSnapshot.WriteXmlWithoutReferences(value, "external-type-model-01"),
                EnumSnapshot.ReadXmlWithoutReferences<ExternalTypeModel01>),
            ("yaml", EnumSnapshot.WriteYamlWithoutReferences, EnumSnapshot.ReadYamlWithoutReferences<ExternalTypeModel01>)
        ], static (format, text) =>
        {
            if (format == "xml") Assert.Equal("external-type-model-01", XDocument.Parse(text).Root!.Name.LocalName);
        });
    }

    [Fact]
    public void XmlRootAliasIsOptInAndMustIdentifyTheActualModel()
    {
        var model = new ExternalTypeModel01 { EnumValue = ExternalEnum.FIRST };
        var canonical = EnumSnapshot.WriteXmlWithoutReferences(model);
        Assert.Equal("eu.mihosoft.vmf.jackson.test.external_types01.ExternalTypeModel01",
            XDocument.Parse(canonical).Root!.Name.LocalName);
        Assert.Equal(ExternalEnum.FIRST, EnumSnapshot.ReadXmlWithoutReferences<ExternalTypeModel01>(canonical).EnumValue);
        Assert.Throws<ArgumentException>(() => EnumSnapshot.WriteXmlWithoutReferences(model, "unregistered"));

        var registered = new SnapshotRegistry(
            [new("probe.first", typeof(FirstProbe), () => new FirstProbe(), []),
             new("probe.other", typeof(OtherProbe), () => new OtherProbe(), [])],
            aliases: new Dictionary<string, string> { ["first"] = "probe.first", ["other"] = "probe.other" });
        Assert.Throws<ArgumentException>(() => SnapshotXml.Write(new FirstProbe(), registered, "other"));
        Assert.Throws<XmlException>(() => SnapshotXml.Read<FirstProbe>("<other/>", registered));
        Assert.IsType<FirstProbe>(SnapshotXml.Read<FirstProbe>(SnapshotXml.Write(new FirstProbe(), registered, "first"), registered));
        Assert.IsType<FirstProbe>(SnapshotXml.ReadPlain<FirstProbe>(SnapshotXml.WritePlain(new FirstProbe(), registered, "first"), registered));
    }

    private sealed class FirstProbe { }
    private sealed class OtherProbe { }

    [Fact]
    public void PetEnumDefaultRetainsItsNameAcrossSnapshots()
    {
        var model = FormatModel01.NewBuilder().WithPets([
            Pet.NewBuilder().WithName("unknown").Build(),
            Pet.NewBuilder().WithName("known").WithType(PetType.DOG).Build(),
        ]).Build();
        Assert.Equal(PetType.UNKNOWN, new Pet().Type);
        Assert.Equal(PetType.UNKNOWN, FormatSnapshot.ReadWithoutReferences<Pet>("{}").Type);
        Exchange("stock-pet-types", model, static restored =>
        {
            Assert.Collection(restored.Pets,
                pet => { Assert.Equal(PetType.UNKNOWN, pet!.Type); Assert.Equal("#ffa500", pet.Color); },
                pet => Assert.Equal(PetType.DOG, pet!.Type));
        }, [
            ("json", FormatSnapshot.WriteWithoutReferences, FormatSnapshot.ReadWithoutReferences<FormatModel01>),
            ("xml", FormatSnapshot.WriteXmlWithoutReferences, FormatSnapshot.ReadXmlWithoutReferences<FormatModel01>),
            ("yaml", FormatSnapshot.WriteYamlWithoutReferences, FormatSnapshot.ReadYamlWithoutReferences<FormatModel01>)
        ]);
    }

    [Fact]
    public void MutableDateUsesFullWidthMillisecondsInTheOriginalDocuments()
    {
        var date = new JavaDate(0);
        var order = new PurchaseOrder { Date = date };
        order.Items.Add(new Item { ShipDate = new JavaDate(ShipDate) });
        var view = order.AsReadOnly();
        date.EpochMilliseconds = OrderDate;
        Assert.Same(date, view.Date);
        Assert.Equal(OrderDate, view.Date!.EpochMilliseconds);

        Exchange("stock-supplier-date", order, static restored =>
        {
            Assert.Equal(OrderDate, restored.Date!.EpochMilliseconds);
            Assert.Equal(ShipDate, Assert.Single(restored.Items)!.ShipDate!.EpochMilliseconds);
        }, [
            ("json", SupplierSnapshot.WriteWithoutReferences, SupplierSnapshot.ReadWithoutReferences<PurchaseOrder>),
            ("xml", SupplierSnapshot.WriteXmlWithoutReferences, SupplierSnapshot.ReadXmlWithoutReferences<PurchaseOrder>),
            ("yaml", SupplierSnapshot.WriteYamlWithoutReferences, SupplierSnapshot.ReadYamlWithoutReferences<PurchaseOrder>)
        ]);
    }

    [Fact]
    public void SchemasDescribeTheOriginalNamedHostsAndStockPropertyMetadata()
    {
        ExchangeSchema("stock-named-enum", EnumSnapshot.GenerateJsonSchema<ExternalTypeModel01>(), schema =>
        {
            Assert.Equal(["FIRST", "SECOND", "THIRD"], EnumNames(Properties(schema, "enumValue").GetProperty("enumValue")));
        });
        ExchangeSchema("stock-pet-types", FormatSnapshot.GenerateJsonSchema<FormatModel01>(), schema =>
        {
            var pets = Properties(schema, "pets").GetProperty("pets");
            var pet = Properties(schema, "otherProperty");
            Assert.Equal("table", pets.GetProperty("format").GetString());
            Assert.True(pets.GetProperty("uniqueItems").GetBoolean());
            var type = pet.GetProperty("type");
            Assert.Equal("UNKNOWN", type.GetProperty("default").GetString());
            Assert.Equal(["BIRD", "CAT", "DOG", "FISH", "UNKNOWN"], EnumNames(type));
            var color = pet.GetProperty("color");
            Assert.Equal("color", color.GetProperty("format").GetString());
            Assert.Equal("#ffa500", color.GetProperty("default").GetString());
            Assert.Equal("My Title", pet.GetProperty("otherProperty").GetProperty("title").GetString());
            Assert.Equal("table", pet.GetProperty("properties").GetProperty("format").GetString());
            Assert.False(pet.TryGetProperty("model", out _));
        });
        ExchangeSchema("stock-supplier-date", SupplierSnapshot.GenerateJsonSchema<PurchaseOrder>(), schema =>
        {
            AssertInteger(Properties(schema, "date").GetProperty("date"));
            AssertInteger(Properties(schema, "shipDate").GetProperty("shipDate"));
        });
    }

    private static void Exchange<T>(string name, T value, Action<T> verify,
        (string Suffix, Func<T, string> Write, Func<string, T> Read)[] formats, Action<string, string>? verifyWire = null)
    {
        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE");
        foreach (var format in formats)
        {
            var wire = format.Write(value);
            verifyWire?.Invoke(format.Suffix, wire);
            verify(format.Read(wire));
            if (directory is null) continue;
            var java = File.ReadAllText(Path.Combine(directory, $"java-{name}.{format.Suffix}"));
            verifyWire?.Invoke(format.Suffix, java);
            verify(format.Read(java));
            File.WriteAllText(Path.Combine(directory, $"csharp-{name}.{format.Suffix}"), wire);
        }
    }

    private static void ExchangeSchema(string name, string native, Action<JsonElement> verify)
    {
        using var own = JsonDocument.Parse(native);
        verify(own.RootElement);
        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_EXTERNAL_TYPE");
        if (directory is null) return;
        using var java = JsonDocument.Parse(File.ReadAllText(Path.Combine(directory, $"java-{name}-schema.json")));
        verify(java.RootElement);
        File.WriteAllText(Path.Combine(directory, $"csharp-{name}-schema.json"), native);
    }

    private static JsonElement Properties(JsonElement schema, string identifyingField)
    {
        if (schema.TryGetProperty("properties", out var properties) && properties.TryGetProperty(identifyingField, out _))
            return properties;
        foreach (var definition in schema.GetProperty("definitions").EnumerateObject())
        {
            if (definition.Value.TryGetProperty("properties", out properties) && properties.TryGetProperty(identifyingField, out _))
                return properties;
        }
        throw new InvalidOperationException($"Unknown schema property {identifyingField}.");
    }

    private static string[] EnumNames(JsonElement schema)
    {
        if (schema.TryGetProperty("enum", out var values))
            return values.EnumerateArray().Select(value => value.GetString()!).Order(StringComparer.Ordinal).ToArray();
        return schema.GetProperty("anyOf").EnumerateArray().Where(alternative => alternative.TryGetProperty("enum", out _))
            .SelectMany(EnumNames).ToArray();
    }

    private static void AssertInteger(JsonElement schema)
    {
        var type = schema.GetProperty("type");
        if (type.ValueKind == JsonValueKind.String) Assert.Equal("integer", type.GetString());
        else Assert.Contains(type.EnumerateArray(), alternative => alternative.GetString() == "integer");
    }
}
