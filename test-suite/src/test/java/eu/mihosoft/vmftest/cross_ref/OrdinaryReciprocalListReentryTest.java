/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OrdinaryReciprocalListReentryTest {
    @Test public void firstInverseObserverCanClearTheInitiatingBulkAdd() {
        var source = EntityOneC.newInstance();
        var repeated = EntityTwoC.newInstance();
        var other = EntityTwoC.newInstance();
        boolean[] cleared = { false };
        repeated.vmf().changes().addPropertyListener("refs", change -> {
            if (cleared[0] || !change.listChange().orElseThrow().wasAdded()) return;
            cleared[0] = true;
            source.getRefs().clear();
        });

        source.getRefs().addAll(List.of(repeated, other, repeated));
        assertTrue(cleared[0]);
        assertTrue(source.getRefs().isEmpty());
        assertTrue(repeated.getRefs().isEmpty());
        assertTrue(other.getRefs().isEmpty());
    }

    @Test public void reentrantRemovalKeepsOnlyTheRemainingRepeatedAlias() {
        var source = EntityOneC.newInstance();
        var repeated = EntityTwoC.newInstance();
        var other = EntityTwoC.newInstance();
        boolean[] removed = { false };
        repeated.vmf().changes().addPropertyListener("refs", change -> {
            if (removed[0] || !change.listChange().orElseThrow().wasAdded()) return;
            removed[0] = true;
            source.getRefs().remove(0);
        });

        source.getRefs().addAll(List.of(repeated, other, repeated));
        assertTrue(removed[0]);
        assertEquals(List.of(other, repeated), source.getRefs());
        assertEquals(List.of(source), repeated.getRefs());
        assertEquals(List.of(source), other.getRefs());
    }

    @Test public void anOppositeReinsertedDuringBulkClearKeepsItsLatestLink() {
        var source = EntityOneC.newInstance();
        var repeated = EntityTwoC.newInstance();
        var kept = EntityTwoC.newInstance();
        source.getRefs().addAll(List.of(repeated, kept, repeated));
        boolean[] reinserted = { false };
        repeated.vmf().changes().addPropertyListener("refs", change -> {
            if (reinserted[0] || !change.listChange().orElseThrow().wasRemoved()) return;
            reinserted[0] = true;
            kept.getRefs().remove(source);
            kept.getRefs().add(source);
        });

        source.getRefs().clear();
        assertTrue(reinserted[0]);
        assertEquals(List.of(kept), source.getRefs());
        assertEquals(List.of(source), kept.getRefs());
        assertTrue(repeated.getRefs().isEmpty());
    }
}
