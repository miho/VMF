package eu.mihosoft.vmf.example.workshops.verification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.example.workshops.Catalog;
import eu.mihosoft.vmf.example.workshops.ExternalInventory;
import eu.mihosoft.vmf.example.workshops.Instrument;
import eu.mihosoft.vmf.example.workshops.Shelf;
import eu.mihosoft.vmf.example.workshops.Station;
import eu.mihosoft.vmf.example.workshops.Thermometer;
import eu.mihosoft.vmf.example.workshops.Workshop;
import eu.mihosoft.vmf.example.workshops.WorkshopSite;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class WorkshopSnapshots {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                    .withModelWireTypeAliases(Catalog.class).withReferences(true));

    private static final ObjectMapper PLAIN_MAPPER = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL));

    private WorkshopSnapshots() {
    }

    public static void main(String[] args) throws IOException {
        Path snapshots = Path.of(args[1]);
        Path fixtures = Path.of(args[2]);
        switch (args[0]) {
            case "snapshot-write":
                Files.createDirectories(snapshots);
                WorkshopSite site = workshopSite();
                write(snapshots.resolve("workshops.json"), site);
                write(snapshots.resolve("catalog.json"), catalog());
                var inventory = PLAIN_MAPPER.readValue(fixtures.resolve("inventory.json").toFile(), ExternalInventory.class);
                PLAIN_MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValue(snapshots.resolve("inventory.json").toFile(), inventory);
                reject(() -> PLAIN_MAPPER.writeValueAsString(site), "live favorite in plain JSON");
                reject(() -> MAPPER.writeValueAsString(site.getWorkshops().get(0)), "detached favorite");
                break;
            case "snapshot-read":
                verifySnapshots(snapshots);
                verifySnapshots(fixtures);
                verifyPlainSnapshot(snapshots, fixtures);
                verifyInvalidSnapshots(fixtures);
                verifySchema();
                break;
            default:
                throw new IllegalArgumentException("Unknown snapshot command " + args[0]);
        }
    }

    private static WorkshopSite workshopSite() {
        var site = WorkshopSite.newInstance();
        var left = Workshop.newInstance();
        var right = Workshop.newInstance();
        site.getWorkshops().add(left);
        site.getWorkshops().add(right);
        var shared = station("shared");
        left.getStations().add(station("left"));
        left.getStations().add(shared);
        left.getFavorites().add(shared);
        shared.setWorkshop(right);
        right.getStations().add(station("right"));
        return site;
    }

    private static Station station(String name) {
        return Station.newBuilder().withName(name).build();
    }

    private static Catalog catalog() {
        var catalog = Catalog.newInstance();
        var thermometer = Thermometer.newInstance();
        thermometer.setSku("T-1");
        thermometer.setUnit("C");
        var instrument = Instrument.newInstance();
        instrument.setSku("I-1");
        catalog.getProducts().add(thermometer);
        catalog.getProducts().add(instrument);
        catalog.setFeatured(thermometer);
        return catalog;
    }

    private static void write(Path path, Object value) throws IOException {
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private static void verifySnapshots(Path directory) throws IOException {
        var site = MAPPER.readValue(directory.resolve("workshops.json").toFile(), WorkshopSite.class);
        var left = site.getWorkshops().get(0);
        var right = site.getWorkshops().get(1);
        var shared = right.getStations().get(0);
        check(site.getWorkshops().size() == 2 && left.getStations().size() == 1
                && "left".equals(left.getStations().get(0).getName()) && right.getStations().size() == 2
                && "shared".equals(shared.getName()) && "right".equals(right.getStations().get(1).getName()),
                "Workshop containment order");
        check(left.getFavorites().iterator().next() == shared && shared.getWorkshop() == right,
                "Workshop favorite identity and opposite link");
        check(site.vmf().content().validate().isValid(), "Valid restored workshop graph");
        shared.setWorkshop(left);
        check(left.getStations().get(1) == shared && left.getFavorites().contains(shared)
                && right.getStations().size() == 1, "Reparenting a restored station");

        Path catalogFile = directory.resolve("catalog.json");
        JsonNode json = MAPPER.readTree(catalogFile.toFile());
        check(!json.has("products") && "thermometer".equals(json.path("items").path(0).path("@vmf-type").asText())
                && "instrument".equals(json.path("items").path(1).path("@vmf-type").asText()),
                "Shared wire field name and subtype aliases");
        Shelf<Instrument> shelf = MAPPER.readValue(catalogFile.toFile(), Catalog.class);
        check(shelf.getProducts().size() == 2 && shelf.getProducts().get(0) instanceof Thermometer
                && "T-1".equals(shelf.getFeatured().getSku())
                && "C".equals(((Thermometer) shelf.getFeatured()).getUnit())
                && "I-1".equals(shelf.getProducts().get(1).getSku())
                && !(shelf.getProducts().get(1) instanceof Thermometer)
                && shelf.getFeatured() == shelf.getProducts().get(0),
                "Generic containment, concrete subtype and featured reference identity");
    }

    private static void verifyPlainSnapshot(Path snapshots, Path fixtures) throws IOException {
        var external = PLAIN_MAPPER.readTree(fixtures.resolve("inventory.json").toFile());
        var input = snapshots.resolve("inventory.json").toFile();
        check(external.equals(PLAIN_MAPPER.readTree(input)), "Plain JSON keeps the external field names and shape");

        var inventory = PLAIN_MAPPER.readValue(input, ExternalInventory.class);
        var first = inventory.getItems().get(0);
        var second = inventory.getItems().get(1);
        check("BER-1".equals(inventory.getWarehouseId()) && inventory.getItems().size() == 2
                && "A-42".equals(first.getSku()) && first.getQuantity() == 0 && !first.isActive()
                && "B-10".equals(second.getSku()) && second.getQuantity() == 8 && second.isActive(),
                "Plain JSON restores the typed inventory, including zero and false");
        check(external.equals(PLAIN_MAPPER.readTree(PLAIN_MAPPER.writeValueAsString(inventory))),
                "Plain JSON re-exports without VMF metadata");
    }

    private static void verifyInvalidSnapshots(Path fixtures) throws IOException {
        String workshop = Files.readString(fixtures.resolve("workshops.json"));
        reject(() -> MAPPER.readValue(workshop.replace("\"2\"", "\"missing\""), WorkshopSite.class),
                "dangling reference");
        reject(() -> MAPPER.readValue(workshop.replace("[\"2\"]", "[\"2\", 2]"), WorkshopSite.class),
                "duplicate reference to one identity");
        reject(() -> MAPPER.readValue(workshop.replace("{\"name\": \"right\"}",
                "{\"@vmf-id\": \"2\", \"name\": \"right\"}"), WorkshopSite.class), "duplicate identity");
        reject(() -> MAPPER.readValue(workshop.replace("\"favorites\": [\"2\"]",
                "\"@vmf-id\": \"wrong\", \"favorites\": [\"wrong\"]"), WorkshopSite.class), "wrong reference type");
        String catalog = Files.readString(fixtures.resolve("catalog.json"));
        reject(() -> MAPPER.readValue(catalog.replace("\"thermometer\"", "\"unknown-subtype\""), Catalog.class),
                "unknown subtype alias");

        String legacyCatalog = catalog.replace("\"thermometer\"", "\"" + Thermometer.class.getName() + "\"");
        var legacy = MAPPER.readValue(legacyCatalog, Catalog.class);
        check(legacy.getFeatured() instanceof Thermometer && legacy.getFeatured() == legacy.getProducts().get(0),
                "Legacy JSON with a qualified Java type name");
    }

    private static void verifySchema() {
        var schema = VMFJsonSchemaGenerator.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withModelWireTypeAliases(Catalog.class).withReferences(true);
        JsonNode schemaJson = MAPPER.valueToTree(schema.generateSchema(Catalog.class));
        var definitions = schemaJson.path("definitions");
        check(definitions.has("thermometer") && definitions.has("instrument"), "Schema uses the model's wire aliases");
    }

    private static void reject(IoOperation operation, String problem) throws IOException {
        try {
            operation.run();
        } catch (JsonProcessingException expected) {
            return;
        }
        throw new IllegalStateException("Accepted invalid snapshot: " + problem);
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws IOException;
    }

    private static void check(boolean condition, String problem) {
        if (!condition) {
            throw new IllegalStateException(problem);
        }
    }
}
