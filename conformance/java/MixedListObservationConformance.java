package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import eu.mihosoft.vmf.conformance.ordinarycontainment.LegacyChild;
import eu.mihosoft.vmf.conformance.ordinarycontainment.LegacyContainer;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import vjavax.observer.Subscription;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class MixedListObservationConformance {
    private MixedListObservationConformance() { }

    public static void main(String[] args) throws IOException {
        var scripts = new ObjectMapper().readTree(Path.of(args[0]).toFile());
        for (var script : scripts) {
            List<String> trace;
            switch (script.path("kind").asText()) {
                case "containment-inverse": trace = observeContainingInverse(); break;
                case "journal-reentry": trace = recordReentry(); break;
                default: trace = new Scenario().run(script); break;
            }
            var expected = new ArrayList<String>();
            script.path("trace").forEach(event -> expected.add(event.asText()));
            if (!expected.equals(trace)) {
                throw new AssertionError(script.path("name").asText() + " expected " + expected + ", observed " + trace);
            }
        }
        System.out.println("Java verified " + scripts.size() + " mixed collection observer traces.");
    }

    private static List<String> recordReentry() throws IOException {
        var model = CollectionProfile.newInstance();
        var trace = new ArrayList<String>();
        var source = model.getMessages();
        var direct = source.addChangeListener(edit -> {
            String message = edit.added().elements().get(0);
            trace.add("direct:" + message);
            if ("outer".equals(message)) source.add("nested");
        });
        var binding = new JournalBinding() {
            @Override public String modelId() { return "conformance.mixed-list-observers"; }
            @Override public int modelVersion() { return 1; }
            @Override public String id(VObject value) { return "root"; }
            @Override public String type(VObject value) { return value.vmf().reflect().type().getName(); }
            @Override public VObject create(String type, String id) { return CollectionProfile.newInstance(); }
        };
        try (var recorder = JournalRecorder.start(model, binding, "empty", 0)) {
            source.add("outer");
            for (var edit : model.vmf().changes().all()) {
                trace.add("native:" + edit.listChange().orElseThrow().added().elements().get(0));
            }
            var journal = VMFJournalCodec.read(VMFJournalCodec.write(recorder.finish(), VMFDocumentFormat.JSON),
                    VMFDocumentFormat.JSON);
            if (journal.wireVersion() != 1) throw new AssertionError("Expected journal V1");
            var result = JournalReplayer.replay(CollectionProfile.newInstance(), binding, "empty", journal);
            result.getMessages().forEach(message -> trace.add("replay:" + message));
            return trace;
        } finally {
            direct.unsubscribe();
        }
    }

    private static List<String> observeContainingInverse() {
        var model = LegacyContainer.newInstance();
        var child = LegacyChild.newInstance();
        var source = model.getChildren();
        var view = model.asReadOnly().getChildren();
        var trace = new ArrayList<String>();
        var original = view.addChangeListener(edit -> trace.add("old"));
        var replacement = new Subscription[1];
        var inverse = child.vmf().changes().addPropertyListener("owner", change -> {
            trace.add("inverse");
            original.unsubscribe();
            replacement[0] = view.addChangeListener(edit -> trace.add("new"));
        });
        try {
            source.add(child);
            return trace;
        } finally {
            original.unsubscribe();
            inverse.unsubscribe();
            if (replacement[0] != null) replacement[0].unsubscribe();
        }
    }

    private static final class Scenario {
        private final CollectionProfile model = CollectionProfile.newInstance();
        private final VList<String> mutable = model.getMessages();
        private final ArrayList<String> trace = new ArrayList<>();
        private final ArrayList<Subscription> subscriptions = new ArrayList<>();
        private VList<String> projected;
        private Subscription original;
        private boolean modelRan;

        List<String> run(JsonNode script) {
            try {
                for (var step : script.path("steps")) applyStep(step);
            } finally {
                subscriptions.forEach(Subscription::unsubscribe);
            }
            return trace;
        }

        private void applyStep(JsonNode step) {
            String action = step.path("action").asText();
            switch (action) {
                case "read-projected-list":
                    projected = model.asReadOnly().getMessages();
                    break;
                case "observe-original-projection":
                    original = projected.addChangeListener(edit -> trace.add("old:" + edit.added().elements().get(0)));
                    subscriptions.add(original);
                    break;
                case "observe-model-change":
                    subscriptions.add(model.vmf().changes().addPropertyListener("messages", ignored -> {
                        trace.add("model:" + mutable.size());
                        if (mutable.size() != 1) return;
                        if (original != null) original.unsubscribe();
                        subscriptions.add(projected.addChangeListener(edit -> trace.add("new:" + edit.added().elements().get(0))));
                    }));
                    break;
                case "observe-mutable-list":
                    subscriptions.add(mutable.addChangeListener(ignored -> {
                        String label = modelRan ? "B" : "A";
                        trace.add("register:" + label);
                        subscriptions.add(projected.addChangeListener(edit -> trace.add("projected:" + label)));
                    }));
                    break;
                case "mark-mutable-list":
                    subscriptions.add(mutable.addChangeListener(ignored -> trace.add(step.path("value").asText())));
                    break;
                case "mark-projected-list":
                    subscriptions.add(projected.addChangeListener(ignored -> trace.add(step.path("value").asText())));
                    break;
                case "mark-projected-model":
                    subscriptions.add(model.asReadOnly().vmf().changes().addPropertyListener(
                            "messages", ignored -> trace.add(step.path("value").asText())));
                    break;
                case "mark-modeled-delivery":
                    subscriptions.add(model.vmf().changes().addPropertyListener("messages", ignored -> {
                        modelRan = true;
                        trace.add("model");
                    }));
                    break;
                case "add":
                    mutable.add(step.path("value").asText());
                    break;
                default:
                    throw new IllegalArgumentException("Unknown observer step '" + action + "'");
            }
        }
    }
}
