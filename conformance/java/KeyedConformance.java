package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.mihosoft.vmf.conformance.values.KeyedAssignment;
import eu.mihosoft.vmf.conformance.values.KeyedInventory;
import eu.mihosoft.vmf.conformance.values.KeyedInventoryBase;
import eu.mihosoft.vmf.conformance.values.KeyedItem;
import eu.mihosoft.vmf.conformance.values.KeyedPart;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternal;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

final class KeyedConformance {
    private static final ObjectMapper PLAIN = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL));
    private static final ObjectMapper REFERENCES = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    private static final JournalBinding CAUSAL = new JournalBinding() {
        public String modelId() { return "conformance.keyed-causality"; }
        public int modelVersion() { return 1; }
        public String id(VObject model) {
            if (model instanceof KeyedInventory) return "root";
            if (model instanceof KeyedItem) return "item-" + ((KeyedItem) model).getQuantity();
            if (model instanceof KeyedAssignment) return "entry-" + ((KeyedAssignment) model).getCount();
            return ((KeyedPart) model).getName();
        }
        public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        public VObject create(String type, String id) {
            if (type.equals(KeyedItem.class.getName())) return product("initial", Integer.parseInt(id.substring(5)));
            if (type.equals(KeyedAssignment.class.getName())) return KeyedAssignment.newBuilder()
                    .withCount(Integer.parseInt(id.substring(6))).build();
            throw new IllegalArgumentException(type);
        }
    };

    private KeyedConformance() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("read")) {
            var interchange = Path.of(args[1]);
            checkReferenceSnapshot(REFERENCES.readValue(interchange.resolve("csharp-reference.json").toFile(), KeyedInventory.class));
            checkPlainSnapshot(PLAIN.readValue(interchange.resolve("csharp-plain.json").toFile(), KeyedInventory.class));
            verifyKeyedCausality(interchange, false);
            System.out.println("Java restored C# keyed containment in both JSON profiles.");
        } else if (args.length == 3 && args[0].equals("write")) {
            var interchange = Files.createDirectories(Path.of(args[1]));
            var fixture = (ObjectNode) REFERENCES.readTree(Path.of(args[2]).toFile());
            var reference = REFERENCES.treeToValue(fixture, KeyedInventory.class);
            checkReferenceSnapshot(reference);
            rejectSnapshot(() -> PLAIN.writeValueAsString(reference), "plain output for mutable model keys");
            var plain = KeyedInventory.newBuilder().withProducts(product("A", 3), product("B", 4)).build();
            checkPlainSnapshot(plain);
            REFERENCES.writeValue(interchange.resolve("java-reference.json").toFile(), reference);
            PLAIN.writeValue(interchange.resolve("java-plain.json").toFile(), plain);

            checkScalarEdits(plain);
            checkModelKeyEdits(reference);
            checkLazyOrderedKeyedBuilder();
            verifyKeyedListObservers();
            checkMalformedSnapshots(fixture);
            verifyKeyedCausality(interchange, true);
            System.out.println("Java verified keyed lookups, attachment preflight, and both JSON profiles.");
        } else {
            throw new IllegalArgumentException("Usage: KeyedConformance write <directory> <fixture> | read <directory>");
        }
    }

    private static KeyedInventory journalCheckpoint() {
        return KeyedInventory.newBuilder().withProducts(product("left", 1), product("middle", 2), product("right", 3))
                .withParts(KeyedPart.newBuilder().withName("key").build()).build();
    }

    private static void verifyKeyedListObservers() {
        var inventory = journalCheckpoint();
        var products = inventory.getProducts();
        var view = inventory.asReadOnly().getProducts();
        var oldItem = products.get(1);
        var replacement = product("replacement", 7);
        var count = new int[2];
        var mutableListener = products.addChangeListener(edit -> {
            count[0]++;
            check(edit.source() == products && edit.removed().elements().get(0) == oldItem
                    && edit.added().elements().get(0) == replacement, "Mutable keyed event identities");
            check(java.util.Arrays.equals(edit.removed().indices(), new int[] {1})
                    && java.util.Arrays.equals(edit.added().indices(), new int[] {1}), "Mutable keyed event positions");
            check(inventory.findProductsBySku("middle").isEmpty()
                    && inventory.findProductsBySku("replacement").orElseThrow() == replacement, "Keyed lookups inside callback");
        });
        var viewListener = view.addChangeListener(edit -> {
            count[1]++;
            check(edit.source() == view && edit.removed().elements().get(0) == oldItem.asReadOnly()
                    && edit.added().elements().get(0) == replacement.asReadOnly(), "Read-only keyed event identities");
            check(java.util.Arrays.equals(edit.removed().indices(), new int[] {1})
                    && java.util.Arrays.equals(edit.added().indices(), new int[] {1})
                    && view.get(1) == replacement.asReadOnly(), "Live read-only keyed event positions");
        });
        try {
            rejectEdit(() -> products.set(1, product("left", 8)), "duplicate keyed replacement");
            check(count[0] == 0 && count[1] == 0, "Rejected replacement emits no direct notifications");
            products.set(1, replacement);
        } finally {
            mutableListener.unsubscribe();
            viewListener.unsubscribe();
        }
        products.remove(1);
        check(count[0] == 1 && count[1] == 1, "Each keyed view observes only subscribed edits");
    }

    private static void verifyKeyedCausality(Path directory, boolean write) throws IOException {
        for (String mode : List.of("add", "sort", "remove", "identity", "transfer")) {
            for (boolean recursive : new boolean[] { false, true }) {
                var journal = write ? recordKeyedEdit(mode, recursive) : null;
                for (var format : VMFDocumentFormat.values()) {
                    var file = directory.resolve((write ? "java-" : "csharp-") + mode + (recursive ? "-recursive" : "-local")
                            + "-journal." + format.name().toLowerCase(Locale.ROOT));
                    if (write) Files.writeString(file, VMFJournalCodec.write(journal, format));
                    verifyKeyedEdit(mode, VMFJournalCodec.read(Files.readString(file), format));
                }
            }
        }
    }

    private static ChangeJournal recordKeyedEdit(String mode, boolean recursive) {
        var owner = journalCheckpoint();
        var products = owner.getProducts();
        var incoming = product("outer", 4);
        var replacement = product("nested", 5);
        var target = owner.getParts().get(0);
        var source = KeyedInventory.newInstance();
        if (mode.equals("transfer")) source.getProducts().add(incoming);
        var departure = source.getProducts().addChangeListener(event -> {
            if (!event.wasRemoved()) return;
            check(owner.findProductsBySku("outer").orElseThrow() == incoming, "pending ownership is visible");
            rejectEdit(() -> incoming.setSku("left"), "pending keyed transfer collision");
            incoming.setSku("moved");
        });
        boolean[] observed = { false };
        String property = mode.equals("identity") ? "assignments" : "products";
        var watch = owner.vmf().changes().addListener(change -> {
            if (observed[0] || change.object() != owner || !change.propertyName().equals(property)) return;
            observed[0] = true;
            switch (mode) {
                case "add":
                    check(owner.findProductsBySku("outer").orElseThrow() == incoming, "outer key lookup");
                    products.set(3, replacement);
                    break;
                case "sort": products.set(0, replacement); break;
                case "remove":
                    check(owner.findProductsBySku("middle").isEmpty(), "removed key lookup");
                    products.add(1, replacement);
                    break;
                case "identity":
                    check(owner.findAssignmentsByPart(target).orElseThrow().getCount() == 4, "installed model key");
                    owner.getAssignments().set(0, KeyedAssignment.newBuilder().withPart(target).withCount(5).build());
                    break;
                case "transfer":
                    check(owner.findProductsBySku("moved").orElseThrow() == incoming, "source observer changed destination key");
                    incoming.setSku("renamed");
                    check(owner.findProductsBySku("renamed").orElseThrow() == incoming, "destination observer rekey");
                    break;
                default: throw new AssertionError(mode);
            }
        }, recursive);
        try (var recorder = JournalRecorder.start(owner, CAUSAL, "initial", 0)) {
            switch (mode) {
                case "add": case "transfer": products.add(incoming); break;
                case "sort": products.sort(Comparator.comparing(KeyedItem::getSku).reversed()); break;
                case "remove": products.remove(1); break;
                case "identity": owner.getAssignments().add(KeyedAssignment.newBuilder().withPart(target).withCount(4).build()); break;
                default: throw new AssertionError(mode);
            }
            check(observed[0] && source.getProducts().isEmpty(), "the observer ran and source completed the transfer");
            var positions = owner.vmf().changes().all().stream().filter(change -> change.propertyName().equals(property))
                    .collect(Collectors.toList());
            if (mode.equals("transfer")) check(positions.size() == 1, "native transfer add");
            else check(positions.size() == 2 && (positions.get(0).listChange().map(event -> event.wasAdded())
                            .orElseGet(() -> !positions.get(0).referenceSetChange().orElseThrow().added().isEmpty())),
                    "native history retains nested-before-outer delivery");
            return recorder.finish();
        } finally {
            watch.unsubscribe();
            departure.unsubscribe();
        }
    }

    private static void verifyKeyedEdit(String mode, ChangeJournal journal) {
        var operations = journal.transactions().get(0).operations();
        check(journal.wireVersion() == 1 && journal.transactions().size() == 1
                && operations.size() == (mode.equals("transfer") ? 1 : 2), "one V1 causal frame");
        var outer = operations.get(0);
        check(outer.kind() == (mode.equals("remove") ? ChangeJournal.Kind.LIST_REMOVE
                : mode.equals("sort") ? ChangeJournal.Kind.LIST_SET : ChangeJournal.Kind.LIST_ADD), "outer operation first");
        check(outer.indices().equals(mode.equals("sort") ? List.of(0, 2)
                : List.of(mode.equals("remove") ? 1 : mode.equals("identity") ? 0 : 3)), "correct outer slots");
        var checkpoint = journalCheckpoint();
        var restored = JournalReplayer.replay(checkpoint, CAUSAL, "initial", journal);
        var entries = restored.getProducts().stream().map(KeyedItem::getSku).collect(Collectors.toList());
        List<String> expected;
        switch (mode) {
            case "add": expected = List.of("left", "middle", "right", "nested"); break;
            case "sort": expected = List.of("nested", "middle", "left"); break;
            case "remove": expected = List.of("left", "nested", "right"); break;
            case "transfer": expected = List.of("left", "middle", "right", "renamed"); break;
            default: expected = List.of("left", "middle", "right");
        }
        check(entries.equals(expected) && restored.getProducts().stream()
                        .allMatch(child -> restored.findProductsBySku(child.getSku()).orElseThrow() == child)
                && checkpoint.getProducts().size() == 3, "scalar-keyed replay retains order, stable identity and independent checkpoint");
        if (mode.equals("identity")) {
            var entry = restored.findAssignmentsByPart(restored.getParts().get(0)).orElseThrow();
            check(entry == restored.getAssignments().get(0) && entry.getCount() == 5, "V1 model identity key still resolves");
        }
    }

    private static void checkProducts(KeyedInventoryBase inventory) {
        var products = inventory.getProducts();
        check(products.size() == 2, "two ordered products");
        var first = products.get(0);
        var second = products.get(1);
        check("A".equals(first.getSku()) && first.getQuantity() == 3 && "B".equals(second.getSku()) && second.getQuantity() == 4,
                "ordered payloads and aliased keys");
        check(inventory.findProductsBySku("A").orElseThrow() == first && inventory.findProductsBySku("B").orElseThrow() == second,
                "inherited lookup returns the actual contained objects");
        var readOnly = inventory.asReadOnly();
        check(readOnly.findProductsBySku("A").orElseThrow() == first.asReadOnly() && readOnly.findProductsBySku("missing").isEmpty(),
                "read-only lookup returns the same live projection as containment");
        var metadata = inventory.vmf().reflect().propertyByName("products").orElseThrow();
        check("sku".equals(metadata.getKeyPropertyName().orElseThrow()), "runtime key metadata uses the logical property name");
    }

    private static void checkPlainSnapshot(KeyedInventory inventory) {
        checkProducts(inventory);
        check(inventory.getAssignments().isEmpty() && inventory.getParts().isEmpty(), "plain fixture only contains scalar-keyed products");
    }

    private static void checkReferenceSnapshot(KeyedInventory inventory) {
        checkProducts(inventory);
        var parts = inventory.getParts();
        var assignments = inventory.getAssignments();
        check(parts.size() == 2 && assignments.size() == 2, "two parts and assignments");
        var first = parts.get(0);
        var second = parts.get(1);
        var firstAssignment = assignments.get(0);
        var secondAssignment = assignments.get(1);
        check(first != second && first.getName().equals(second.getName()), "two distinct targets have the same display label");
        check(firstAssignment.getPart() == first && secondAssignment.getPart() == second
                && firstAssignment.getCount() == 5 && secondAssignment.getCount() == 6,
                "the forward key IDs resolve before admitting their ordered assignments");
        check(inventory.findAssignmentsByPart(first).orElseThrow() == firstAssignment
                && inventory.findAssignmentsByPart(second).orElseThrow() == secondAssignment,
                "model keys retain identity and list positions");
        var readOnly = inventory.asReadOnly();
        check(readOnly.findAssignmentsByPart(first.asReadOnly()).orElseThrow() == firstAssignment.asReadOnly()
                && readOnly.findAssignmentsByPart(second.asReadOnly()).orElseThrow() == secondAssignment.asReadOnly(),
                "read-only key and result projections preserve reference identity");
        var unrelated = KeyedPart.newBuilder().withName(first.getName()).build();
        check(inventory.findAssignmentsByPart(unrelated).isEmpty(), "an unrelated target with the same scalar data cannot match");
    }

    private static void checkScalarEdits(KeyedInventory source) {
        var moving = source.getProducts().get(0);
        var kept = source.getProducts().get(1);
        var readOnly = source.asReadOnly();
        rejectEdit(() -> moving.setSku("B"), "a sibling key");
        rejectEdit(() -> moving.setSku(null), "clearing an attached key");
        check(readOnly.findProductsBySku("A").orElseThrow() == moving.asReadOnly(), "rejected setters preserve the live lookup");

        var reserved = product("reserved", 10);
        var blocking = product("A", 20);
        var destination = KeyedInventoryBase.newBuilder().withProducts(reserved, blocking).build();
        rejectEdit(() -> destination.getProducts().add(moving), "moving an entry to an occupied key");
        check(source.getProducts().equals(List.of(moving, kept)) && destination.getProducts().equals(List.of(reserved, blocking)),
                "a rejected move preserves both lists and their order");
        destination.getProducts().remove(blocking);

        var duplicate = product("reserved", 30);
        var invalid = KeyedInventoryBase.newBuilder().withProducts(reserved, duplicate);
        rejectEdit(invalid::build, "a duplicate in a fresh builder batch");
        rejectEdit(() -> invalid.applyTo(destination), "a duplicate in a builder replacement");
        check(destination.getProducts().equals(List.of(reserved)) && ((VObjectInternal) duplicate)._vmf_getContainer() == null,
                "builder preflight preserves an existing attachment and leaves the rejected entry detached");

        int[] callbackCount = {0};
        var subscription = source.getProducts().addChangeListener(event -> {
            if (!event.wasRemoved()) return;
            callbackCount[0]++;
            check(((VObjectInternal) moving)._vmf_getContainer() == source && destination.getProducts().get(1) == moving,
                    "the item still points to its old owner while held in the pending destination slot");
            rejectEdit(() -> moving.setSku("reserved"), "colliding with a pending destination key");
            rejectEdit(() -> moving.setSku(null), "clearing a pending destination key");
            moving.setSku("renamed");
            check(destination.findProductsBySku("renamed").orElseThrow() == moving, "a free key is visible during the pending move");
        });
        try {
            destination.getProducts().add(moving);
        } finally {
            subscription.unsubscribe();
        }
        check(callbackCount[0] == 1 && source.getProducts().equals(List.of(kept))
                && readOnly.findProductsBySku("A").isEmpty() && destination.findProductsBySku("renamed").orElseThrow() == moving
                && ((VObjectInternal) moving)._vmf_getContainer() == destination, "move retains a valid edit from its source callback");
        destination.getProducts().remove(moving);
        moving.setSku(null);
        check(moving.getSku() == null && destination.findProductsBySku("renamed").isEmpty(), "detached keys can be cleared");
    }

    private static void checkModelKeyEdits(KeyedInventory source) {
        var first = source.getParts().get(0);
        var second = source.getParts().get(1);
        var moving = source.getAssignments().get(0);
        var kept = source.getAssignments().get(1);
        rejectEdit(() -> moving.setPart(second), "a duplicate attached model key");
        rejectEdit(() -> moving.setPart(null), "a missing attached model key");
        check(moving.getPart() == first && source.findAssignmentsByPart(second).orElseThrow() == kept,
                "model-key setters preserve both lookups when rejected");

        var competing = KeyedAssignment.newBuilder().withPart(first).build();
        var destination = KeyedInventory.newBuilder().withAssignments(competing).build();
        rejectEdit(() -> destination.getAssignments().add(moving), "moving an entry to an occupied model identity");
        check(source.getAssignments().get(0) == moving && destination.getAssignments().get(0) == competing,
                "identity-key move rejects before detaching either entry");
    }

    private static void checkLazyOrderedKeyedBuilder() {
        for (boolean append : List.of(false, true)) {
            var calls = new AtomicInteger();
            var lazyPart = KeyedPart.newBuilder().withName("shared lazy key").build();
            var child = KeyedAssignment.newBuilder().withPart(lazyPart).withCount(1);
            var lazy = KeyedAssignment.Builder.class.cast(Proxy.newProxyInstance(KeyedAssignment.Builder.class.getClassLoader(),
                    new Class<?>[] { KeyedAssignment.Builder.class }, (proxy, method, arguments) -> {
                        check(method.getName().equals("build"), "nested assignment is only used to build");
                        calls.incrementAndGet();
                        return child.build();
                    }));
            var staged = KeyedInventory.newBuilder().appendCollections(append).withAssignments(lazy);
            var existing = KeyedAssignment.newBuilder().withPart(lazyPart).build();
            var target = KeyedInventory.newBuilder().withAssignments(existing).build();
            staged.applyTo(target);
            check(calls.get() == 0 && target.getAssignments().size() == (append ? 1 : 0)
                    && target.findAssignmentsByPart(lazyPart).orElse(null) == (append ? existing : null),
                    "ApplyTo uses eager memberships and does not build lazy ordered keyed entries");

            var first = staged.build();
            var second = staged.build();
            var firstEntry = first.findAssignmentsByPart(lazyPart).orElseThrow();
            var secondEntry = second.findAssignmentsByPart(lazyPart).orElseThrow();
            check(calls.get() == 2 && firstEntry != secondEntry
                    && first.getAssignments().get(0) == firstEntry && second.getAssignments().get(0) == secondEntry
                    && firstEntry.getPart() == lazyPart && secondEntry.getPart() == lazyPart,
                    "Each Build materializes a fresh ordered entry and preserves its model key identity");

            var importedPart = KeyedPart.newBuilder().withName("imported key").build();
            var imported = KeyedAssignment.newBuilder().withPart(importedPart).build();
            var source = KeyedInventory.newBuilder().withParts(importedPart).withAssignments(imported).build();
            staged.applyFrom(source);
            check(source.findAssignmentsByPart(importedPart).orElseThrow() == imported,
                    "ApplyFrom stages the original entry before any ownership transfer");
            var built = staged.build();
            check(calls.get() == 2 && source.getParts().isEmpty() && source.getAssignments().isEmpty()
                    && built.getParts().get(0) == importedPart && built.getAssignments().get(0) == imported
                    && built.findAssignmentsByPart(importedPart).orElseThrow() == imported,
                    "Build after import moves the original keyed entry and its key without materializing the former builder");
            staged.applyTo(target);
            check(calls.get() == 2 && built.getParts().isEmpty() && built.getAssignments().isEmpty()
                    && target.findAssignmentsByPart(importedPart).orElseThrow() == imported
                    && target.getParts().get(0) == importedPart && imported.getPart() == importedPart,
                    "ApplyTo transfers the imported entry and preserves its contained model key alias");
        }
    }

    private static void checkMalformedSnapshots(ObjectNode fixture) throws IOException {
        var missingSku = fixture.deepCopy();
        row(missingSku, "items", 0).remove("sku_code");
        rejectSnapshot(() -> REFERENCES.treeToValue(missingSku, KeyedInventory.class), "missing scalar key");

        var repeatedSku = fixture.deepCopy();
        row(repeatedSku, "items", 1).put("sku_code", "A");
        rejectSnapshot(() -> REFERENCES.treeToValue(repeatedSku, KeyedInventory.class), "duplicate scalar key");

        var missingKey = fixture.deepCopy();
        row(missingKey, "assignments", 0).remove("part");
        rejectSnapshot(() -> REFERENCES.treeToValue(missingKey, KeyedInventory.class), "missing model key property");

        var repeatedKey = fixture.deepCopy();
        row(repeatedKey, "assignments", 1).put("part", "one");
        rejectSnapshot(() -> REFERENCES.treeToValue(repeatedKey, KeyedInventory.class), "duplicate model key");

        var missingId = fixture.deepCopy();
        row(missingId, "parts", 0).remove("@vmf-id");
        rejectSnapshot(() -> REFERENCES.treeToValue(missingId, KeyedInventory.class), "unresolved reference ID");

        var repeatedId = fixture.deepCopy();
        row(repeatedId, "parts", 1).put("@vmf-id", "one");
        rejectSnapshot(() -> REFERENCES.treeToValue(repeatedId, KeyedInventory.class), "duplicate reference ID");
    }

    private static ObjectNode row(ObjectNode snapshot, String field, int index) {
        return (ObjectNode) snapshot.path(field).path(index);
    }

    private static KeyedItem product(String sku, int quantity) {
        return KeyedItem.newBuilder().withSku(sku).withQuantity(quantity).build();
    }

    private static void rejectEdit(Runnable edit, String behavior) {
        try {
            edit.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected rejection of " + behavior);
    }

    private static void rejectSnapshot(SnapshotAction action, String behavior) throws IOException {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected snapshot rejection of " + behavior);
    }

    private interface SnapshotAction {
        void run() throws IOException;
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
