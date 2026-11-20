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
 * M. Hoffer, C. Poliwoda, & W. Wuttke. (2013). Visual reflection library:
 * A framework for declarative GUI programming. InComputing and Visualization in Science, 2013, 16(4),
 * 181–192. http://dx.doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmftest.journalvalues.impl;

import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import eu.mihosoft.vmftest.journalvalues.ArchiveEntry;
import org.junit.Test;
import vjavax.observer.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class VerifiedFrameConvenienceTest {
    private static final String CHECKPOINT = "initial";
    private static final long SEQUENCE = 3;
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() {
            return "test.archive";
        }

        @Override public int modelVersion() {
            return 1;
        }

        @Override public String id(VObject object) {
            return ((ArchiveEntry) object).getId();
        }

        @Override public String type(VObject object) {
            return "archive-entry";
        }

        @Override public VObject create(String type, String id) {
            return ArchiveEntry.newBuilder().withId(id).build();
        }
    };

    @Test
    public void rewritesAndRestoresAValueInOrderAndCallsTheBodyOnce() {
        var working = workingCopy();
        var calls = new AtomicInteger();
        var journal = record(working, model -> {
            calls.incrementAndGet();
            model.setSourceId(SECOND);
            model.setSourceId(FIRST);
            model.getRelatedIds().add(SECOND);
        });

        assertEquals(1, calls.get());
        assertEquals(1, journal.transactions().size());
        var frame = journal.transactions().get(0);
        assertEquals(SEQUENCE + 1, frame.sequence());
        assertEquals(3, frame.operations().size());
        assertEquals(SECOND.toString(), frame.operations().get(0).after().get(0).text());
        assertEquals(FIRST.toString(), frame.operations().get(1).after().get(0).text());
        assertEquals(1, working.vmf().changes().transactions().size());
        assertFalse(working.vmf().changes().isModelVersioningEnabled());

        var checkpoint = workingCopy();
        var replayed = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, journal);
        assertEquals(FIRST, replayed.getSourceId());
        assertEquals(List.of(FIRST, SECOND), replayed.getRelatedIds());
        assertEquals(List.of(FIRST), checkpoint.getRelatedIds());
        assertEquals(1, calls.get());
    }

    @Test
    public void noEditsReturnAnEmptyJournal() {
        var working = workingCopy();
        var journal = record(working, model -> { });
        assertEquals(SEQUENCE, journal.baseSequence());
        assertTrue(journal.transactions().isEmpty());
        assertTrue(working.vmf().changes().transactions().isEmpty());
        assertFalse(working.vmf().changes().isModelVersioningEnabled());
    }

    @Test
    public void throwingBodyLeavesLiveEditsButSkipsPendingPublication() {
        var working = workingCopy();
        var changes = working.vmf().changes();
        var published = new AtomicInteger();
        var observer = changes.transactions().addChangeListener(event -> {
            if (event.wasAdded()) published.incrementAndGet();
        });
        var failure = new AssertionError("edit failed");
        try {
            var actual = assertThrows(AssertionError.class, () -> record(working, model -> {
                model.setSourceId(SECOND);
                throw failure;
            }));
            assertSame(failure, actual);
            assertEquals(SECOND, working.getSourceId());
            assertEquals(0, published.get());
            assertTrue(changes.transactions().isEmpty());
            assertFalse(changes.isModelVersioningEnabled());

            assertEquals(1, record(working, model -> model.setSourceId(FIRST)).transactions().size());
            assertEquals(1, published.get());
        } finally {
            observer.unsubscribe();
        }
    }

    @Test
    public void closingAfterFailedEditsPreservesLiveChangesAndPreviouslyPublishedGroups() {
        var working = workingCopy();
        var changes = working.vmf().changes();
        var published = new AtomicInteger();
        var observer = changes.transactions().addChangeListener(event -> {
            if (event.wasAdded()) published.incrementAndGet();
        });
        var failure = new AssertionError("edit failed");
        try {
            var actual = assertThrows(AssertionError.class, () -> {
                try (var recorder = start(working)) {
                    working.setSourceId(SECOND);
                    assertTrue(recorder.publishFrame().isPresent());
                    working.getRelatedIds().add(SECOND);
                    throw failure;
                }
            });
            assertSame(failure, actual);
            assertEquals(SECOND, working.getSourceId());
            assertEquals(List.of(FIRST, SECOND), working.getRelatedIds());
            assertEquals(2, changes.all().size());
            assertEquals(1, published.get());
            assertEquals(1, changes.transactions().size());
            assertEquals("sourceId", changes.transactions().get(0).changes().get(0).propertyName());
            assertEquals(1, changes.transactions().get(0).changes().size());
            assertFalse(changes.isModelVersioningEnabled());
        } finally {
            observer.unsubscribe();
        }
    }

    @Test
    public void finishPublishesAndVerifiesThePendingGroup() {
        var working = workingCopy();
        var changes = working.vmf().changes();
        ChangeJournal journal;
        try (var recorder = start(working)) {
            working.setSourceId(SECOND);
            assertTrue(recorder.publishFrame().isPresent());
            working.getRelatedIds().add(SECOND);
            assertEquals(1, changes.transactions().size());

            journal = recorder.finish();
        }

        assertEquals(2, changes.transactions().size());
        assertEquals(2, journal.transactions().size());
        assertFalse(changes.isModelVersioningEnabled());
        var replayed = JournalReplayer.replay(workingCopy(), BINDING, CHECKPOINT, journal);
        assertEquals(SECOND, replayed.getSourceId());
        assertEquals(List.of(FIRST, SECOND), replayed.getRelatedIds());
    }

    @Test
    public void rejectsAnUnobservedEditFromALaterPublicationListener() {
        var working = workingCopy();
        var changes = working.vmf().changes();
        var backing = ((ArchiveEntryImpl) working).__vmf_get_prop_relatedIds__vmf_RawList();
        List<Subscription> listeners = new ArrayList<>();
        try {
            var failure = assertThrows(IllegalStateException.class, () -> record(working, model -> {
                listeners.add(changes.transactions().addChangeListener(event -> {
                    if (event.wasAdded()) backing.add(SECOND);
                }));
                model.setSourceId(SECOND);
            }));
            assertTrue(failure.getMessage().contains("could not reproduce the model state"));
            assertEquals(List.of(FIRST, SECOND), working.getRelatedIds());
            assertFalse(changes.isModelVersioningEnabled());
        } finally {
            listeners.forEach(Subscription::unsubscribe);
        }
    }

    @Test
    public void rejectsANestedFrameOnTheSameModel() {
        var working = workingCopy();
        var calls = new AtomicInteger();
        var journal = record(working, model -> {
            assertThrows(IllegalStateException.class, () -> record(model, nested -> calls.incrementAndGet()));
            model.setSourceId(SECOND);
        });
        assertEquals(1, journal.transactions().size());
        assertEquals(0, calls.get());
        assertFalse(working.vmf().changes().isModelVersioningEnabled());
    }

    @Test
    public void rejectsExplicitPublicationInsideTheEdit() {
        var working = workingCopy();
        var failure = assertThrows(IllegalStateException.class, () -> record(working, model -> {
            model.setSourceId(SECOND);
            model.vmf().changes().publishTransaction();
        }));
        assertTrue(failure.getMessage().contains("published a transaction"));
        assertFalse(working.vmf().changes().isModelVersioningEnabled());
    }

    @Test
    public void clearingCapturedHistoryThenMakingARawEditCannotReturnAJournal() {
        var working = workingCopy();
        var changes = working.vmf().changes();
        var backing = ((ArchiveEntryImpl) working).__vmf_get_prop_relatedIds__vmf_RawList();

        var failure = assertThrows(IllegalStateException.class, () -> record(working, model -> {
            model.setSourceId(SECOND);
            changes.clear();
            backing.add(SECOND);
        }));
        assertEquals("Native change history was cleared during recording", failure.getCause().getMessage());
        assertEquals(List.of(FIRST, SECOND), working.getRelatedIds());
        assertTrue(changes.transactions().isEmpty());
        assertFalse(changes.isModelVersioningEnabled());
    }

    @Test
    public void reentrantPropertyWritesReplayInCausalOrder() {
        var working = workingCopy();
        var changes = working.vmf().changes();
        var listener = changes.addListener(change -> {
            if (change.propertyName().equals("sourceId")
                    && SECOND.equals(change.propertyChange().orElseThrow().newValue())) {
                working.setSourceId(FIRST);
            }
        }, false);

        try {
            var journal = record(working, model -> model.setSourceId(SECOND));
            var operations = journal.transactions().get(0).operations();
            assertEquals(2, operations.size());
            assertEquals(SECOND.toString(), operations.get(0).after().get(0).text());
            assertEquals(FIRST.toString(), operations.get(1).after().get(0).text());
            assertEquals(FIRST, JournalReplayer.replay(workingCopy(), BINDING, CHECKPOINT, journal).getSourceId());
            assertFalse(changes.isModelVersioningEnabled());
        } finally {
            listener.unsubscribe();
        }
    }

    @Test
    public void reentrantNativeHistoryObserverLeavesPortableOperationsInOrder() {
        var working = workingCopy();
        var changes = working.vmf().changes();
        var listener = changes.all().addChangeListener(event -> {
            for (var change : event.added().elements()) {
                if (change.propertyName().equals("sourceId")
                        && SECOND.equals(change.propertyChange().orElseThrow().newValue())) {
                    working.setSourceId(FIRST);
                }
            }
        });

        try {
            var journal = record(working, model -> model.setSourceId(SECOND));
            var operations = journal.transactions().get(0).operations();
            assertEquals(2, operations.size());
            assertEquals(SECOND.toString(), operations.get(0).after().get(0).text());
            assertEquals(FIRST.toString(), operations.get(1).after().get(0).text());
            assertEquals(FIRST, JournalReplayer.replay(workingCopy(), BINDING, CHECKPOINT, journal).getSourceId());
        } finally {
            listener.unsubscribe();
        }
    }

    @Test
    public void openRecorderReturnsEachFrameAndFinishesWithTheWholeJournal() {
        var working = workingCopy();
        ChangeJournal journal;
        ChangeJournal.Frame first;
        ChangeJournal.Frame second;
        try (var recorder = start(working)) {
            assertTrue(recorder.publishFrame().isEmpty());
            working.setSourceId(SECOND);
            working.setSourceId(FIRST);
            first = recorder.publishFrame().orElseThrow();
            assertEquals(SEQUENCE + 1, first.sequence());
            assertEquals(2, first.operations().size());
            assertEquals(SECOND.toString(), first.operations().get(0).after().get(0).text());

            working.getRelatedIds().add(SECOND);
            second = recorder.publishFrame().orElseThrow();
            assertEquals(SEQUENCE + 2, second.sequence());
            assertEquals(1, second.operations().size());
            assertTrue(recorder.publishFrame().isEmpty());
            assertTrue(working.vmf().changes().isModelVersioningEnabled());
            journal = recorder.finish();
        }

        assertEquals(List.of(first, second), journal.transactions());
        var prefix = new ChangeJournal(journal.model(), journal.modelVersion(), journal.root(),
                journal.checkpoint(), journal.baseSequence(), List.of(first));
        var prefixState = JournalReplayer.replay(workingCopy(), BINDING, CHECKPOINT, prefix);
        assertEquals(FIRST, prefixState.getSourceId());
        assertEquals(List.of(FIRST), prefixState.getRelatedIds());
        var complete = JournalReplayer.replay(workingCopy(), BINDING, CHECKPOINT, journal);
        assertEquals(List.of(FIRST, SECOND), complete.getRelatedIds());
        assertFalse(working.vmf().changes().isModelVersioningEnabled());
    }

    @Test
    public void lateObservedEditsAbortAndAreNotPublishedAsAnotherGroup() {
        var working = workingCopy();
        var changes = working.vmf().changes();
        try (var recorder = start(working)) {
            var listener = changes.transactions().addChangeListener(event -> {
                if (event.wasAdded()) working.getRelatedIds().add(SECOND);
            });
            try {
                working.setSourceId(SECOND);
                var failure = assertThrows(IllegalStateException.class, recorder::publishFrame);
                assertTrue(failure.getMessage().contains("edited during frame publication"));
                assertEquals(List.of(FIRST, SECOND), working.getRelatedIds());
                assertEquals(1, changes.transactions().size());
                assertFalse(changes.isModelVersioningEnabled());
                assertThrows(IllegalStateException.class, recorder::finish);
            } finally {
                listener.unsubscribe();
            }
        }
    }

    @Test
    public void lateUnobservedEditsAreRejectedEvenAfterBoundaryVerification() {
        var working = workingCopy();
        var changes = working.vmf().changes();
        var backing = ((ArchiveEntryImpl) working).__vmf_get_prop_relatedIds__vmf_RawList();
        try (var recorder = start(working)) {
            var listener = changes.transactions().addChangeListener(event -> {
                if (event.wasAdded()) backing.add(SECOND);
            });
            try {
                working.setSourceId(SECOND);
                var failure = assertThrows(IllegalStateException.class, recorder::publishFrame);
                assertTrue(failure.getMessage().contains("could not reproduce the model state after publication"));
                assertEquals(List.of(FIRST, SECOND), working.getRelatedIds());
                assertFalse(changes.isModelVersioningEnabled());
            } finally {
                listener.unsubscribe();
            }
        }
    }

    @Test
    public void aLateRestoreCannotHideBoundaryDivergence() {
        var working = workingCopy();
        var changes = working.vmf().changes();
        var backing = ((ArchiveEntryImpl) working).__vmf_get_prop_relatedIds__vmf_RawList();
        try (var recorder = start(working)) {
            var listener = changes.transactions().addChangeListener(event -> {
                if (event.wasAdded()) backing.remove(SECOND);
            });
            try {
                working.setSourceId(SECOND);
                backing.add(SECOND);
                var failure = assertThrows(IllegalStateException.class, recorder::publishFrame);
                assertTrue(failure.getCause().getMessage().contains("Published transaction state could not be reproduced"));
                assertEquals(List.of(FIRST), working.getRelatedIds());
                assertFalse(changes.isModelVersioningEnabled());
            } finally {
                listener.unsubscribe();
            }
        }
    }

    @Test
    public void throwingPublicationObserverClosesSessionAndPreservesItsError() {
        var working = workingCopy();
        var changes = working.vmf().changes();
        var failure = new AssertionError("publication failed");
        var listener = changes.transactions().addChangeListener(event -> {
            if (event.wasAdded()) throw failure;
        });
        try {
            try (var recorder = start(working)) {
                working.setSourceId(SECOND);
                assertSame(failure, assertThrows(AssertionError.class, recorder::publishFrame));
                assertEquals(SECOND, working.getSourceId());
                assertFalse(changes.isModelVersioningEnabled());
                assertThrows(IllegalStateException.class, recorder::publishFrame);
            }
        } finally {
            listener.unsubscribe();
        }
    }

    @Test
    public void rejectsManualPublicationAndSkippedNativeGroup() {
        var working = workingCopy();
        var changes = working.vmf().changes();
        try (var recorder = start(working)) {
            working.setSourceId(SECOND);
            changes.publishTransaction();
            var failure = assertThrows(IllegalStateException.class, recorder::publishFrame);
            assertTrue(failure.getMessage().contains("outside publishFrame"));
            assertFalse(changes.isModelVersioningEnabled());
        }

        try (var recorder = start(working)) {
            working.setSourceId(FIRST);
            changes.startTransaction();
            var failure = assertThrows(IllegalStateException.class, recorder::publishFrame);
            assertTrue(failure.getMessage().contains("did not match the pending frame"));
            assertFalse(changes.isModelVersioningEnabled());
        }
    }

    @Test
    public void recorderEntryPointsCannotReenterItsPublication() {
        var working = workingCopy();
        var changes = working.vmf().changes();
        try (var recorder = start(working)) {
            var listener = changes.transactions().addChangeListener(event -> {
                if (!event.wasAdded()) return;
                assertThrows(IllegalStateException.class, recorder::publishFrame);
                assertThrows(IllegalStateException.class, recorder::finish);
                assertThrows(IllegalStateException.class, recorder::close);
            });
            try {
                working.setSourceId(SECOND);
                assertTrue(recorder.publishFrame().isPresent());
                assertTrue(changes.isModelVersioningEnabled());
                assertEquals(1, recorder.finish().transactions().size());
            } finally {
                listener.unsubscribe();
            }
        }
    }

    @Test
    public void anEmptyPublicationStillChecksForUnobservedEdits() {
        var working = workingCopy();
        try (var recorder = start(working)) {
            ((ArchiveEntryImpl) working).__vmf_get_prop_relatedIds__vmf_RawList().add(SECOND);
            assertThrows(IllegalStateException.class, recorder::publishFrame);
            assertFalse(working.vmf().changes().isModelVersioningEnabled());
        }
    }

    @Test
    public void failedRawListObserversCannotHideAnAddAndItsReversal() {
        var working = workingCopy();
        var list = working.getRelatedIds();
        var failure = new AssertionError("raw list observer");
        var first = list.addChangeListener(event -> {
            assertSame(list, event.source());
            throw failure;
        });
        var delivered = new ArrayList<ChangeJournal.Kind>();
        var later = list.asUnmodifiable().addChangeListener(event -> {
            assertSame(list, event.source());
            delivered.add(event.wasAdded() ? ChangeJournal.Kind.LIST_ADD : ChangeJournal.Kind.LIST_REMOVE);
        });
        try {
            var journal = record(working, model -> {
                assertSame(failure, assertThrows(AssertionError.class, () -> list.add(SECOND)));
                assertSame(failure, assertThrows(AssertionError.class, () -> list.remove(1)));
            });
            var operations = journal.transactions().get(0).operations();
            assertEquals(2, operations.size());
            assertEquals(ChangeJournal.Kind.LIST_ADD, operations.get(0).kind());
            assertEquals(ChangeJournal.Kind.LIST_REMOVE, operations.get(1).kind());
            assertEquals(List.of(ChangeJournal.Kind.LIST_ADD, ChangeJournal.Kind.LIST_REMOVE), delivered);
            assertEquals(List.of(FIRST), JournalReplayer.replay(workingCopy(), BINDING, CHECKPOINT, journal).getRelatedIds());
        } finally {
            later.unsubscribe();
            first.unsubscribe();
        }
    }

    private static JournalRecorder start(ArchiveEntry working) {
        return JournalRecorder.start(working, BINDING, CHECKPOINT, SEQUENCE);
    }

    private static ChangeJournal record(ArchiveEntry working, Consumer<ArchiveEntry> edit) {
        return JournalRecorder.recordFrame(working, BINDING, CHECKPOINT, SEQUENCE, edit);
    }

    private static ArchiveEntry workingCopy() {
        var working = ArchiveEntry.newBuilder().withId("entry").build();
        working.setSourceId(FIRST);
        working.getRelatedIds().add(FIRST);
        return working;
    }
}
