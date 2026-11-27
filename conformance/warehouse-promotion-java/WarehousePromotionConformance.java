package eu.mihosoft.vmf.conformance.warehousepromotion;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class WarehousePromotionConformance {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    private static final String CHECKPOINT = "warehouse-4";
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "conformance.warehouse-promotion"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject value) {
            return value instanceof Warehouse ? "warehouse" : ((Item) value).getSku();
        }
        @Override public String type(VObject value) { return value.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) {
            throw new IllegalArgumentException("No creation in the warehouse probe");
        }
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !("read".equals(args[0]) || "write".equals(args[0]))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        var dir = Files.createDirectories(Path.of(args[1]));
        checkBuilderImports();
        if (args[0].equals("read")) {
            verify(dir, "csharp");
            return;
        }

        Warehouse warehouse = Warehouse.newInstance();
        UnkeyedInventory plain = warehouse;
        Inventory keyed = warehouse;
        ExtraInventory secondary = warehouse;
        Item first = item("A"), second = item("B");
        check(plain.getItems() == keyed.getItems() && keyed.getItems() == secondary.getItems(),
                "All writable views return the same list instance");
        plain.getItems().addAll(List.of(first, second));
        check(warehouse.findItemsBySku("A").orElseThrow() == first
                && ((VObjectInternal) first)._vmf_getContainer() == warehouse,
                "An ordinary-view add adopts the entry and indexes its key");
        try { plain.getItems().add(null); throw new AssertionError("accepted null"); }
        catch (IllegalArgumentException | NullPointerException expected) {
            System.out.println("Promoted null rejection: " + expected.getClass().getSimpleName());
        }
        reject(() -> plain.getItems().add(item("A")));
        reject(() -> first.setSku("B"));
        check(keyed.findItemsBySku("A").orElseThrow() == first, "Rejected edits preserve the key index");
        first.setSku("C");
        ReadOnlyUnkeyedInventory unkeyedView = warehouse.asReadOnly();
        ReadOnlyInventory keyedView = warehouse.asReadOnly();
        check(unkeyedView.getItems() == keyedView.getItems()
                && keyedView.findItemsBySku("C").orElseThrow() == first.asReadOnly(),
                "Both read-only views use the same projection");
        var promoted = warehouse.vmf().reflect().propertyByName("items").orElseThrow();
        check(promoted.getKeyPropertyName().orElseThrow().equals("sku")
                && promoted.annotationByKey("vmf:property:containment-info").orElseThrow().getValue().equals("contained"),
                "The effective field reflects keyed containment");

        LooseWarehouse loose = LooseWarehouse.newInstance();
        loose.getItems().addAll(java.util.Arrays.asList(first, first, null));
        var unkeyed = loose.vmf().reflect().propertyByName("items").orElseThrow();
        check(loose.getItems().size() == 3 && loose.getItems().get(2) == null && unkeyed.getKeyPropertyName().isEmpty()
                && ((VObjectInternal) first)._vmf_getContainer() == warehouse,
                "An independent unkeyed owner preserves repeated aliases/null and cannot adopt entries");
        UnkeyedInventory overlay = LooseOverlay.newInstance();
        overlay.getItems().addAll(java.util.Arrays.asList(first, first, null));
        check(overlay.getItems().size() == 3 && ((VObjectInternal) first)._vmf_getContainer() == warehouse,
                "An ordinary secondary interface does not adopt aliases");

        MAPPER.writeValue(dir.resolve("java-checkpoint.json").toFile(), warehouse);
        var journal = JournalRecorder.recordFrame(warehouse, BINDING, CHECKPOINT, 4,
                model -> ((UnkeyedInventory) model).getItems().move(1, 0));
        Files.writeString(dir.resolve("java-journal.json"), VMFJournalCodec.write(journal));
        Files.writeString(dir.resolve("java-journal.xml"), VMFJournalCodec.write(journal, VMFDocumentFormat.XML));
        Files.writeString(dir.resolve("java-journal.yaml"), VMFJournalCodec.write(journal, VMFDocumentFormat.YAML));
        verify(dir, "java");
        System.out.println("Java retained one owning storage, separate unkeyed list behavior, and V1 journal replay.");
    }

    private static void verify(Path dir, String producer) throws Exception {
        for (var format : VMFDocumentFormat.values()) {
            var suffix = format.name().toLowerCase(java.util.Locale.ROOT);
            var checkpoint = MAPPER.readValue(dir.resolve(producer + "-checkpoint.json").toFile(), Warehouse.class);
            var journal = VMFJournalCodec.read(Files.readString(dir.resolve(producer + "-journal." + suffix)), format);
            var operation = journal.transactions().get(0).operations().get(0);
            check(journal.wireVersion() == 1 && operation.target().equals("warehouse") && operation.property().equals("items"),
                    "The ordinary view still emits a normal positional containment edit");
            var replayed = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, journal);
            check(replayed.getItems().get(0) == replayed.findItemsBySku("B").orElseThrow()
                    && checkpoint.getItems().get(0) == checkpoint.findItemsBySku("C").orElseThrow(),
                    "A reloaded graph replays the single owning list without changing its input");
        }
    }

    private static Item item(String sku) { return Item.newBuilder().withSku(sku).build(); }

    private static void checkBuilderImports() {
        var child = item("imported");
        UnkeyedInventory loose = LooseWarehouse.newInstance();
        loose.getItems().add(child);
        var builder = Warehouse.newBuilder().applyFrom(loose);
        check(((VObjectInternal) child)._vmf_getContainer() == null, "Import stages the source without adopting the child");
        var warehouse = builder.build();
        check(warehouse.findItemsBySku("imported").orElseThrow() == child, "Build adopts the original staged identity");
        loose.getItems().add(null);
        var invalid = Warehouse.newBuilder().appendCollections(false).applyFrom(loose);
        rejectNull(invalid::build);
        rejectNull(() -> invalid.applyTo(warehouse));
        check(loose.getItems().size() == 2 && loose.getItems().get(0) == child
                && ((VObjectInternal) child)._vmf_getContainer() == warehouse, "Rejecting a null import preserves both graphs");
    }
    private static void reject(Runnable change) {
        try { change.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid keyed change");
    }

    private static void rejectNull(Runnable change) {
        try { change.run(); }
        catch (IllegalArgumentException | NullPointerException expected) { return; }
        throw new AssertionError("Expected rejection of a null keyed entry");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
