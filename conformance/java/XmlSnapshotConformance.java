package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import eu.mihosoft.vmf.conformance.immutables.ExtendedPortfolio;
import eu.mihosoft.vmf.conformance.immutables.PortfolioSelection;
import eu.mihosoft.vmf.conformance.values.ArchiveRoot;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.conformance.values.DefaultEntry;
import eu.mihosoft.vmf.conformance.values.DefaultSettings;
import eu.mihosoft.vmf.example.workshops.Catalog;
import eu.mihosoft.vmf.example.workshops.ExternalInventory;
import eu.mihosoft.vmf.example.workshops.Thermometer;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class XmlSnapshotConformance {
    private static final ObjectMapper PLAIN_JSON = new ObjectMapper().registerModule(module(false));
    private static final ObjectMapper REFERENCES_JSON = new ObjectMapper().registerModule(module(true));
    private static final ObjectMapper PLAIN_XML = new XmlMapper().registerModule(module(false));
    private static final ObjectMapper REFERENCES_XML = new XmlMapper().registerModule(module(true));
    private static final String ENCODED_KEY = "entrée🌱\rline";
    private static final String COLLECTION_ROOT = "eu.mihosoft.vmf.conformance.values.CollectionProfile";

    private XmlSnapshotConformance() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 3 && args[0].equals("write")) {
            var directory = Files.createDirectories(Path.of(args[1]));
            write(directory, Path.of(args[2]));
            verify(directory, "java");
            verifyAdditiveFields(Path.of(args[2]));
            verifyFloatingLexemes();
            verifyIntegerLexemes();
            verifyEmptyStringSlots();
            XmlNilCollectionsConformance.verify(PLAIN_XML, REFERENCES_XML);
            System.out.println("Java wrote and restored plain, typed by-value, and reference-aware XML snapshots.");
        } else if (args.length == 2 && args[0].equals("read")) {
            verify(Path.of(args[1]), "csharp");
            System.out.println("Java restored the C# inventory, defaults, catalog, archive, collections, and typed by-value XML.");
        } else {
            throw new IllegalArgumentException("Usage: XmlSnapshotConformance write <directory> <repoRoot> | read <directory>");
        }
    }

    private static void write(Path directory, Path repository) throws IOException {
        var examples = repository.resolve("examples/csharp-workshop/fixtures");
        var inventory = PLAIN_JSON.readValue(examples.resolve("inventory.json").toFile(), ExternalInventory.class);
        PLAIN_XML.writeValue(file(directory, "java", "inventory").toFile(), inventory);

        var settings = DefaultSettings.newBuilder().withName(null).withLimit(null)
                .withStages("one", "").withEntries(DefaultEntry.newInstance()).build();
        PLAIN_XML.writeValue(file(directory, "java", "defaults").toFile(), settings);

        var catalog = REFERENCES_JSON.readValue(examples.resolve("catalog.json").toFile(), Catalog.class);
        REFERENCES_XML.writeValue(file(directory, "java", "catalog").toFile(), catalog);

        var archive = REFERENCES_JSON.readValue(repository.resolve("conformance/fixtures/archive-references.json")
                .toFile(), ArchiveRoot.class);
        REFERENCES_XML.writeValue(file(directory, "java", "archive").toFile(), archive);

        var collections = PLAIN_JSON.readValue(repository.resolve("conformance/fixtures/scalar-collections.json")
                .toFile(), CollectionProfile.class);
        collections.getMessages().add("");
        collections.getLevels().add(1e23);
        collections.getOptions().put("", true);
        collections.getOptions().put(ENCODED_KEY, null);
        collections.getMarkers().put(1e23, '\ud800');
        collections.getMarkers().put(Double.MIN_VALUE, '中');
        collections.getMarkers().put(Double.POSITIVE_INFINITY, '∞');
        collections.getMarkers().put(Double.NEGATIVE_INFINITY, null);
        PLAIN_XML.writeValue(file(directory, "java", "collections").toFile(), collections);

        var values = PortfolioSelection.newBuilder().withPortfolio(ImmutableConformance.specimen()).build();
        PLAIN_XML.writeValue(file(directory, "java", "values").toFile(), values);
    }

    private static void verify(Path directory, String author) throws IOException {
        var inventoryFile = file(directory, author, "inventory").toFile();
        var inventoryWire = PLAIN_XML.readTree(inventoryFile);
        check(inventoryWire.has("stock_items") && inventoryWire.has("warehouse_id")
                && inventoryWire.findValue("vmf-id") == null && inventoryWire.findValue("vmf-type") == null,
                "Plain inventory uses its external wire names and no VMF graph metadata");
        var inventory = PLAIN_XML.readValue(inventoryFile, ExternalInventory.class);
        check("BER-1".equals(inventory.getWarehouseId()) && inventory.getItems().size() == 2, "Inventory identity and shape");
        var first = inventory.getItems().get(0);
        var second = inventory.getItems().get(1);
        check("A-42".equals(first.getSku()) && first.getQuantity() == 0 && !first.isActive()
                && "B-10".equals(second.getSku()) && second.getQuantity() == 8 && second.isActive(),
                "Inventory order and typed zero and false payloads");

        var defaultsFile = file(directory, author, "defaults").toFile();
        var defaultsWire = PLAIN_XML.readTree(defaultsFile);
        check(defaultsWire.path("display_name").isNull() && defaultsWire.path("limit").isNull()
                && !defaultsWire.has("note"), "XML explicitly clears non-null defaults using nil");
        var defaults = PLAIN_XML.readValue(defaultsFile, DefaultSettings.class);
        check(defaults.getName() == null && defaults.getLimit() == null
                && defaults.getStages().equals(List.of("one", "")) && defaults.getEntries().size() == 1
                && "new".equals(defaults.getEntries().get(0).getLabel()), "Cleared defaults, empty scalar item, and entry default");
        verifyTypedDefaults(defaults);
        var missing = PLAIN_XML.readValue("<DefaultSettings/>", DefaultSettings.class);
        check("idle\nready".equals(missing.getName()) && Integer.valueOf(7).equals(missing.getLimit()),
                "Absent XML fields preserve inherited defaults");
        verifyTypedDefaults(missing);

        var catalogFile = file(directory, author, "catalog").toFile();
        var catalogWire = REFERENCES_XML.readTree(catalogFile);
        check(catalogWire.has("items") && !catalogWire.has("products")
                && catalogWire.findValuesAsText("vmf-type").equals(List.of("thermometer", "instrument"))
                && !catalogWire.findValuesAsText("vmf-id").isEmpty(), "Catalog XML uses its renamed field and subtype aliases");
        var catalog = REFERENCES_XML.readValue(catalogFile, Catalog.class);
        var products = catalog.getProducts();
        check(products.size() == 2 && products.get(0) instanceof Thermometer && !(products.get(1) instanceof Thermometer)
                && "T-1".equals(products.get(0).getSku()) && "C".equals(((Thermometer) products.get(0)).getUnit())
                && "I-1".equals(products.get(1).getSku()) && catalog.getFeatured() == products.get(0),
                "Catalog subtypes, order, and shared featured instance");

        var archive = REFERENCES_XML.readValue(file(directory, author, "archive").toFile(), ArchiveRoot.class);
        var head = archive.getHead();
        check("main".equals(head.getName()) && head.getChildren().size() == 1, "Archive root shape");
        var left = head.getChildren().get(0);
        var right = head.getInset();
        check("left".equals(left.getName()) && left.getParent() == head && left.getInsetOf() == null
                && "right".equals(right.getName()) && right.getParent() == null && right.getInsetOf() == head,
                "Archive restores both owning properties and their opposites");
        var trail = head.getTrail();
        check(trail.size() == 5 && trail.get(0) == right && trail.get(1) == null && trail.get(2) == left
                && trail.get(3) == right && trail.get(4) == head, "Archive reference slots include repeats, null, and self");
        check(head.getFeatured().size() == 2 && head.getFeatured().get(0) == left && head.getFeatured().get(1) == right
                && archive.getReview().size() == 3 && archive.getReview().get(0) == left && archive.getReview().get(1) == null
                && archive.getReview().get(2) == right, "Ordered aliases and root-level null slot");

        verifyCollections(directory, author);
        verifyValues(directory, author);
    }

    private static void verifyAdditiveFields(Path repository) throws IOException {
        var document = Files.readString(repository.resolve("conformance/fixtures/xml-additive-fields.xml"));
        String name = DefaultSettings.class.getName();
        String root = "<" + name + ">";
        String typed = document.replace(root, root + "<vmf-type>" + name + "</vmf-type>");
        String referenced = typed.replace(root, root + "<vmf-id>1</vmf-id>");
        verifyAdditiveFields(PLAIN_XML.readValue(document, DefaultSettings.class));
        verifyAdditiveFields(PLAIN_XML.readValue(typed, DefaultSettings.class));
        verifyAdditiveFields(REFERENCES_XML.readValue(referenced, DefaultSettings.class));
    }

    private static void verifyAdditiveFields(DefaultSettings model) {
        check("known".equals(model.getName()) && Integer.valueOf(7).equals(model.getLimit())
                && model.getEntries().size() == 1 && "nested".equals(model.getEntries().get(0).getLabel()),
                "Repeated additive XML fields preserve known fields and inherited defaults");
    }

    private static void verifyValues(Path directory, String author) throws IOException {
        var valuesFile = file(directory, author, "values").toFile();
        var wire = PLAIN_XML.readTree(valuesFile);
        check(wire.path("portfolio").path("vmf-type").asText().equals(ExtendedPortfolio.class.getName())
                && wire.findValue("vmf-id") == null, "By-value XML retains subtype metadata and omits object IDs");
        var selection = PLAIN_XML.readValue(valuesFile, PortfolioSelection.class);
        check(selection.getPortfolio() instanceof ExtendedPortfolio, "By-value XML restores the immutable subtype");
        ImmutableConformance.verifyByValue((ExtendedPortfolio) selection.getPortfolio(), ImmutableConformance.specimen());
    }

    private static void verifyCollections(Path directory, String author) throws IOException {
        var collectionsFile = file(directory, author, "collections").toFile();
        var wire = PLAIN_XML.readTree(collectionsFile);
        check(wire.path("options").has("entry") && wire.path("markers").has("entry")
                && wire.path("levels").has("member") && wire.findValue("key64") != null
                && wire.findValue("value64") != null && "true".equals(wire.findValue("null").asText()),
                "XML uses fixed managed scalar wrappers, encoded text, and explicit null markers");
        var model = PLAIN_XML.readValue(collectionsFile, CollectionProfile.class);
        var messages = model.getMessages();
        check(messages.size() == 4 && "ready".equals(messages.get(0)) && messages.get(1) == null
                && "ready".equals(messages.get(2)) && "".equals(messages.get(3)),
                "Nullable scalar sequence distinguishes an empty string from a null slot");
        var levels = model.getLevels();
        check(levels.size() == 4 && levels.contains(0.0) && levels.contains(-0.0)
                && levels.contains(Double.NaN) && levels.contains(1e23), "Unordered floating set retains value semantics");
        var options = model.getOptions();
        check(Boolean.TRUE.equals(options.get("@vmf-id")) && Boolean.FALSE.equals(options.get("@vmf-type"))
                && Boolean.TRUE.equals(options.get("")) && options.containsKey(ENCODED_KEY) && options.get(ENCODED_KEY) == null,
                "String keys, including metadata-like and base64 text, retain values and explicit null");
        var limits = model.getLimits();
        check(Long.valueOf(Long.MAX_VALUE).equals(limits.get("limit"))
                && limits.containsKey("optional") && limits.get("optional") == null, "Long and present-null map values");
        var markers = model.getMarkers();
        check(markers.size() == 7 && Character.valueOf('+').equals(markers.get(0.0)) && Character.valueOf('-').equals(markers.get(-0.0))
                && Character.valueOf('\ud800').equals(markers.get(1e23)) && Character.valueOf('中').equals(markers.get(Double.MIN_VALUE))
                && Character.valueOf('∞').equals(markers.get(Double.POSITIVE_INFINITY)) && markers.containsKey(Double.NEGATIVE_INFINITY)
                && markers.get(Double.NEGATIVE_INFINITY) == null && markers.containsKey(Double.NaN) && markers.get(Double.NaN) == null,
                "Typed keys preserve signed zeros, extremes, infinities, NaN, and UTF-16 character values");
    }

    private static void verifyFloatingLexemes() throws IOException {
        var model = readCollections("<levels><member><value>1e23</value></member><member><value>5e-324</value></member></levels>"
                + "<markers><entry><key>-0</key><null>true</null></entry><entry><key>0</key><null>true</null></entry></markers>");
        check(model.getLevels().contains(1e23) && model.getLevels().contains(Double.MIN_VALUE)
                && model.getMarkers().size() == 2 && model.getMarkers().containsKey(-0.0), "Equivalent finite float text is portable");
        for (String invalid : List.of("0x1.0p0", "1e9999", "1e-9999", "+Infinity", " 1.0", "1..0")) {
            rejectsCollections("<levels><member><value>" + invalid + "</value></member></levels>");
            rejectsCollections("<markers><entry><key>" + invalid + "</key><null>true</null></entry></markers>");
        }
        rejectsCollections("<levels><member><value>1e23</value></member><member><value>9.999999999999999E22</value></member></levels>");
        rejectsCollections("<markers><entry><key>1e23</key><null>true</null></entry>"
                + "<entry><key>9.999999999999999E22</key><null>true</null></entry></markers>");
    }

    private static void verifyIntegerLexemes() throws IOException {
        String root = DefaultSettings.class.getName();
        for (var reader : List.of(PLAIN_XML, REFERENCES_XML)) {
            for (String text : List.of("+0007", "-0007")) {
                String document = "<" + root + "><limit>" + text
                        + "</limit><sequence>-09223372036854775808</sequence></" + root + ">";
                var model = reader.readValue(document, DefaultSettings.class);
                check(Integer.valueOf(text).equals(model.getLimit()) && model.getSequence() == Long.MIN_VALUE,
                        "Signed padded XML integer fields retain their exact values");
            }
        }
    }

    private static void verifyEmptyStringSlots() throws IOException {
        String fields = "<messages><item/><item></item>"
                + "<item xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:nil=\"true\"/></messages>";
        String document = "<" + COLLECTION_ROOT + ">" + fields + "</" + COLLECTION_ROOT + ">";
        for (var reader : List.of(PLAIN_XML, REFERENCES_XML)) {
            var restored = reader.readValue(document, CollectionProfile.class);
            check(restored.getMessages().equals(java.util.Arrays.asList("", "", null)),
                    "A string element can be empty without representing null");
        }
    }

    private static CollectionProfile readCollections(String fields) throws IOException {
        return PLAIN_XML.readValue("<" + COLLECTION_ROOT + ">" + fields + "</" + COLLECTION_ROOT + ">", CollectionProfile.class);
    }

    private static void rejectsCollections(String fields) throws IOException {
        try {
            readCollections(fields);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Accepted invalid collections XML " + fields);
    }

    private static void verifyTypedDefaults(DefaultSettings settings) {
        check(Double.doubleToRawLongBits(settings.getBaseline()) == Long.MIN_VALUE && settings.getRatio() == 0.3125f
                && settings.getSequence() == 9_007_199_254_740_993L && settings.getMode() == '中'
                && Boolean.TRUE.equals(settings.getFlag()) && settings.getUnsetValue() == null && settings.getNote() == null,
                "Typed floating zero, long, UTF-16 char, flag, and absent optional values");
    }

    private static VMFJacksonModule module(boolean references) {
        return VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withModelWireTypeAliases(Catalog.class).withReferences(references);
    }

    private static Path file(Path directory, String author, String kind) {
        return directory.resolve(author + "-" + kind + ".xml");
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
