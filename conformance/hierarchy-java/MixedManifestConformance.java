package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.hierarchy.InstrumentedShipment;
import eu.mihosoft.vmf.conformance.hierarchy.ManifestRevision;
import eu.mihosoft.vmf.conformance.hierarchy.ManifestStorage;
import eu.mihosoft.vmf.conformance.hierarchy.Parcel;
import eu.mihosoft.vmf.conformance.hierarchy.ParcelSelection;
import eu.mihosoft.vmf.conformance.hierarchy.ReadOnlyTelemetryManifest;
import eu.mihosoft.vmf.conformance.hierarchy.ReadOnlyTrackedParcel;
import eu.mihosoft.vmf.conformance.hierarchy.TelemetryManifest;
import eu.mihosoft.vmf.conformance.hierarchy.TrackedParcel;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class MixedManifestConformance {
    private static final long UPDATED_REVISION = 9_007_199_254_740_993L;
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "mixed-manifest"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject model) {
            return model instanceof InstrumentedShipment ? ((InstrumentedShipment) model).getId() : ((Parcel) model).getId();
        }
        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new IllegalStateException("no allocations"); }
    };

    private MixedManifestConformance() {}

    static void verify(boolean write, Path directory, ObjectMapper mapper) throws IOException {
        var specimen = manifest();
        TelemetryManifest<TrackedParcel, String, Long> roles = specimen;
        ManifestStorage<TrackedParcel, String, Long> storage = roles;
        ManifestRevision<Long> revisions = storage;
        ParcelSelection<TrackedParcel> selection = roles;
        ReadOnlyTelemetryManifest<ReadOnlyTrackedParcel, String, Long> view = specimen.asReadOnly();
        revisions.setRevision(UPDATED_REVISION);
        storage.getLabels().put("lane", "east");
        check(view.getRevision() == UPDATED_REVISION && "east".equals(view.getLabels().get("lane"))
                && view.getParcels().get(0) == view.getSelected() && selection.getHistory().get(0) == selection.getSelected(),
                "mixed roles retain live typed storage and reference identity");
        String prefix = write ? "java" : "csharp";
        var saved = directory.resolve(prefix + "-mixed-manifest.json");
        var edits = directory.resolve(prefix + "-mixed-manifest-journal.json");
        if (write) {
            mapper.writeValue(saved.toFile(), manifest());
            var journal = JournalRecorder.recordFrame(manifest(), BINDING, "initial", 12, model -> {
                model.setRevision(UPDATED_REVISION);
                model.getLabels().put("lane", "east");
                model.getHistory().add(model.getParcels().get(0));
            });
            Files.writeString(edits, VMFJournalCodec.write(journal));
        }
        var original = mapper.readValue(saved.toFile(), InstrumentedShipment.class);
        check(original.getRevision() == 12L && "west".equals(original.getLabels().get("lane"))
                && original.getParcels().get(0) == original.getSelected() && original.getSelected() == original.getHistory().get(0),
                "snapshot preserves numeric and map types beside the owned reference");
        var loaded = VMFJournalCodec.read(Files.readString(edits));
        var replayed = JournalReplayer.replay(original, BINDING, "initial", loaded);
        check(loaded.wireVersion() == 1 && replayed.getRevision() == UPDATED_REVISION
                && "east".equals(replayed.getLabels().get("lane")) && original.getHistory().size() == 1
                && replayed.getParcels().get(0) != original.getParcels().get(0)
                && replayed.getHistory().size() == 2 && replayed.getHistory().get(0) == replayed.getSelected()
                && replayed.getHistory().get(1) == replayed.getSelected(), "V1 replay combines typed scalar and model edits");
        System.out.println("Java verified mixed open roles, reference snapshots and V1 replay.");
    }

    private static InstrumentedShipment manifest() {
        var parcel = TrackedParcel.newBuilder().withId("parcel").withTrackingCode("T-1").build();
        var shipment = InstrumentedShipment.newBuilder().withId("manifest").withParcels(parcel)
                .withSelected(parcel).withHistory(parcel).withRevision(12L).build();
        shipment.getLabels().put("lane", "west");
        return shipment;
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
