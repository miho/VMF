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
package eu.mihosoft.vmf.runtime.core.internal;

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.util.IdentityHashMap;

/** Identifies which native histories observed the indexed removal in a generated scalar edit. */
public final class OppositeReferenceRemoval implements AutoCloseable {
    private static final ThreadLocal<OppositeReferenceRemoval> ACTIVE = new ThreadLocal<>();
    private static final OppositeReferenceRemoval NONE = new OppositeReferenceRemoval(null, null, null);

    private final VList<?> list;
    private final VObject member;
    private final OppositeReferenceRemoval previous;
    private IdentityHashMap<ChangesImpl, Long> histories;

    private OppositeReferenceRemoval(VList<?> list, VObject member, OppositeReferenceRemoval previous) {
        this.list = list;
        this.member = member;
        this.previous = previous;
    }

    public static OppositeReferenceRemoval begin(VList<?> list, VObject member) {
        if (list == null) return NONE;
        var removal = new OppositeReferenceRemoval(list, member, ACTIVE.get());
        ACTIVE.set(removal);
        return removal;
    }

    static void record(ListChangeImpl change, ChangesImpl history, long generation) {
        var removal = ACTIVE.get();
        if (removal == null || !change.removesFrom(removal.list, removal.member)) return;
        if (removal.histories == null) removal.histories = new IdentityHashMap<>();
        removal.histories.put(history, generation);
    }

    static boolean wasRecorded(VObject member, ChangesImpl history, long generation) {
        var removal = ACTIVE.get();
        if (removal == null || removal.member != member || removal.histories == null) return false;
        Long recorded = removal.histories.get(history);
        return recorded != null && recorded == generation;
    }

    @Override public void close() {
        if (list != null) ACTIVE.set(previous);
    }
}
