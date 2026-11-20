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
 * a framework for declarative GUI programming. InComputing and Visualization in Science, 2013, 16(4),
 * 181–192. http://dx.doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmftest.events_undo_redo;

import eu.mihosoft.vmf.runtime.core.Changes;
import eu.mihosoft.vmf.runtime.core.Transaction;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TransactionPublicationTest {
    @Test
    public void explicitPublicationFailureKeepsCommittedTransactionAndRecordsLaterEditsOnce() {
        var node = ChildListContainment.newInstance();
        var changes = node.vmf().changes();
        var expected = new IllegalStateException("observer failed");
        changes.start();
        try {
            var throwing = changes.transactions().addChangeListener(event -> {
                if (event.wasAdded()) throw expected;
            });
            try {
                node.setName("first");
                assertSame(expected, assertThrows(IllegalStateException.class, changes::publishTransaction));
                assertTrue(changes.isModelVersioningEnabled());
                assertEquals(List.of("first"), names(changes));
            } finally {
                throwing.unsubscribe();
            }

            changes.publishTransaction();
            assertEquals(List.of("first"), names(changes));
            node.setName("second");
            changes.publishTransaction();
            assertEquals(List.of("first", "second"), names(changes));
        } finally {
            changes.stop();
        }
    }

    @Test
    public void nestedPublicationDeliversOnlyNewRangesInSynchronousOrder() {
        var node = ChildListContainment.newInstance();
        var changes = node.vmf().changes();
        var order = new ArrayList<String>();
        changes.start();
        var first = changes.transactions().addChangeListener(event -> {
            var name = name(event.added().elements().get(0));
            order.add("first " + name);
            if (name.equals("outer") && order.size() == 1) {
                // Publishing an already committed range has no event.
                changes.publishTransaction();
                node.setName("nested");
                changes.publishTransaction();
                node.setName("next");
            }
        });
        var second = changes.transactions().addChangeListener(event ->
                order.add("second " + name(event.added().elements().get(0))));
        try {
            node.setName("outer");
            changes.publishTransaction();
            assertEquals(List.of("outer", "nested"), names(changes));
            assertEquals(List.of("first outer", "first nested", "second nested", "second outer"), order);
            changes.publishTransaction();
            assertEquals(List.of("outer", "nested", "next"), names(changes));
            assertEquals(List.of("first outer", "first nested", "second nested", "second outer",
                    "first next", "second next"), order);
        } finally {
            first.unsubscribe();
            second.unsubscribe();
            changes.stop();
        }
    }

    @Test
    public void stoppingCleansUpAndAllowsRestartWhenPublicationObserverThrowsAnError() {
        var node = ChildListContainment.newInstance();
        var changes = node.vmf().changes();
        var expected = new AssertionError("observer failed");
        changes.start();
        var throwing = changes.transactions().addChangeListener(event -> {
            if (event.wasAdded()) throw expected;
        });
        try {
            node.setName("recorded");
            assertSame(expected, assertThrows(AssertionError.class, changes::stop));
        } finally {
            throwing.unsubscribe();
        }

        assertFalse(changes.isModelVersioningEnabled());
        assertThrows(RuntimeException.class, changes::startTransaction);
        node.setName("after stop");
        changes.stop();
        assertEquals(1, changes.all().size());
        assertEquals(List.of("recorded"), names(changes));

        changes.start();
        node.setName("restart");
        changes.stop();
        assertEquals(List.of("restart"), names(changes));
    }

    private static List<String> names(Changes changes) {
        return changes.transactions().stream().map(TransactionPublicationTest::name).collect(Collectors.toList());
    }

    private static String name(Transaction transaction) {
        assertEquals(1, transaction.changes().size());
        return (String) transaction.changes().get(0).propertyChange().orElseThrow().newValue();
    }
}
