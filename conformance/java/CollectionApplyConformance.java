package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.multiplebases.JoinedCollections;
import eu.mihosoft.vmf.conformance.values.CollectionApplyIncompatible;
import eu.mihosoft.vmf.conformance.values.CollectionApplyPeer;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class CollectionApplyConformance {
    private static final String CHECKPOINT = "collection-apply-0";
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "conformance.collection-apply"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject model) { return "peer"; }
        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new AssertionError("No creations."); }
    };

    private CollectionApplyConformance() {}

    static void exchange(Path directory, boolean write) throws IOException {
        ChangeJournal recorded = null;
        if (write) {
            var source = CollectionProfile.newInstance();
            source.getLimits().put("keep", 10L);
            source.getLevels().add(+0.0);
            var history = source.vmf().changes();
            history.start();
            var limits = new LinkedHashMap<String, Long>();
            limits.put("keep", 20L);
            limits.put("optional", null);
            source.getLimits().putAll(limits);
            source.getLevels().addAll(List.of(-0.0, Double.NaN));
            history.stop();
            var map = history.all().get(0);
            var set = history.all().get(1);
            verifyRejectedTargets(map, set);
            verifyExternalPeer(map, set);

            var peer = peer();
            var snapshots = new ObjectMapper().registerModule(
                    VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL));
            check(snapshots.valueToTree(peer).has("peer_limits"), "Snapshots use the peer's declared wire alias");
            var callbacks = new ArrayList<Change>();
            peer.vmf().changes().addPropertyListener("limits", callbacks::add);
            peer.vmf().changes().addPropertyListener("levels", callbacks::add);
            try (var recorder = JournalRecorder.start(peer, BINDING, CHECKPOINT, 0)) {
                map.apply(peer);
                set.apply(peer);
                checkFinal(peer);
                check(callbacks.size() == 2 && callbacks.get(0).object() == peer && callbacks.get(1).object() == peer
                                && callbacks.get(0).mapChange().orElseThrow().removed().entries().get("keep").equals(99L)
                                && callbacks.get(1).getType() == Change.ChangeType.SET,
                        "The peer publishes activity on its own stored values");
                recorded = recorder.finish();
            }
        }
        String author = write ? "java" : "csharp";
        for (var format : VMFDocumentFormat.values()) {
            var file = directory.resolve(author + "-collection-apply." + format.name().toLowerCase(Locale.ROOT));
            if (write) Files.writeString(file, VMFJournalCodec.write(recorded, format));
            var journal = VMFJournalCodec.read(Files.readString(file), format);
            check(journal.wireVersion() == 1 && journal.transactions().size() == 1, "One V1 peer frame");
            var edits = journal.transactions().get(0).operations();
            check(edits.size() == 2 && edits.get(0).kind() == ChangeJournal.Kind.MAP_EDIT
                            && edits.get(0).property().equals("limits")
                            && edits.get(0).beforeEntries().get("keep").text().equals("99")
                            && edits.get(1).kind() == ChangeJournal.Kind.SET_EDIT && edits.get(1).property().equals("levels"),
                    "The applied edit records logical names and the peer's own before values");
            var baseline = peer();
            checkFinal(JournalReplayer.replay(baseline, BINDING, CHECKPOINT, journal));
            check(baseline.getLimits().equals(Map.of("keep", 99L)) && baseline.getLevels().equals(Set.of(+0.0)),
                    "The replay checkpoint remains independent");
        }
    }

    private static void verifyRejectedTargets(Change map, Change set) {
        var wrong = CollectionApplyIncompatible.newInstance();
        wrong.getLimits().put("keep", "original");
        wrong.getLevels().add(true);
        reject(() -> map.apply(wrong));
        reject(() -> set.apply(wrong));
        check(wrong.getLimits().equals(Map.of("keep", "original")) && wrong.getLevels().equals(Set.of(true)),
                "Incompatible value types are rejected without edit");

        var full = peer();
        full.getLimits().put("extra", 8L);
        full.getLevels().addAll(List.of(1.0, 2.0));
        full.vmf().changes().addPropertyListener("limits", _change -> { throw new AssertionError("Uncommitted map edit"); });
        full.vmf().changes().addPropertyListener("levels", _change -> { throw new AssertionError("Uncommitted set edit"); });
        reject(() -> map.apply(full));
        reject(() -> set.apply(full));
        check(full.getLimits().equals(Map.of("keep", 99L, "extra", 8L)) && full.getLevels().equals(Set.of(+0.0, 1.0, 2.0)),
                "The target's independent bounds reject the complete edit");

        var peer = peer();
        try {
            map.apply(peer.asReadOnly());
            throw new AssertionError("Read-only map edit accepted.");
        } catch (UnsupportedOperationException expected) { }
        try {
            set.apply(peer.asReadOnly());
            throw new AssertionError("Read-only set edit accepted.");
        } catch (UnsupportedOperationException expected) { }
        check(peer.getLimits().equals(Map.of("keep", 99L)) && peer.getLevels().equals(Set.of(+0.0)),
                "Explicit read-only targets remain unchanged");
    }

    private static void verifyExternalPeer(Change map, Change set) {
        var peer = JoinedCollections.newInstance();
        peer.getLimits().put("keep", 99L);
        peer.getLevels().add(+0.0);
        map.apply(peer);
        set.apply(peer);
        check(peer.getLimits().get("keep").equals(20L)
                        && peer.getLimits().containsKey("optional") && peer.getLimits().get("optional") == null
                        && peer.getLevels().equals(Set.of(+0.0, -0.0, Double.NaN)),
                "An unrelated composed type in a separate generated package can receive the same edits");
    }

    private static void reject(Runnable edit) {
        try {
            edit.run();
            throw new AssertionError("Incompatible collection edit accepted.");
        } catch (IllegalArgumentException expected) { }
    }

    private static CollectionApplyPeer peer() {
        var peer = CollectionApplyPeer.newInstance();
        peer.getLimits().put("keep", 99L);
        peer.getLevels().add(+0.0);
        return peer;
    }

    private static void checkFinal(CollectionApplyPeer peer) {
        check(peer.getLimits().size() == 2 && peer.getLimits().get("keep").equals(20L)
                        && peer.getLimits().containsKey("optional") && peer.getLimits().get("optional") == null
                        && peer.getLevels().equals(Set.of(+0.0, -0.0, Double.NaN)),
                "The peer preserves explicit nulls, signed zeros and NaN membership");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
