package eu.mihosoft.vmf.conformance.genericidentitykey;

import com.fasterxml.jackson.databind.ObjectMapper;
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

public final class GenericIdentityKeyConformance {
    private static final String CHECKPOINT = "generic-identity-key-8";
    private static final ObjectMapper REFERENCES = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "conformance.generic-identity-key"; }
        @Override public int modelVersion() { return 1; }

        @Override public String id(VObject value) {
            if (value instanceof Dispatch) return "dispatch";
            if (value instanceof RouteManifest) return "manifest";
            if (value instanceof Location) return ((Location) value).getCode();
            if (value instanceof Shipment) return ((Shipment) value).getCode();
            throw new IllegalArgumentException("Unknown identity-key model object");
        }

        @Override public String type(VObject value) { return value.vmf().reflect().type().getName(); }

        @Override public VObject create(String type, String id) {
            throw new IllegalArgumentException("The journal does not introduce objects");
        }
    };

    private GenericIdentityKeyConformance() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !("write".equals(args[0]) || "read".equals(args[0]))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        var directory = Path.of(args[1]);
        if ("write".equals(args[0])) {
            verifyLive();
            Files.createDirectories(directory);
            write(directory);
            verify(directory, "java");
            System.out.println("Java verified model-identity keys through a concrete inherited generic specialization.");
        } else {
            verify(directory, "csharp");
            System.out.println("Java restored the C# identity-key snapshot and replayed all three journal formats.");
        }
    }

    private static Dispatch specimen() {
        var first = location("first");
        var second = location("second");
        var spare = location("spare");
        var manifest = RouteManifest.newBuilder()
                .withEntries(shipment("alpha", first), shipment("beta", second)).build();
        return Dispatch.newBuilder().withManifest(manifest).withTargets(first, second, spare).build();
    }

    private static Location location(String code) {
        return Location.newBuilder().withCode(code).withName("Same appearance").build();
    }

    private static Shipment shipment(String code, Location target) {
        return Shipment.newBuilder().withCode(code).withLocation(target).build();
    }

    private static void verifyLive() throws IOException {
        var root = specimen();
        Routes<Shipment> opened = root.getManifest();
        ReadOnlyRoutes<ReadOnlyShipment> view = root.getManifest().asReadOnly();
        var first = root.getTargets().get(0);
        var second = root.getTargets().get(1);
        var spare = root.getTargets().get(2);
        Shipment alpha = opened.findEntriesByLocation(first).orElseThrow();
        check(first != second && first.equals(second) && alpha == opened.getEntries().get(0)
                && view.findEntriesByLocation(first.asReadOnly()).orElseThrow() == alpha.asReadOnly()
                && opened.findEntriesByLocation(spare).isEmpty(), "Mutable and read-only lookups use identity with a closed Location key");
        check(root.getManifest().vmf().reflect().propertyByName("entries").orElseThrow().getKeyPropertyName()
                .orElseThrow().equals("location"), "The index uses the logical key name");
        reject(() -> alpha.setLocation(second));
        reject(() -> alpha.setLocation(null));
        reject(() -> RouteManifest.newBuilder().withEntries(shipment("invalid-a", second),
                shipment("invalid-b", second)).build());
        check(alpha.getLocation() == first && opened.findEntriesByLocation(first).orElseThrow() == alpha,
                "Duplicate and null key edits are atomic");
        alpha.setLocation(spare);
        check(opened.findEntriesByLocation(first).isEmpty() && opened.findEntriesByLocation(spare).orElseThrow() == alpha,
                "An equal but distinct key can replace the previous key");

        var code = PermitCode.newBuilder().withLabel("same label").build();
        var other = PermitCode.newBuilder().withLabel("same label").build();
        var gate = Gate.newBuilder().withPermits(Permit.newBuilder().withProof(code).build(),
                Permit.newBuilder().withProof(other).build()).build();
        check(code != other && code.equals(other) && code.asReadOnly() == code
                && gate.asReadOnly().findPermitsByProof(code).orElseThrow() == gate.getPermits().get(0).asReadOnly(),
                "An immutable key is its own projection and retains separate identity");
        var copy = REFERENCES.readValue(REFERENCES.writeValueAsBytes(gate), Gate.class);
        check(copy.asReadOnly().findPermitsByProof(copy.getPermits().get(0).getProof()).orElseThrow()
                == copy.getPermits().get(0).asReadOnly(), "Snapshot-restored immutable key remains directly selectable");
    }

    private static void write(Path directory) throws IOException {
        var root = specimen();
        var alpha = root.getManifest().getEntries().get(0);
        REFERENCES.writeValue(path(directory, "java", "checkpoint.json").toFile(), root);
        var journal = JournalRecorder.recordFrame(root, BINDING, CHECKPOINT, 8, edit -> {
            alpha.setLocation(root.getTargets().get(2));
            root.getManifest().getEntries().move(1, 0);
        });
        for (var format : VMFDocumentFormat.values()) {
            Files.writeString(path(directory, "java", journalFile(format)), VMFJournalCodec.write(journal, format));
        }
    }

    private static void verify(Path directory, String producer) throws IOException {
        String json = Files.readString(path(directory, producer, "checkpoint.json"));
        var manifest = REFERENCES.readTree(json).path("manifest");
        var entries = manifest.path("shipments");
        check(entries.isArray() && !manifest.has("entries") && entries.get(0).has("destination")
                && json.indexOf("\"shipments\"") < json.indexOf("\"targets\""), "Snapshot forwards the renamed key reference");
        for (var format : VMFDocumentFormat.values()) {
            var checkpoint = REFERENCES.readValue(json, Dispatch.class);
            var original = checkpoint.getTargets().get(0);
            check(checkpoint.getManifest().findEntriesByLocation(original).orElseThrow().getCode().equals("alpha"),
                    "Forward reference is available before keyed attachment");
            var journal = VMFJournalCodec.read(Files.readString(path(directory, producer, journalFile(format))), format);
            var frame = journal.transactions().get(0);
            check(journal.wireVersion() == 1 && frame.operations().get(0).target().equals("alpha")
                    && frame.operations().get(0).property().equals("location"), "Journal retains the entry's stable ID and logical key name");
            var replay = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, journal);
            var replayed = replay.getManifest();
            check(replayed.findEntriesByLocation(replay.getTargets().get(0)).isEmpty()
                    && replayed.findEntriesByLocation(replay.getTargets().get(2)).orElseThrow() == replayed.getEntries().get(1)
                    && replayed.getEntries().get(1).getCode().equals("alpha")
                    && checkpoint.getManifest().findEntriesByLocation(original).isPresent(),
                    "Replayed key points to the existing destination ID and leaves the checkpoint independent");
        }
    }

    private static String journalFile(VMFDocumentFormat format) { return "journal." + format.name().toLowerCase(Locale.ROOT); }

    private static Path path(Path dir, String producer, String suffix) { return dir.resolve(producer + "-" + suffix); }

    private static void reject(Runnable edit) {
        try { edit.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid identity key edit to fail");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
