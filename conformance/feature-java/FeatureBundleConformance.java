package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.immutables.ExtendedPortfolio;
import eu.mihosoft.vmf.conformance.immutables.ImmutableBadge;
import eu.mihosoft.vmf.conformance.immutables.ImmutablePortfolio;
import eu.mihosoft.vmf.conformance.immutables.PortfolioSelection;
import eu.mihosoft.vmf.conformance.values.ArchiveRoot;
import eu.mihosoft.vmf.example.workshops.Catalog;
import eu.mihosoft.vmf.example.workshops.Thermometer;
import eu.mihosoft.vmf.jackson.VMFFeatureCodec;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.FeatureAttachments;
import eu.mihosoft.vmf.runtime.core.ModelRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

final class FeatureBundleConformance {
    private static final List<String> FORMATS = List.of("json", "yaml", "xml");
    private static final String HEAD_NAME = "main\r\u0001🚀";
    private static final String KEY = "limit\r\n\t";
    private static final ModelRegistry REGISTRY = new ModelRegistry(List.of(
            Catalog.type().reflect().allTypes(), ArchiveRoot.type().reflect().allTypes(),
            PortfolioSelection.type().reflect().allTypes()));

    private FeatureBundleConformance() { }

    public static void main(String[] args) throws IOException {
        if (args.length == 3 && args[0].equals("write")) {
            var directory = Files.createDirectories(Path.of(args[1]));
            write(directory, Path.of(args[2]));
            verify(directory, "java");
            exchangeImmutableOwner(directory, true);
            FeatureBundleDepthConformance.exchange(directory, true, REGISTRY);
            System.out.println("Java wrote and restored native feature bundles in JSON, YAML, and XML.");
        } else if (args.length == 2 && args[0].equals("read")) {
            verify(Path.of(args[1]), "csharp");
            exchangeImmutableOwner(Path.of(args[1]), false);
            FeatureBundleDepthConformance.exchange(Path.of(args[1]), false, REGISTRY);
            System.out.println("Java restored the C# JSON, YAML, and XML feature bundles and their entry-local aliases.");
        } else {
            throw new IllegalArgumentException("Usage: FeatureBundleConformance write <directory> <repoRoot> | read <directory>");
        }
    }

    private static void exchangeImmutableOwner(Path directory, boolean write) throws IOException {
        var codec = new VMFFeatureCodec(REGISTRY);
        for (String extension : FORMATS) {
            var file = directory.resolve((write ? "java" : "csharp") + "-immutable-bundle." + extension);
            if (write) {
                var badge = ImmutableBadge.newBuilder().withText("shared").build();
                var owner = ExtendedPortfolio.newBuilder().withStatus("archived").withCurrent(badge)
                        .withTrail(badge, null, badge).build();
                var feature = PortfolioSelection.newBuilder().withPortfolio(owner).build();
                var attachments = new FeatureAttachments(REGISTRY);
                check(owner.asReadOnly() == owner, "The immutable attachment owner is its own read-only view");
                attachments.attach(owner, feature);
                attachments.attach(owner, badge);
                codec.save(file, owner, attachments);
            }

            var document = codec.load(file, ImmutablePortfolio.class);
            check(document.owner() instanceof ExtendedPortfolio, "The concrete immutable owner satisfies its declared base");
            var restored = (ExtendedPortfolio) document.owner();
            var entries = document.attachments().all(restored.asReadOnly());
            check(entries.size() == 2 && entries.get(0) instanceof PortfolioSelection
                    && entries.get(1) instanceof ImmutableBadge, "The immutable owner retains its ordered attachments");
            var featurePortfolio = (ExtendedPortfolio) ((PortfolioSelection) entries.get(0)).getPortfolio();
            var featureBadge = (ImmutableBadge) entries.get(1);
            check(restored.getStatus().equals("archived") && restored.equals(featurePortfolio)
                    && restored != featurePortfolio && restored.getCurrent().equals(featureBadge)
                    && restored.getCurrent() != featureBadge && featurePortfolio.getCurrent() != featureBadge
                    && featurePortfolio.getCurrent() != restored.getCurrent(),
                    "Every bundle entry restores its own equal immutable values");
            for (var portfolio : List.of(restored, featurePortfolio)) {
                check(portfolio.getTrail().get(0) == portfolio.getCurrent() && portfolio.getTrail().get(1) == null
                        && portfolio.getTrail().get(2) == portfolio.getCurrent(),
                        "Repeated immutable references remain shared within their entry");
            }
        }
    }

    private static void write(Path directory, Path repository) throws IOException {
        var mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(true).withModelWireTypeAliases(Catalog.class));
        var owner = mapper.readValue(repository.resolve("examples/csharp-workshop/fixtures/catalog.json").toFile(), Catalog.class);
        var archive = mapper.readValue(repository.resolve("conformance/fixtures/archive-references.json").toFile(), ArchiveRoot.class);
        archive.getHead().setName(HEAD_NAME);
        var badge = ImmutableBadge.newBuilder().withText("shared").build();
        var markers = new LinkedHashMap<Double, Character>();
        markers.put(-0.0, (char) 0xd800);
        markers.put(+0.0, null);
        var limits = new LinkedHashMap<String, Long>();
        limits.put(KEY, Long.MAX_VALUE);
        limits.put("absent-value", null);
        var portfolio = ExtendedPortfolio.newBuilder().withCurrent(badge).withTrail(badge, null, badge)
                .withPreferred(badge).withPool(badge).withLevels(-0.0, +0.0, Double.NaN)
                .withMarkers(markers).withLimits(limits).build();
        var selection = PortfolioSelection.newBuilder().withPortfolio(portfolio).build();

        var view = owner.asReadOnly();
        var attachments = new FeatureAttachments(REGISTRY);
        var archiveView = archive.asReadOnly();
        attachments.attach(view, archiveView);
        attachments.attach(view, archiveView);
        attachments.attach(view, selection);
        check(attachments.all(owner).isEmpty() && attachments.all(view).get(0) == archiveView
                && attachments.all(view).get(1) == selection, "The exact live owner view and attachment order are used");
        check(!attachments.detach(view, archive), "Detaching the mutable archive does not detach its view");
        var codec = new VMFFeatureCodec(REGISTRY);
        for (String format : FORMATS) codec.save(file(directory, "java", format), view, attachments);
    }

    private static void verify(Path directory, String author) throws IOException {
        var codec = new VMFFeatureCodec(REGISTRY);
        for (String format : FORMATS) {
            var document = codec.load(file(directory, author, format), Catalog.class);
            var owner = document.owner();
            var products = owner.getProducts();
            check(products.size() == 2 && products.get(0) instanceof Thermometer
                    && "C".equals(((Thermometer) products.get(0)).getUnit()) && owner.getFeatured() == products.get(0),
                    "The owner has its concrete subtype and local featured alias");
            var features = document.attachments().all(owner);
            check(features.size() == 2 && features.get(0) instanceof ArchiveRoot && features.get(1) instanceof PortfolioSelection,
                    "Independent model types and feature insertion order");
            var archive = (ArchiveRoot) features.get(0);
            var head = archive.getHead();
            var left = head.getChildren().get(0);
            var right = head.getInset();
            var trail = head.getTrail();
            check(HEAD_NAME.equals(head.getName()) && head.getChildren().size() == 1 && left.getParent() == head
                    && right.getInsetOf() == head && trail.size() == 5 && trail.get(0) == right && trail.get(1) == null
                    && trail.get(2) == left && trail.get(3) == right && trail.get(4) == head,
                    "The archive resolves nullable, repeated, and self references within its entry");
            var selection = (PortfolioSelection) features.get(1);
            check(selection.getPortfolio() instanceof ExtendedPortfolio, "The immutable subtype is resolved by its registry");
            var portfolio = (ExtendedPortfolio) selection.getPortfolio();
            var badge = portfolio.getCurrent();
            check("shared".equals(badge.getText()) && portfolio.getTrail().size() == 3
                    && portfolio.getTrail().get(0) == badge && portfolio.getTrail().get(1) == null
                    && portfolio.getTrail().get(2) == badge && portfolio.getPreferred().get(0) == badge
                    && portfolio.getPool().iterator().next() == badge, "Immutable aliases remain inside their feature entry");
            var markers = portfolio.getMarkers();
            var limits = portfolio.getLimits();
            check(markers.size() == 2 && markers.get(-0.0) == (char) 0xd800 && markers.containsKey(+0.0)
                    && markers.get(+0.0) == null && limits.get(KEY) == Long.MAX_VALUE && limits.containsKey("absent-value")
                    && limits.get("absent-value") == null && portfolio.getLevels().containsAll(List.of(-0.0, +0.0, Double.NaN)),
                    "The bundle retains numeric width, distinct signed zeros, Unicode code units, and present null map values");
            check(document.attachments().all(owner.asReadOnly()).isEmpty(), "The loaded mutable owner is the exact attachment key");
        }
    }

    private static Path file(Path directory, String author, String extension) {
        return directory.resolve(author + "-bundle." + extension);
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
