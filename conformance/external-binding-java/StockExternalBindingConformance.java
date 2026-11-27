package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.History;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import eu.mihosoft.vmf.runtime.core.journal.JournalValueCodecs;
import eu.mihosoft.vmftest.genericseries.StateSeries;
import eu.mihosoft.vmftest.journalvalues.ArchiveEntry;
import eu.mihosoft.vmftest.journalvalues.DocumentRevision;
import eu.mihosoft.vmftest.readonlyexternallist.Entry;
import eu.mihosoft.vmftest.readonlyexternallist.Sample;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

final class StockExternalBindingConformance {
    private static final UUID FIRST = UUID.fromString("b0e36ead-e4e3-4de7-bca4-b2ba2a7c6035");
    private static final UUID SECOND = UUID.fromString("7bfefa79-00d2-44b6-823a-b7c29e8d2246");
    private static final UUID THIRD = UUID.fromString("0daa4f9e-7295-4f23-8c73-f99b7d04553b");
    private static final JournalBinding ARCHIVE = new JournalBinding() {
        private final JournalValueCodecs codecs = JournalValueCodecs.standard()
                .withImmutable("test.document-revision/v1", DocumentRevision.class,
                        revision -> Integer.toString(revision.number()), text -> new DocumentRevision(Integer.parseInt(text)));

        @Override public String modelId() { return "test.archive"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) { return ((ArchiveEntry) object).getId(); }
        @Override public String type(VObject object) { return "archive-entry"; }
        @Override public VObject create(String type, String id) {
            if (!"archive-entry".equals(type)) throw new IllegalArgumentException("Unknown archive type");
            return ArchiveEntry.newBuilder().withId(id).build();
        }
        @Override public JournalValueCodecs valueCodecs() { return codecs; }
    };

    private StockExternalBindingConformance() {}

    static void verify(boolean write, Path exchange, String producer) throws IOException {
        Sample buffers = buffers();
        var states = StateSeries.newBuilder().withLatest(Thread.State.NEW)
                .withSamples(Thread.State.BLOCKED, null, Thread.State.RUNNABLE).build();
        ChangeJournal archive = write ? recordArchive() : null;
        if (write) verifyInMemoryHistory(archive);
        for (var format : VMFDocumentFormat.values()) {
            String extension = "." + format.name().toLowerCase(Locale.ROOT);
            var snapshot = exchange.resolve(producer + "stock-external-list" + extension);
            var mapper = mapper(format);
            if (write) Files.writeString(snapshot, mapper.writeValueAsString(buffers));
            verifySnapshot(mapper.readValue(snapshot.toFile(), Sample.class));

            var stateSeries = exchange.resolve(producer + "stock-state-series" + extension);
            if (write) Files.writeString(stateSeries, mapper.writeValueAsString(states));
            verifyStates(mapper.readValue(stateSeries.toFile(), StateSeries.class));

            var journal = exchange.resolve(producer + "stock-archive-journal" + extension);
            if (write) Files.writeString(journal, VMFJournalCodec.write(archive, format));
            verifyArchive(VMFJournalCodec.read(Files.readString(journal), format));
        }
        var invalid = baseline();
        invalid.setScratch(new StringBuilder("mutable"));
        try (var rejected = JournalRecorder.start(invalid, ARCHIVE, "initial", 0)) {
            throw new AssertionError("An unregistered mutable host value must not become a portable journal value");
        } catch (IllegalArgumentException expected) {
            check(!invalid.vmf().changes().isModelVersioningEnabled(), "Rejected capture leaves recording inactive");
        }
    }

    private static ObjectMapper mapper(VMFDocumentFormat format) {
        ObjectMapper mapper;
        switch (format) {
            case XML: mapper = new XmlMapper(); break;
            case YAML: mapper = new YAMLMapper(); break;
            default: mapper = new ObjectMapper(); break;
        }
        return mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(true));
    }

    private static Sample buffers() {
        var text = new StringBuilder("first");
        var model = Sample.newBuilder().withLabels("label").withBuffers(text, null)
                .withEntries(Entry.newBuilder().withName("owned").build()).build();
        var readOnly = model.asReadOnly();
        check(readOnly.getBuffers().get(0) == text, "External element identity remains live");
        text.append(" & changed");
        try {
            readOnly.getBuffers().add(new StringBuilder("forbidden"));
            throw new AssertionError("The read-only buffer list cannot be mutated");
        } catch (UnsupportedOperationException expected) {
            check(readOnly.getBuffers().size() == 2, "Rejected edit preserves both list slots");
        }
        return model;
    }

    private static void verifySnapshot(Sample model) {
        check(model.getLabels().size() == 1 && model.getLabels().get(0).equals("label"), "Stock label list survives");
        check(model.getBuffers().size() == 2 && model.getBuffers().get(0).toString().equals("first & changed")
                && model.getBuffers().get(1) == null, "Snapshot preserves the host text and explicit null slot");
        check(model.getEntries().size() == 1 && model.getEntries().get(0).getName().equals("owned"),
                "Model containment retains its original shape beside the external list");
    }

    private static void verifyStates(StateSeries model) {
        var readOnly = model.asReadOnly();
        check(readOnly.getLatest() == Thread.State.NEW
                && readOnly.getSamples().equals(Arrays.asList(Thread.State.BLOCKED, null, Thread.State.RUNNABLE)),
                "The stock closed generic series keeps its nested enum and null position");
        var reflected = model.vmf().reflect();
        check(reflected.propertyByName("latest").orElseThrow().getType().getName().equals(Thread.State.class.getName())
                && reflected.propertyByName("samples").orElseThrow().getType().getElementTypeName().orElseThrow()
                        .equals(Thread.State.class.getName()),
                "Java reflection keeps its loadable binary name");
    }

    private static ArchiveEntry baseline() {
        return ArchiveEntry.newBuilder().withId("entry").withSourceId(FIRST)
                .withRelatedIds(SECOND).withRevision(new DocumentRevision(1)).build();
    }

    private static ChangeJournal recordArchive() {
        var working = baseline();
        try (var recorder = JournalRecorder.start(working, ARCHIVE, "initial", 0)) {
            working.setSourceId(SECOND);
            working.getRelatedIds().add(THIRD);
            working.setRevision(new DocumentRevision(2));
            recorder.publishFrame().orElseThrow();

            working.getRelatedIds().set(0, FIRST);
            working.setRevision(new DocumentRevision(3));
            return recorder.finish();
        }
    }

    private static void verifyArchive(ChangeJournal journal) {
        check(journal.wireVersion() == 1 && journal.transactions().size() == 2, "The stock journal retains both preview V1 frames");
        var first = journal.transactions().get(0).operations();
        check(first.get(0).after().equals(java.util.List.of(ChangeJournal.Value.external("java.uuid/v1", SECOND.toString())))
                && first.get(2).after().equals(java.util.List.of(ChangeJournal.Value.external("test.document-revision/v1", "2"))),
                "The UUID and DocumentRevision payloads retain their exact stock codec IDs and canonical text");
        var model = JournalReplayer.replay(baseline(), ARCHIVE, "initial", journal);
        check(model.getSourceId().equals(SECOND) && model.getRelatedIds().equals(java.util.List.of(FIRST, THIRD))
                && model.getRevision().equals(new DocumentRevision(3)), "The stock archive journal replays on a fresh checkpoint");
    }

    private static void verifyInMemoryHistory(ChangeJournal journal) {
        var checkpoint = baseline();
        var history = new History<ArchiveEntry>(ARCHIVE);
        history.addSegment("initial", checkpoint, journal);
        var original = history.seek(0);
        var after = history.seek(2);
        check(original != checkpoint && original.getRevision() == checkpoint.getRevision()
                && checkpoint.getRevision().number() == 1 && after.getRevision().number() == 3,
                "In-memory history seeks using the journal binding and preserves the original value");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
