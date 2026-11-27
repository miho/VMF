package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.conformance.external.BoundedExternalIds;
import eu.mihosoft.vmf.conformance.external.GenericExternalIds;
import eu.mihosoft.vmf.conformance.immutables.ExtendedPortfolio;
import eu.mihosoft.vmf.conformance.immutables.ImmutableBadge;
import eu.mihosoft.vmf.conformance.immutables.PortfolioSelection;
import eu.mihosoft.vmf.conformance.values.ArchiveRoot;
import eu.mihosoft.vmf.conformance.values.CheckedOptionalDefaults;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.conformance.values.DefaultEntry;
import eu.mihosoft.vmf.conformance.values.DefaultSettings;
import eu.mihosoft.vmf.conformance.values.KeyedInventory;
import eu.mihosoft.vmf.conformance.values.ScalarBatch;
import eu.mihosoft.vmf.conformance.values.ScalarEntry;
import eu.mihosoft.vmf.conformance.values.ScalarSample;
import eu.mihosoft.vmf.example.workshops.Catalog;
import eu.mihosoft.vmf.example.workshops.ExternalInventory;
import eu.mihosoft.vmf.example.workshops.Product;
import eu.mihosoft.vmf.example.workshops.Shelf;
import eu.mihosoft.vmf.example.workshops.Thermometer;
import eu.mihosoft.vmf.jackson.test.readvalidation.Instrument;
import eu.mihosoft.vmf.jackson.test.readvalidation.InstrumentGroup;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;
import eu.mihosoft.vmf.jackson.VMFValidationException;
import eu.mihosoft.vmf.runtime.core.ModelValidator;
import eu.mihosoft.vmf.runtime.core.ValidationIssue;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class SnapshotSchemaConformance {
    private static final VMFJacksonModule.RUNTIME_TYPE MODE = VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL;

    private SnapshotSchemaConformance() { }

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("read")) {
            verifyPolymorphicRoots(Path.of(args[1]), false);
            verifyCatalogTypeNames(Path.of(args[1]), false);
            verifyInterfaceOnlyRoots(Path.of(args[1]), false);
            return;
        }
        if (args.length != 3 || !args[0].equals("write")) {
            throw new IllegalArgumentException("Usage: SnapshotSchemaConformance write <directory> <repoRoot> | read <directory>");
        }
        var directory = Files.createDirectories(Path.of(args[1]));
        var repository = Path.of(args[2]);
        var examples = repository.resolve("examples/csharp-workshop/fixtures");
        var fixtures = repository.resolve("conformance/fixtures");
        var values = mapper(false);
        var references = mapper(true);

        var inventory = values.readValue(examples.resolve("inventory.json").toFile(), ExternalInventory.class);
        write(directory, "inventory", ExternalInventory.class, inventory, false);

        var catalog = references.readValue(examples.resolve("catalog.json").toFile(), Catalog.class);
        write(directory, "catalog-references", Catalog.class, catalog, true);
        catalog.setFeatured(null);
        write(directory, "catalog-values", Catalog.class, catalog, false);
        verifyPolymorphicRoots(directory, true);
        verifyCatalogTypeNames(directory, true);
        verifyInterfaceOnlyRoots(directory, true);

        var keyed = references.readValue(fixtures.resolve("keyed-inventory.json").toFile(), KeyedInventory.class);
        write(directory, "keyed", KeyedInventory.class, keyed, true);
        var collections = values.readValue(fixtures.resolve("scalar-collections.json").toFile(), CollectionProfile.class);
        write(directory, "collections", CollectionProfile.class, collections, false);
        var archive = references.readValue(fixtures.resolve("archive-references.json").toFile(), ArchiveRoot.class);
        write(directory, "archive", ArchiveRoot.class, archive, true);

        var badge = ImmutableBadge.newBuilder().withText("shared").build();
        var limits = new LinkedHashMap<String, Long>();
        limits.put("exact", 9_007_199_254_740_993L);
        limits.put("unknown", null);
        var portfolio = ExtendedPortfolio.newBuilder().withCurrent(badge).withTrail(badge, null, badge)
                .withPreferred(badge).withPool(badge).withAliases("first", "second")
                .withLimits(limits).build();
        var selection = PortfolioSelection.newBuilder().withPortfolio(portfolio).build();
        write(directory, "immutable", PortfolioSelection.class, selection, true);

        var defaults = DefaultSettings.newBuilder().withName(null).withLimit(null)
                .withStages("one", "").withEntries(DefaultEntry.newInstance()).build();
        write(directory, "defaults", DefaultSettings.class, defaults, false);

        var cleared = CheckedOptionalDefaults.newInstance();
        cleared.setLabel(null);
        cleared.setLimit(null);
        cleared.setEnabled(null);
        for (boolean includeReferences : List.of(false, true)) {
            var validator = ModelValidator.builder().build();
            var checkedMapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE)
                    .withReferences(includeReferences ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT).withModelValidator(validator));
            var loaded = checkedMapper.readValue(checkedMapper.writeValueAsString(cleared), CheckedOptionalDefaults.class);
            if (loaded.getLabel() != null || loaded.getLimit() != null || loaded.getEnabled() != null
                    || !validator.validate(loaded).isValid()) {
                throw new IllegalStateException("Snapshot reinstated cleared optional defaults");
            }
            loaded.setLabel("");
            loaded.setLimit(11);
            var issues = validator.validate(loaded).getIssues();
            if (issues.size() != 2 || !issues.get(0).getPath().equals("/label")
                    || !issues.get(1).getPath().equals("/limit")) {
                throw new IllegalStateException("Non-null values must still satisfy declared constraints");
            }
            String name = includeReferences ? "checked-defaults-references" : "checked-defaults-values";
            write(directory, name, CheckedOptionalDefaults.class, cleared, includeReferences);
        }
        var scalars = ScalarSample.newBuilder().withByteValue((byte) -128).withShortValue((short) 32767)
                .withIntValue(Integer.MIN_VALUE).withLongValue(Long.MAX_VALUE).withCharValue('中')
                .withDoubleValue(Double.NEGATIVE_INFINITY).withOptionalFloat(Float.NaN).build();
        write(directory, "scalars", ScalarSample.class, scalars, false);

        var valid = values.readTree(fixtures.resolve("scalar-validation.json").toFile()).get(0).get("model");
        var constraints = values.treeToValue(valid, ScalarBatch.class);
        write(directory, "constraints", ScalarBatch.class, constraints, false);

        var id = UUID.fromString("87fcac65-2efc-4ce8-9e64-303ef53bb7a1");
        var generic = GenericExternalIds.newBuilder().withLatest(id).withSamples(id, null, id).build();
        var bounded = BoundedExternalIds.newBuilder().withIds((UUID) null, id).build();
        for (boolean includeReferences : List.of(false, true)) {
            String profile = includeReferences ? "references" : "values";
            write(directory, "uuid-generic-" + profile, GenericExternalIds.class, generic, includeReferences);
            write(directory, "uuid-bounded-" + profile, BoundedExternalIds.class, bounded, includeReferences);
        }

        verifyStockAlarmPolicy(directory, fixtures.resolve("stock-alarm-policy.json"));
        System.out.println("Java wrote Draft-07 schemas for generated VMF snapshots and the stock read-validation model.");
    }

    private static void verifyPolymorphicRoots(Path directory, boolean produce) throws IOException {
        for (boolean references : List.of(false, true)) {
            String name = "instrument-root-" + (references ? "references" : "values");
            var mapper = mapper(references);
            String prefix = produce ? "java-" : "csharp-";
            var baseFile = directory.resolve(prefix + name + "-base.json");
            var subtypeFile = directory.resolve(prefix + name + "-snapshot.json");
            var rootType = eu.mihosoft.vmf.example.workshops.Instrument.class;
            if (produce) {
                mapper.writeValue(baseFile.toFile(), eu.mihosoft.vmf.example.workshops.Instrument.newBuilder()
                        .withSku("B-1").build());
                var thermometer = Thermometer.newBuilder().withSku("T-1").withUnit("C").build();
                write(directory, name, rootType, thermometer, references);
            }
            var base = mapper.readValue(baseFile.toFile(), rootType);
            var subtype = mapper.readValue(subtypeFile.toFile(), rootType);
            var untagged = mapper.readValue("{\"sku\":\"legacy\"}", rootType);
            if (base instanceof Thermometer || !"B-1".equals(base.getSku())
                    || !(subtype instanceof Thermometer) || !"C".equals(((Thermometer) subtype).getUnit())
                    || untagged instanceof Thermometer || !"legacy".equals(untagged.getSku())) {
                throw new IllegalStateException("The schema root did not select the declared or tagged subtype");
            }
            for (String invalid : List.of("{\"@vmf-type\":\"not-registered\"}",
                    "{\"@vmf-type\":\"thermometer\",\"unit\":{}}")) {
                try {
                    mapper.readValue(invalid, rootType);
                    throw new IllegalStateException("Invalid polymorphic root was accepted: " + invalid);
                } catch (JsonMappingException expected) {
                    // Bad discriminators and malformed subtype fields are structural failures.
                }
            }
        }
        System.out.println("Java verified polymorphic roots from " + (produce ? "both Java snapshot profiles." : "both C# snapshot profiles."));
    }

    private static void verifyCatalogTypeNames(Path directory, boolean produce) throws IOException {
        String canonical = Thermometer.class.getName();
        String previous = "thermometer/old~name";
        String preferred = "sensor";
        for (boolean references : List.of(false, true)) {
            String name = "catalog-renamed-" + (references ? "references" : "values");
            var configuredModule = VMFJacksonModule.newInstance(MODE).withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT)
                    .withModelWireTypeAliases(Catalog.class).withTypeAlias(previous, canonical).withTypeAlias(preferred, canonical);
            var configured = new ObjectMapper().registerModule(configuredModule);
            if (produce) {
                var generator = VMFJsonSchemaGenerator.newInstance(MODE).withReferences(references)
                        .withModelWireTypeAliases(Catalog.class).withTypeAlias(previous, canonical).withTypeAlias(preferred, canonical);
                Files.writeString(directory.resolve("java-" + name + "-schema.json"), generator.generateSchemaAsString(Catalog.class));
                var sensor = Thermometer.newBuilder().withSku("T-1").withUnit("C").build();
                var catalog = Catalog.newInstance();
                catalog.getProducts().add(sensor);
                if (references) catalog.setFeatured(sensor);
                var unaliased = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE).withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
                var legacy = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE)
                        .withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT).withModelWireTypeAliases(Catalog.class).withTypeAlias(previous, canonical));
                writeCatalogTypeName(directory, name, "snapshot", preferred, configured, catalog);
                writeCatalogTypeName(directory, name, "canonical", canonical, unaliased, catalog);
                writeCatalogTypeName(directory, name, "previous", previous, legacy, catalog);
            } else {
                var file = directory.resolve("csharp-" + name + "-snapshot.json");
                assertCatalogTypeName(configured, configured.readTree(file.toFile()), preferred, references);
            }
            for (var entry : Map.of("snapshot", preferred, "canonical", canonical, "previous", previous).entrySet()) {
                var document = configured.readTree(directory.resolve("java-" + name + "-" + entry.getKey() + ".json").toFile());
                assertCatalogTypeName(configured, document, entry.getValue(), references);
            }
            var file = directory.resolve((produce ? "java-" : "csharp-") + name + "-untagged.json");
            if (produce) {
                String untagged = references
                        ? "{\"items\":[{\"@vmf-id\":\"legacy\",\"sku\":\"B-1\"}],\"featured\":\"legacy\"}"
                        : "{\"items\":[{\"sku\":\"B-1\"}]}";
                Files.writeString(file, untagged);
            }
            var fallback = configured.readValue(file.toFile(), Catalog.class);
            var item = fallback.getProducts().get(0);
            if (item instanceof Thermometer || !"B-1".equals(item.getSku())
                    || (references ? item != fallback.getFeatured() : fallback.getFeatured() != null)) {
                throw new IllegalStateException("Untagged items must use the declared concrete type.");
            }
        }
        System.out.println("Java verified configured, previous, and qualified nested polymorphic type names.");
    }

    private static void writeCatalogTypeName(Path directory, String name, String artifact, String expectedAlias,
                                             ObjectMapper writer, Catalog catalog) throws IOException {
        var file = directory.resolve("java-" + name + "-" + artifact + ".json");
        writer.writeValue(file.toFile(), catalog);
        var item = writer.readTree(file.toFile()).path("items").path(0);
        if (!expectedAlias.equals(item.path("@vmf-type").asText())) {
            throw new IllegalStateException("Unexpected nested name in " + file);
        }
    }

    private static void assertCatalogTypeName(ObjectMapper mapper, JsonNode document, String alias,
                                              boolean references) throws IOException {
        var catalog = mapper.treeToValue(document, Catalog.class);
        var item = catalog.getProducts().get(0);
        if (!alias.equals(document.path("items").path(0).path("@vmf-type").asText())
                || !(item instanceof Thermometer) || !"T-1".equals(((Thermometer) item).getSku())
                || !"C".equals(((Thermometer) item).getUnit())
                || (references ? item != catalog.getFeatured() : catalog.getFeatured() != null)) {
            throw new IllegalStateException("Could not read nested polymorphic type name " + alias);
        }
    }

    private static void verifyInterfaceOnlyRoots(Path directory, boolean produce) throws IOException {
        for (boolean references : List.of(false, true)) {
            String suffix = references ? "references" : "values";
            var mappers = Map.of("json", new ObjectMapper(), "yaml", new YAMLMapper(), "xml", new XmlMapper());
            var sensor = Thermometer.newBuilder().withSku("P-1").withUnit("C").build();
            var catalog = Catalog.newInstance();
            catalog.getProducts().add(sensor);
            if (references) catalog.setFeatured(sensor);
            for (var entry : mappers.entrySet()) {
                String format = entry.getKey();
                var mapper = entry.getValue().registerModule(VMFJacksonModule.newInstance(MODE)
                        .withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT).withModelWireTypeAliases(Catalog.class).withRootTypeDiscriminator(true));
                String author = produce ? "java" : "csharp";
                var product = directory.resolve(author + "-role-product-" + suffix + "-snapshot." + format);
                var shelf = directory.resolve(author + "-role-shelf-" + suffix + "-snapshot." + format);
                if (produce) {
                    mapper.writeValue(product.toFile(), sensor);
                    mapper.writeValue(shelf.toFile(), catalog);
                }
                var readProduct = mapper.readValue(product.toFile(), Product.class);
                var readShelf = mapper.readValue(shelf.toFile(), Shelf.class);
                var item = readShelf.getProducts().get(0);
                if (!(readProduct instanceof Thermometer) || !"P-1".equals(readProduct.getSku())
                        || !"C".equals(((Thermometer) readProduct).getUnit()) || !(readShelf instanceof Catalog)
                        || !(item instanceof Thermometer) || !"P-1".equals(((Thermometer) item).getSku())
                        || !"C".equals(((Thermometer) item).getUnit())
                        || (references ? item != readShelf.getFeatured() : readShelf.getFeatured() != null)) {
                    throw new IllegalStateException("Cannot restore an interface-only root from " + format);
                }
            }
            if (produce) {
                var generator = VMFJsonSchemaGenerator.newInstance(MODE).withReferences(references)
                        .withModelWireTypeAliases(Product.class);
                Files.writeString(directory.resolve("java-role-product-" + suffix + "-schema.json"),
                        generator.generateSchemaAsString(Product.class));
                Files.writeString(directory.resolve("java-role-shelf-" + suffix + "-schema.json"),
                        generator.generateSchemaAsString(Shelf.class));
                for (String invalid : List.of("{\"sku\":\"P-1\"}", "{\"@vmf-type\":\"missing\"}",
                        "{\"@vmf-type\":\"eu.mihosoft.vmf.example.workshops.Catalog\"}",
                        "{\"@vmf-type\":\"eu.mihosoft.vmf.example.workshops.Product\"}")) {
                    try {
                        mapper(references).readValue(invalid, Product.class);
                        throw new IllegalStateException("Accepted an invalid VMF role discriminator");
                    } catch (JsonMappingException expected) {
                        // Only registered concrete implementations can be constructed.
                    }
                }
            }
        }
        System.out.println("Java verified interface-only root snapshots from " + (produce ? "Java." : "C#."));
    }

    private static void verifyStockAlarmPolicy(Path directory, Path fixture) throws IOException {
        var samples = new ObjectMapper().readTree(fixture.toFile());
        String code = "instrument.alarm-limit.required";
        var validator = ModelValidator.builder().withRule(Instrument.class, (instrument, context) -> {
            if (instrument.isAlarmEnabled() && instrument.getAlarmLimit() == null) {
                context.report(ValidationIssue.Severity.ERROR, code, "alarmLimit", "An enabled alarm must have a limit");
            }
        }).build();
        var condition = Map.of(
                "if", Map.of("required", List.of("alarm-enabled"),
                        "properties", Map.of("alarm-enabled", Map.of("const", true))),
                "then", Map.of("required", List.of("alarm-limit")));

        for (boolean references : List.of(false, true)) {
            var standard = mapper(references).treeToValue(samples.get("missing"), InstrumentGroup.class);
            var configured = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE)
                    .withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT).withModelValidator(validator));
            var diagnostics = validator.validate(standard).getIssues();
            if (diagnostics.size() != 1 || !standard.vmf().content().validate().isValid()) {
                throw new IllegalStateException("Stock alarm validation did not separate default and configured policies");
            }
            var issue = diagnostics.get(0);
            if (!code.equals(issue.getCode())
                    || !"/instruments/0/alarmLimit".equals(issue.getPath())
                    || !"alarmLimit".equals(issue.getProperty().orElseThrow().getName())) {
                throw new IllegalStateException("Stock alarm validator reported an unexpected result");
            }
            try {
                configured.treeToValue(samples.get("missing"), InstrumentGroup.class);
                throw new IllegalStateException("Configured Java read accepted a missing alarm limit");
            } catch (VMFValidationException exception) {
                var issues = exception.getReport().getIssues();
                if (issues.size() != 1 || !issue.toString().equals(issues.get(0).toString())) throw exception;
            }
            for (String sample : List.of("configured", "inactive")) {
                var accepted = configured.treeToValue(samples.get(sample), InstrumentGroup.class);
                if (!validator.validate(accepted).isValid()) throw new IllegalStateException("Valid alarm was rejected");
            }

            String profile = references ? "references" : "values";
            var generator = VMFJsonSchemaGenerator.newInstance(MODE).withReferences(references);
            Files.writeString(directory.resolve("java-stock-alarm-" + profile + "-default-schema.json"),
                    generator.generateSchemaAsString(InstrumentGroup.class));
            Files.writeString(directory.resolve("java-stock-alarm-" + profile + "-configured-schema.json"),
                    generator.withTypeConstraint(Instrument.class, condition).generateSchemaAsString(InstrumentGroup.class));
        }
        Files.copy(fixture, directory.resolve("java-stock-alarm-input-fixtures.json"),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static void write(Path directory, String name, Class<? extends VObject> root, VObject value,
                              boolean references) throws IOException {
        var generator = VMFJsonSchemaGenerator.newInstance(MODE).withReferences(references)
                .withModelWireTypeAliases(root);
        if (root == ScalarBatch.class) {
            generator.withTypeConstraint(ScalarEntry.class, Map.of(
                    "if", Map.of("properties", Map.of("status_label", Map.of("const", "A-10")),
                            "required", List.of("status_label")),
                    "then", Map.of("required", List.of("sequence"))));
        }
        Files.writeString(directory.resolve("java-" + name + "-schema.json"), generator.generateSchemaAsString(root));
        mapper(references).writerWithDefaultPrettyPrinter()
                .writeValue(directory.resolve("java-" + name + "-snapshot.json").toFile(), value);
    }

    private static ObjectMapper mapper(boolean references) {
        return new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE)
                .withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT).withModelWireTypeAliases(Catalog.class));
    }
}
