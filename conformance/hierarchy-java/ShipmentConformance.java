package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.conformance.hierarchy.DepotShipment;
import eu.mihosoft.vmf.conformance.hierarchy.Inspection;
import eu.mihosoft.vmf.conformance.hierarchy.InspectionDeck;
import eu.mihosoft.vmf.conformance.hierarchy.Parcel;
import eu.mihosoft.vmf.conformance.hierarchy.ParcelInspection;
import eu.mihosoft.vmf.conformance.hierarchy.ParcelSelection;
import eu.mihosoft.vmf.conformance.hierarchy.ReadOnlyParcelInspection;
import eu.mihosoft.vmf.conformance.hierarchy.ReadOnlyShipment;
import eu.mihosoft.vmf.conformance.hierarchy.ReadOnlyTrackedParcel;
import eu.mihosoft.vmf.conformance.hierarchy.Shipment;
import eu.mihosoft.vmf.conformance.hierarchy.TrackedParcel;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class ShipmentConformance {
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "open-shipment"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject model) {
            if (model instanceof DepotShipment) return ((DepotShipment) model).getId();
            if (model instanceof Parcel) return ((Parcel) model).getId();
            if (model instanceof Inspection) return ((Inspection) model).getId();
            throw new IllegalArgumentException("Unexpected shipment model");
        }
        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new IllegalStateException("no allocations"); }
    };

    private ShipmentConformance() {}

    static void verify(boolean write, Path directory) throws IOException {
        var model = shipment();
        Shipment<TrackedParcel, ParcelInspection> roles = model;
        ParcelSelection<TrackedParcel> selection = roles;
        InspectionDeck<ParcelInspection> inspections = roles;
        ReadOnlyShipment<ReadOnlyTrackedParcel, ReadOnlyParcelInspection> view = model.asReadOnly();
        check(selection.getSelected() == roles.getParcels().get(0) && selection.getSelected() == selection.getHistory().get(0)
                && view.getSelected() == view.getParcels().get(0) && view.getSelected() == view.getHistory().get(0)
                && view.getInspections().get(0) == view.getLatestInspection(), "both generic owning slots project to their aliases");
        var copy = model.clone();
        check(copy.getSelected() != selection.getSelected() && copy.getParcels().get(0) == copy.getSelected()
                && copy.getLatestInspection() != inspections.getLatestInspection()
                && copy.getInspections().get(0) == copy.getLatestInspection(), "deep copy remaps each owning slot");
        var moved = inspections.getInspections().get(1);
        InspectionDeck<ParcelInspection> destination = DepotShipment.newInstance();
        destination.getInspections().add(moved);
        check(destination.getInspections().get(0) == moved && inspections.getInspections().size() == 1
                && view.getInspections().size() == 1 && roles.getParcels().size() == 1, "transfer keeps independent slots");

        var journal = write ? JournalRecorder.recordFrame(shipment(), BINDING, "initial", 30, changed -> {
            Shipment<TrackedParcel, ParcelInspection> open = changed;
            open.getInspections().move(1, 0);
            open.setLatestInspection(open.getInspections().get(0));
            open.getHistory().set(1, open.getParcels().get(0));
        }) : null;
        String prefix = write ? "java-shipment-" : "csharp-shipment-";
        for (var format : VMFDocumentFormat.values()) {
            String extension = "." + format.name().toLowerCase(Locale.ROOT);
            var checkpoint = directory.resolve(prefix + "checkpoint" + extension);
            var edits = directory.resolve(prefix + "journal" + extension);
            var mapper = mapper(format);
            if (write) {
                mapper.writeValue(checkpoint.toFile(), shipment());
                Files.writeString(edits, VMFJournalCodec.write(journal, format));
            }
            var original = mapper.readValue(checkpoint.toFile(), DepotShipment.class);
            check(original.getParcels().get(0) == original.getSelected()
                    && original.getSelected() == original.getHistory().get(0)
                    && original.getSelected() == original.getHistory().get(2), "snapshot retains repeated parcel aliases");
            var decoded = VMFJournalCodec.read(Files.readString(edits), format);
            var restored = JournalReplayer.replay(original, BINDING, "initial", decoded);
            check(decoded.wireVersion() == 1 && "follow-up".equals(restored.getInspections().get(0).getId())
                    && "report".equals(restored.getInspections().get(1).getId())
                    && restored.getInspections().get(0) == restored.getLatestInspection()
                    && restored.getParcels().get(0) == restored.getHistory().get(1), "V1 replay preserves both role edits");
            check("report".equals(original.getInspections().get(0).getId())
                    && original.getInspections().get(0) == original.getLatestInspection()
                    && original.getHistory().get(1) == null, "replay leaves original checkpoint intact");
        }
        System.out.println("Java verified open model roles with independent ownership, snapshots and V1 replay.");
    }

    private static DepotShipment shipment() {
        var parcel = TrackedParcel.newBuilder().withId("parcel").withTrackingCode("T-1").build();
        var first = ParcelInspection.newBuilder().withId("report").withCleared(true).build();
        var next = ParcelInspection.newBuilder().withId("follow-up").build();
        return DepotShipment.newBuilder().withId("depot").withParcels(parcel).withSelected(parcel)
                .withHistory(parcel, null, parcel).withInspections(first, next).withLatestInspection(first).build();
    }

    private static ObjectMapper mapper(VMFDocumentFormat format) {
        ObjectMapper mapper;
        switch (format) {
            case XML: mapper = new XmlMapper(); break;
            case YAML: mapper = new YAMLMapper(); break;
            default: mapper = new ObjectMapper();
        }
        return mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
