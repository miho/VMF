package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.externalbinding.GenericModeProfile;
import eu.mihosoft.vmf.conformance.externalbinding.GenericThreadProfile;
import eu.mihosoft.vmf.conformance.externalbinding.ModeSamples;
import eu.mihosoft.vmf.conformance.externalbinding.ReadOnlyModeSamples;
import eu.mihosoft.vmf.conformance.externalbinding.host.DispatchMode;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;
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
import java.util.Locale;

final class GenericEnumConformance {
    private static final JournalBinding BINDING = new JournalBinding() {
        private final JournalValueCodecs codecs = JournalValueCodecs.standard()
                .withImmutable("probe.generic-mode/v1", DispatchMode.class, DispatchMode::name, DispatchMode::valueOf);
        @Override public String modelId() { return "enum-generic"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject model) { return ((GenericModeProfile) model).getId(); }
        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new IllegalStateException("no model allocation"); }
        @Override public JournalValueCodecs valueCodecs() { return codecs; }
    };

    private GenericEnumConformance() { }

    static void verify(boolean write, Path directory) throws IOException {
        var baseline = GenericModeProfile.newBuilder().withId("series").withLatest(null)
                .withSamples(Arrays.asList(DispatchMode.UNDER_REVIEW, null, DispatchMode.READY)).build();

        ModeSamples<DispatchMode> typed = baseline;
        ReadOnlyModeSamples<DispatchMode> view = typed.asReadOnly();
        check(view.getLatest() == null && view.getSamples().get(0) == DispatchMode.UNDER_REVIEW, "typed view");
        var latest = baseline.vmf().reflect().propertyByName("latest").orElseThrow().getType().getDescriptor();
        var samples = baseline.vmf().reflect().propertyByName("samples").orElseThrow().getType().getDescriptor();
        var generic = GenericModeProfile.type().superTypes().get(0).getDescriptor();
        check(latest.getName().equals(DispatchMode.class.getName()) && samples.getArguments().get(0).getName().equals(DispatchMode.class.getName())
                && generic.getArguments().get(0).getName().equals(DispatchMode.class.getName()), "closed external reflection");

        var journal = !write ? null : JournalRecorder.recordFrame(baseline.vmf().content().deepCopy(), BINDING, "initial", 0, (GenericModeProfile model) -> {
            model.setLatest(DispatchMode.ARCHIVED);
            model.getSamples().set(1, DispatchMode.UNDER_REVIEW);
            model.getSamples().add(null);
        });
        String prefix = write ? "java-" : "csharp-";
        for (var format : VMFDocumentFormat.values()) {
            String extension = "." + format.name().toLowerCase(Locale.ROOT);
            for (boolean refs : new boolean[]{false, true}) {
                var mapper = ExternalBindingConformance.mapper(format, refs);
                var path = directory.resolve(prefix + "generic-mode-snapshot-" + refs + extension);
                if (write) Files.writeString(path, mapper.writeValueAsString(baseline));
                var hydrated = mapper.readValue(path.toFile(), GenericModeProfile.class);
                check(hydrated.getLatest() == null && Arrays.asList(DispatchMode.UNDER_REVIEW, null, DispatchMode.READY).equals(hydrated.getSamples()), "nullable snapshot");
            }
            Path log = directory.resolve(prefix + "generic-mode-journal" + extension);
            if (write) Files.writeString(log, VMFJournalCodec.write(journal, format));
            var edits = VMFJournalCodec.read(Files.readString(log), format);
            check(edits.wireVersion() == 1, "Version 1 journal for bound enum values");
            var restored = JournalReplayer.replay(baseline, BINDING, "initial", edits);
            check(restored.getLatest() == DispatchMode.ARCHIVED && restored.getSamples().size() == 4
                    && restored.getSamples().get(1) == DispatchMode.UNDER_REVIEW && restored.getSamples().get(3) == null
                    && baseline.getLatest() == null && baseline.getSamples().get(1) == null, "replay into a fresh model");
        }
        System.out.println("Verified " + prefix + " enum bound snapshots, reflection, and external value journal replay");
        verifyNested(write, directory, prefix);
    }

    private static void verifyNested(boolean write, Path directory, String prefix) throws IOException {
        var model = GenericThreadProfile.newBuilder().withLatest(Thread.State.RUNNABLE).withSamples(Thread.State.NEW, null).build();
        var property = model.vmf().reflect().propertyByName("latest").orElseThrow();
        check(property.getType().getDescriptor().getName().equals("java.lang.Thread.State") && property.getType().isEnumType(),
                "nested Java lookup retains the canonical model descriptor");
        var io = ExternalBindingConformance.mapper(VMFDocumentFormat.JSON, false);
        var schema = io.readTree(VMFJsonSchemaGenerator.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .generateSchemaAsString(GenericThreadProfile.class));
        var fields = schema.get("properties");
        check(fields.get("latest").get("enum").size() == Thread.State.values().length
                        && fields.get("samples").at("/items/anyOf/0/enum").size() == Thread.State.values().length
                        && fields.get("samples").at("/items/anyOf/1/type").asText().equals("null"),
                "native schema lists nested enum names and nullable list slots");

        var codecs = JournalValueCodecs.standard()
                .withImmutable("probe.thread-state/v1", Thread.State.class, Thread.State::name, Thread.State::valueOf);
        JournalBinding binding = new JournalBinding() {
            @Override public String modelId() { return "nested-external"; }
            @Override public int modelVersion() { return 1; }
            @Override public String id(VObject model) { return "profile"; }
            @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
            @Override public VObject create(String type, String id) { throw new UnsupportedOperationException(); }
            @Override public JournalValueCodecs valueCodecs() { return codecs; }
        };
        Path snapshot = directory.resolve(prefix + "nested-enum-snapshot.json");
        Path changes = directory.resolve(prefix + "nested-enum-journal.json");
        if (write) {
            Files.writeString(snapshot, io.writeValueAsString(model));
            var journal = JournalRecorder.recordFrame(model.vmf().content().deepCopy(), binding, "base", 0,
                    (GenericThreadProfile working) -> {
                        working.setLatest(Thread.State.TERMINATED);
                        working.getSamples().add(Thread.State.BLOCKED);
                    });
            Files.writeString(changes, VMFJournalCodec.write(journal));
        }
        var baseline = io.readValue(snapshot.toFile(), GenericThreadProfile.class);
        check(baseline.getLatest() == Thread.State.RUNNABLE && Arrays.asList(Thread.State.NEW, null).equals(baseline.getSamples()),
                "nested alias snapshots preserve values and null slots");
        var journal = VMFJournalCodec.read(Files.readString(changes));
        var restored = JournalReplayer.replay(baseline, binding, "base", journal);
        check(journal.wireVersion() == 1 && restored.getLatest() == Thread.State.TERMINATED
                && Arrays.asList(Thread.State.NEW, null, Thread.State.BLOCKED).equals(restored.getSamples()), "nested enum journal replay");
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
