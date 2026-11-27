package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.externalbinding.ForwardModeSamples;
import eu.mihosoft.vmf.conformance.externalbinding.ScheduledEndpoint;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import eu.mihosoft.vmf.runtime.core.journal.JournalValueCodecs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

final class SecondaryExternalConformance {
    private static final JournalBinding BINDING = new JournalBinding() {
        private final JournalValueCodecs codecs = JournalValueCodecs.standard()
                .withImmutable("probe.thread-state/v1", Thread.State.class, Thread.State::name, Thread.State::valueOf);
        @Override public String modelId() { return "secondary-enum"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject model) { return ((ScheduledEndpoint) model).getId(); }
        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new UnsupportedOperationException(); }
        @Override public JournalValueCodecs valueCodecs() { return codecs; }
    };

    private SecondaryExternalConformance() {}

    static void verify(boolean write, Path directory, ObjectMapper mapper) throws IOException {
        String prefix = write ? "java-" : "csharp-";
        var saved = directory.resolve(prefix + "secondary-enum-snapshot.json");
        var edits = directory.resolve(prefix + "secondary-enum-journal.json");
        if (write) {
            var source = ScheduledEndpoint.newBuilder().withId("scheduled").withLatest(Thread.State.NEW)
                    .withSamples(Arrays.asList(Thread.State.NEW, null)).build();
            ForwardModeSamples<Thread.State> role = source;
            var view = role.asReadOnly();
            role.setLatest(Thread.State.WAITING);
            check(view == source.asReadOnly() && view.getLatest() == Thread.State.WAITING && view.getSamples().get(1) == null
                    && source.clone().getLatest() == Thread.State.WAITING, "mapped value types remain visible through secondary roles");
            role.setLatest(Thread.State.NEW);
            mapper.writeValue(saved.toFile(), source);
            var journal = JournalRecorder.recordFrame(source, BINDING, "base", 0, changed -> {
                ForwardModeSamples<Thread.State> edited = changed;
                edited.setLatest(Thread.State.WAITING);
                edited.getSamples().add(Thread.State.RUNNABLE);
            });
            Files.writeString(edits, VMFJournalCodec.write(journal));
        }
        var checkpoint = mapper.readValue(saved.toFile(), ScheduledEndpoint.class);
        check("scheduled".equals(checkpoint.getId()) && checkpoint.getLatest() == Thread.State.NEW
                && Arrays.asList(Thread.State.NEW, null).equals(checkpoint.getSamples()), "plain snapshot keeps enum names and null slots");
        var journal = VMFJournalCodec.read(Files.readString(edits));
        var restored = JournalReplayer.replay(checkpoint, BINDING, "base", journal);
        check(journal.wireVersion() == 1 && restored.getLatest() == Thread.State.WAITING
                && Arrays.asList(Thread.State.NEW, null, Thread.State.RUNNABLE).equals(restored.getSamples())
                && checkpoint.getLatest() == Thread.State.NEW, "external enum edits replay onto a fresh model");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
