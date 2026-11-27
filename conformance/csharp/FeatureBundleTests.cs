using System.Text.Json;
using System.Text.Json.Nodes;
using System.Xml;
using System.Xml.Linq;
using Eu.Mihosoft.Vmf.Conformance.Equality;
using Eu.Mihosoft.Vmf.Conformance.Immutables;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Eu.Mihosoft.Vmf.Example.Workshops;
using Vmf.Runtime;
using Xunit;
using EqualityModels = Eu.Mihosoft.Vmf.Conformance.Equality.ModelSnapshot;
using ImmutableModels = Eu.Mihosoft.Vmf.Conformance.Immutables.ModelSnapshot;
using ValueModels = Eu.Mihosoft.Vmf.Conformance.Values.ModelSnapshot;
using WorkshopModels = Eu.Mihosoft.Vmf.Example.Workshops.ModelSnapshot;

namespace Vmf.CSharp.Conformance;

public sealed class FeatureBundleTests
{
    private const string HeadName = "main\r\u0001🚀";
    private const string Key = "limit\r\n\t";
    private static readonly double NegativeZero = BitConverter.Int64BitsToDouble(long.MinValue);
    private static readonly SnapshotRegistry Registry = SnapshotRegistry.CombineCanonical(
        WorkshopModels.Registry, ValueModels.Registry, ImmutableModels.Registry);

    [Fact]
    public void AttachmentsKeepExactOwnersAndLiveFeaturesInInsertionOrder()
    {
        var registry = SnapshotRegistry.CombineCanonical(EqualityModels.Registry, ValueModels.Registry, ImmutableModels.Registry);
        var owner = new EqualityNode { Name = "same" };
        var equal = new EqualityNode { Name = "same" };
        Assert.Equal(owner, equal);
        var ownerView = owner.AsReadOnly();
        var archive = new ArchiveRoot();
        var view = archive.AsReadOnly();
        var portfolio = new PortfolioSelection();
        var attachments = new FeatureAttachments(registry);
        attachments.Attach(owner, archive);
        attachments.Attach(ownerView, view);
        attachments.Attach(ownerView, view);
        attachments.Attach(ownerView, portfolio);
        Assert.Same(archive, Assert.Single(attachments.All(owner)));
        Assert.Empty(attachments.All(equal));
        Assert.Null(attachments.Find<ArchiveRoot>(ownerView));
        Assert.Same(view, attachments.Find<IReadOnlyArchiveRoot>(ownerView));
        Assert.Same(view, attachments.Find<object>(ownerView));
        var prior = attachments.All(ownerView);
        Assert.Collection(prior, entry => Assert.Same(view, entry), entry => Assert.Same(portfolio, entry));
        Assert.Throws<ArgumentException>(() => attachments.Attach(ownerView, archive));
        Assert.Throws<ArgumentException>(() => attachments.Attach(ownerView, new object()));
        Assert.Throws<ArgumentException>(() => attachments.Attach(new object(), archive));
        Assert.False(attachments.Detach(ownerView, archive));
        Assert.True(attachments.Detach(ownerView, view));
        Assert.Same(portfolio, Assert.Single(attachments.All(ownerView)));
        Assert.Equal(2, prior.Count);
        Assert.True(attachments.Detach(ownerView, portfolio));
        Assert.Empty(attachments.All(ownerView));
        Assert.Same(archive, attachments.Find<ArchiveRoot>(owner));

        archive.Head = new ArchiveNode { Name = "live" };
        Assert.Equal("live", ((IReadOnlyArchiveRoot)prior[0]).Head?.Name);
    }

    [Theory]
    [InlineData(VmfDocumentFormat.Json, "json")]
    [InlineData(VmfDocumentFormat.Yaml, "yaml")]
    [InlineData(VmfDocumentFormat.Xml, "xml")]
    public void BundlesExchangeIndependentModelsAndLocalGraphReferences(VmfDocumentFormat format, string extension)
    {
        var (owner, features) = Specimen();
        var codec = new FeatureBundleCodec(Registry);
        string serialized = codec.Write(owner, features, format);
        Verify(codec.Read<Catalog>(serialized, format));
        Assert.Contains("eu.mihosoft.vmf.example.workshops.Thermometer", serialized);
        if (format == VmfDocumentFormat.Xml)
        {
            var root = Assert.IsType<XElement>(XDocument.Parse(serialized).Root);
            Assert.Equal("vmf-features", root.Name.LocalName);
            Assert.Equal("1", root.Attribute("version")?.Value);
            Assert.Contains(root.Descendants("property"), element => element.Attribute("encoding")?.Value == "utf16be-base64");
            Assert.Contains(root.Descendants("property"), element => element.Attribute("name-encoding")?.Value == "utf16be-base64");
        }

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_FEATURES");
        if (exchange is null) return;
        Verify(codec.Load<Catalog>(Path.Combine(exchange, "java-bundle." + extension)));
        codec.Save(Path.Combine(exchange, "csharp-bundle." + extension), owner, features);
    }

    [Theory]
    [InlineData(VmfDocumentFormat.Json, "json")]
    [InlineData(VmfDocumentFormat.Yaml, "yaml")]
    [InlineData(VmfDocumentFormat.Xml, "xml")]
    public void BundlesRestoreAnImmutableOwnerWithIndependentFeatureScopes(VmfDocumentFormat format, string extension)
    {
        var badge = new ImmutableBadge("shared");
        var owner = new ExtendedPortfolio(status: "archived", current: badge, trail: [badge, null, badge]);
        var attachments = new FeatureAttachments(Registry);
        Assert.Same(owner, owner.AsReadOnly());
        attachments.Attach(owner, new PortfolioSelection { Portfolio = owner });
        attachments.Attach(owner, badge);

        var codec = new FeatureBundleCodec(Registry);
        string wire = codec.Write(owner, attachments, format);
        VerifyImmutableOwner(codec.Read<ImmutablePortfolio>(wire, format));

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_FEATURES");
        if (exchange is null) return;
        VerifyImmutableOwner(codec.Load<ImmutablePortfolio>(Path.Combine(exchange, "java-immutable-bundle." + extension)));
        codec.Save(Path.Combine(exchange, "csharp-immutable-bundle." + extension), owner, attachments);

        void VerifyImmutableOwner(FeatureBundle<ImmutablePortfolio> document)
        {
            var restored = Assert.IsType<ExtendedPortfolio>(document.Owner);
            Assert.NotSame(owner, restored);
            Assert.Empty(document.Attachments.All(owner));
            var entries = document.Attachments.All(restored.AsReadOnly());
            Assert.Equal(2, entries.Count);
            var featurePortfolio = Assert.IsType<ExtendedPortfolio>(Assert.IsType<PortfolioSelection>(entries[0]).Portfolio);
            var featureBadge = Assert.IsType<ImmutableBadge>(entries[1]);
            Assert.Equal("archived", restored.Status);
            Assert.Equal(restored, featurePortfolio);
            Assert.NotSame(restored, featurePortfolio);
            Assert.Equal(restored.Current, featureBadge);
            Assert.NotSame(restored.Current, featureBadge);
            Assert.NotSame(featurePortfolio.Current, featureBadge);
            Assert.NotSame(featurePortfolio.Current, restored.Current);
            foreach (var portfolio in new[] { restored, featurePortfolio })
            {
                Assert.Collection(portfolio.Trail, entry => Assert.Same(portfolio.Current, entry), Assert.Null,
                    entry => Assert.Same(portfolio.Current, entry));
            }
        }
    }

    [Theory]
    [InlineData(VmfDocumentFormat.Json, "json")]
    [InlineData(VmfDocumentFormat.Yaml, "yaml")]
    [InlineData(VmfDocumentFormat.Xml, "xml")]
    public void OwnerAndFeatureEntriesRespectTheSelectedBundleDepth(VmfDocumentFormat format, string extension)
    {
        var codec = new FeatureBundleCodec(Registry);
        var temporary = Directory.CreateTempSubdirectory("vmf-feature-depth-");
        try
        {
            foreach (bool feature in new[] { false, true })
            {
                int depth = feature && format != VmfDocumentFormat.Xml ? 497 : 498;
                string entry = feature ? "feature" : "owner";
                string path = Path.Combine(temporary.FullName, $"depth-{entry}.{extension}");
                var (owner, attachments) = DeepEntry(depth, feature);
                codec.Save(path, owner, attachments, format);
                string accepted = File.ReadAllText(path);
                var (oversized, excessive) = DeepEntry(depth + 1, feature);
                Assert.Throws<IOException>(() => codec.Save(path, oversized, excessive, format));
                Assert.Equal(accepted, File.ReadAllText(path));
                Assert.DoesNotContain(temporary.EnumerateFiles(), file => file.Name.StartsWith(".vmf-features-", StringComparison.Ordinal));
                VerifyDepth(codec.Load<ArchiveNode>(path, format), depth, feature);

                string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_FEATURES");
                if (exchange is null) continue;
                VerifyDepth(codec.Load<ArchiveNode>(Path.Combine(exchange, $"java-depth-{entry}.{extension}"), format), depth, feature);
                File.Copy(path, Path.Combine(exchange, $"csharp-depth-{entry}.{extension}"), overwrite: true);
            }
        }
        finally
        {
            temporary.Delete(recursive: true);
        }
    }

    private static (IReadOnlyArchiveNode Owner, FeatureAttachments Attachments) DeepEntry(int depth, bool feature)
    {
        var tail = new ArchiveNode { Name = "tail" };
        var tree = tail;
        for (int link = depth; link > 0; link--)
        {
            var parent = new ArchiveNode { Name = "parent" };
            parent.Children.Add(tree);
            tree = parent;
        }
        tree.Anchor = tail;
        var owner = feature ? new ArchiveNode().AsReadOnly() : tree.AsReadOnly();
        var attachments = new FeatureAttachments(Registry);
        if (feature) attachments.Attach(owner, tree.AsReadOnly());
        return (owner, attachments);
    }

    private static void VerifyDepth(FeatureBundle<ArchiveNode> bundle, int depth, bool feature)
    {
        var tree = feature ? Assert.IsType<ArchiveNode>(bundle.Attachments.Find<ArchiveNode>(bundle.Owner)) : bundle.Owner;
        Assert.Equal(feature ? 1 : 0, bundle.Attachments.All(bundle.Owner).Count);
        Assert.Empty(bundle.Attachments.All(bundle.Owner.AsReadOnly()));
        var tail = tree;
        for (int link = depth; link > 0; link--)
        {
            var parent = tail;
            tail = Assert.IsType<ArchiveNode>(Assert.Single(parent.Children));
            Assert.Same(parent, tail.Parent);
        }
        Assert.Same(tail, tree.Anchor);
        Assert.Equal("tail", tail.Name);
    }

    [Fact]
    public void ReadChecksTheEnvelopeTypesDuplicatesAndRegistryBeforeReturningABundle()
    {
        var owner = new Catalog();
        var features = new FeatureAttachments(Registry);
        features.Attach(owner, new ArchiveRoot { Head = new ArchiveNode { Name = "registered feature" } });
        var codec = new FeatureBundleCodec(Registry);
        string wire = codec.Write(owner, features);
        Assert.Throws<JsonException>(() => new FeatureBundleCodec(WorkshopModels.Registry).Read<Catalog>(wire));
        Assert.Throws<JsonException>(() => codec.Read<ArchiveRoot>(wire));
        var document = JsonNode.Parse(wire)!;
        document["version"] = 2;
        Assert.Throws<JsonException>(() => codec.Read<Catalog>(document.ToJsonString()));
        document["version"] = 1;
        document["owner"]!["value"]!["@vmf-type"] = "thermometer";
        Assert.Throws<JsonException>(() => codec.Read<Catalog>(document.ToJsonString()));
        Assert.IsType<JsonObject>(document["owner"]!["value"]).Remove("@vmf-type");
        var entries = Assert.IsType<JsonArray>(document["features"]);
        entries.Add(entries[0]!.DeepClone());
        Assert.Throws<JsonException>(() => codec.Read<Catalog>(document.ToJsonString()));
        Assert.Throws<JsonException>(() => codec.Read<Catalog>(wire.Insert(1, "\"version\":1,")));
        Assert.Throws<JsonException>(() => codec.Read<Catalog>("""
            {"version":1,"owner":{"type":"eu.mihosoft.vmf.example.workshops.Catalog","value":{"@vmf-type":"\ud800"}},"features":[]}
            """));
        Assert.Throws<XmlException>(() => codec.Read<Catalog>(
            "<!DOCTYPE vmf-features><vmf-features version=\"1\"/>", VmfDocumentFormat.Xml));
    }

    [Fact]
    public void MutableReferencesCannotResolveAgainstASeparateBundleEntry()
    {
        var codec = new FeatureBundleCodec(Registry);
        var owner = new ArchiveRoot { Head = new ArchiveNode { Name = "owner" } };
        owner.Review.Add(owner.Head);
        var archive = new ArchiveRoot { Head = new ArchiveNode { Name = "feature" } };
        var features = new FeatureAttachments(Registry);
        features.Attach(owner, archive);
        var wire = JsonNode.Parse(codec.Write(owner, features))!;
        string ownerId = wire["owner"]!["value"]!["head"]!["@vmf-id"]!.GetValue<string>();
        wire["features"]![0]!["value"]!["head"]!["anchor"] = ownerId;
        Assert.Throws<JsonException>(() => codec.Read<ArchiveRoot>(wire.ToJsonString()));
        archive.Head!.Anchor = owner.Head;
        Assert.Throws<JsonException>(() => codec.Write(owner, features));
    }

    [Fact]
    public void CanonicalCompositionKeepsCollidingPackageAliasesIndependent()
    {
        var rootType = new SnapshotType("App.Owner", typeof(AppOwner), () => new AppOwner(), [], writeType: true);
        var featureType = new SnapshotType("Plugin.Feature", typeof(PluginFeature), () => new PluginFeature(), [], writeType: true);
        var rootRegistry = new SnapshotRegistry([rootType], new Dictionary<string, string> { ["shared"] = "App.Owner" });
        var featureRegistry = new SnapshotRegistry([featureType], new Dictionary<string, string> { ["shared"] = "Plugin.Feature" });
        var registry = SnapshotRegistry.CombineCanonical(rootRegistry, featureRegistry, rootRegistry);
        var owner = new AppOwner();
        var feature = new PluginFeature();
        var attachments = new FeatureAttachments(registry);
        attachments.Attach(owner, feature);
        Assert.Contains("\"shared\"", SnapshotJson.Write(owner, rootRegistry));
        Assert.Contains("\"shared\"", SnapshotJson.Write(feature, featureRegistry));
        var codec = new FeatureBundleCodec(registry);
        string output = codec.Write(owner, attachments);
        Assert.DoesNotContain("\"shared\"", output);
        var restored = codec.Read<AppOwner>(output);
        Assert.IsType<PluginFeature>(Assert.Single(restored.Attachments.All(restored.Owner)));
    }

    [Fact]
    public void SavingValidatesTheWholeBundleBeforeReplacingTheDestination()
    {
        var (owner, features) = Specimen();
        var codec = new FeatureBundleCodec(Registry);
        var directory = Directory.CreateTempSubdirectory("vmf-features-");
        try
        {
            string destination = Path.Combine(directory.FullName, "document");
            codec.Save(destination, owner, features);
            Verify(codec.Load<Catalog>(destination));
            string previous = File.ReadAllText(destination);
            Assert.Throws<IOException>(() => new FeatureBundleCodec(WorkshopModels.Registry).Save(destination, owner, features));
            Assert.Equal(previous, File.ReadAllText(destination));
            Assert.Single(directory.EnumerateFiles());
            string yaml = Path.Combine(directory.FullName, "document.YML");
            codec.Save(yaml, owner, features);
            Verify(codec.Load<Catalog>(yaml));
            string other = Path.Combine(directory.FullName, "document.feature");
            Assert.Throws<IOException>(() => codec.Save(other, owner, features));
            codec.Save(other, owner, features, VmfDocumentFormat.Xml);
            Verify(codec.Load<Catalog>(other, VmfDocumentFormat.Xml));
        }
        finally
        {
            directory.Delete(recursive: true);
        }
    }

    private static (IReadOnlyCatalog Owner, FeatureAttachments Features) Specimen()
    {
        var thermometer = new Thermometer { Sku = "T-1", Unit = "C" };
        var catalog = new Catalog { Featured = thermometer };
        catalog.Products.AddRange([thermometer, new Instrument { Sku = "I-1" }]);
        var archive = ValueModels.Read<ArchiveRoot>(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "fixtures", "archive-references.json")));
        archive.Head!.Name = HeadName;
        var badge = new ImmutableBadge("shared");
        var portfolio = new ExtendedPortfolio(current: badge, trail: [badge, null, badge], preferred: [badge], pool: [badge],
            levels: [NegativeZero, +0.0, double.NaN], limits: new Dictionary<string, long?> { [Key] = long.MaxValue, ["absent-value"] = null },
            markers: new KeyValuePair<double, char?>[] { new(NegativeZero, '\ud800'), new(+0.0, null) });
        var owner = catalog.AsReadOnly();
        var features = new FeatureAttachments(Registry);
        features.Attach(owner, archive.AsReadOnly());
        features.Attach(owner, new PortfolioSelection { Portfolio = portfolio });
        return (owner, features);
    }

    private static void Verify(FeatureBundle<Catalog> result)
    {
        var owner = result.Owner;
        var probe = Assert.IsType<Thermometer>(owner.Products[0]);
        Assert.Equal("C", probe.Unit);
        Assert.Same(probe, owner.Featured);
        var features = result.Attachments.All(owner);
        Assert.Empty(result.Attachments.All(owner.AsReadOnly()));
        Assert.Equal(2, features.Count);
        var archive = Assert.IsType<ArchiveRoot>(features[0]);
        var head = Assert.IsType<ArchiveNode>(archive.Head);
        Assert.Equal(HeadName, head.Name);
        var left = Assert.Single(head.Children);
        var right = Assert.IsType<ArchiveNode>(head.Inset);
        Assert.Same(head, left.Parent);
        Assert.Same(head, right.InsetOf);
        Assert.Collection(head.Trail, item => Assert.Same(right, item), Assert.Null, item => Assert.Same(left, item),
            item => Assert.Same(right, item), item => Assert.Same(head, item));
        var selection = Assert.IsType<PortfolioSelection>(features[1]);
        var portfolio = Assert.IsType<ExtendedPortfolio>(selection.Portfolio);
        var badge = Assert.IsType<ImmutableBadge>(portfolio.Current);
        Assert.Equal("shared", badge.Text);
        Assert.Collection(portfolio.Trail, item => Assert.Same(badge, item), Assert.Null, item => Assert.Same(badge, item));
        Assert.Same(badge, Assert.Single(portfolio.Preferred));
        Assert.Same(badge, Assert.Single(portfolio.Pool));
        Assert.Equal('\ud800', portfolio.Markers[NegativeZero]);
        Assert.True(portfolio.Markers.TryGetValue(+0.0, out var marker));
        Assert.Null(marker);
        Assert.Equal(long.MaxValue, portfolio.Limits[Key]);
        Assert.True(portfolio.Limits.TryGetValue("absent-value", out var limit));
        Assert.Null(limit);
        Assert.Equal(3, portfolio.Levels.Count);
        Assert.True(portfolio.Levels.Contains(NegativeZero) && portfolio.Levels.Contains(+0.0) && portfolio.Levels.Contains(double.NaN));
    }

    private sealed class AppOwner;
    private sealed class PluginFeature;
}
