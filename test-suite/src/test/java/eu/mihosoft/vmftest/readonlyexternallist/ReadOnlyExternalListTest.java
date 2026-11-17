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
package eu.mihosoft.vmftest.readonlyexternallist;

import eu.mihosoft.vcollections.VList;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

/**
 * The generated read-only views expose collection properties with external
 * element types as live, structurally unmodifiable lists (miho/VMF#92).
 */
public class ReadOnlyExternalListTest {

    private static Sample sampleWithLabels(String... labels) {
        Sample model = Sample.newInstance();
        model.getLabels().addAll(Arrays.asList(labels));
        return model;
    }

    /**
     * Every structural change through the read-only getter is rejected,
     * including iterator, sub-list, bulk and {@link VList}-specific operations,
     * and none of them reaches the model.
     */
    @Test
    public void readOnlyGetterRejectsStructuralChanges() {
        Sample model = sampleWithLabels("a", "b");
        VList<String> labels = model.asReadOnly().getLabels();

        List<Runnable> mutations = Arrays.asList(
                () -> labels.add("x"),
                () -> labels.add(0, "x"),
                () -> labels.set(0, "x"),
                () -> labels.remove(0),
                () -> labels.remove("a"),
                () -> labels.clear(),
                () -> labels.addAll(Arrays.asList("x", "y")),
                () -> labels.addAll(0, Arrays.asList("x", "y")),
                () -> labels.removeAll(Arrays.asList("a")),
                () -> labels.retainAll(Arrays.asList("a")),
                () -> labels.removeIf(s -> true),
                () -> labels.replaceAll(s -> s + "!"),
                () -> labels.sort(Comparator.reverseOrder()),
                () -> { Iterator<String> it = labels.iterator(); it.next(); it.remove(); },
                () -> { ListIterator<String> it = labels.listIterator(); it.add("x"); },
                () -> { ListIterator<String> it = labels.listIterator(); it.next(); it.set("x"); },
                () -> { ListIterator<String> it = labels.listIterator(1); it.previous(); it.remove(); },
                () -> labels.subList(0, 1).clear(),
                () -> labels.subList(0, 1).add("x"),
                () -> labels.subList(0, 1).set(0, "x"),
                () -> labels.subList(0, 2).remove(0),
                () -> labels.subList(0, 2).removeIf(s -> true),
                () -> labels.removeAll(0),
                () -> labels.setAll(0, Arrays.asList("x")),
                () -> labels.addAll(new int[]{0}, Arrays.asList("x"))
        );

        for (int i = 0; i < mutations.size(); i++) {
            Runnable mutation = mutations.get(i);
            try {
                mutation.run();
                Assert.fail("structural change #" + i + " through the read-only view must be rejected");
            } catch (UnsupportedOperationException expected) {
                // expected
            }
        }

        Assert.assertEquals(Arrays.asList("a", "b"), model.getLabels());
        Assert.assertEquals(Arrays.asList("a", "b"), labels);
    }

    /**
     * The view is live: changes made through the mutable model are visible,
     * and the getter keeps returning the same view.
     */
    @Test
    public void readOnlyListIsLive() {
        Sample model = sampleWithLabels("a");
        ReadOnlySample view = model.asReadOnly();
        VList<String> labels = view.getLabels();

        model.getLabels().add("b");
        model.getLabels().set(0, "z");
        Assert.assertEquals(Arrays.asList("z", "b"), labels);

        model.getLabels().remove("b");
        Assert.assertEquals(Arrays.asList("z"), labels);

        Assert.assertSame(labels, view.getLabels());

        // the view compares like the list it shows, in both directions
        Assert.assertEquals(model.getLabels(), labels);
        Assert.assertEquals(labels, model.getLabels());
        Assert.assertEquals(model.getLabels().hashCode(), labels.hashCode());
    }

    /**
     * Listeners on the view observe the legitimate changes made through the
     * model; rejected changes through the view produce no events, and the
     * events name the view (not the mutable list) as their source.
     */
    @Test
    public void readOnlyListForwardsChangeEvents() {
        Sample model = sampleWithLabels("a", "b");
        VList<String> labels = model.asReadOnly().getLabels();

        List<String> events = new ArrayList<>();
        List<Object> sources = new ArrayList<>();
        labels.addChangeListener(evt -> {
            sources.add(evt.source());
            if (evt.wasAdded()) events.add("+" + evt.added().elements());
            if (evt.wasRemoved()) events.add("-" + evt.removed().elements());
        });

        // rejected attempts through the view
        try { Iterator<String> it = labels.iterator(); it.next(); it.remove(); } catch (UnsupportedOperationException expected) { }
        try { labels.subList(0, 1).clear(); } catch (UnsupportedOperationException expected) { }
        try { labels.subList(0, 2).removeIf(s -> true); } catch (UnsupportedOperationException expected) { }
        Assert.assertEquals("rejected changes must not be reported", new ArrayList<String>(), events);

        // legitimate changes through the model
        model.getLabels().add("c");
        model.getLabels().remove("a");
        Assert.assertEquals(Arrays.asList("+[c]", "-[a]"), events);

        for (Object source : sources) {
            Assert.assertSame(labels, source);
        }
    }

    /**
     * The view hands out the element instances of the model, also for mutable
     * external element types.
     */
    @Test
    public void readOnlyListKeepsElementIdentity() {
        Sample model = Sample.newInstance();
        StringBuilder first = new StringBuilder("first");
        StringBuilder second = new StringBuilder("second");
        model.getBuffers().addAll(Arrays.asList(first, second));

        VList<StringBuilder> buffers = model.asReadOnly().getBuffers();
        Assert.assertSame(first, buffers.get(0));
        Assert.assertSame(second, buffers.get(1));

        try {
            buffers.add(new StringBuilder("third"));
            Assert.fail("the read-only view must reject add()");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        Assert.assertEquals(2, model.getBuffers().size());
    }

    /**
     * Reflection on a read-only view returns the same unmodifiable list as the
     * getter.
     */
    @Test
    public void reflectionOnReadOnlyViewReturnsUnmodifiableList() {
        Sample model = sampleWithLabels("a");
        ReadOnlySample view = model.asReadOnly();

        @SuppressWarnings("unchecked")
        List<String> labels = (List<String>) view.vmf().reflect().propertyByName("labels").get().get();
        Assert.assertSame(view.getLabels(), labels);

        try {
            labels.add("x");
            Assert.fail("the list returned by reflection must reject add()");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        Assert.assertEquals(Arrays.asList("a"), model.getLabels());
    }

    /**
     * Model-element collections keep their unmodifiable mapped view.
     */
    @Test
    public void modelElementListStaysUnmodifiable() {
        Sample model = Sample.newInstance();
        model.getEntries().add(Entry.newBuilder().withName("e1").build());
        ReadOnlySample view = model.asReadOnly();

        Assert.assertEquals("e1", view.getEntries().get(0).getName());
        try {
            view.getEntries().remove(0);
            Assert.fail("the read-only view must reject remove()");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        Assert.assertEquals(1, model.getEntries().size());
    }
}
