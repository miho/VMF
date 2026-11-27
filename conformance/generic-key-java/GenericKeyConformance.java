package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.generickeys.FloatTable;
import eu.mihosoft.vmf.conformance.generickeys.IntegerIndex;
import eu.mihosoft.vmf.conformance.generickeys.IntegerTable;
import eu.mihosoft.vmf.conformance.generickeys.KeyIndex;
import eu.mihosoft.vmf.conformance.generickeys.PriceCatalog;
import eu.mihosoft.vmf.conformance.generickeys.Prices;
import eu.mihosoft.vmf.conformance.generickeys.ValueTable;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

public final class GenericKeyConformance {
    private static final String CHECKPOINT = "base";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL));
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "conformance.generic-key"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) { return type(object); }
        @Override public String type(VObject object) {
            if (object instanceof IntegerTable) return "table";
            throw new IllegalArgumentException("Unknown journal model type");
        }
        @Override public VObject create(String type, String id) {
            if (type.equals("table") && id.equals(type)) return IntegerTable.newInstance();
            throw new IllegalArgumentException("Unknown journal model type or ID");
        }
        @Override public String propertyKey(VObject object, String name) { return name; }
        @Override public String propertyName(VObject object, String key) { return key; }
    };

    private GenericKeyConformance() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        Path directory = Path.of(args[1]);
        boolean write = args[0].equals("write");
        String author = write ? "java" : "csharp";
        var checkpointFile = directory.resolve(author + "-checkpoint.json");
        var journalFile = directory.resolve(author + "-journal.json");
        if (write) {
            checkManagedPositions();
            Files.createDirectories(directory);
            var map = new LinkedHashMap<Integer, Integer>();
            map.put(1, null);
            var working = IntegerTable.newBuilder().withChecked(1).withKnown(1).withPriority(1)
                    .withPaired(map).withHistory(Arrays.asList((Integer) null)).build();
            MAPPER.writeValue(checkpointFile.toFile(), working);
            var journal = JournalRecorder.recordFrame(working, BINDING, CHECKPOINT, 0, table -> {
                table.setSelected(0);
                table.getPaired().put(1, 8);
                table.getPaired().put(2, null);
                table.getChecked().add(2);
                table.getKnown().add(2);
                table.getPriority().add(2);
            });
            Files.writeString(journalFile, VMFJournalCodec.write(journal));
        }
        var checkpoint = MAPPER.readValue(checkpointFile.toFile(), IntegerTable.class);
        var journal = VMFJournalCodec.read(Files.readString(journalFile));
        var result = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, journal);
        check(journal.wireVersion() == 1 && checkpoint.getSelected() == null && checkpoint.getPaired().get(1) == null
                && !checkpoint.getPaired().containsKey(2) && result.getSelected() == 0 && result.getPaired().get(1) == 8
                && result.getPaired().containsKey(2) && result.getPaired().get(2) == null
                && result.getChecked().equals(List.of(1, 2)) && result.getKnown().size() == 2
                && result.getPriority().equals(List.of(1, 2)), "typed generic map and managed collection journal");
        verifyPrices(directory.resolve(author + "-prices.json"), write);
        System.out.println("Java verified the typed key snapshot and collection journal from " + author + ".");
    }

    private static void verifyPrices(Path path, boolean write) throws IOException {
        if (write) {
            Prices<Integer, Long> catalog = PriceCatalog.newInstance();
            var prices = catalog.getPrices();
            prices.put(2, 20L);
            prices.put(3, 30L);
            rejects(IllegalArgumentException.class, () -> prices.put(4, 40L));
            check(catalog.vmf().reflect().propertyByName("prices").orElseThrow().get() == prices,
                    "the reflected and typed maps share storage");
            MAPPER.writeValue(path.toFile(), catalog);
        }
        var restored = MAPPER.readValue(path.toFile(), PriceCatalog.class);
        check(List.copyOf(restored.getPrices().keySet()).equals(List.of(2, 3))
                && restored.getPrices().get(3) == 30L, "prices snapshot retains native typed entries and order");
    }

    private static void checkManagedPositions() throws IOException {
        KeyIndex<Integer> index = IntegerIndex.newInstance();
        index.getEntries().put(3, "kept");
        check(index.getEntries().get(null) == null && !index.getEntries().containsKey(null)
                && index.getEntries().remove(null) == null, "null key lookups are misses");
        rejects(NullPointerException.class, () -> index.getEntries().put(null, "invalid"));
        var batch = new LinkedHashMap<Integer, String>();
        batch.put(9, "first");
        batch.put(null, "invalid");
        rejects(NullPointerException.class, () -> index.getEntries().putAll(batch));
        check(index.getEntries().size() == 1 && !index.getEntries().containsKey(9), "atomic null key rejection");

        ValueTable<Integer> table = IntegerTable.newInstance();
        table.getChecked().add(0);
        table.getKnown().add(0);
        table.getPriority().add(0);
        table.getHistory().add(null);
        rejects(NullPointerException.class, () -> table.getChecked().add(null));
        rejects(NullPointerException.class, () -> table.getKnown().add(null));
        rejects(NullPointerException.class, () -> table.getPriority().add(null));
        rejects(IllegalArgumentException.class, () -> table.getChecked().addAll(List.of(1, 2)));
        check(table.getChecked().equals(List.of(0)) && !table.getPriority().add(0), "atomic bounds and unique positions");

        var floats = FloatTable.newInstance();
        floats.getPaired().put(+0.0, null);
        floats.getPaired().put(-0.0, 1.0);
        floats.getPaired().put(Double.NaN, 3.0);
        var restored = MAPPER.readValue(MAPPER.writeValueAsString(floats), FloatTable.class);
        check(restored.getPaired().size() == 3 && restored.getPaired().containsKey(+0.0)
                && restored.getPaired().get(+0.0) == null && restored.getPaired().get(-0.0) == 1.0
                && restored.getPaired().get(Double.longBitsToDouble(0x7ff8000000000007L)) == 3.0,
                "typed keys distinguish signed zeros and coalesce NaN");
    }

    private static void rejects(Class<? extends RuntimeException> expected, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException issue) {
            check(expected.isInstance(issue), "expected " + expected.getName() + ", got " + issue);
            return;
        }
        throw new AssertionError("Expected " + expected.getName());
    }

    private static void check(boolean success, String description) {
        if (!success) throw new AssertionError(description);
    }
}
