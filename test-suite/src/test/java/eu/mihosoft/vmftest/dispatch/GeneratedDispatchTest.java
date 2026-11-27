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
package eu.mihosoft.vmftest.dispatch;

import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternal;
import eu.mihosoft.vmftest.dispatch.foreign.ForeignNode;
import eu.mihosoft.vmftest.dispatch.foreign.ReadOnlyForeignNode;
import eu.mihosoft.vmftest.dispatch.local.ListenerForLocalModel;
import eu.mihosoft.vmftest.dispatch.local.LocalNode;
import eu.mihosoft.vmftest.dispatch.local.ReadOnlyListenerForLocalModel;
import eu.mihosoft.vmftest.dispatch.local.ReadOnlyLocalNode;
import eu.mihosoft.vmftest.dispatch.local.ReadOnlySwitchForLocalModel;
import eu.mihosoft.vmftest.dispatch.local.SwitchForLocalModel;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class GeneratedDispatchTest {
    @Test
    public void mutableSwitchFallsBackForForeignModel() {
        LocalNode local = LocalNode.newInstance();
        ForeignNode foreign = ForeignNode.newInstance();
        assertTypeIdsCollide(local, foreign);

        SwitchForLocalModel<String> modelSwitch = new SwitchForLocalModel<String>() {
            @Override
            public String caseLocalNode(LocalNode object) {
                return "local";
            }

            @Override
            public String defaultValue(VObject object) {
                return "fallback";
            }
        };

        assertEquals("local", modelSwitch.doSwitch(local));
        assertEquals("fallback", modelSwitch.doSwitch(foreign));
    }

    @Test
    public void readOnlySwitchFallsBackForForeignModel() {
        ReadOnlyLocalNode local = LocalNode.newInstance().asReadOnly();
        ReadOnlyForeignNode foreign = ForeignNode.newInstance().asReadOnly();
        assertTypeIdsCollide(local, foreign);

        ReadOnlySwitchForLocalModel<String> modelSwitch = new ReadOnlySwitchForLocalModel<String>() {
            @Override
            public String caseReadOnlyLocalNode(ReadOnlyLocalNode object) {
                return "local";
            }

            @Override
            public String defaultValue(VObject object) {
                return "fallback";
            }
        };

        assertEquals("local", modelSwitch.doSwitch(local));
        assertEquals("fallback", modelSwitch.doSwitch(foreign));
    }

    @Test
    public void mutableTraversalListenerSkipsForeignModel() {
        LocalNode local = LocalNode.newInstance();
        ForeignNode foreign = ForeignNode.newInstance();
        assertTypeIdsCollide(local, foreign);

        List<String> events = new ArrayList<>();
        ListenerForLocalModel listener = new ListenerForLocalModel() {
            @Override
            public void onEnterLocalNode(LocalNode object) {
                events.add("enter");
            }

            @Override
            public void onExitLocalNode(LocalNode object) {
                events.add("exit");
            }
        };

        listener.onEnter(local);
        listener.onEnter(foreign);
        listener.onExit(foreign);
        listener.onExit(local);
        assertEquals(List.of("enter", "exit"), events);
    }

    @Test
    public void readOnlyTraversalListenerSkipsForeignModel() {
        ReadOnlyLocalNode local = LocalNode.newInstance().asReadOnly();
        ReadOnlyForeignNode foreign = ForeignNode.newInstance().asReadOnly();
        assertTypeIdsCollide(local, foreign);

        List<String> events = new ArrayList<>();
        ReadOnlyListenerForLocalModel listener = new ReadOnlyListenerForLocalModel() {
            @Override
            public void onEnterReadOnlyLocalNode(ReadOnlyLocalNode object) {
                events.add("enter");
            }

            @Override
            public void onExitReadOnlyLocalNode(ReadOnlyLocalNode object) {
                events.add("exit");
            }
        };

        listener.onEnter(local);
        listener.onEnter(foreign);
        listener.onExit(foreign);
        listener.onExit(local);
        assertEquals(List.of("enter", "exit"), events);
    }

    @SuppressWarnings("deprecation")
    private static void assertTypeIdsCollide(VObject local, VObject foreign) {
        assertEquals("The independently generated fixtures must share a raw type ID",
                ((VObjectInternal) local)._vmf_getTypeId(), ((VObjectInternal) foreign)._vmf_getTypeId());
    }
}
