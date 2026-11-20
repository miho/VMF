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

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.runtime.core.Change;
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
    public void historyViewsStayLiveAndRejectMembershipChanges() {
        var node = ChildListContainment.newInstance();
        var changes = node.vmf().changes();
        var all = changes.all();
        var transactions = changes.transactions();

        changes.start();
        try {
            node.setName("first");
            changes.publishTransaction();
            assertReadOnly(all);
            assertReadOnly(transactions);

            var first = all.get(0);
            var firstTransaction = transactions.get(0);
            assertSame(first, firstTransaction.changes().get(0));
            assertThrows(UnsupportedOperationException.class, firstTransaction.changes()::clear);
            assertThrows(UnsupportedOperationException.class, () -> firstTransaction.changes().remove(0));

            node.setName("second");
            changes.publishTransaction();
            assertEquals(2, all.size());
            assertEquals(List.of("first", "second"), names(changes));
            assertSame(all, changes.all());
            assertSame(transactions, changes.transactions());

            changes.clear();
            assertTrue(all.isEmpty());
            assertTrue(transactions.isEmpty());
            assertSame(first, firstTransaction.changes().get(0));
        } finally {
            changes.stop();
        }
    }

    @Test
    public void historyEventsExposeTheReadOnlyViewAndOriginalEntries() {
        var node = ChildListContainment.newInstance();
        var changes = node.vmf().changes();
        var all = changes.all();
        var transactions = changes.transactions();
        var addedChanges = new ArrayList<Change>();
        var removedChanges = new ArrayList<Change>();
        var addedTransactions = new ArrayList<Transaction>();
        var removedTransactions = new ArrayList<Transaction>();
        var changeObserver = all.addChangeListener(event -> {
            assertSame(all, event.source());
            if (event.wasAdded()) {
                assertThrows(UnsupportedOperationException.class, () -> event.source().remove(0));
                addedChanges.addAll(event.added().elements());
            }
            if (event.wasRemoved()) removedChanges.addAll(event.removed().elements());
        });
        var transactionObserver = transactions.addChangeListener(event -> {
            assertSame(transactions, event.source());
            if (event.wasAdded()) {
                assertThrows(UnsupportedOperationException.class, event.source()::clear);
                addedTransactions.addAll(event.added().elements());
            }
            if (event.wasRemoved()) removedTransactions.addAll(event.removed().elements());
        });
        try {
            changes.start();
            node.setName("recorded");
            changes.stop();
            assertEquals(1, addedChanges.size());
            assertEquals(1, addedTransactions.size());
            assertSame(all.get(0), addedChanges.get(0));
            assertSame(transactions.get(0), addedTransactions.get(0));

            changes.clear();
            assertEquals(addedChanges, removedChanges);
            assertEquals(addedTransactions, removedTransactions);
        } finally {
            transactionObserver.unsubscribe();
            changeObserver.unsubscribe();
            changes.stop();
        }
    }

    private static <T> void assertReadOnly(VList<T> view) {
        T first = view.get(0);
        assertThrows(UnsupportedOperationException.class, () -> view.add(first));
        assertThrows(UnsupportedOperationException.class, () -> view.addAll(new int[]{0}, List.of(first)));
        assertThrows(UnsupportedOperationException.class, () -> view.set(0, first));
        assertThrows(UnsupportedOperationException.class, () -> view.setAll(0, List.of(first)));
        assertThrows(UnsupportedOperationException.class, () -> view.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> view.remove(first));
        assertThrows(UnsupportedOperationException.class, () -> view.removeAll(0));
        assertThrows(UnsupportedOperationException.class, () -> view.removeIf(element -> true));
        assertThrows(UnsupportedOperationException.class, () -> view.subList(0, 1).clear());
        assertThrows(UnsupportedOperationException.class, view::clear);

        var iterator = view.iterator();
        assertSame(first, iterator.next());
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertSame(first, view.get(0));
    }

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
