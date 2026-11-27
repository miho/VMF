package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vcollections.VListChange;
import eu.mihosoft.vmf.conformance.hierarchy.CatalogItem;
import eu.mihosoft.vmf.conformance.redeclarations.PayloadList;
import eu.mihosoft.vmf.conformance.redeclarations.PayloadRecord;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.ValueLabel;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.ObservableObject;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import vjavax.observer.collection.CollectionChangeEvent;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeListenerProxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class DirectPropertyObservationConformance {
    private DirectPropertyObservationConformance() {}

    public static void main(String[] args) throws Exception {
        var expected = new ObjectMapper().readValue(Path.of(args[0]).toFile(), new TypeReference<Map<String, List<String>>>() {});
        expect(expected.get("order"), order());
        expect(expected.get("reentry"), reentry());
        expect(expected.get("projected"), projected());
        expect(expected.get("ownership"), ownership());
        expect(expected.get("recorder"), recorder());
        verifyOpaquePayloadProjection();
        verifyOpaqueListEntryIdentity();
        System.out.println("Java verified direct property delivery, modeled and opaque projection, and history.");
    }

    private static void verifyOpaquePayloadProjection() {
        var first = ArchiveNode.newBuilder().withName("foreign").build();
        var derived = CatalogItem.newBuilder().withCode("derived").build();
        var alreadyProjected = ArchiveNode.newInstance().asReadOnly();
        var immutable = ValueLabel.newBuilder().withText("immutable").build();
        var host = new StringBuilder("host");
        var values = Arrays.asList(first, derived, alreadyProjected, immutable, host, null);
        var projections = Arrays.asList(first.asReadOnly(), derived.asReadOnly(), alreadyProjected, immutable, host, null);
        var owner = PayloadRecord.newBuilder().withPayload(first).build();
        var view = owner.asReadOnly();
        var mutableChanges = new ArrayList<PropertyChangeEvent>();
        var readOnlyChanges = new ArrayList<PropertyChangeEvent>();
        raw(owner).addPropertyChangeListener(mutableChanges::add);
        raw(view).addPropertyChangeListener(readOnlyChanges::add);
        var modeledValues = new ArrayList<List<Object>>();
        var modeled = view.vmf().changes().addPropertyListener("payload", change -> {
            same(view, change.object());
            var property = change.propertyChange().orElseThrow();
            modeledValues.add(Arrays.asList(property.oldValue(), property.newValue()));
        });
        try {
            for (int index = 1; index < values.size(); ++index) {
                owner.setPayload(values.get(index));
                same(values.get(index), owner.getPayload());
                same(values.get(index), view.getPayload());
            }
            if (mutableChanges.size() != values.size() - 1 || readOnlyChanges.size() != mutableChanges.size()
                    || modeledValues.size() != mutableChanges.size()) throw new AssertionError("Expected matching property deliveries");
            for (int edit = 0; edit < mutableChanges.size(); ++edit) {
                var direct = mutableChanges.get(edit);
                same(owner, direct.getSource());
                same(values.get(edit), direct.getOldValue());
                same(values.get(edit + 1), direct.getNewValue());
                var projected = readOnlyChanges.get(edit);
                same(view, projected.getSource());
                same(projections.get(edit), projected.getOldValue());
                same(projections.get(edit + 1), projected.getNewValue());
                same(projections.get(edit), modeledValues.get(edit).get(0));
                same(projections.get(edit + 1), modeledValues.get(edit).get(1));
            }
        } finally {
            modeled.unsubscribe();
        }
    }

    private static void verifyOpaqueListEntryIdentity() {
        var owner = PayloadList.newInstance();
        var view = owner.asReadOnly();
        var items = owner.getItems();
        var viewItems = view.getItems();
        var foreign = ArchiveNode.newInstance();
        var foreignView = foreign.asReadOnly();
        int[] counts = new int[4];
        var direct = items.addChangeListener(edit ->
                verifyOpaqueListEdit(items, edit, counts[0]++, foreign, foreignView));
        var viewDirect = viewItems.addChangeListener(edit ->
                verifyOpaqueListEdit(viewItems, edit, counts[1]++, foreign, foreignView));
        var modeled = owner.vmf().changes().addPropertyListener("items", change -> {
            same(owner, change.object());
            verifyOpaqueListEdit(items, change.listChange().orElseThrow(), counts[2]++, foreign, foreignView);
        });
        var viewModeled = view.vmf().changes().addPropertyListener("items", change -> {
            same(view, change.object());
            verifyOpaqueListEdit(viewItems, change.listChange().orElseThrow(), counts[3]++, foreign, foreignView);
        });
        try {
            items.add(foreign);
            same(foreign, items.get(0));
            same(foreign, viewItems.get(0));
            items.set(0, foreignView);
            same(foreignView, items.get(0));
            same(foreignView, viewItems.get(0));
            items.remove(0);
            if (Arrays.stream(counts).anyMatch(count -> count != 3)) {
                throw new AssertionError("Expected three opaque list edits per observer: " + Arrays.toString(counts));
            }
        } finally {
            viewModeled.unsubscribe();
            modeled.unsubscribe();
            viewDirect.unsubscribe();
            direct.unsubscribe();
        }
    }

    private static void verifyOpaqueListEdit(VList<Object> source,
            CollectionChangeEvent<Object, VList<Object>, VListChange<Object>> edit,
            int step, Object foreign, Object foreignView) {
        same(source, edit.source());
        var removed = edit.removed().elements();
        var added = edit.added().elements();
        if (step >= 3 || removed.size() != (step == 0 ? 0 : 1) || added.size() != (step == 2 ? 0 : 1)) {
            throw new AssertionError("Unexpected opaque list delta at step " + step);
        }
        if (step != 0) same(step == 1 ? foreign : foreignView, removed.get(0));
        if (step != 2) same(step == 0 ? foreign : foreignView, added.get(0));
    }

    private static List<String> order() {
        var source = ArchiveNode.newInstance();
        var trace = new ArrayList<String>();
        var direct = raw(source);
        direct.addPropertyChangeListener(change -> {
            same(source, change.getSource());
            trace.add("early:" + change.getOldValue() + ":" + change.getNewValue());
        });
        source.vmf().changes().addLocalListener(change -> trace.add("model:" + change.propertyName()));
        direct.addPropertyChangeListener(new PropertyChangeListenerProxy("name",
                change -> trace.add("named:" + change.getPropertyName())));
        direct.addPropertyChangeListener(change -> trace.add("late:" + change.getPropertyName()));
        source.setName("one");
        source.setName("one");
        source.getTrail().add(ArchiveNode.newInstance());
        return trace;
    }

    private static List<String> reentry() {
        var source = ArchiveNode.newInstance();
        var raw = raw(source);
        var trace = new ArrayList<String>();
        var earlyFailure = new IllegalStateException("early");
        var namedFailure = new IllegalArgumentException("named");
        PropertyChangeListener old = change -> trace.add("old:" + change.getNewValue());
        raw.addPropertyChangeListener(change -> {
            trace.add("early:" + change.getNewValue());
            if ("outer".equals(change.getNewValue())) {
                raw.removePropertyChangeListener(old);
                raw.addPropertyChangeListener(next -> trace.add("new:" + next.getNewValue()));
                source.setName("nested");
                throw earlyFailure;
            }
        });
        raw.addPropertyChangeListener(old);
        var changes = source.vmf().changes();
        changes.addLocalListener(change -> trace.add("model:" + change.propertyChange().orElseThrow().newValue()));
        changes.start();
        raw.addPropertyChangeListener(new PropertyChangeListenerProxy("name", change -> {
            trace.add("named:" + change.getNewValue());
            if ("outer".equals(change.getNewValue())) throw namedFailure;
        }));
        try {
            source.setName("outer");
            throw new AssertionError("Expected first callback failure");
        } catch (IllegalStateException e) {
            same(earlyFailure, e);
            if (!Arrays.equals(e.getSuppressed(), new Throwable[]{namedFailure})) throw e;
        }
        changes.all().forEach(change -> trace.add("history:" + change.propertyChange().orElseThrow().newValue()));
        changes.stop();
        if (!"nested".equals(source.getName())) throw new AssertionError("Nested assignment must remain committed");
        return trace;
    }

    private static List<String> projected() {
        var source = ArchiveNode.newInstance();
        var old = ArchiveNode.newBuilder().withName("same").build();
        var replacement = ArchiveNode.newBuilder().withName("same").build();
        source.setAnchor(old);
        var view = source.asReadOnly();
        var trace = new ArrayList<String>();
        raw(source).addPropertyChangeListener(change -> {
            same(old, change.getOldValue());
            same(replacement, change.getNewValue());
            trace.add("raw:" + change.getPropertyName());
        });
        raw(view).addPropertyChangeListener(change -> {
            same(view, change.getSource());
            same(old.asReadOnly(), change.getOldValue());
            same(replacement.asReadOnly(), change.getNewValue());
            trace.add("projected:" + change.getPropertyName());
        });
        source.vmf().changes().addPropertyListener("anchor", change -> trace.add("model:" + change.propertyName()));
        raw(source).addPropertyChangeListener(new PropertyChangeListenerProxy("anchor",
            change -> trace.add("named:" + change.getPropertyName())));
        source.setAnchor(replacement);
        return trace;
    }

    private static List<String> ownership() {
        var parent = ArchiveNode.newInstance();
        var child = ArchiveNode.newInstance();
        var trace = new ArrayList<String>();
        raw(parent).addPropertyChangeListener(change -> trace.add("parentExisting:" + change.getPropertyName()));
        raw(child).addPropertyChangeListener(change -> {
            same(child, change.getSource());
            same(parent, change.getNewValue());
            trace.add("child:" + change.getPropertyName() + ":" + (parent.getInset() == child));
            raw(parent).addPropertyChangeListener(next -> trace.add("parentAdded:" + next.getPropertyName()));
            parent.vmf().changes().addLocalListener(next -> trace.add("parentModelAdded:" + next.propertyName()));
        });
        parent.setInset(child);
        return trace;
    }

    private static List<String> recorder() throws Exception {
        var source = ArchiveNode.newInstance();
        var trace = new ArrayList<String>();
        var binding = new JournalBinding() {
            @Override public String modelId() { return "conformance.direct-property-observers"; }
            @Override public int modelVersion() { return 1; }
            @Override public String id(VObject value) { return "root"; }
            @Override public String type(VObject value) { return value.vmf().reflect().type().getName(); }
            @Override public VObject create(String type, String id) { return ArchiveNode.newInstance(); }
        };
        var recording = new AtomicReference<JournalRecorder>();
        raw(source).addPropertyChangeListener(change -> {
            trace.add("raw:" + change.getNewValue());
            if ("outer".equals(change.getNewValue())) {
                recording.set(JournalRecorder.start(source, binding, "assigned", 0));
                source.setName("nested");
            }
        });
        source.vmf().changes().addLocalListener(change -> trace.add("model:" + change.propertyChange().orElseThrow().newValue()));
        source.setName("outer");
        try (var recorder = recording.get()) {
            var journal = VMFJournalCodec.read(VMFJournalCodec.write(recorder.finish(), VMFDocumentFormat.JSON),
                    VMFDocumentFormat.JSON);
            if (journal.wireVersion() != 1) throw new AssertionError("Expected journal V1");
            var snapshots = new ObjectMapper().registerModule(VMFJacksonModule
                    .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
            var baseline = snapshots.readValue(
                    snapshots.writeValueAsString(ArchiveNode.newBuilder().withName("outer").build()), ArchiveNode.class);
            var replayed = JournalReplayer.replay(baseline, binding, "assigned", journal);
            trace.add("replay:" + replayed.getName());
            trace.add("checkpoint:" + baseline.getName());
            return trace;
        }
    }

    private static ObservableObject raw(Object model) { return (ObservableObject) model; }

    private static void same(Object expected, Object actual) {
        if (expected != actual) throw new AssertionError("Expected the original object identity");
    }

    private static void expect(List<String> expected, List<String> actual) {
        if (!actual.equals(expected)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
