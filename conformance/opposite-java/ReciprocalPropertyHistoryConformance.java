package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.opposites.Endpoint;
import eu.mihosoft.vmf.conformance.opposites.ReciprocalGraph;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternal;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeListenerProxy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@SuppressWarnings("deprecation")
final class ReciprocalPropertyHistoryConformance {
    private static final String MODEL = "conformance.reciprocal-property-history";
    private static final String CHECKPOINT = "peer-base";
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return MODEL; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) {
            if (!(object instanceof Endpoint)) return "graph";
            return ((Endpoint)object).getId().split("#", 2)[0];
        }
        @Override public String type(VObject object) { return object instanceof Endpoint ? "endpoint" : "graph"; }
        @Override public VObject create(String type, String id) { throw new AssertionError("No nodes are created"); }
    };

    private ReciprocalPropertyHistoryConformance() { }

    static void exchange(Path directory, boolean write) throws IOException {
        for (String timing : List.of("early-primary", "named-primary", "late-primary", "old-inverse")) {
            var journal = write ? record(timing) : null;
            for (var format : VMFDocumentFormat.values()) {
                var file = directory.resolve((write ? "java-" : "csharp-") + "reciprocal-property-" + timing + "-journal."
                        + format.name().toLowerCase(Locale.ROOT));
                if (write) Files.writeString(file, VMFJournalCodec.write(journal, format));
                verify(VMFJournalCodec.read(Files.readString(file), format), timing);
            }
        }
    }

    private static ChangeJournal record(String timing) {
        var graph = checkpoint();
        var a = graph.getNodes().get(0);
        var b = graph.getNodes().get(1);
        var c = graph.getNodes().get(2);
        var d = graph.getNodes().get(3);
        boolean inverse = timing.equals("old-inverse");
        var watched = inverse ? d : c;
        Runnable removed = inverse ? raw(c, "peer", edit -> { throw new AssertionError("Departed callback was delivered"); }) : () -> {};
        Runnable removedOpposite = inverse ? raw(b, "peer", edit -> { throw new AssertionError("Departed inverse callback was delivered"); }) : () -> {};
        var callbacks = new ArrayList<String>();
        var installed = new ArrayList<Runnable>();
        Consumer<PropertyChangeEvent> reenter = edit -> {
            if (!edit.getPropertyName().equals("peer")) return;
            check(edit.getSource() == watched && edit.getOldValue() == (inverse ? c : d)
                    && edit.getNewValue() == (inverse ? null : b), "Exact raw property payload");
            callbacks.add(timing);
            if (inverse) {
                removed.run();
                installed.add(raw(c, "peer", sourceEdit -> {
                    check(sourceEdit.getSource() == c && sourceEdit.getOldValue() == d && sourceEdit.getNewValue() == b,
                            "Late primary receives the initiating payload");
                    callbacks.add("installed-primary");
                    removedOpposite.run();
                    installed.add(raw(b, "peer", inverseEdit -> {
                        check(inverseEdit.getSource() == b && inverseEdit.getOldValue() == a && inverseEdit.getNewValue() == c,
                                "Late inverse receives the target payload");
                        callbacks.add("installed-final-inverse");
                    }));
                }));
            }
            c.setId("c#observed");
        };
        String name = timing.equals("named-primary") ? "peer" : null;
        if (!timing.equals("late-primary")) installed.add(raw(watched, name, reenter));
        try (var recorder = JournalRecorder.start(graph, BINDING, CHECKPOINT, 0)) {
            if (timing.equals("late-primary")) installed.add(raw(watched, null, reenter));
            c.setPeer(b);
            check(callbacks.equals(inverse ? List.of(timing, "installed-primary", "installed-final-inverse") : List.of(timing)),
                    "Each reciprocal dispatch snapshots its direct recipients independently: " + callbacks);
            var history = graph.vmf().changes().all().stream().map(change -> BINDING.id(change.object()) + "." + change.propertyName())
                    .collect(Collectors.toList());
            check(expected(timing).equals(history), "Native history delivery order: " + history);
            return recorder.finish();
        } finally {
            removed.run();
            removedOpposite.run();
            installed.forEach(Runnable::run);
        }
    }

    private static Runnable raw(Endpoint endpoint, String name, Consumer<PropertyChangeEvent> callback) {
        var source = (VObjectInternal)endpoint;
        PropertyChangeListener direct = callback::accept;
        var listener = name == null ? direct : new PropertyChangeListenerProxy(name, direct);
        source.addPropertyChangeListener(listener);
        return () -> source.removePropertyChangeListener(listener);
    }

    private static List<String> expected(String timing) {
        return timing.equals("early-primary") || timing.equals("old-inverse")
                ? List.of("a.peer", "c.id", "c.peer") : List.of("a.peer", "c.peer", "c.id");
    }

    private static void verify(ChangeJournal journal, String timing) {
        check(journal.wireVersion() == 1 && journal.transactions().size() == 1, "One V1 frame");
        var operations = journal.transactions().get(0).operations();
        check(operations.stream().allMatch(edit -> edit.kind() == ChangeJournal.Kind.SET && edit.created().isEmpty())
                        && operations.stream().map(edit -> edit.target() + "." + edit.property()).collect(Collectors.toList())
                        .equals(expected(timing)), "V1 relay order for reciprocal singles");
        var start = checkpoint();
        var restored = JournalReplayer.replay(start, BINDING, CHECKPOINT, journal);
        var a = restored.getNodes().get(0);
        var b = restored.getNodes().get(1);
        var c = restored.getNodes().get(2);
        var d = restored.getNodes().get(3);
        check(a.getPeer() == null && d.getPeer() == null && b.getPeer() == c && c.getPeer() == b
                        && c.getId().equals("c#observed"), "A fresh checkpoint replays both primary and nested edits");
        check(start.getNodes().get(0).getPeer() == start.getNodes().get(1)
                        && start.getNodes().get(2).getPeer() == start.getNodes().get(3)
                        && start.getNodes().get(2).getId().equals("c"), "Original checkpoint remains available");
    }

    private static ReciprocalGraph checkpoint() {
        var a = Endpoint.newBuilder().withId("a").build();
        var b = Endpoint.newBuilder().withId("b").build();
        var c = Endpoint.newBuilder().withId("c").build();
        var d = Endpoint.newBuilder().withId("d").build();
        a.setPeer(b);
        c.setPeer(d);
        return ReciprocalGraph.newBuilder().withNodes(a, b, c, d).build();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
