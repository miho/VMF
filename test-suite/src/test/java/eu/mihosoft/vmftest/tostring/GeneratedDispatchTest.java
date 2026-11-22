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
package eu.mihosoft.vmftest.tostring;

import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternal;
import eu.mihosoft.vmftest.staticreflection.ReadOnlyRoot;
import eu.mihosoft.vmftest.staticreflection.Root;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class GeneratedDispatchTest {
    @Test
    public void mutableSwitchFallsBackForForeignModel() {
        Child child = Child.newInstance();
        Root foreign = Root.newInstance();
        assertTypeIdsCollide(child, foreign);

        SwitchForTostringModel<String> modelSwitch = new SwitchForTostringModel<String>() {
            @Override
            public String caseChild(Child object) {
                return "child";
            }

            @Override
            public String defaultValue(VObject object) {
                return "fallback";
            }
        };

        assertEquals("child", modelSwitch.doSwitch(child));
        assertEquals("fallback", modelSwitch.doSwitch(foreign));
    }

    @Test
    public void readOnlySwitchFallsBackForForeignModel() {
        ReadOnlyChild child = Child.newInstance().asReadOnly();
        ReadOnlyRoot foreign = Root.newInstance().asReadOnly();
        assertTypeIdsCollide(child, foreign);

        ReadOnlySwitchForTostringModel<String> modelSwitch = new ReadOnlySwitchForTostringModel<String>() {
            @Override
            public String caseReadOnlyChild(ReadOnlyChild object) {
                return "child";
            }

            @Override
            public String defaultValue(VObject object) {
                return "fallback";
            }
        };

        assertEquals("child", modelSwitch.doSwitch(child));
        assertEquals("fallback", modelSwitch.doSwitch(foreign));
    }

    @Test
    public void mutableTraversalListenerSkipsForeignModel() {
        Child child = Child.newInstance();
        Root foreign = Root.newInstance();
        assertTypeIdsCollide(child, foreign);

        List<String> events = new ArrayList<>();
        ListenerForTostringModel listener = new ListenerForTostringModel() {
            @Override
            public void onEnterChild(Child object) {
                events.add("enter");
            }

            @Override
            public void onExitChild(Child object) {
                events.add("exit");
            }
        };

        listener.onEnter(child);
        listener.onEnter(foreign);
        listener.onExit(foreign);
        listener.onExit(child);
        assertEquals(List.of("enter", "exit"), events);
    }

    @Test
    public void readOnlyTraversalListenerSkipsForeignModel() {
        ReadOnlyChild child = Child.newInstance().asReadOnly();
        ReadOnlyRoot foreign = Root.newInstance().asReadOnly();
        assertTypeIdsCollide(child, foreign);

        List<String> events = new ArrayList<>();
        ReadOnlyListenerForTostringModel listener = new ReadOnlyListenerForTostringModel() {
            @Override
            public void onEnterReadOnlyChild(ReadOnlyChild object) {
                events.add("enter");
            }

            @Override
            public void onExitReadOnlyChild(ReadOnlyChild object) {
                events.add("exit");
            }
        };

        listener.onEnter(child);
        listener.onEnter(foreign);
        listener.onExit(foreign);
        listener.onExit(child);
        assertEquals(List.of("enter", "exit"), events);
    }

    @SuppressWarnings("deprecation")
    private static void assertTypeIdsCollide(VObject local, VObject foreign) {
        assertEquals(((VObjectInternal) local)._vmf_getTypeId(), ((VObjectInternal) foreign)._vmf_getTypeId());
    }
}
