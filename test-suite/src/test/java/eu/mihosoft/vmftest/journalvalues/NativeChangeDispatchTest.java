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
package eu.mihosoft.vmftest.journalvalues;

import org.junit.Test;
import vjavax.observer.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class NativeChangeDispatchTest {
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    public void mixedScopesRecordEachMutationOnceWhenTheLocalObserversReenterAndThrow() {
        var entry = ArchiveEntry.newInstance();
        entry.setSourceId(FIRST);
        var changes = entry.vmf().changes();
        var firstFailure = new AssertionError("first local observer");
        var secondFailure = new IllegalStateException("second local observer");
        List<String> delivered = new ArrayList<>();
        var firstLocal = changes.addListener(change -> {
            var value = change.propertyChange().orElseThrow().newValue();
            delivered.add(SECOND.equals(value) ? "first outer" : "first inner");
            if (SECOND.equals(value)) {
                entry.setSourceId(FIRST);
                throw firstFailure;
            }
        }, false);
        var secondLocal = changes.addListener(change -> {
            var value = change.propertyChange().orElseThrow().newValue();
            delivered.add(SECOND.equals(value) ? "second outer" : "second inner");
            if (SECOND.equals(value)) throw secondFailure;
        }, false);
        var recursive = changes.addListener(change -> {
            var value = change.propertyChange().orElseThrow().newValue();
            delivered.add(SECOND.equals(value) ? "recursive outer" : "recursive inner");
        });
        changes.start();
        try {
            var actual = assertThrows(AssertionError.class, () -> entry.setSourceId(SECOND));
            assertSame(firstFailure, actual);
            assertEquals(List.of(secondFailure), List.of(actual.getSuppressed()));
            assertEquals(List.of("first outer", "first inner", "second inner", "recursive inner",
                    "second outer", "recursive outer"), delivered);
            assertEquals(2, changes.all().size());
            var outer = changes.all().get(0).propertyChange().orElseThrow();
            var inner = changes.all().get(1).propertyChange().orElseThrow();
            assertEquals(FIRST, outer.oldValue());
            assertEquals(SECOND, outer.newValue());
            assertEquals(SECOND, inner.oldValue());
            assertEquals(FIRST, inner.newValue());
            assertEquals(FIRST, entry.getSourceId());
        } finally {
            changes.stop();
            recursive.unsubscribe();
            secondLocal.unsubscribe();
            firstLocal.unsubscribe();
        }
    }

    @Test
    public void recentlyRemovedLocalScopeCannotDuplicateItsSnapshottedDelivery() {
        var entry = ArchiveEntry.newInstance();
        var changes = entry.vmf().changes();
        var subscription = new AtomicReference<Subscription>();
        var raw = entry.getRelatedIds().addChangeListener(event -> subscription.get().unsubscribe());
        var recursive = changes.addListener(change -> { });
        subscription.set(changes.addListener(change -> { }, false));
        changes.start();
        try {
            entry.getRelatedIds().add(FIRST);
            assertEquals(1, changes.all().size());
        } finally {
            changes.stop();
            recursive.unsubscribe();
            raw.unsubscribe();
        }
    }

    @Test
    public void independentListObserverCanInterleaveBothScopeDeliveriesWithoutDuplicatingHistory() {
        var entry = ArchiveEntry.newInstance();
        var changes = entry.vmf().changes();
        List<String> delivered = new ArrayList<>();
        var local = changes.addListener(change -> {
            var added = change.listChange().orElseThrow().added().elements();
            delivered.add(added.contains(FIRST) ? "local first" : "local second");
        }, false);
        var raw = entry.getRelatedIds().addChangeListener(event -> {
            if (event.added().elements().contains(FIRST)) entry.getRelatedIds().add(SECOND);
        });
        var recursive = changes.addListener(change -> {
            var added = change.listChange().orElseThrow().added().elements();
            delivered.add(added.contains(FIRST) ? "recursive first" : "recursive second");
        });
        changes.start();
        try {
            entry.getRelatedIds().add(FIRST);
            // The object's local and recursive listeners receive a change together, in the order they were
            // added (portable contract E7), before the raw list listener adds the second value.
            assertEquals(List.of("local first", "recursive first", "local second", "recursive second"), delivered);
            assertEquals(2, changes.all().size());
            assertEquals(List.of(FIRST), changes.all().get(0).listChange().orElseThrow().added().elements());
            assertEquals(List.of(SECOND), changes.all().get(1).listChange().orElseThrow().added().elements());
        } finally {
            changes.stop();
            recursive.unsubscribe();
            raw.unsubscribe();
            local.unsubscribe();
        }
    }
}
