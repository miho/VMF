package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.Changes;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.internal.PreparedMembershipConformance;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import vjavax.observer.Subscription;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

final class ScalarMembershipObserverConformance {
    private static final List<String> EDITS = List.of("outer", "nested", "future");
    private static final JournalBinding JOURNAL = new JournalBinding() {
        @Override public String modelId() { return "conformance.membership-observer-route"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject node) { return "profile"; }
        @Override public String type(VObject node) { return node.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new AssertionError("Unexpected node creation"); }
    };

    private ScalarMembershipObserverConformance() {}

    public static void main(String[] args) throws IOException {
        boolean prepared = PreparedMembershipConformance.available();
        var traces = new ObjectMapper().readValue(Path.of(args[0]).toFile(),
                new TypeReference<Map<String, Map<String, List<String>>>>() {});
        for (boolean map : new boolean[]{true, false}) {
            var expected = traces.get(map ? "map" : "set");
            verifyMixedOrder(create(map, expected));
            verifyImplicitProjection(create(map, expected));
            verifyMaterialization(create(map, expected));
            verifySourceReentry(create(map, expected), false);
            verifySourceReentry(create(map, expected), true);
            verifyProjectionReentry(create(map, expected));
            verifyModeledReentry(create(map, expected), false);
            verifyModeledReentry(create(map, expected), true);
            verifyHistory(create(map, expected), false);
            verifyHistory(create(map, expected), true);
            verifyFirstFailure(create(map, expected));
            if (prepared) {
                for (boolean projected : new boolean[]{false, true}) {
                    verifyPortableInitiation(create(map, expected), projected, false);
                    verifyPortableInitiation(create(map, expected), projected, true);
                }
            }
        }
        System.out.println("Java verified 22 shared scalar map and set observer cases.");
        if (prepared) System.out.println("Java verified all eight prepared V1 initiation cases.");
    }

    private static void verifyMixedOrder(Fixture f) {
        var trace = new ArrayList<String>();
        f.raw.add(key -> trace.add("M0"));
        f.model.asReadOnly();
        f.raw.add(key -> trace.add("M1"));
        f.mutableModel.add(key -> trace.add("C0"), false);
        var view = f.readOnly.get();
        f.raw.add(key -> trace.add("M2"));
        view.add(key -> trace.add("P0"));
        f.raw.add(key -> trace.add("M3"));
        f.projectedModel.add(key -> trace.add("R0"), false);
        view.add(key -> trace.add("P1"));
        f.mutableModel.add(key -> trace.add("C1"), true);
        f.projectedModel.add(key -> trace.add("R1"), true);
        f.edit.accept("outer");
        f.expect("mixed-order", trace);
    }

    private static void verifyImplicitProjection(Fixture f) {
        var trace = new ArrayList<String>();
        f.raw.add(key -> trace.add("M0"));
        f.projectedModel.add(key -> trace.add("R0"), false);
        f.raw.add(key -> trace.add("M1"));
        f.mutableModel.add(key -> trace.add("C0"), false);
        f.readOnly.get().add(key -> trace.add("P0"));
        f.raw.add(key -> trace.add("M2"));
        f.edit.accept("outer");
        f.expect("implicit-projection", trace);
    }

    private static void verifyMaterialization(Fixture f) {
        var trace = new ArrayList<String>();
        f.raw.add(key -> trace.add("M0"));
        var view = f.readOnly.get();
        f.raw.add(key -> trace.add("M1"));
        var projected = view.add(key -> trace.add("P0"));
        f.edit.accept("outer");
        f.expect("first-projected-subscription", trace);
        trace.clear();
        projected.unsubscribe();
        f.edit.accept("nested");
        f.expect("unsubscribed-projection", trace);
        trace.clear();
        f.raw.add(key -> trace.add("M2"));
        view.add(key -> trace.add("Pnew"));
        f.edit.accept("future");
        f.expect("resubscribed-projection", trace);
    }

    private static void verifySourceReentry(Fixture f, boolean initiallyObserved) {
        var trace = new ArrayList<String>();
        var old = new Subscription[1];
        f.raw.add(key -> {
            trace.add("early:" + key);
            if (!"outer".equals(key)) return;
            if (old[0] != null) old[0].unsubscribe();
            f.readOnly.get().add(edit -> trace.add("Pnew:" + edit));
            f.raw.add(edit -> trace.add("Mnew:" + edit));
            f.edit.accept("nested");
        });
        var view = f.readOnly.get();
        if (initiallyObserved) old[0] = view.add(key -> trace.add("Pold:" + key));
        f.raw.add(key -> trace.add("late:" + key));
        f.edit.accept("outer");
        f.expect(initiallyObserved ? "source-reentry-existing-projection" : "source-reentry-empty-projection", trace);
    }

    private static void verifyProjectionReentry(Fixture f) {
        var trace = new ArrayList<String>();
        var view = f.readOnly.get();
        var old = new Subscription[1];
        view.add(key -> {
            trace.add("Pfirst:" + key);
            if (!"outer".equals(key)) return;
            old[0].unsubscribe();
            view.add(edit -> trace.add("Pnew:" + edit));
            f.edit.accept("nested");
        });
        old[0] = view.add(key -> trace.add("Pold:" + key));
        f.raw.add(key -> trace.add("late:" + key));
        f.edit.accept("outer");
        f.expect("projected-reentry", trace);
    }

    private static void verifyModeledReentry(Fixture f, boolean initialGroup) {
        var trace = new ArrayList<String>();
        var old = new Subscription[1];
        f.raw.add(key -> {
            trace.add("early");
            if (old[0] != null) old[0].unsubscribe();
            f.projectedModel.add(property -> trace.add("Rnew"), false);
        });
        f.readOnly.get().add(key -> trace.add("P"));
        if (initialGroup) {
            f.projectedModel.add(key -> trace.add("Rkeep"), false);
            old[0] = f.projectedModel.add(key -> trace.add("Rold"), false);
        }
        f.raw.add(key -> trace.add("late"));
        f.edit.accept("outer");
        f.expect(initialGroup ? "modeled-reentry-existing-scope" : "modeled-reentry-new-scope", trace);
    }

    private static void verifyHistory(Fixture f, boolean recordingFirst) {
        var stream = f.model.vmf().changes();
        var visible = new ArrayList<String>();
        if (recordingFirst) stream.start();
        var direct = f.raw.add(key -> {
            visible.addAll(history(stream));
            f.model.getMessages().add("nested");
        });
        if (!recordingFirst) stream.start();
        try {
            f.edit.accept("outer");
            var recording = recordingFirst ? "early-recording" : "late-recording";
            f.expect(recording + "-visible-in-direct-callback", visible);
            f.expect(recording + "-history", history(stream));
        } finally {
            direct.unsubscribe();
            stream.stop();
        }
    }

    private static void verifyFirstFailure(Fixture f) throws IOException {
        var checkpoint = f.model.clone();
        var trace = new ArrayList<String>();
        var rawFailure = new IllegalStateException("raw-fail");
        var firstProjectedFailure = new IllegalStateException("P0-fail");
        var secondProjectedFailure = new IllegalStateException("P1-fail");
        var raw = f.raw.add(key -> {
            trace.add("raw");
            throw rawFailure;
        });
        var view = f.readOnly.get();
        var first = view.add(key -> {
            trace.add("P0");
            throw firstProjectedFailure;
        });
        var late = f.raw.add(key -> trace.add("late"));
        var last = view.add(key -> {
            trace.add("P1");
            throw secondProjectedFailure;
        });
        try (var recorder = JournalRecorder.start(f.model, JOURNAL, "checkpoint", 0)) {
            try {
                f.edit.accept("outer");
                throw new AssertionError("Expected the first listener failure");
            } catch (IllegalStateException actual) {
                checkIdentity(actual, rawFailure);
            }

            f.expect("first-failure-order", trace);
            f.expect("first-failure-suppression", List.of(suppressionTree(rawFailure)));
            if (!f.contains(f.model, "outer") || f.contains(checkpoint, "outer")) {
                throw new AssertionError("Expected a committed edit and an untouched independent checkpoint");
            }
            f.expect("first-failure-native-history", history(f.model.vmf().changes()));
            verifyReplay(f, checkpoint, recorder.finish(), List.of("outer"));
            if (f.contains(checkpoint, "outer")) throw new AssertionError("Replay mutated its checkpoint");
        } finally {
            raw.unsubscribe();
            first.unsubscribe();
            late.unsubscribe();
            last.unsubscribe();
        }
    }

    private static void verifyPortableInitiation(Fixture f, boolean projected, boolean priorGroup) throws IOException {
        var trace = new ArrayList<String>();
        var checkpoint = new CollectionProfile[1];
        var active = new JournalRecorder[1];
        var source = projected ? f.readOnly.get() : f.raw;
        var direct = source.add(key -> {
            if (active[0] != null) {
                trace.add("direct inner");
                return;
            }
            trace.add("direct outer");
            checkpoint[0] = f.model.clone();
            active[0] = JournalRecorder.start(f.model, JOURNAL, "checkpoint", 0);
            f.edit.accept("nested");
        });
        var modeled = priorGroup ? f.mutableModel.add(property -> trace.add("modeled"), true) : null;
        try {
            f.edit.accept("outer");
            if (active[0] == null || !f.contains(checkpoint[0], "outer") || f.contains(checkpoint[0], "nested")) {
                throw new AssertionError("Expected the committed outer edit at the recording boundary");
            }
            var channel = projected ? "projected" : "raw";
            var group = priorGroup ? "with-group" : "without-group";
            f.expect("portable-initiation-" + channel + "-" + group, trace);
            verifyReplay(f, checkpoint[0], active[0].finish(), List.of("outer", "nested"));
            if (f.contains(checkpoint[0], "nested")) throw new AssertionError("Replay mutated its checkpoint");
        } finally {
            direct.unsubscribe();
            if (modeled != null) modeled.unsubscribe();
            if (active[0] != null) active[0].close();
        }
    }

    private static void verifyReplay(Fixture f, CollectionProfile checkpoint, ChangeJournal journal,
                                     List<String> expected) throws IOException {
        var kind = "limits".equals(f.property) ? ChangeJournal.Kind.MAP_EDIT : ChangeJournal.Kind.SET_EDIT;
        for (var format : VMFDocumentFormat.values()) {
            var document = VMFJournalCodec.read(VMFJournalCodec.write(journal, format), format);
            if (document.wireVersion() != 1 || document.transactions().size() != 1) {
                throw new AssertionError("Expected a single V1 transaction");
            }
            var edits = document.transactions().get(0).operations();
            if (edits.size() != 1 || edits.get(0).kind() != kind || !edits.get(0).property().equals(f.property)) {
                throw new AssertionError("Expected one collection operation for " + f.property);
            }
            var replayed = JournalReplayer.replay(checkpoint, JOURNAL, "checkpoint", document);
            if (replayed == checkpoint || replayed == f.model || expected.stream().anyMatch(key -> !f.contains(replayed, key))) {
                throw new AssertionError("V1 did not restore the committed changes from an independent checkpoint");
            }
        }
    }

    private static String suppressionTree(Throwable failure) {
        var children = new ArrayList<String>();
        for (var suppressed : failure.getSuppressed()) children.add(suppressionTree(suppressed));
        return failure.getMessage() + children;
    }

    private static List<String> history(Changes stream) {
        return stream.all().stream().map(edit -> edit.propertyName()).collect(Collectors.toList());
    }

    private static Fixture create(boolean map, Map<String, List<String>> expected) {
        var model = CollectionProfile.newInstance();
        if (map) {
            var source = model.getLimits();
            return new Fixture(model, "limits", expected, action -> source.addChangeListener(edit -> {
                checkIdentity(edit.source(), source);
                action.accept(edit.added().entries().keySet().iterator().next());
            }), () -> {
                var view = model.asReadOnly().getLimits();
                return action -> view.addChangeListener(edit -> {
                    checkIdentity(edit.source(), view);
                    action.accept(edit.added().entries().keySet().iterator().next());
                });
            }, key -> source.put(key, 1L));
        }
        var source = model.getLevels();
        return new Fixture(model, "levels", expected, action -> source.addChangeListener(edit -> {
            checkIdentity(edit.source(), source);
            action.accept(EDITS.get(edit.added().iterator().next().intValue() - 1));
        }), () -> {
            var view = model.asReadOnly().getLevels();
            return action -> view.addChangeListener(edit -> {
                checkIdentity(edit.source(), view);
                action.accept(EDITS.get(edit.added().iterator().next().intValue() - 1));
            });
        }, key -> source.add((double) (EDITS.indexOf(key) + 1)));
    }

    private static void checkIdentity(Object observed, Object subscribed) {
        if (observed != subscribed) throw new AssertionError("Expected the subscribed collection identity");
    }

    private interface Observe {
        Subscription add(Consumer<String> callback);
    }

    private interface ModelObserve {
        Subscription add(Consumer<String> callback, boolean recursive);
    }

    private static final class Fixture {
        final CollectionProfile model;
        final String property;
        final Map<String, List<String>> expected;
        final Observe raw;
        final Supplier<Observe> readOnly;
        final ModelObserve mutableModel;
        final ModelObserve projectedModel;
        final Consumer<String> edit;
        private Changes viewChanges;

        Fixture(CollectionProfile model, String property, Map<String, List<String>> expected,
                Observe raw, Supplier<Observe> readOnly, Consumer<String> edit) {
            this.model = model;
            this.property = property;
            this.expected = expected;
            this.raw = raw;
            this.readOnly = readOnly;
            this.edit = edit;
            mutableModel = (action, recursive) -> watch(model.vmf().changes(), action, recursive);
            projectedModel = (action, recursive) -> {
                // Retain the same read-only Changes instance across observer registrations.
                if (viewChanges == null) viewChanges = model.asReadOnly().vmf().changes();
                return watch(viewChanges, action, recursive);
            };
        }

        void expect(String name, List<String> actual) {
            var trace = expected.get(name);
            if (!actual.equals(trace)) {
                throw new AssertionError(property + " " + name + ": expected " + trace + ", got " + actual);
            }
        }

        boolean contains(CollectionProfile value, String key) {
            return "limits".equals(property) ? Long.valueOf(1L).equals(value.getLimits().get(key))
                    : value.getLevels().contains((double) (EDITS.indexOf(key) + 1));
        }

        private Subscription watch(Changes stream, Consumer<String> action, boolean recursive) {
            return stream.addListener(change -> {
                if (property.equals(change.propertyName())) action.accept(property);
            }, recursive);
        }
    }
}
