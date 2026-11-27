/*
 * Copyright 2017-2026 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 * Copyright 2017 Samuel Michaelis. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * A framework for declarative GUI programming. InComputing and Visualization in Science, 2013, 16(4),
 * 181–192. http://dx.doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmftest.journalvalues;

import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Frame;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Kind;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Operation;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Value;
import eu.mihosoft.vmf.runtime.core.journal.History;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import eu.mihosoft.vmf.runtime.core.journal.JournalValueCodecs;
import org.junit.Test;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("deprecation")
public class ExternalValueJournalTest {
    private static final UUID FIRST = UUID.fromString("b0e36ead-e4e3-4de7-bca4-b2ba2a7c6035");
    private static final UUID SECOND = UUID.fromString("7bfefa79-00d2-44b6-823a-b7c29e8d2246");
    private static final UUID THIRD = UUID.fromString("0daa4f9e-7295-4f23-8c73-f99b7d04553b");
    private static final String UUID_CODEC = "java.uuid/v1";
    private static final String REVISION_CODEC = "test.document-revision/v1";
    private static final String CHECKPOINT = "initial";
    private static final String ENTRY_ID = "entry";

    private static final JournalBinding BINDING = new ArchiveBinding(JournalValueCodecs.standard()
            .withImmutable(REVISION_CODEC, DocumentRevision.class,
                    revision -> Integer.toString(revision.number()),
                    text -> new DocumentRevision(Integer.parseInt(text))));

    @Test
    public void recordsUuidScalarsAndListsAndSeeksWithCustomImmutableValues() {
        ArchiveEntry checkpoint = initial();
        ArchiveEntry work = checkpoint.clone();
        ChangeJournal journal;
        try (JournalRecorder recorder = JournalRecorder.start(work, BINDING, CHECKPOINT, 0)) {
            var changes = work.vmf().changes();
            changes.startTransaction();
            work.setSourceId(SECOND);
            work.getRelatedIds().add(THIRD);
            work.setRevision(new DocumentRevision(2));
            changes.publishTransaction();
            changes.startTransaction();
            work.getRelatedIds().set(0, FIRST);
            work.setRevision(new DocumentRevision(3));
            changes.publishTransaction();
            journal = recorder.finish();
        }

        assertEquals(1, journal.wireVersion());
        assertEquals(2, journal.transactions().size());
        var first = journal.transactions().get(0).operations();
        assertEquals(List.of(Value.external(UUID_CODEC, SECOND.toString())), first.get(0).after());
        assertEquals(List.of(Value.external(REVISION_CODEC, "2")), first.get(2).after());

        ArchiveEntry replay = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, journal);
        assertNotSame(work, replay);
        assertEquals(SECOND, replay.getSourceId());
        assertEquals(List.of(FIRST, THIRD), replay.getRelatedIds());
        assertEquals(new DocumentRevision(3), replay.getRevision());

        History<ArchiveEntry> history = new History<>(BINDING);
        history.addSegment(CHECKPOINT, checkpoint, journal);
        ArchiveEntry middle = history.seek(1);
        assertEquals(List.of(SECOND, THIRD), middle.getRelatedIds());
        assertEquals(new DocumentRevision(2), middle.getRevision());
        middle.getRelatedIds().clear();
        assertEquals(List.of(SECOND), history.seek(0).getRelatedIds());
        assertEquals(new DocumentRevision(1), history.seek(0).getRevision());
        assertEquals(List.of(FIRST, THIRD), history.seek(2).getRelatedIds());
        assertEquals(FIRST, checkpoint.getSourceId());
    }

    @Test
    public void cannotJournalAnUnregisteredMutableExternalValue() {
        ArchiveEntry checkpoint = ArchiveEntry.newBuilder().withId(ENTRY_ID).build();
        checkpoint.setScratch(new StringBuilder("mutable"));
        assertThrows(IllegalArgumentException.class,
                () -> JournalRecorder.start(checkpoint, BINDING, CHECKPOINT, 0));
        assertFalse(checkpoint.vmf().changes().isModelVersioningEnabled());
    }

    @Test
    public void rejectsUnknownAndNoncanonicalExternalValues() {
        ArchiveEntry checkpoint = initial();
        ChangeJournal unknown = replaceUuid(Value.external("test.unknown/v1", SECOND.toString()));
        ChangeJournal noncanonical = replaceUuid(Value.external(UUID_CODEC,
                SECOND.toString().toUpperCase(Locale.ROOT)));

        IllegalArgumentException unknownError = assertThrows(IllegalArgumentException.class,
                () -> JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, unknown));
        assertTrue(unknownError.getMessage().contains("Unknown external codec"));
        IllegalArgumentException noncanonicalError = assertThrows(IllegalArgumentException.class,
                () -> JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, noncanonical));
        assertTrue(noncanonicalError.getMessage().contains("noncanonical"));
        assertEquals(FIRST, checkpoint.getSourceId());
        assertEquals(List.of(SECOND), checkpoint.getRelatedIds());
    }

    @Test
    public void checksTheCurrentBindingAndCodecRegistryAfterPublication() {
        var codecs = new AtomicReference<>(BINDING.valueCodecs());
        JournalBinding binding = new ArchiveBinding(codecs.get()) {
            @Override public String id(VObject object) {
                // A binding may produce fresh strings with the same persistent contents.
                return new String(super.id(object));
            }

            @Override public String type(VObject object) {
                return new String(super.type(object));
            }

            @Override public JournalValueCodecs valueCodecs() {
                return codecs.get();
            }
        };

        ArchiveEntry checkpoint = initial();
        ArchiveEntry work = checkpoint.clone();
        try (JournalRecorder recorder = JournalRecorder.start(work, binding, CHECKPOINT, 0)) {
            var changes = work.vmf().changes();
            changes.startTransaction();
            work.setRevision(new DocumentRevision(2));
            changes.publishTransaction();
            ChangeJournal journal = recorder.finish();
            ArchiveEntry result = JournalReplayer.replay(checkpoint, binding, CHECKPOINT, journal);
            assertEquals(new DocumentRevision(2), result.getRevision());
        }

        try (JournalRecorder recorder = JournalRecorder.start(work, binding, CHECKPOINT, 0)) {
            var changes = work.vmf().changes();
            changes.startTransaction();
            work.getRelatedIds().add(THIRD);
            changes.publishTransaction();
            codecs.set(JournalValueCodecs.standard());
            var error = assertThrows(IllegalArgumentException.class, recorder::finish);
            assertTrue(error.getMessage().contains("Unsupported journal value type"));
            assertFalse(changes.isModelVersioningEnabled());
        }
    }

    private static ArchiveEntry initial() {
        ArchiveEntry entry = ArchiveEntry.newBuilder().withId(ENTRY_ID).build();
        entry.setSourceId(FIRST);
        entry.getRelatedIds().add(SECOND);
        entry.setRevision(new DocumentRevision(1));
        return entry;
    }

    private static ChangeJournal replaceUuid(Value value) {
        Operation replace = new Operation(Kind.SET, ENTRY_ID, "sourceId", List.of(),
                List.of(Value.external(UUID_CODEC, FIRST.toString())), List.of(value), List.of());
        return new ChangeJournal(BINDING.modelId(), BINDING.modelVersion(), ENTRY_ID, CHECKPOINT,
                0, List.of(new Frame(1, List.of(replace))));
    }

    private static class ArchiveBinding implements JournalBinding {
        private final JournalValueCodecs codecs;

        ArchiveBinding(JournalValueCodecs codecs) {
            this.codecs = codecs;
        }

        @Override
        public String modelId() {
            return "test.archive";
        }

        @Override
        public int modelVersion() {
            return 1;
        }

        @Override
        public String id(VObject object) {
            return ((ArchiveEntry) object).getId();
        }

        @Override
        public String type(VObject object) {
            return "archive-entry";
        }

        @Override
        public VObject create(String type, String id) {
            if (!"archive-entry".equals(type)) {
                throw new IllegalArgumentException("Unknown archive entry type " + type);
            }
            return ArchiveEntry.newBuilder().withId(id).build();
        }

        @Override
        public JournalValueCodecs valueCodecs() {
            return codecs;
        }
    }
}
