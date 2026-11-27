package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.conformance.immutables.PortfolioSelection;
import eu.mihosoft.vmf.conformance.values.ArchiveRoot;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.conformance.values.DefaultEntry;
import eu.mihosoft.vmf.conformance.values.DefaultSettings;
import eu.mihosoft.vmf.conformance.values.ScalarSample;
import eu.mihosoft.vmf.example.workshops.Catalog;
import eu.mihosoft.vmf.example.workshops.ExternalInventory;
import eu.mihosoft.vmf.example.workshops.Instrument;
import eu.mihosoft.vmf.example.workshops.Thermometer;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

final class YamlConformance {
    private static final ObjectMapper PLAIN_JSON = new ObjectMapper().registerModule(module(false));
    private static final ObjectMapper REFERENCES_JSON = new ObjectMapper().registerModule(module(true));
    private static final ObjectMapper PLAIN_YAML = new YAMLMapper().registerModule(module(false));
    private static final ObjectMapper REFERENCES_YAML = new YAMLMapper().registerModule(module(true));
    private static final String DESCRIPTION = "🚀 \"\\ud800\" and null";

    private YamlConformance() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 3 && args[0].equals("write")) {
            var directory = Files.createDirectories(Path.of(args[1]));
            write(directory, Path.of(args[2]));
            verify(directory, "java");
            System.out.println("Java wrote and reread VMF YAML snapshots and an immutable reference journal.");
        } else if (args.length == 2 && args[0].equals("read")) {
            verify(Path.of(args[1]), "csharp");
            System.out.println("Java restored C# YAML snapshots and replayed the C# immutable reference YAML journal.");
        } else {
            throw new IllegalArgumentException("Usage: YamlConformance write <directory> <repoRoot> | read <directory>");
        }
    }

    private static void write(Path directory, Path repository) throws IOException {
        var examples = repository.resolve("examples/csharp-workshop/fixtures");
        var fixtures = repository.resolve("conformance/fixtures");
        var inventory = PLAIN_JSON.readValue(examples.resolve("inventory.json").toFile(), ExternalInventory.class);
        PLAIN_YAML.writeValue(file(directory, "java", "inventory").toFile(), inventory);

        var catalog = REFERENCES_JSON.readValue(examples.resolve("catalog.json").toFile(), Catalog.class);
        REFERENCES_YAML.writeValue(file(directory, "java", "catalog").toFile(), catalog);
        catalog.setFeatured(null);
        PLAIN_YAML.writeValue(file(directory, "java", "catalog-value").toFile(), catalog);

        var archive = REFERENCES_JSON.readValue(fixtures.resolve("archive-references.json").toFile(), ArchiveRoot.class);
        REFERENCES_YAML.writeValue(file(directory, "java", "archive").toFile(), archive);

        var collections = PLAIN_JSON.readValue(fixtures.resolve("scalar-collections.json").toFile(), CollectionProfile.class);
        collections.getMessages().add("");
        collections.getOptions().put("<<", null);
        collections.getMarkers().put(Double.MIN_VALUE, '\ud800');
        PLAIN_YAML.writeValue(file(directory, "java", "collections").toFile(), collections);

        var defaults = DefaultSettings.newBuilder().withName(null).withLimit(null)
                .withStages("one", "").withEntries(DefaultEntry.newInstance()).build();
        PLAIN_YAML.writeValue(file(directory, "java", "defaults").toFile(), defaults);

        var scalars = ScalarSample.newInstance();
        scalars.setDescription(DESCRIPTION);
        scalars.setOptionalLong(9_007_199_254_740_993L);
        scalars.setOptionalDouble(-0.0);
        scalars.setOptionalFloat(Float.NaN);
        scalars.setDoubleValue(Double.NEGATIVE_INFINITY);
        scalars.setSurrogateUnit((char) 0xd800);
        PLAIN_YAML.writeValue(file(directory, "java", "scalars").toFile(), scalars);

        var json = directory.resolve("json");
        CollectionCreationJournalConformance.main(new String[]{"write", json.toString()});
        var checkpoint = REFERENCES_JSON.readValue(json.resolve("java-checkpoint.json").toFile(), PortfolioSelection.class);
        var journal = VMFJournalCodec.read(Files.readString(json.resolve("java-journal.json")));
        REFERENCES_YAML.writeValue(file(directory, "java", "journal-checkpoint").toFile(), checkpoint);
        Files.writeString(file(directory, "java", "journal"), VMFJournalCodec.write(journal, VMFDocumentFormat.YAML));
    }

    private static void verify(Path directory, String author) throws IOException {
        var inventoryFile = file(directory, author, "inventory").toFile();
        var inventoryWire = PLAIN_YAML.readTree(inventoryFile);
        check(inventoryWire.has("stock_items") && inventoryWire.has("warehouse_id")
                && inventoryWire.findValue("@vmf-id") == null && inventoryWire.findValue("@vmf-type") == null,
                "Plain inventory uses external names without graph metadata");
        var inventory = PLAIN_YAML.readValue(inventoryFile, ExternalInventory.class);
        check("BER-1".equals(inventory.getWarehouseId()) && inventory.getItems().size() == 2, "Inventory shape");
        var first = inventory.getItems().get(0);
        var second = inventory.getItems().get(1);
        check("A-42".equals(first.getSku()) && first.getQuantity() == 0 && !first.isActive()
                && "B-10".equals(second.getSku()) && second.getQuantity() == 8 && second.isActive(),
                "Inventory order and typed zero and false");

        verifyCatalog(directory, author, true);
        verifyCatalog(directory, author, false);
        var archive = REFERENCES_YAML.readValue(file(directory, author, "archive").toFile(), ArchiveRoot.class);
        var head = archive.getHead();
        var left = head.getChildren().get(0);
        var right = head.getInset();
        check("main".equals(head.getName()) && "left".equals(left.getName()) && "right".equals(right.getName())
                && head.getChildren().size() == 1 && left.getParent() == head && right.getInsetOf() == head,
                "Archive restores ownership and both opposites");
        var trail = head.getTrail();
        check(trail.size() == 5 && trail.get(0) == right && trail.get(1) == null && trail.get(2) == left
                && trail.get(3) == right && trail.get(4) == head, "Archive restores repeated, null, and self references");
        check(head.getFeatured().size() == 2 && head.getFeatured().get(0) == left && head.getFeatured().get(1) == right
                && archive.getReview().size() == 3 && archive.getReview().get(0) == left && archive.getReview().get(1) == null
                && archive.getReview().get(2) == right, "Archive retains ordered aliases and the root-level null");

        var collections = PLAIN_YAML.readValue(file(directory, author, "collections").toFile(), CollectionProfile.class);
        check(collections.getMessages().equals(Arrays.asList("ready", null, "ready", ""))
                && collections.getOptions().containsKey("<<") && collections.getOptions().get("<<") == null
                && Boolean.TRUE.equals(collections.getOptions().get("@vmf-id"))
                && Boolean.FALSE.equals(collections.getOptions().get("@vmf-type")), "Null sequence slots and literal mapping keys");
        check(collections.getLevels().size() == 3 && collections.getLevels().containsAll(List.of(+0.0, -0.0, Double.NaN))
                && collections.getLimits().get("limit") == Long.MAX_VALUE && collections.getLimits().containsKey("optional")
                && collections.getLimits().get("optional") == null, "Managed sets and precise integers in string maps");
        var markers = collections.getMarkers();
        check(markers.size() == 4 && markers.get(+0.0) == '+' && markers.get(-0.0) == '-'
                && markers.containsKey(Double.NaN) && markers.get(Double.NaN) == null
                && markers.get(Double.MIN_VALUE) == (char) 0xd800, "Typed map retains signed zeros, NaN, and UTF-16 code units");

        var defaultsFile = file(directory, author, "defaults").toFile();
        var defaultsWire = PLAIN_YAML.readTree(defaultsFile);
        var defaults = PLAIN_YAML.readValue(defaultsFile, DefaultSettings.class);
        check(defaultsWire.path("display_name").isNull() && defaultsWire.path("limit").isNull()
                && defaults.getName() == null && defaults.getLimit() == null && "new".equals(defaults.getEntries().get(0).getLabel())
                && defaults.getStages().equals(List.of("one", "")) && defaults.getSequence() == 9_007_199_254_740_993L
                && Double.doubleToRawLongBits(defaults.getBaseline()) == Long.MIN_VALUE, "Explicit null, defaults, and empty strings");

        var scalars = PLAIN_YAML.readValue(file(directory, author, "scalars").toFile(), ScalarSample.class);
        check(DESCRIPTION.equals(scalars.getDescription()) && scalars.getOptionalLong() == 9_007_199_254_740_993L
                && scalars.getCharValue() == '\0' && scalars.getSurrogateUnit() == (char) 0xd800
                && Double.doubleToRawLongBits(scalars.getOptionalDouble()) == Long.MIN_VALUE
                && Float.isNaN(scalars.getOptionalFloat()) && scalars.getDoubleValue() == Double.NEGATIVE_INFINITY,
                "YAML preserves strings, 64-bit integers, code units, signed zero, and nonfinite numbers");

        var checkpoint = REFERENCES_YAML.readValue(file(directory, author, "journal-checkpoint").toFile(), PortfolioSelection.class);
        var journal = VMFJournalCodec.read(Files.readString(file(directory, author, "journal")), VMFDocumentFormat.YAML);
        var json = Files.createDirectories(directory.resolve("json"));
        check(journal.wireVersion() == 1 && checkpoint.getPortfolio() == null, "Version 1 journal and its original checkpoint");
        if (author.equals("java")) {
            check(Files.readString(json.resolve("java-journal.json")).equals(VMFJournalCodec.write(journal)),
                    "Java YAML reads back the exact immutable reference journal used in the replay fixture");
        } else {
            REFERENCES_JSON.writeValue(json.resolve("csharp-checkpoint.json").toFile(), checkpoint);
            Files.writeString(json.resolve("csharp-journal.json"), VMFJournalCodec.write(journal));
            CollectionCreationJournalConformance.main(new String[]{"read", json.toString()});
        }
    }

    private static void verifyCatalog(Path directory, String author, boolean references) throws IOException {
        var mapper = references ? REFERENCES_YAML : PLAIN_YAML;
        var catalogFile = file(directory, author, references ? "catalog" : "catalog-value").toFile();
        var wire = mapper.readTree(catalogFile);
        check(wire.has("items") && !wire.has("products")
                && wire.findValuesAsText("@vmf-type").equals(List.of("thermometer", "instrument"))
                && wire.findValuesAsText("@vmf-id").isEmpty() != references, "Catalog alias and graph metadata profiles");
        var catalog = mapper.readValue(catalogFile, Catalog.class);
        var products = catalog.getProducts();
        check(products.size() == 2 && products.get(0) instanceof Thermometer && products.get(1) instanceof Instrument
                && !(products.get(1) instanceof Thermometer) && "T-1".equals(products.get(0).getSku())
                && "C".equals(((Thermometer) products.get(0)).getUnit()) && "I-1".equals(products.get(1).getSku())
                && catalog.getFeatured() == (references ? products.get(0) : null), "Catalog's subtypes and optional shared alias");
    }

    private static VMFJacksonModule module(boolean references) {
        return VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withModelWireTypeAliases(Catalog.class).withReferences(references);
    }

    private static Path file(Path directory, String author, String kind) {
        return directory.resolve(author + "-" + kind + ".yaml");
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
