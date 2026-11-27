package eu.mihosoft.vmf.conformance.keyedredeclaration;

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
import java.util.Locale;

public final class KeyedRedeclarationConformance {
    private static final String CHECKPOINT = "keyed-inheritance-12";
    private static final ObjectMapper REFERENCES = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "conformance.keyed-redeclaration"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject value) {
            if (value instanceof Warehouse) return "warehouse";
            if (value instanceof StockEntry) return ((StockEntry) value).getId();
            throw new IllegalArgumentException("Unknown keyed model object");
        }
        @Override public String type(VObject value) { return value.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) {
            throw new IllegalArgumentException("The journal does not introduce objects");
        }
    };

    private KeyedRedeclarationConformance() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !("write".equals(args[0]) || "read".equals(args[0]))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        var directory = Path.of(args[1]);
        if ("write".equals(args[0])) {
            verifyLive();
            Files.createDirectories(directory);
            var model = specimen();
            REFERENCES.writeValue(path(directory, "java", "checkpoint.json").toFile(), model);
            var journal = JournalRecorder.recordFrame(model, BINDING, CHECKPOINT, 12, warehouse -> {
                warehouse.getItems().get(0).setSku("C");
                warehouse.getItems().move(1, 0);
            });
            for (var format : VMFDocumentFormat.values()) {
                Files.writeString(path(directory, "java", journalFile(format)), VMFJournalCodec.write(journal, format));
            }
            verify(directory, "java");
            System.out.println("Java verified inherited keyed lookup, effective aliases and V1 replay.");
        } else {
            verify(directory, "csharp");
            System.out.println("Java restored the C# renamed keyed snapshot and replayed all three journals.");
        }
    }

    private static Warehouse specimen() {
        return Warehouse.newBuilder().withLabel("Warehouse").withItems(entry("a", "A"), entry("b", "B")).build();
    }

    private static StockEntry entry(String id, String sku) { return StockEntry.newBuilder().withId(id).withSku(sku).build(); }

    private static void verifyLive() throws IOException {
        var warehouse = specimen();
        StockOwner stock = warehouse;
        BackupOwner backup = warehouse;
        CombinedStock combined = warehouse;
        StockEntry first = combined.findItemsBySku("A").orElseThrow();
        ReadOnlyCombinedStock projected = warehouse.asReadOnly();
        check(stock.getItems() == backup.getItems() && stock.getItems() == warehouse.getItems()
                && projected.findItemsBySku("A").orElseThrow() == first.asReadOnly(),
                "Both owning declarations use one keyed collection and projection");
        reject(() -> first.setSku("B"));
        check(stock.findItemsBySku("A").orElseThrow() == first, "Duplicate rekeys preserve the index");
        first.setSku("C");
        check(backup.findItemsBySku("C").orElseThrow() == first, "A rekey is visible through the second declaration");
        var separate = StandardStock.newBuilder().withItems(entry("other", "other")).build();
        var oldAlias = REFERENCES.readTree(REFERENCES.writeValueAsString(separate));
        check(oldAlias.has("stock") && !oldAlias.has("receipts"), "Sibling types retain the inherited snapshot field name");
        separate.getItems().add(first);
        check(combined.findItemsBySku("C").isEmpty() && separate.findItemsBySku("C").orElseThrow() == first,
                "A reparent updates both owners' indexes");
        var direct = DirectStock.newBuilder().withItems(entry("direct", "D")).build();
        check(REFERENCES.readTree(REFERENCES.writeValueAsString(direct)).has("direct_stock")
                && direct.asReadOnly().findItemsBySku("D").orElseThrow() == direct.getItems().get(0).asReadOnly(),
                "An explicit alias on a direct merge still exposes the reused index");
    }

    private static void verify(Path directory, String producer) throws IOException {
        String checkpointJson = Files.readString(path(directory, producer, "checkpoint.json"));
        var document = REFERENCES.readTree(checkpointJson);
        check(document.has("receipts") && !document.has("stock") && !document.has("items"), "Only the local wire alias is written");
        for (var format : VMFDocumentFormat.values()) {
            var initial = REFERENCES.readValue(checkpointJson, Warehouse.class);
            var journal = VMFJournalCodec.read(Files.readString(path(directory, producer, journalFile(format))), format);
            var operations = journal.transactions().get(0).operations();
            check(journal.wireVersion() == 1 && operations.get(0).target().equals("a")
                    && operations.get(1).property().equals("items"), "The journal uses a stable ID and the logical collection name");
            var replayed = JournalReplayer.replay(initial, BINDING, CHECKPOINT, journal);
            check(replayed.getItems().get(1) == replayed.findItemsBySku("C").orElseThrow()
                    && replayed.getItems().get(1).getId().equals("a") && initial.findItemsBySku("A").isPresent(),
                    "The same stored object is rekeyed and moved in the independent replay");
        }
    }

    private static String journalFile(VMFDocumentFormat format) { return "journal." + format.name().toLowerCase(Locale.ROOT); }
    private static Path path(Path dir, String producer, String suffix) { return dir.resolve(producer + "-" + suffix); }

    private static void reject(Runnable edit) {
        try { edit.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected a duplicate key to be rejected");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
