package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.genericscalars.MeasurementChannel;
import eu.mihosoft.vmf.conformance.genericscalars.OpenScalarChannel;
import eu.mihosoft.vmf.conformance.genericscalars.PayloadChannel;
import eu.mihosoft.vmf.conformance.genericscalars.ReadOnlyOpenScalarChannel;
import eu.mihosoft.vmf.conformance.genericscalars.RevisionChannel;
import eu.mihosoft.vmf.conformance.genericscalars.TextChannel;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class OpenScalarChannelConformance {
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "open-scalar-channel"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) { return "channel"; }
        @Override public String type(VObject object) { return object.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new IllegalStateException("no allocations"); }
    };

    private OpenScalarChannelConformance() { }

    static void verify(boolean write, Path directory) throws IOException {
        var model = baseline();
        OpenScalarChannel<String, Double, Long> roles = model;
        ReadOnlyOpenScalarChannel<String, Double, Long> view = roles.asReadOnly();
        PayloadChannel<Double> payloads = roles;
        RevisionChannel<Long> revisions = roles;
        roles.setChannelKey("next");
        payloads.getNamedPayloads().put("secondary", 3.5);
        revisions.setRevision(6L);
        var copy = model.clone();
        check(view.getChannelKey().equals("next") && view.getNamedPayloads().get("secondary") == 3.5
                && view.getRevision() == 6L && copy.getNamedPayloads().get("secondary") == 3.5
                && copy.getNamedPayloads() != model.getNamedPayloads(), "typed live roles and independent copy");
        OpenScalarChannel<Integer, String, Integer> text = TextChannel.newBuilder()
                .withChannelKey(20).withLatestPayload("text").withRevision(3).build();
        check(text.getChannelKey() == 20 && text.getLatestPayload().equals("text") && text.getRevision() == 3,
                "the other specialization resolves its own argument types");

        var journal = write ? JournalRecorder.recordFrame(baseline(), BINDING, "initial", 4, changed -> {
            OpenScalarChannel<String, Double, Long> channel = changed;
            channel.getPreviousKeys().set(1, "next");
            channel.getNamedPayloads().put("secondary", 3.5);
            channel.setRevision(6L);
        }) : null;
        String prefix = write ? "java-open-channel-" : "csharp-open-channel-";
        for (var format : VMFDocumentFormat.values()) {
            String extension = "." + format.name().toLowerCase(Locale.ROOT);
            var plain = directory.resolve(prefix + "plain" + extension);
            var checkpoint = directory.resolve(prefix + "checkpoint" + extension);
            var edits = directory.resolve(prefix + "journal" + extension);
            var plainMapper = SecondaryScalarConformance.mapper(format, false);
            var referenceMapper = SecondaryScalarConformance.mapper(format, true);
            if (write) {
                Files.writeString(plain, plainMapper.writeValueAsString(baseline()));
                Files.writeString(checkpoint, referenceMapper.writeValueAsString(baseline()));
                Files.writeString(edits, VMFJournalCodec.write(journal, format));
            }
            verifyInitial(plainMapper.readValue(plain.toFile(), MeasurementChannel.class));
            var fresh = referenceMapper.readValue(checkpoint.toFile(), MeasurementChannel.class);
            var decoded = VMFJournalCodec.read(Files.readString(edits), format);
            var restored = JournalReplayer.replay(fresh, BINDING, "initial", decoded);
            check(decoded.wireVersion() == 1 && List.of("previous", "next").equals(restored.getPreviousKeys())
                    && restored.getNamedPayloads().get("secondary") == 3.5 && restored.getRevision() == 6L,
                    "V1 replay preserves edits from all three scalar roles");
            verifyInitial(fresh);
        }
        System.out.println("Java verified open scalar roles, typed snapshots, and V1 interchange.");
    }

    private static MeasurementChannel baseline() {
        return MeasurementChannel.newBuilder().withChannelKey("current").withPreviousKeys(Arrays.asList("previous", null))
                .withLatestPayload(12.5).withRevision(5L).build();
    }

    private static void verifyInitial(MeasurementChannel channel) {
        check(channel.getChannelKey().equals("current") && Arrays.asList("previous", null).equals(channel.getPreviousKeys())
                && channel.getLatestPayload() == 12.5 && channel.getRevision() == 5L && channel.getNamedPayloads().isEmpty(),
                "initial checkpoint and scalar presence");
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
