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
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import eu.mihosoft.vmftest.journalvalues.ArchiveEntry;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JournalVerificationTest {
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
    public void finishRejectsAnUnobservedEditAfterPublishing() {
        var entry = ArchiveEntry.newBuilder().withId("entry").build();
        entry.getRelatedIds().add(FIRST);
        // Mutating the underlying list bypasses the generated observable wrapper.
        var backing = ((ArchiveEntryImpl) entry).__vmf_get_prop_relatedIds__vmf_RawList();
        try (var recorder = JournalRecorder.start(entry, BINDING, "initial", 0)) {
            var changes = entry.vmf().changes();
            changes.startTransaction();
            entry.setSourceId(FIRST);
            changes.publishTransaction();
            assertEquals(1, changes.transactions().size());
            assertEquals(1, changes.all().size());

            backing.add(SECOND);
            assertEquals(List.of(FIRST, SECOND), entry.getRelatedIds());
            assertEquals(1, changes.all().size());

            var error = assertThrows(IllegalStateException.class, recorder::finish);
            assertTrue(error.getMessage().contains("could not reproduce the model state"));
            assertFalse(changes.isModelVersioningEnabled());
        }
    }

    @Test
    public void finishRejectsAnUnobservedEditThatWasVisibleAtPublicationAndThenRestored() {
        var entry = ArchiveEntry.newBuilder().withId("entry").build();
        entry.getRelatedIds().add(FIRST);
        var backing = ((ArchiveEntryImpl) entry).__vmf_get_prop_relatedIds__vmf_RawList();
        var changes = entry.vmf().changes();
        try (var recorder = JournalRecorder.start(entry, BINDING, "initial", 0)) {
            changes.startTransaction();
            entry.setSourceId(FIRST);
            backing.set(0, SECOND);
            changes.publishTransaction();
            assertEquals(1, changes.transactions().size());

            backing.set(0, FIRST);
            assertEquals(List.of(FIRST), entry.getRelatedIds());
            assertEquals(1, changes.all().size());

            var error = assertThrows(IllegalStateException.class, recorder::finish);
            assertTrue(error.getCause().getMessage().contains("Published transaction state could not be reproduced"));
        }
        assertFalse(changes.isModelVersioningEnabled());
    }

    @Test
    public void finishRejectsAnUnobservedEditFromALaterPublicationListener() {
        var entry = ArchiveEntry.newBuilder().withId("entry").build();
        entry.getRelatedIds().add(FIRST);
        var backing = ((ArchiveEntryImpl) entry).__vmf_get_prop_relatedIds__vmf_RawList();
        var changes = entry.vmf().changes();
        try (var recorder = JournalRecorder.start(entry, BINDING, "initial", 0)) {
            var observer = changes.transactions().addChangeListener(event -> {
                if (event.wasAdded()) backing.add(SECOND);
            });
            try {
                changes.startTransaction();
                entry.setSourceId(FIRST);
                // finish publishes the pending frame. The observer runs after the journal's
                // boundary validation and before finish performs its own comparison.
                var error = assertThrows(IllegalStateException.class, recorder::finish);
                assertTrue(error.getMessage().contains("could not reproduce the model state"));
                assertEquals(List.of(FIRST, SECOND), entry.getRelatedIds());
                assertEquals(1, changes.all().size());
            } finally {
                observer.unsubscribe();
            }
        }
        assertFalse(changes.isModelVersioningEnabled());
    }

    @Test
    public void throwingPublicationObserverDuringFinishReleasesRecording() {
        var entry = ArchiveEntry.newBuilder().withId("entry").build();
        var changes = entry.vmf().changes();
        var expected = new IllegalStateException("observer failed");
        try (var recorder = JournalRecorder.start(entry, BINDING, "initial", 0)) {
            var observer = changes.transactions().addChangeListener(event -> {
                if (event.wasAdded()) throw expected;
            });
            try {
                entry.setSourceId(FIRST);
                assertSame(expected, assertThrows(IllegalStateException.class, recorder::finish));
                assertFalse(changes.isModelVersioningEnabled());
                assertEquals(1, changes.transactions().size());
            } finally {
                observer.unsubscribe();
            }
        }

        try (var recorder = JournalRecorder.start(entry, BINDING, "after failure", 1)) {
            entry.setSourceId(SECOND);
            assertEquals(1, recorder.finish().transactions().size());
        }
        assertFalse(changes.isModelVersioningEnabled());
    }

    @Test
    public void finishRejectsPublicationSkippedByAnEarlierThrowingObserver() {
        var entry = ArchiveEntry.newBuilder().withId("entry").build();
        var changes = entry.vmf().changes();
        var expected = new IllegalStateException("observer failed");
        var observer = changes.transactions().addChangeListener(event -> {
            if (event.wasAdded()) throw expected;
        });
        try (var recorder = JournalRecorder.start(entry, BINDING, "initial", 0)) {
            entry.setSourceId(FIRST);
            assertSame(expected, assertThrows(IllegalStateException.class, changes::publishTransaction));
            observer.unsubscribe();

            var error = assertThrows(IllegalStateException.class, recorder::finish);
            assertTrue(error.getMessage().contains("Native transaction history was replaced"));
            assertEquals(1, changes.transactions().size());
            assertFalse(changes.isModelVersioningEnabled());
        } finally {
            observer.unsubscribe();
        }
    }

    @Test
    public void finishVerifiesObservedPublicationEditsWithoutLeavingANativeGroupPending() {
        var checkpoint = ArchiveEntry.newBuilder().withId("entry").build();
        var entry = checkpoint.clone();
        var changes = entry.vmf().changes();
        try (var recorder = JournalRecorder.start(entry, BINDING, "initial", 0)) {
            var observer = changes.transactions().addChangeListener(event -> {
                if (event.wasAdded() && FIRST.equals(entry.getSourceId())) entry.setSourceId(SECOND);
            });
            try {
                entry.setSourceId(FIRST);
                var journal = recorder.finish();
                assertFalse(changes.isModelVersioningEnabled());
                assertEquals(2, changes.all().size());
                assertEquals(2, journal.transactions().size());
                assertEquals(SECOND, JournalReplayer.replay(checkpoint, BINDING, "initial", journal).getSourceId());

                assertEquals(1, changes.transactions().size());
                changes.stop();
                changes.publishTransaction();
                assertEquals(1, changes.transactions().size());
            } finally {
                observer.unsubscribe();
            }
        }
    }
}
