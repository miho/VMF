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
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmftest.cross_ref;

import eu.mihosoft.vmf.runtime.core.Change;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class OneToOneReassignmentTest {
    @Test public void subtypeCanTakeOverFromBaseOwner() {
        EntityOneA first=EntityOneA.newInstance();SpecializedEntityOneA second=SpecializedEntityOneA.newInstance();EntityTwoA value=EntityTwoA.newInstance();
        first.setRef(value);second.setRef(value);assertNull(first.getRef());assertSame(value,second.getRef());assertSame(second,value.getRef());
    }
    @Test public void repointingReleasesOldOppositeWithoutClearingNewValue() {
        EntityOneA owner=EntityOneA.newInstance();EntityTwoA a=EntityTwoA.newInstance(),b=EntityTwoA.newInstance();
        owner.setRef(a);owner.setRef(b);assertSame(b,owner.getRef());assertNull(a.getRef());assertSame(owner,b.getRef());
    }
    @Test public void takingOverReleasesPreviousOwner() {
        EntityOneA first=EntityOneA.newInstance(),second=EntityOneA.newInstance();EntityTwoA value=EntityTwoA.newInstance();
        first.setRef(value);second.setRef(value);assertNull(first.getRef());assertSame(value,second.getRef());assertSame(second,value.getRef());
    }
    @Test public void transferringBetweenTwoExistingPairsWorksInBothDirections() {
        EntityOneA first=EntityOneA.newInstance(),second=EntityOneA.newInstance();EntityTwoA a=EntityTwoA.newInstance(),b=EntityTwoA.newInstance();
        first.setRef(a);second.setRef(b);a.setRef(second);
        assertNull(first.getRef());assertNull(b.getRef());assertSame(second,a.getRef());assertSame(a,second.getRef());
    }
    @Test public void clearAndIdenticalAssignmentHaveExpectedEvents() {
        EntityOneA owner=EntityOneA.newInstance();EntityTwoA value=EntityTwoA.newInstance();List<Change> left=new ArrayList<>(),right=new ArrayList<>();
        owner.vmf().changes().addListener(left::add);value.vmf().changes().addListener(right::add);
        owner.setRef(value);owner.setRef(value);assertEquals(1,left.size());assertEquals(1,right.size());
        owner.setRef(null);assertEquals(2,left.size());assertEquals(2,right.size());assertNull(owner.getRef());assertNull(value.getRef());
        assertSame(value,left.get(1).propertyChange().get().oldValue());assertNull(left.get(1).propertyChange().get().newValue());
    }
    @Test public void takeoverTransactionUndoAndRedoRestoreBothPairs() {
        CrossRefGraph graph=CrossRefGraph.newInstance();EntityOneA first=EntityOneA.newInstance(),second=EntityOneA.newInstance();EntityTwoA a=EntityTwoA.newInstance(),b=EntityTwoA.newInstance();
        graph.getLefts().addAll(Arrays.asList(first,second));graph.getRights().addAll(Arrays.asList(a,b));first.setRef(a);second.setRef(b);
        var changes=graph.vmf().changes();changes.start();changes.startTransaction();second.setRef(a);changes.publishTransaction();changes.stop();
        assertNull(first.getRef());assertSame(a,second.getRef());assertSame(second,a.getRef());assertNull(b.getRef());
        List<Change> records=new ArrayList<>(changes.all());assertEquals(2,records.size());
        changes.transactions().get(0).undo();assertSame(a,first.getRef());assertSame(first,a.getRef());assertSame(b,second.getRef());assertSame(second,b.getRef());
        records.forEach(c->c.apply(c.object()));assertNull(first.getRef());assertSame(a,second.getRef());assertSame(second,a.getRef());assertNull(b.getRef());
    }

    // a one-to-one reference that is its own opposite: re-pointing, taking over,
    // transferring and clearing keep every pair symmetric
    @Test public void selfOppositeReferenceStaysSymmetric() {
        Partner a=partner("a"),b=partner("b"),c=partner("c"),d=partner("d");

        a.setPartner(b);
        assertSame(b,a.getPartner());assertSame(a,b.getPartner());

        a.setPartner(c);
        assertSame(c,a.getPartner());assertSame(a,c.getPartner());assertNull(b.getPartner());

        d.setPartner(c);
        assertSame(c,d.getPartner());assertSame(d,c.getPartner());assertNull(a.getPartner());

        b.setPartner(a);
        c.setPartner(b);
        assertSame(b,c.getPartner());assertSame(c,b.getPartner());assertNull(a.getPartner());assertNull(d.getPartner());

        c.setPartner(null);
        assertNull(c.getPartner());assertNull(b.getPartner());
    }

    // a one-to-one reference declared on interface-only types
    @Test public void interfaceOnlyReferenceCanBeRepointedAndTakenOver() {
        Panel first=Panel.newBuilder().withName("first").build(),second=Panel.newBuilder().withName("second").build();
        Button a=Button.newBuilder().withName("a").build(),b=Button.newBuilder().withName("b").build();

        first.setSelected(a);
        assertSame(a,first.getSelected());assertSame(first,a.getSelectedBy());

        first.setSelected(b);
        assertSame(b,first.getSelected());assertSame(first,b.getSelectedBy());assertNull(a.getSelectedBy());

        second.setSelected(b);
        assertSame(b,second.getSelected());assertSame(second,b.getSelectedBy());assertNull(first.getSelected());

        a.setSelectedBy(second);
        assertSame(a,second.getSelected());assertSame(second,a.getSelectedBy());assertNull(b.getSelectedBy());
    }

    private static Partner partner(String name) {
        return Partner.newBuilder().withName(name).build();
    }
}
