using System.Text.Json;
using System.Xml;
using System.Xml.Linq;
using Eu.Mihosoft.Vmf.Conformance.Immutables;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Eu.Mihosoft.Vmf.Example.Workshops;
using Vmf.Runtime;
using Values = Eu.Mihosoft.Vmf.Conformance.Values.ModelSnapshot;
using ImmutableModels = Eu.Mihosoft.Vmf.Conformance.Immutables.ModelSnapshot;
using WorkshopModels = Eu.Mihosoft.Vmf.Example.Workshops.ModelSnapshot;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class XmlSnapshotTests
{
    private static readonly string Fixtures = Path.Combine(AppContext.BaseDirectory, "fixtures");
    private const string EncodedKey = "entrée🌱\rline";
    private const string CollectionsRoot = "eu.mihosoft.vmf.conformance.values.CollectionProfile";
    private const string DefaultsRoot = "eu.mihosoft.vmf.conformance.values.DefaultSettings";

    [Fact]
    public void ExternalInventoryPreservesWireNamesAndZeroValues()
    {
        var inventory = ExternalInventory.NewBuilder().WithWarehouseId("BER-1").WithItems(
            [StockItem.NewBuilder().WithSku("A-42").WithQuantity(0).WithActive(false).Build(),
             StockItem.NewBuilder().WithSku("B-10").WithQuantity(8).WithActive(true).Build()]).Build();
        Exchange("inventory", WorkshopModels.WritePlainXml(inventory.AsReadOnly()), xml =>
        {
            var document = XDocument.Parse(xml);
            var root = Assert.IsType<XElement>(document.Root);
            Assert.Equal("eu.mihosoft.vmf.example.workshops.ExternalInventory", root.Name.LocalName);
            Assert.Equal("BER-1", root.Element("warehouse_id")?.Value);
            Assert.Equal("A-42", root.Element("stock_items")?.Elements().First().Element("part_no")?.Value);
            Assert.Null(root.Element("vmf-id"));
            var restored = WorkshopModels.ReadPlainXml<ExternalInventory>(xml);
            Assert.Equal(2, restored.Items.Count);
            Assert.Equal("A-42", restored.Items[0].Sku);
            Assert.Equal(0, restored.Items[0].Quantity);
            Assert.False(restored.Items[0].Active);
            Assert.Equal(8, restored.Items[1].Quantity);
            Assert.True(restored.Items[1].Active);
        });
    }

    [Fact]
    public void ExplicitNilPreservesClearedDefaultsAndAnEmptyStringMember()
    {
        var model = DefaultSettings.NewBuilder().WithName(null).WithLimit(null)
            .WithStages(["one", ""]).WithEntries([DefaultEntry.NewBuilder().Build()]).Build();
        Exchange("defaults", Values.WritePlainXml(model), xml =>
        {
            XNamespace xsi = "http://www.w3.org/2001/XMLSchema-instance";
            var root = Assert.IsType<XElement>(XDocument.Parse(xml).Root);
            Assert.Equal("true", root.Element("display_name")?.Attribute(xsi + "nil")?.Value);
            Assert.Equal("true", root.Element("limit")?.Attribute(xsi + "nil")?.Value);
            var restored = Values.ReadPlainXml<DefaultSettings>(xml);
            Assert.Null(restored.Name);
            Assert.Null(restored.Limit);
            Assert.Equal(["one", ""], restored.Stages);
            Assert.Equal("new", Assert.Single(restored.Entries).Label);
            Assert.Equal(long.MinValue, BitConverter.DoubleToInt64Bits(restored.Baseline));
            Assert.Equal(9_007_199_254_740_993L, restored.Sequence);
            Assert.Equal('中', restored.Mode);
        });
    }

    [Fact]
    public void RepeatedAdditiveXmlFieldsAreIgnoredInAllSnapshotProfiles()
    {
        string xml = File.ReadAllText(Path.Combine(Fixtures, "xml-additive-fields.xml"));
        var withMetadata = XDocument.Parse(xml);
        withMetadata.Root!.AddFirst(new XElement("vmf-type", DefaultsRoot));
        string typed = withMetadata.ToString();
        withMetadata.Root.AddFirst(new XElement("vmf-id", "1"));
        foreach (var model in new[]
        {
            Values.ReadPlainXml<DefaultSettings>(xml),
            Values.ReadXmlWithoutReferences<DefaultSettings>(typed),
            Values.ReadXml<DefaultSettings>(withMetadata.ToString()),
        })
        {
            Assert.Equal("known", model.Name);
            Assert.Equal(7, model.Limit);
            Assert.Equal("nested", Assert.Single(model.Entries).Label);
        }
    }

    [Theory]
    [InlineData("vmf-id")]
    [InlineData("vmf-type")]
    public void PlainXmlStillRejectsGraphMetadataInAnIgnoredSibling(string field)
    {
        string xml = File.ReadAllText(Path.Combine(Fixtures, "xml-additive-fields.xml"))
            .Replace("<level>56</level>", $"<{field}>56</{field}>", StringComparison.Ordinal);
        var error = Assert.Throws<XmlException>(() => Values.ReadPlainXml<DefaultSettings>(xml));
        Assert.Contains("Plain XML cannot contain VMF graph metadata", error.Message);
    }

    [Fact]
    public void IgnoredXmlFieldsRespectThePhysicalDepthLimit()
    {
        string[] layers = Enumerable.Repeat("<future>", 999).ToArray();
        string inside = string.Concat(layers) + string.Concat(Enumerable.Repeat("</future>", layers.Length));
        string Readable(string nested) => $"<{DefaultsRoot}><future/>{nested}<display_name>known</display_name></{DefaultsRoot}>";

        Assert.Equal("known", Values.ReadPlainXml<DefaultSettings>(Readable(inside)).Name);
        Assert.Contains("maximum element depth", Assert.Throws<XmlException>(() =>
            Values.ReadPlainXml<DefaultSettings>(Readable($"<future>{inside}</future>"))).Message);
    }

    [Theory]
    [InlineData("display_name", "known")]
    [InlineData("vmf-type", DefaultsRoot)]
    [InlineData("vmf-id", "1")]
    public void RepeatedKnownAndReservedXmlFieldsAreRejected(string field, string value)
    {
        string xml = $"<{DefaultsRoot}><{field}>{value}</{field}><future/><{field}>{value}</{field}></{DefaultsRoot}>";
        Assert.Contains("Duplicate field", Assert.Throws<XmlException>(() => Values.ReadPlainXml<DefaultSettings>(xml)).Message);
        Assert.Contains("Duplicate field", Assert.Throws<XmlException>(() => Values.ReadXmlWithoutReferences<DefaultSettings>(xml)).Message);
        Assert.Contains("Duplicate field", Assert.Throws<XmlException>(() => Values.ReadXml<DefaultSettings>(xml)).Message);
    }

    [Fact]
    public void IgnoringRepeatedFieldsStillRequiresARegisteredRoot()
    {
        string xml = File.ReadAllText(Path.Combine(Fixtures, "xml-additive-fields.xml"))
            .Replace(DefaultsRoot, "settings", StringComparison.Ordinal);
        Assert.Contains("Expected the registered root element", Assert.Throws<XmlException>(() =>
            Values.ReadPlainXml<DefaultSettings>(xml)).Message);
        var registry = Values.Registry.WithTypeAliases(new Dictionary<string, string> { ["settings"] = DefaultsRoot });
        Assert.Equal("known", SnapshotXml.ReadPlain<DefaultSettings>(xml, registry).Name);
    }

    [Fact]
    public void LegacyStringListsDistinguishEmptyElementsFromExplicitNil()
    {
        const string fields = "<messages><item/><item></item>"
            + "<item xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:nil=\"true\"/></messages>";
        string doc = $"<{CollectionsRoot}>{fields}</{CollectionsRoot}>";
        foreach (var model in new[]
        {
            Values.ReadPlainXml<CollectionProfile>(doc),
            Values.ReadXmlWithoutReferences<CollectionProfile>(doc),
            Values.ReadXml<CollectionProfile>(doc),
        })
        {
            Assert.Equal(new string?[] { "", "", null }, model.Messages);
        }
    }

    [Fact]
    public void PolymorphicCatalogReplaysTheConcreteTypeAndAlias()
    {
        var thermometer = Thermometer.NewBuilder().WithSku("T-1").WithUnit("C").Build();
        var instrument = Instrument.NewBuilder().WithSku("I-1").Build();
        var catalog = Catalog.NewBuilder().WithProducts([thermometer, instrument]).WithFeatured(thermometer).Build();
        Exchange("catalog", WorkshopModels.WriteXml(catalog.AsReadOnly()), xml =>
        {
            var root = Assert.IsType<XElement>(XDocument.Parse(xml).Root);
            Assert.Contains("thermometer", root.Descendants("vmf-type").Select(field => field.Value));
            var restored = WorkshopModels.ReadXml<Catalog>(xml);
            Assert.Equal(2, restored.Products.Count);
            var probe = Assert.IsType<Thermometer>(restored.Products[0]);
            Assert.Same(probe, restored.Featured);
            Assert.Equal("C", probe.Unit);
            Assert.IsType<Instrument>(restored.Products[1]);
        });
    }

    [Fact]
    public void XmlByValueRestoresImmutableSubtypeAndSeparateOccurrences()
    {
        var wrapper = new PortfolioSelection { Portfolio = ImmutableCollectionTests.Create() };
        Exchange("values", ImmutableModels.WriteXmlWithoutReferences(wrapper.AsReadOnly()), xml =>
        {
            var document = XDocument.Parse(xml);
            Assert.Contains("eu.mihosoft.vmf.conformance.immutables.ExtendedPortfolio",
                document.Descendants("vmf-type").Select(field => field.Value));
            Assert.Empty(document.Descendants("vmf-id"));
            var restored = ImmutableModels.ReadXmlWithoutReferences<PortfolioSelection>(xml);
            var portfolio = Assert.IsType<ExtendedPortfolio>(restored.Portfolio);
            ImmutableCollectionTests.Verify(portfolio);
            Assert.NotSame(portfolio.Current, portfolio.Trail[0]);
            Assert.NotSame(portfolio.Trail[0], portfolio.Trail[2]);
            Assert.Equal(portfolio.Current, portfolio.Trail[0]);
            Assert.Throws<XmlException>(() => ImmutableModels.ReadPlainXml<PortfolioSelection>(xml));
            document.Root!.AddFirst(new XElement("vmf-id", "1"));
            Assert.Throws<XmlException>(() => ImmutableModels.ReadXmlWithoutReferences<PortfolioSelection>(document.ToString()));
        });
    }

    [Fact]
    public void ArchiveKeepsRepeatedAndNullReferenceSlots()
    {
        var model = Values.Read<ArchiveRoot>(File.ReadAllText(Path.Combine(Fixtures, "archive-references.json")));
        Exchange("archive", Values.WriteXml(model), xml =>
        {
            var restored = Values.ReadXml<ArchiveRoot>(xml);
            var head = Assert.IsType<ArchiveNode>(restored.Head);
            var left = Assert.Single(head.Children);
            var right = Assert.IsType<ArchiveNode>(head.Inset);
            Assert.Same(head, left.Parent);
            Assert.Same(head, right.InsetOf);
            Assert.Equal(5, head.Trail.Count);
            Assert.Same(right, head.Trail[0]);
            Assert.Null(head.Trail[1]);
            Assert.Same(left, head.Trail[2]);
            Assert.Same(right, head.Trail[3]);
            Assert.Same(head, head.Trail[4]);
            Assert.Same(left, head.Featured[0]);
            Assert.Same(right, head.Featured[1]);
        });
    }

    [Fact]
    public void ManagedScalarCollectionsUseJavaXmlEntriesAndPreserveTypedValues()
    {
        var model = Values.ReadPlain<CollectionProfile>(File.ReadAllText(Path.Combine(Fixtures, "scalar-collections.json")));
        model.Messages.Add("");
        model.Levels.Add(1e23);
        model.Options.Add("", true);
        model.Options.Add(EncodedKey, null);
        model.Markers.Add(1e23, '\ud800');
        model.Markers.Add(double.Epsilon, '中');
        model.Markers.Add(double.PositiveInfinity, '∞');
        model.Markers.Add(double.NegativeInfinity, null);
        Exchange("collections", Values.WritePlainXml(model.AsReadOnly()), xml =>
        {
            var root = Assert.IsType<XElement>(XDocument.Parse(xml).Root);
            Assert.Equal(CollectionsRoot, root.Name.LocalName);
            Assert.NotEmpty(root.Element("options")!.Elements("entry"));
            Assert.NotEmpty(root.Element("markers")!.Elements("entry"));
            Assert.Equal(4, root.Element("levels")!.Elements("member").Count());
            Assert.Single(root.Descendants("key64"));
            Assert.Single(root.Descendants("value64"));
            Assert.All(root.Descendants("null"), marker => Assert.Equal("true", marker.Value));

            var restored = Values.ReadPlainXml<CollectionProfile>(xml);
            Assert.Equal(new string?[] { "ready", null, "ready", "" }, restored.Messages);
            Assert.Equal(4, restored.Levels.Count);
            Assert.All(new[] { 0.0, -0.0, double.NaN, 1e23 }, value => Assert.True(restored.Levels.Contains(value)));
            Assert.True(restored.Options["@vmf-id"]);
            Assert.False(restored.Options["@vmf-type"]);
            Assert.True(restored.Options[""]);
            Assert.True(restored.Options.TryGetValue(EncodedKey, out var option));
            Assert.Null(option);
            Assert.Equal(long.MaxValue, restored.Limits["limit"]);
            Assert.True(restored.Limits.TryGetValue("optional", out var limit));
            Assert.Null(limit);
            Assert.Equal(7, restored.Markers.Count);
            Assert.Equal('+', restored.Markers[0.0]);
            Assert.Equal('-', restored.Markers[-0.0]);
            Assert.Equal('\ud800', restored.Markers[1e23]);
            Assert.Equal('中', restored.Markers[double.Epsilon]);
            Assert.Equal('∞', restored.Markers[double.PositiveInfinity]);
            Assert.True(restored.Markers.TryGetValue(double.NaN, out var nan));
            Assert.Null(nan);
            Assert.True(restored.Markers.TryGetValue(double.NegativeInfinity, out var infinity));
            Assert.Null(infinity);
        });
    }

    [Theory]
    [InlineData("0x1.0p0")]
    [InlineData("1e9999")]
    [InlineData("1e-9999")]
    [InlineData("+Infinity")]
    [InlineData(" 1.0")]
    [InlineData("1..0")]
    public void MalformedFloatingMapKeysAndSetMembersAreRejected(string number)
    {
        Assert.Throws<XmlException>(() => ReadCollections("<levels><member><value>" + number + "</value></member></levels>"));
        Assert.Throws<XmlException>(() => ReadCollections("<markers><entry><key>" + number + "</key><null>true</null></entry></markers>"));
    }

    [Theory]
    [InlineData("+0007", 7)]
    [InlineData("-0007", -7)]
    public void IntegerModelFieldsAcceptSignedPaddedXmlText(string text, int expected)
    {
        const string root = "eu.mihosoft.vmf.conformance.values.DefaultSettings";
        string Document(string limit, string sequence) => $"<{root}><limit>{limit}</limit><sequence>{sequence}</sequence></{root}>";
        string xml = Document(text, "-09223372036854775808");
        foreach (var model in new[]
        {
            Values.ReadPlainXml<DefaultSettings>(xml),
            Values.ReadXmlWithoutReferences<DefaultSettings>(xml),
            Values.ReadXml<DefaultSettings>(xml),
        })
        {
            Assert.Equal(expected, model.Limit);
            Assert.Equal(long.MinValue, model.Sequence);
        }
        Assert.Throws<XmlException>(() => Values.ReadPlainXml<DefaultSettings>(Document("2147483648", "0")));
        Assert.Throws<XmlException>(() => Values.ReadPlainXml<DefaultSettings>(Document("0", "9223372036854775808")));
        Assert.ThrowsAny<JsonException>(() => Values.ReadPlain<DefaultSettings>("""{"limit":+0007}"""));
    }

    [Fact]
    public void FloatingCollectionsRejectDuplicatesEvenWithDifferentDecimalSpelling()
    {
        var valid = ReadCollections("<levels><member><value>1e23</value></member><member><value>4.9E-324</value></member></levels>"
            + "<markers><entry><key>-0</key><null>true</null></entry><entry><key>0</key><null>true</null></entry></markers>");
        Assert.True(valid.Levels.Contains(1e23));
        Assert.True(valid.Levels.Contains(double.Epsilon));
        Assert.Equal(2, valid.Markers.Count);
        Assert.True(valid.Markers.ContainsKey(-0.0));
        Assert.Throws<XmlException>(() => ReadCollections("<levels><member><value>1e23</value></member>"
            + "<member><value>9.999999999999999E22</value></member></levels>"));
        Assert.Throws<XmlException>(() => ReadCollections("<markers><entry><key>1e23</key><null>true</null></entry>"
            + "<entry><key>9.999999999999999E22</key><null>true</null></entry></markers>"));
    }

    [Theory]
    [InlineData("<!DOCTYPE x [<!ELEMENT x ANY>]><x/>")]
    [InlineData("<eu.mihosoft.vmf.example.workshops.ExternalInventory xmlns=\"https://example.invalid\"><warehouse_id>X</warehouse_id></eu.mihosoft.vmf.example.workshops.ExternalInventory>")]
    [InlineData("<eu.mihosoft.vmf.example.workshops.ExternalInventory><warehouse_id>A</warehouse_id><warehouse_id>B</warehouse_id></eu.mihosoft.vmf.example.workshops.ExternalInventory>")]
    public void MalformedPlainArchivesAreRejected(string xml)
    {
        Assert.Throws<XmlException>(() => WorkshopModels.ReadPlainXml<ExternalInventory>(xml));
    }

    private static CollectionProfile ReadCollections(string fields) =>
        Values.ReadPlainXml<CollectionProfile>("<" + CollectionsRoot + ">" + fields + "</" + CollectionsRoot + ">");

    private static void Exchange(string name, string xml, Action<string> verify)
    {
        verify(xml);
        string? interchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_XML");
        if (interchange is not null)
        {
            verify(File.ReadAllText(Path.Combine(interchange, "java-" + name + ".xml")));
            File.WriteAllText(Path.Combine(interchange, "csharp-" + name + ".xml"), xml);
        }
    }
}
