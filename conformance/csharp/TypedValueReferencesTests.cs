using System.Text.Json;
using System.Xml;
using Eu.Mihosoft.Vmf.Conformance.Traits;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Eu.Mihosoft.Vmf.Example.Workshops;
using Vmf.Runtime;
using Xunit;
using YamlDotNet.Core;
using TraitModels = Eu.Mihosoft.Vmf.Conformance.Traits.ModelSnapshot;
using ValueModels = Eu.Mihosoft.Vmf.Conformance.Values.ModelSnapshot;
using WorkshopModels = Eu.Mihosoft.Vmf.Example.Workshops.ModelSnapshot;

namespace Vmf.CSharp.Conformance;

public sealed class TypedValueReferencesTests
{
    private const string InlineJson = """
        {"head":{"name":"owned","anchor":{"name":"inline","anchor":{"name":"nested"}},
        "trail":[{"name":"copy"},null,{"name":"copy"}]},"review":[{"name":"outside"},{"name":"outside"}]}
        """;
    private const string InlineYaml = """
        head:
          name: owned
          anchor:
            name: inline
            anchor: {name: nested}
          trail: [{name: copy}, null, {name: copy}]
        review: [{name: outside}, {name: outside}]
        """;
    private const string InlineXml = """
        <eu.mihosoft.vmf.conformance.values.ArchiveRoot xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <head><name>owned</name><anchor><name>inline</name><anchor><name>nested</name></anchor></anchor>
            <trail><item><name>copy</name></item><item xsi:nil="true"/><item><name>copy</name></item></trail>
          </head><review><item><name>outside</name></item><item><name>outside</name></item></review>
        </eu.mihosoft.vmf.conformance.values.ArchiveRoot>
        """;

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void TypedProjectionMatchesJavaForNestedAndMixedReferencesAndScalarMaps(string format)
    {
        var probe = new Thermometer { Sku = "T-1", Unit = "C" };
        var catalog = new Catalog { Featured = probe };
        catalog.Products.AddRange([probe, new Instrument { Sku = "I-1" }]);
        Exchange(format, "catalog", Write(format, catalog, WorkshopModels.Registry), wire =>
        {
            Assert.DoesNotContain("featured", wire);
            var restored = Read<Catalog>(format, wire, WorkshopModels.Registry);
            Assert.Null(restored.Featured);
            Assert.Equal("C", Assert.IsType<Thermometer>(restored.Products[0]).Unit);
        });

        var archive = ValueModels.Read<ArchiveRoot>(Fixture("archive-references"));
        var sourceHead = Assert.IsType<ArchiveNode>(archive.Head);
        sourceHead.Featured.Clear();
        sourceHead.Anchor = new ArchiveNode { Name = "external" };
        Exchange(format, "archive", Write(format, archive, ValueModels.Registry), wire =>
        {
            foreach (string omitted in new[] { "review", "anchor", "trail", "featured", "peers" }) Assert.DoesNotContain(omitted, wire);
            var restored = Read<ArchiveRoot>(format, wire, ValueModels.Registry);
            var head = Assert.IsType<ArchiveNode>(restored.Head);
            Assert.Empty(restored.Review);
            Assert.Null(head.Anchor);
            Assert.Same(head, Assert.Single(head.Children).Parent);
            Assert.Same(head, head.Inset?.InsetOf);
        });
        Assert.NotNull(sourceHead.Anchor);
        Assert.Equal(5, sourceHead.Trail.Count);
        Assert.Equal(3, archive.Review.Count);

        var fixedBadge = FixedBadge.NewBuilder().Build();
        var mixed = BadgeShowcase.NewBuilder().WithSelected(fixedBadge)
            .WithChoices([fixedBadge, null, new MutableBadge(), fixedBadge]).Build();
        Exchange(format, "mixed", Write(format, mixed, TraitModels.Registry), wire =>
        {
            Assert.DoesNotContain("choices", wire);
            var restored = Read<BadgeShowcase>(format, wire, TraitModels.Registry);
            Assert.IsType<FixedBadge>(restored.Selected);
            Assert.Empty(restored.Choices);
        });
        var immutable = BadgeShowcase.NewBuilder().WithSelected(fixedBadge).WithChoices([fixedBadge, null, fixedBadge]).Build();
        Exchange(format, "immutable", Write(format, immutable, TraitModels.Registry), wire =>
        {
            var restored = Read<BadgeShowcase>(format, wire, TraitModels.Registry);
            Assert.Equal(3, restored.Choices.Count);
            Assert.IsType<FixedBadge>(restored.Choices[0]);
            Assert.Null(restored.Choices[1]);
            Assert.NotSame(restored.Choices[0], restored.Choices[2]);
            Assert.NotSame(restored.Selected, restored.Choices[0]);
        });

        var collections = ValueModels.Read<CollectionProfile>(Fixture("scalar-collections"));
        Exchange(format, "collections", Write(format, collections, ValueModels.Registry), wire =>
        {
            var restored = Read<CollectionProfile>(format, wire, ValueModels.Registry);
            Assert.True(restored.Options["@vmf-id"]);
            Assert.Equal(long.MaxValue, restored.Limits["limit"]);
            Assert.True(restored.Limits.TryGetValue("optional", out var limit));
            Assert.Null(limit);
            Assert.Equal('-', restored.Markers[-0.0]);
            Assert.Equal('+', restored.Markers[+0.0]);
        });
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void TypedReaderAcceptsLegacyInlineMutableValuesWhilePlainTreeIsStrict(string format)
    {
        string inline = format switch { "json" => InlineJson, "yaml" => InlineYaml, _ => InlineXml };
        VerifyInline(Read<ArchiveRoot>(format, inline, ValueModels.Registry));
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_TYPED_VALUES") is { } directory)
        {
            var java = File.ReadAllText(Path.Combine(directory, "java-inline." + format));
            VerifyInline(Read<ArchiveRoot>(format, java, ValueModels.Registry));
        }

        Reject(format, () => ReadPlain<ArchiveRoot>(format, inline));
        Reject(format, () => Read<ArchiveRoot>(format, inline.Replace("trail", "featured", StringComparison.Ordinal), ValueModels.Registry));
        var inlineIds = format switch
        {
            "json" => "{\"head\":{\"anchor\":\"123\"}}",
            "yaml" => "head: {anchor: '123'}",
            _ => "<eu.mihosoft.vmf.conformance.values.ArchiveRoot><head><anchor>123</anchor></head></eu.mihosoft.vmf.conformance.values.ArchiveRoot>",
        };
        Reject(format, () => Read<ArchiveRoot>(format, inlineIds, ValueModels.Registry));
    }

    [Theory]
    [InlineData("json")]
    [InlineData("yaml")]
    [InlineData("xml")]
    public void MutableSetsAndModelReferenceKeysRequireGraphMode(string format)
    {
        var archive = ValueModels.Read<ArchiveRoot>(Fixture("archive-references"));
        var head = Assert.IsType<ArchiveNode>(archive.Head);
        Reject(format, () => Write(format, archive, ValueModels.Registry));
        head.Featured.Clear();
        head.Peers.Add(new ArchiveNode());
        Reject(format, () => Write(format, archive, ValueModels.Registry));
        head.Peers.Clear();
        Reject(format, () => WritePlain(format, archive));

        archive.Review.Clear();
        archive.Review.Add(null);
        Assert.DoesNotContain("review", Write(format, archive, ValueModels.Registry));
        var inventory = ValueModels.Read<KeyedInventory>(Fixture("keyed-inventory"));
        Reject(format, () => Write(format, inventory, ValueModels.Registry));
        Assert.Same(inventory.Parts[0], inventory.Assignments[0].Part);
    }

    private static void VerifyInline(ArchiveRoot archive)
    {
        var head = Assert.IsType<ArchiveNode>(archive.Head);
        Assert.Equal("nested", head.Anchor?.Anchor?.Name);
        Assert.Equal("inline", head.Anchor?.Name);
        Assert.Equal(3, head.Trail.Count);
        Assert.Equal("copy", head.Trail[0]?.Name);
        Assert.Null(head.Trail[1]);
        Assert.NotSame(head.Trail[0], head.Trail[2]);
        Assert.Equal(2, archive.Review.Count);
        Assert.NotSame(archive.Review[0], archive.Review[1]);
    }

    private static string Write(string format, object root, SnapshotRegistry registry) => format switch
    {
        "json" => SnapshotJson.WriteWithoutReferences(root, registry),
        "yaml" => SnapshotYaml.WriteWithoutReferences(root, registry),
        _ => SnapshotXml.WriteWithoutReferences(root, registry),
    };

    private static T Read<T>(string format, string text, SnapshotRegistry registry) where T : class => format switch
    {
        "json" => SnapshotJson.ReadWithoutReferences<T>(text, registry),
        "yaml" => SnapshotYaml.ReadWithoutReferences<T>(text, registry),
        _ => SnapshotXml.ReadWithoutReferences<T>(text, registry),
    };

    private static T ReadPlain<T>(string format, string text) where T : class => format switch
    {
        "json" => ValueModels.ReadPlain<T>(text),
        "yaml" => ValueModels.ReadPlainYaml<T>(text),
        _ => ValueModels.ReadPlainXml<T>(text),
    };

    private static string WritePlain(string format, ArchiveRoot archive) => format switch
    {
        "json" => ValueModels.WritePlain(archive),
        "yaml" => ValueModels.WritePlainYaml(archive),
        _ => ValueModels.WritePlainXml(archive),
    };

    private static void Reject(string format, Action action)
    {
        if (format == "json") Assert.ThrowsAny<JsonException>(action);
        else if (format == "yaml") Assert.ThrowsAny<YamlException>(action);
        else Assert.ThrowsAny<XmlException>(action);
    }

    private static string Fixture(string name) => File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "fixtures", name + ".json"));

    private static void Exchange(string format, string name, string specimen, Action<string> verify)
    {
        verify(specimen);
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_TYPED_VALUES") is not { } directory) return;
        verify(File.ReadAllText(Path.Combine(directory, $"java-{name}.{format}")));
        File.WriteAllText(Path.Combine(directory, $"csharp-{name}.{format}"), specimen);
    }
}
