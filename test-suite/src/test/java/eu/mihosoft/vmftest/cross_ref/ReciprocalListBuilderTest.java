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

import eu.mihosoft.vmf.runtime.core.VObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ReciprocalListBuilderTest {
    @Test public void reciprocalListsKeepTheBulkPositionsAndPublishInverseEditsInOccurrenceOrder() {
        for (boolean append : List.of(false, true)) {
            var source = EntityOneC.newInstance();
            var target = EntityOneC.newInstance();
            var repeated = EntityTwoC.newInstance();
            var fresh = EntityTwoC.newInstance();
            var former = EntityTwoC.newInstance();
            var original = Arrays.asList(repeated, null, fresh, repeated, null);
            source.getRefs().addAll(original);
            target.getRefs().addAll(Arrays.asList(former, repeated, null));
            var targetRefs = target.getRefs();
            var events = new ArrayList<String>();
            observe(source, "source", events);
            observe(target, "target", events);
            observe(repeated, "repeated", events);
            observe(fresh, "fresh", events);
            observe(former, "former", events);

            var staged = EntityOneC.newBuilder().appendCollections(append).applyFrom(source);
            assertTrue(events.isEmpty());
            assertEquals(List.of(source, source, target), repeated.getRefs());
            staged.applyTo(target);
            assertSame(targetRefs, target.getRefs());
            assertEquals(original, source.getRefs());
            var expected = append ? Arrays.asList(former, repeated, null, repeated, null, fresh, repeated, null) : original;
            assertEquals(expected, targetRefs);
            assertEquals(append ? List.of(source, source, target, target, target) : List.of(source, source, target, target),
                    repeated.getRefs());
            assertEquals(List.of(source, target), fresh.getRefs());
            assertEquals(append ? List.of(target) : List.of(), former.getRefs());
            assertEquals(append ? List.of("repeated:+", "fresh:+", "repeated:+", "target:+")
                    : List.of("former:-", "repeated:-", "target:-", "repeated:+", "fresh:+", "repeated:+", "target:+"), events);

            events.clear();
            EntityOneC.newBuilder().appendCollections(append).applyFrom(target).applyTo(target);
            var sameOwner = new ArrayList<>(expected);
            if (append) sameOwner.addAll(expected);
            assertEquals(sameOwner, targetRefs);
            assertEquals(append ? List.of("former:+", "repeated:+", "repeated:+", "fresh:+", "repeated:+", "target:+")
                    : List.of("repeated:-", "fresh:-", "repeated:-", "target:-",
                        "repeated:+", "fresh:+", "repeated:+", "target:+"), events);
            assertEquals(append ? 8 : 4, repeated.getRefs().size());
            assertEquals(append ? 3 : 2, fresh.getRefs().size());
            assertEquals(append ? 2 : 0, former.getRefs().size());

            events.clear();
            var built = staged.build();
            assertEquals(original, built.getRefs());
            assertEquals(original, source.getRefs());
            assertEquals(sameOwner, targetRefs);
            assertSame(built, repeated.getRefs().get(repeated.getRefs().size() - 1));
            assertSame(repeated.asReadOnly(), built.asReadOnly().getRefs().get(0));
            assertSame(built.asReadOnly().getRefs().get(0), built.asReadOnly().getRefs().get(3));
            assertEquals(List.of("repeated:+", "fresh:+", "repeated:+"), events);
        }
    }

    @Test public void eitherEndCanImportTheSameModelAliasTwice() {
        var source = EntityTwoC.newInstance();
        var target = EntityTwoC.newInstance();
        var left = EntityOneC.newInstance();
        var original = Arrays.asList(left, null, left);
        source.getRefs().addAll(original);
        var staged = EntityTwoC.newBuilder().applyFrom(source).applyTo(target);
        var built = staged.build();
        assertEquals(original, source.getRefs());
        assertEquals(original, target.getRefs());
        assertEquals(original, built.getRefs());
        assertEquals(List.of(source, source, target, target, built, built), left.getRefs());
    }

    private static void observe(VObject owner, String label, List<String> events) {
        owner.vmf().changes().addPropertyListener("refs", change -> {
            var edit = change.listChange().orElseThrow();
            events.add(label + (edit.wasAdded() ? ":+" : ":-"));
        });
    }
}
