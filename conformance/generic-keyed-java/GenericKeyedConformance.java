package eu.mihosoft.vmf.conformance.generickeyed;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class GenericKeyedConformance {
    private static final String CHECKPOINT = "generic-keyed-20";
    private static final VMFJacksonModule.RUNTIME_TYPE MODE = VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL;
    private static final ObjectMapper REFERENCES = new ObjectMapper()
            .registerModule(VMFJacksonModule.newInstance(MODE).withReferences(true));
    private static final ObjectMapper PLAIN = new ObjectMapper()
            .registerModule(VMFJacksonModule.newInstance(MODE));

    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "conformance.generic-keyed"; }
        @Override public int modelVersion() { return 1; }

        @Override public String id(VObject object) {
            if (object instanceof StorageRecord) return ((StorageRecord) object).getIdentity();
            if (object instanceof IncomingStorage<?>) return ((IncomingStorage<?>) object).getName();
            if (object instanceof StorageRegistry) return "registry";
            if (object instanceof CatalogueItem) return ((CatalogueItem) object).getIdentity();
            if (object instanceof InventoryCatalogue) return "catalogue";
            throw new IllegalArgumentException("Unknown generic keyed object " + object.getClass().getName());
        }

        @Override public String type(VObject object) {
            return object.vmf().reflect().type().getName();
        }

        @Override public VObject create(String type, String id) {
            throw new IllegalArgumentException("The journal contains no creation records");
        }
    };

    private GenericKeyedConformance() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !("write".equals(args[0]) || "read".equals(args[0]))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        var directory = Path.of(args[1]);
        if ("write".equals(args[0])) {
            verifyLive();
            Files.createDirectories(directory);
            write(directory);
            verify(directory, "java");
            verifyConcreteBound(directory, "java", true);
            System.out.println("Java verified generic keyed lookup, snapshots, and reference membership journals.");
        } else {
            verify(directory, "csharp");
            verifyConcreteBound(directory, "csharp", false);
            System.out.println("Java read the C# generic keyed snapshots and replayed its reference membership journals.");
        }
    }

    private static StorageRegistry specimen() {
        var primary = Warehouse.newBuilder().withName("primary")
                .withOwned(entry("alpha", "A"), entry("beta", "B")).build();
        var reserve = Warehouse.newBuilder().withName("reserve").withOwned(entry("reserved", "B")).build();
        return StorageRegistry.newBuilder().withPrimary(primary).withReserve(reserve).build();
    }

    private static WarehouseEntry entry(String identity, String slot) {
        return WarehouseEntry.newBuilder().withIdentity(identity).withSlot(slot).build();
    }

    private static void verifyLive() {
        var root = specimen();
        Storage<WarehouseEntry> storage = root.getPrimary();
        OutgoingStorage<WarehouseEntry> secondary = root.getPrimary();
        ReadOnlyStorage<ReadOnlyWarehouseEntry> readOnly = root.getPrimary().asReadOnly();
        ReadOnlyOutgoingStorage<ReadOnlyWarehouseEntry> readOnlySecondary = root.getPrimary().asReadOnly();
        WarehouseEntry first = storage.findOwnedBySlot("A").orElseThrow();
        WarehouseEntry second = secondary.findOwnedBySlot("B").orElseThrow();
        check(first == storage.getOwned().get(0) && second == secondary.getOwned().get(1)
                && readOnly.getOwned().get(0) == readOnlySecondary.findOwnedBySlot("A").orElseThrow(),
                "Declared, secondary and read-only generic lookups resolve the same storage");
        check(root.getPrimary().vmf().reflect().propertyByName("owned").orElseThrow()
                .getKeyPropertyName().orElseThrow().equals("slot"), "Closed reflective metadata retains the key");
        reject(() -> second.setSlot("A"));
        reject(() -> root.getReserve().getOwned().add(second));
        check(second.getSlot().equals("B") && secondary.getOwned().get(1) == second
                && root.getReserve().getOwned().size() == 1, "Failed duplicate edits leave both owners unchanged");
        reject(() -> Warehouse.newBuilder().withOwned(entry("x", "duplicate"), entry("y", "duplicate")).build());
        second.setSlot("C");
        root.getReserve().getOwned().add(second);
        check(storage.findOwnedBySlot("B").isEmpty() && storage.findOwnedBySlot("C").isEmpty()
                && root.getReserve().findOwnedBySlot("C").orElseThrow() == second,
                "Successful rekey and reparent rebuild the index on both sides");
    }

    private static void write(Path directory) throws IOException {
        var root = specimen();
        PLAIN.writeValue(path(directory, "java", "plain.json").toFile(), root);
        var warehouse = root.getPrimary();
        var first = warehouse.getOwned().get(0);
        var second = warehouse.getOwned().get(1);
        warehouse.setCurrent(first);
        warehouse.getQueue().addAll(List.of(first, second));
        warehouse.getFavorites().add(second);
        REFERENCES.writeValue(path(directory, "java", "checkpoint.json").toFile(), root);
        var journal = JournalRecorder.recordFrame(root, BINDING, CHECKPOINT, 20, edit -> {
            first.setSlot("C");
            warehouse.getOwned().move(1, 0);
            warehouse.getQueue().move(1, 0);
            warehouse.setCurrent(second);
            warehouse.getFavorites().add(first);
        });
        for (var format : VMFDocumentFormat.values()) {
            Files.writeString(path(directory, "java", filename(format)), VMFJournalCodec.write(journal, format));
        }
    }

    private static void verifyConcreteBound(Path directory, String producer, boolean write) throws IOException {
        String checkpointId = "concrete-key-bound";
        var checkpointFile = path(directory, producer, "concrete-checkpoint.json");
        var journalFile = path(directory, producer, "concrete-journal.json");
        if (write) {
            var first = CatalogueItem.newBuilder().withIdentity("first").withSku("A").build();
            var derived = LabelledItem.newBuilder().withIdentity("derived").withSku("B").withLabel("priority").build();
            var inventory = InventoryCatalogue.newBuilder().withItems(first, derived).build();
            ItemCatalogue<CatalogueItem> open = inventory;
            ReadOnlyItemCatalogue<ReadOnlyCatalogueItem> view = inventory.asReadOnly();
            check(open.findItemsBySku("B").orElseThrow() == derived
                            && view.findItemsBySku("B").orElseThrow() == derived.asReadOnly(),
                    "A concrete bound lookup returns the derived instance through mutable and read-only views");
            ItemCatalogue<LabelledItem> specialized = LabelledCatalogue.newBuilder().withItems(derived.clone()).build();
            check(specialized.findItemsBySku("B").orElseThrow().getLabel().equals("priority"),
                    "A derived specialization keeps its exact item return type");
            reject(() -> derived.setSku("A"));
            check(derived.getSku().equals("B") && open.findItemsBySku("B").orElseThrow() == derived,
                    "The inherited setter checks the active key index");
            REFERENCES.writeValue(checkpointFile.toFile(), inventory);
            var journal = JournalRecorder.recordFrame(inventory, BINDING, checkpointId, 30, owner -> {
                derived.setSku("C");
                owner.getItems().move(1, 0);
            });
            Files.writeString(journalFile, VMFJournalCodec.write(journal));
        }
        var document = REFERENCES.readTree(checkpointFile.toFile());
        check(document.path("concrete_entries").get(1).path("stock_code").asText().equals("B")
                        && document.path("concrete_entries").get(1).path("@vmf-type").asText().endsWith(".LabelledItem"),
                "Snapshot uses the inherited key's alias and preserves the concrete subtype");
        var checkpoint = REFERENCES.treeToValue(document, InventoryCatalogue.class);
        var journal = VMFJournalCodec.read(Files.readString(journalFile));
        var operations = journal.transactions().get(0).operations();
        check(journal.wireVersion() == 1 && operations.get(0).property().equals("sku")
                        && operations.get(1).property().equals("items"), "V1 journal uses logical property names");
        var restored = JournalReplayer.replay(checkpoint, BINDING, checkpointId, journal);
        var derived = restored.findItemsBySku("C").orElseThrow();
        check(checkpoint.findItemsBySku("B").isPresent() && restored.findItemsBySku("B").isEmpty()
                        && derived == restored.getItems().get(0) && derived instanceof LabelledItem
                        && ((LabelledItem) derived).getLabel().equals("priority"),
                "Replay rekeys and reorders the preserved subtype without changing the checkpoint");
    }

    private static void verify(Path directory, String producer) throws IOException {
        var plainJson = PLAIN.readTree(path(directory, producer, "plain.json").toFile());
        var plainPrimary = plainJson.path("primary");
        check(plainPrimary.has("entries") && !plainPrimary.has("owned") && !plainPrimary.has("@vmf-id"),
                "Plain input uses its declared alias");
        var plain = PLAIN.treeToValue(plainJson, StorageRegistry.class);
        check(plain.getPrimary().findOwnedBySlot("A").orElseThrow() == plain.getPrimary().getOwned().get(0),
                "Plain decoding resolves the concrete bounded type");

        for (var format : VMFDocumentFormat.values()) {
            var checkpoint = REFERENCES.readValue(path(directory, producer, "checkpoint.json").toFile(), StorageRegistry.class);
            var initial = checkpoint.getPrimary();
            check(initial.getCurrent() == initial.findOwnedBySlot("A").orElseThrow()
                    && initial.getQueue().get(1) == initial.findOwnedBySlot("B").orElseThrow(), "Checkpoint reference aliases");
            var journal = VMFJournalCodec.read(Files.readString(path(directory, producer, filename(format))), format);
            check(journal.wireVersion() == 1 && journal.transactions().size() == 1
                    && journal.transactions().get(0).operations().stream().anyMatch(operation -> operation.property().equals("owned")),
                    "The membership frame uses logical names despite the snapshot alias");
            var replayed = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, journal);
            var warehouse = replayed.getPrimary();
            var first = warehouse.findOwnedBySlot("C").orElseThrow();
            var second = warehouse.findOwnedBySlot("B").orElseThrow();
            check(warehouse.findOwnedBySlot("A").isEmpty() && warehouse.getOwned().get(0) == second
                    && warehouse.getOwned().get(1) == first, "Replay applies the separate stable ID's rekey and owned move");
            check(warehouse.getCurrent() == second && warehouse.getQueue().get(0) == second
                    && warehouse.getQueue().get(1) == first && warehouse.getFavorites().contains(first),
                    "Replayed open-generic nonowning references alias the proper owning collection");
            check(initial.findOwnedBySlot("A").isPresent() && replayed.getReserve().findOwnedBySlot("B").isPresent(),
                    "Replay preserves the input checkpoint and the independent reserve key space");
        }
    }

    private static String filename(VMFDocumentFormat format) {
        return "journal." + format.name().toLowerCase(Locale.ROOT);
    }

    private static Path path(Path dir, String producer, String suffix) {
        return dir.resolve(producer + "-" + suffix);
    }

    private static void reject(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected a duplicate key to be rejected");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
