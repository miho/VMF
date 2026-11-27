package eu.mihosoft.vmf.conformance.delegatedlists;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.Change.ChangeType;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DelegatedListsConformance {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(VMFJacksonModule
            .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(VMFJacksonModule.ReferenceMode.OMIT));

    private DelegatedListsConformance() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2 || !(arguments[0].equals("write") || arguments[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>.");
        }
        check(Table.class.getMethod("select").getReturnType() == VList.class,
                "Java lowers a delegated List<Row> result to VList<Row>");
        check(Table.class.getMethod("selectScopes").getReturnType() == VList.class
                        && Table.class.getMethod("selectLabels").getReturnType() == VList.class,
                "Explicit VList returns use the same Java list carrier");
        check(Table.class.getMethod("payloads").getReturnType() == VList.class
                        && Table.class.getMethod("directPayloads").getReturnType() == VList.class,
                "Both explicit Object list declarations use the Java VList carrier");
        exercise();
        exerciseExplicitLists();
        exerciseHostOwnedPayloads();

        var persisted = LiveTable.newBuilder().withName("live").build();
        persisted.selectOwned().add(OwnedRow.newBuilder().withName("owned").build());
        persisted.selectScopes().add(LocalScope.newBuilder().withName("local").withDepth(2).build());
        persisted.selectLabels().add("label");
        persisted.payloads().add("private payload");
        var envelope = TableEnvelope.newBuilder().withTable(persisted).build();
        String wire = MAPPER.writeValueAsString(envelope);
        check(!wire.contains("private payload"), "Host-owned payloads are absent from snapshot data");
        var directory = Path.of(arguments[1]);
        if (arguments[0].equals("write")) {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("java-table.json"), wire);
        } else {
            String nativeWire = Files.readString(directory.resolve("csharp-table.json"));
            check(MAPPER.readTree(wire).equals(MAPPER.readTree(nativeWire)), "C# and Java persist the same model data");
            var table = MAPPER.readValue(nativeWire, TableEnvelope.class).getTable();
            check(table instanceof LiveTable && table.select() == table.getRows()
                            && table.selectOwned() == table.getOwnedRows(),
                    "Restored types still expose their writable backing lists through base calls");
            check(table.selectOwned().get(0).getTable() == table, "The restored owner inverse is live");
            check(table.selectScopes() == table.getScopes() && table.selectLabels() == table.getLabels()
                            && ((LocalScope) table.selectScopes().get(0)).getDepth() == 2
                            && table.selectLabels().equals(List.of("label")),
                    "The restored native list results still expose typed, live storage");
            checkHostPayloads(table, table.payloads());
        }
        System.out.println("Java verified inherited delegated list identity, mutation, observation and snapshot exchange.");
    }

    private static void exerciseHostOwnedPayloads() throws Exception {
        Table table = Table.newBuilder().withName("host").build();
        String original = MAPPER.writeValueAsString(table);
        var payloads = table.payloads();
        checkHostPayloads(table, payloads);

        var observed = new ArrayList<Change>();
        var subscription = table.vmf().changes().addLocalListener(observed::add);
        try {
            var detached = Row.newBuilder().withName("unregistered child").build();
            payloads.set(1, (byte) 4);
            payloads.add(detached);
            check(payloads == table.directPayloads() && Byte.valueOf((byte) 4).equals(payloads.get(1))
                            && payloads.get(4) == detached && observed.isEmpty(),
                    "Host list edits preserve element types and identity without emitting model events");
            check(table.vmf().reflect().properties().stream().noneMatch(
                            p -> p.getName().equals("payloads") || p.getName().equals("directPayloads"))
                            && table.vmf().content().stream().count() == 1,
                    "The returned list and its model references are outside reflected graph storage");

            String wire = MAPPER.writeValueAsString(table);
            check(wire.equals(original), "A snapshot persists modeled fields only");
            var restored = MAPPER.readValue(wire, Table.class);
            checkHostPayloads(restored, restored.payloads());
            check(restored.payloads() != payloads, "A restored model constructs its own host list");
        } finally {
            subscription.unsubscribe();
        }
    }

    private static void checkHostPayloads(Table table, List<Object> payloads) {
        check(payloads == table.directPayloads() && payloads.size() == 4
                        && Integer.valueOf(4).equals(payloads.get(0)) && Long.valueOf(4).equals(payloads.get(1))
                        && payloads.get(2) == null && payloads.get(3) == table,
                "Both methods return the same host list with distinct boxes, null, and original model identity");
    }

    private static void exerciseExplicitLists() {
        Table table = LiveTable.newInstance();
        var scopes = table.selectScopes();
        var labels = table.selectLabels();
        check(scopes == table.getScopes() && labels == table.getLabels(), "Explicit VList calls expose the original lists");
        var observed = new ArrayList<Change>();
        var subscription = table.vmf().changes().addLocalListener(observed::add);
        try {
            var local = LocalScope.newBuilder().withName("local").build();
            scopes.add(local);
            scopes.add(null);
            labels.add("label");
            labels.add(null);
            check(scopes.get(0) == local && scopes.get(1) == null && labels.get(1) == null,
                    "Abstract model and scalar results keep nullable writable slots");
            check(observed.size() == 4 && observed.get(0).propertyName().equals("scopes")
                            && observed.get(2).propertyName().equals("labels"),
                    "The list aliases emit changes on the model");
        } finally {
            subscription.unsubscribe();
        }
    }

    private static void exercise() {
        check(Table.newInstance().select() == null, "A delegated list result can be null");
        Table table = LiveTable.newInstance();
        List<Row> rows = table.select();
        List<OwnedRow> owned = table.selectOwned();
        check(rows == table.getRows() && owned == table.getOwnedRows(), "The delegates return the original lists");
        var row = Row.newBuilder().withName("shared").build();
        var ownedRow = OwnedRow.newBuilder().withName("owner").build();
        var observed = new ArrayList<Change>();
        var changes = table.vmf().changes();
        var subscription = changes.addLocalListener(observed::add);
        changes.start();
        try {
            rows.add(row);
            rows.add(null);
            rows.add(row);
            check(rows.get(0) == row && rows.get(1) == null && rows.get(2) == row,
                    "A borrowed reference list preserves null and duplicate identity slots");
            rows.remove(0);
            check(table.getRows().get(0) == null && table.getRows().get(1) == row,
                    "Removing through the returned list edits the model");

            owned.add(ownedRow);
            owned.add(null);
            check(ownedRow.getTable() == table && table.getOwnedRows().get(0) == ownedRow && owned.get(1) == null,
                    "Insertion through an ordinary owning return keeps inverse and null slots");
            owned.remove(0);
            check(ownedRow.getTable() == null && table.getOwnedRows().get(0) == null,
                    "Removal through the returned owning list clears the inverse");
        } finally {
            subscription.unsubscribe();
            changes.stop();
        }
        check(observed.size() == 7 && changes.all().equals(observed), "Live edits are observed and recorded");
        check(observed.stream().allMatch(change -> change.object() == table && change.getType() == ChangeType.LIST),
                "Each event is a positional edit on the original owner");
        check(observed.get(1).listChange().orElseThrow().added().elements().get(0) == null
                && observed.get(4).listChange().orElseThrow().added().elements().get(0) == ownedRow,
                "The recorded payload retains exact null and model values");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class EmptyBinding implements DelegatedBehavior<Table> {
        public VList<Row> select() { return null; }
    }

    public static final class LiveBinding implements DelegatedBehavior<LiveTable> {
        private LiveTable table;

        @Override public void setCaller(LiveTable caller) { table = caller; }
        public VList<Row> select() { return table.getRows(); }
    }

    public static final class OwnedBinding implements DelegatedBehavior<Table> {
        private Table table;

        @Override public void setCaller(Table caller) { table = caller; }
        public VList<OwnedRow> selectOwned() { return table.getOwnedRows(); }
    }

    public static final class ListBinding implements DelegatedBehavior<Table> {
        private Table table;
        private VList<Object> payloads;

        @Override public void setCaller(Table caller) {
            table = caller;
            payloads = VList.newInstance(new ArrayList<>());
            payloads.add(4);
            payloads.add(4L);
            payloads.add(null);
            payloads.add(caller);
        }

        public VList<Scope> selectScopes() { return table.getScopes(); }
        public VList<String> selectLabels() { return table.getLabels(); }
        public VList<Object> payloads() { return payloads; }
        public VList<Object> directPayloads() { return payloads; }
    }
}
