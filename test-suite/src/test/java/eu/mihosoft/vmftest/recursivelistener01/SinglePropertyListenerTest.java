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
package eu.mihosoft.vmftest.recursivelistener01;

import eu.mihosoft.vmf.runtime.core.ObservableObject;
import eu.mihosoft.vmftest.complex.vflow.VNode;
import org.junit.Test;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeListenerProxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class SinglePropertyListenerTest {
    @Test
    public void failingBeanListenerPreservesLaterDeliveryAndRecording() {
        var node = Node.newInstance();
        var observable = (ObservableObject) node;
        var first = new IllegalStateException("first listener");
        var second = new AssertionError("named listener");
        var order = new ArrayList<String>();
        PropertyChangeListener early = event -> { order.add("early"); throw first; };
        PropertyChangeListener named = new PropertyChangeListenerProxy("name", event -> {
            order.add("named");
            throw second;
        });
        observable.addPropertyChangeListener(early);
        observable.addPropertyChangeListener(named);
        var changes = node.vmf().changes();
        var subscription = changes.addLocalListener(event -> order.add("model"));
        changes.start();
        try {
            assertSame(first, assertThrows(IllegalStateException.class, () -> node.setName("applied")));
            assertEquals(List.of("early", "model", "named"), order);
            assertEquals(1, first.getSuppressed().length);
            assertSame(second, first.getSuppressed()[0]);
            assertEquals(1, changes.all().size());
            assertEquals("applied", changes.all().get(0).propertyChange().orElseThrow().newValue());
        } finally {
            changes.stop();
            subscription.unsubscribe();
            observable.removePropertyChangeListener(early);
            observable.removePropertyChangeListener(named);
        }
    }

    @Test
    public void namedAndOrdinaryListenersKeepTheirDeliveryOrder() {
        var node = Node.newInstance();
        var observable = (ObservableObject) node;
        List<String> delivery = new ArrayList<>();
        PropertyChangeListener ordinary = event -> delivery.add("ordinary " + event.getNewValue());
        var named = new PropertyChangeListenerProxy("name", event -> delivery.add("named " + event.getNewValue()));

        observable.addPropertyChangeListener(ordinary);
        node.setName("sole");
        observable.addPropertyChangeListener(named);
        node.setName("both");
        observable.removePropertyChangeListener(ordinary);
        node.setName("named only");
        node.setParent(Node.newInstance());
        observable.removePropertyChangeListener(named);
        node.setName("none");

        assertEquals(List.of("ordinary sole", "ordinary both", "named both", "named named only"), delivery);
    }

    @Test
    public void nestedPropertyChangesCanSeeNewListenersBeforeOuterDispatchFinishes() {
        var node = Node.newInstance();
        var observable = (ObservableObject) node;
        List<String> delivery = new ArrayList<>();
        observable.addPropertyChangeListener(event -> {
            delivery.add("first " + event.getNewValue());
            if ("outer".equals(event.getNewValue())) {
                observable.addPropertyChangeListener(nestedEvent ->
                        delivery.add("added " + nestedEvent.getNewValue()));
                node.setName("nested");
            }
        });

        node.setName("outer");

        assertEquals(List.of("first outer", "first nested", "added nested"), delivery);
    }

    @Test
    public void listenerRemovalUsesTheRequestedListenersEquality() {
        var node = Node.newInstance();
        var observable = (ObservableObject) node;
        List<String> delivery = new ArrayList<>();
        PropertyChangeListener existing = event -> delivery.add((String) event.getNewValue());
        observable.addPropertyChangeListener(existing);

        PropertyChangeListener requested = new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent ignored) {
            }

            @Override
            public boolean equals(Object other) {
                return other == this || other == existing;
            }

            @Override
            public int hashCode() {
                return existing.hashCode();
            }
        };
        node.setName("visible");
        observable.removePropertyChangeListener(requested);
        node.setName("invisible");

        assertEquals(List.of("visible"), delivery);
    }

    @Test
    public void externalEqualityCanRemoveTheListenerBeforeDispatch() {
        var node = VNode.newInstance();
        var observable = (ObservableObject) node;
        List<String> delivery = new ArrayList<>();
        PropertyChangeListener listener = event -> delivery.add(event.getPropertyName());
        Object previousValue = new Object() {
            private int calls;

            @Override
            public boolean equals(Object other) {
                // The setter checks equality once. JavaBeans checks it again before choosing listeners.
                if (++calls == 2) observable.removePropertyChangeListener(listener);
                return this == other;
            }

            @Override
            public int hashCode() {
                return System.identityHashCode(this);
            }
        };
        node.setValue(previousValue);
        observable.addPropertyChangeListener(listener);

        node.setValue(new Object());
        node.setValue(new Object());

        assertEquals(List.of(), delivery);
    }
}
