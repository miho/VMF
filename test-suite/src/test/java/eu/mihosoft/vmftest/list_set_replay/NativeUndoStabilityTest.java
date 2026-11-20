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
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181â€“192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmftest.list_set_replay;

import eu.mihosoft.vmf.runtime.core.Transaction;
import eu.mihosoft.vmftest.setteridentity.BinaryExpression;
import eu.mihosoft.vmftest.setteridentity.Holder;
import eu.mihosoft.vmftest.setteridentity.NumberExpression;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class NativeUndoStabilityTest {
    @Test
    public void repeatedScalarChangesCanBeUndoneInSequence() {
        var model = BinaryExpression.newInstance();
        model.setOperator("initial");
        var changes = model.vmf().changes();
        changes.start();
        model.setOperator("middle");
        model.setOperator("final");
        changes.stop();

        var transaction = changes.transactions().get(0);
        assertFalse(transaction.changes().get(0).isUndoable());
        assertTrue(transaction.isUndoable());
        transaction.undo();
        assertEquals("initial", model.getOperator());
    }

    @Test
    public void staleListAdditionRejectsTheTransactionBeforeOtherChangesAreUndone() {
        var model = BinaryExpression.newInstance();
        model.setOperator("before");
        var added = NumberExpression.newBuilder().withValue(1).build();
        var equalReplacement = NumberExpression.newBuilder().withValue(1).build();
        assertEquals(added, equalReplacement);
        assertNotSame(added, equalReplacement);
        var changes = model.vmf().changes();
        changes.start();
        model.getArguments().add(added);
        model.setOperator("after");
        changes.stop();

        model.getArguments().set(0, equalReplacement);
        var transaction = changes.transactions().get(0);
        assertFalse(transaction.changes().get(0).isUndoable());
        transaction.changes().get(0).undo();
        assertSame(equalReplacement, model.getArguments().get(0));

        assertFalse(transaction.isUndoable());
        transaction.undo();
        assertSame(equalReplacement, model.getArguments().get(0));
        assertEquals("after", model.getOperator());
    }

    @Test
    public void staleScalarReferencePreservesAnEqualReplacement() {
        var model = Holder.newInstance();
        var added = NumberExpression.newBuilder().withValue(1).build();
        var equalReplacement = NumberExpression.newBuilder().withValue(1).build();
        assertEquals(added, equalReplacement);
        assertNotSame(added, equalReplacement);
        var changes = model.vmf().changes();
        changes.start();
        model.setValue(added);
        changes.stop();

        model.setValue(equalReplacement);
        var transaction = changes.transactions().get(0);
        assertFalse(transaction.isUndoable());
        transaction.undo();
        assertSame(equalReplacement, model.getValue());
    }

    @Test
    public void staleBulkSetLeavesEveryListEntryUnchanged() {
        var model = ListReplayModel.newInstance();
        model.getValues().addAll(List.of("a", "b", "c"));
        var changes = model.vmf().changes();
        changes.start();
        model.getValues().setAll(0, List.of("x", "y"));
        changes.stop();

        model.getValues().set(1, "later");
        var change = changes.all().get(0);
        assertFalse(change.isUndoable());
        change.undo();
        assertEquals(List.of("x", "later", "c"), model.getValues());
    }

    @Test
    public void staleRemovalLeavesTheShortenedListUnchanged() {
        var model = ListReplayModel.newInstance();
        model.getValues().addAll(List.of("a", "b", "c"));
        var changes = model.vmf().changes();
        changes.start();
        model.getValues().remove(1);
        changes.stop();

        model.getValues().remove(0);
        var change = changes.all().get(0);
        assertFalse(change.isUndoable());
        change.undo();
        assertEquals(List.of("c"), model.getValues());
    }

    @Test
    public void noncontiguousRemovalIncludingTailRestoresValuesInOrder() {
        var model = ListReplayModel.newInstance();
        var initial = List.of("a", "b", "c", "d", "e");
        model.getValues().addAll(initial);
        var changes = model.vmf().changes();
        changes.start();
        model.getValues().removeAll(1, 3, 4);
        changes.stop();

        assertEquals(List.of("a", "c"), model.getValues());
        Transaction removal = changes.transactions().get(0);
        assertTrue(removal.isUndoable());
        removal.undo();
        assertEquals(initial, model.getValues());
    }
}
