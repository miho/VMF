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


import eu.mihosoft.vmftest.recursivelistener01.nocontainment.NodeNoContainment;
import org.junit.Assert;
import org.junit.Test;
import vjavax.observer.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class RecursiveListenerTest {

    @Test
    public void recursiveVsNonRecursiveListenerTest() {

        // in this test we validate the behavior of
        // recursive vs. non recursive listeners

        // to accomplish that, we create a tree
        Node root = Node.newInstance();

        root.setName("ROOT");

        List<Node> parents = new ArrayList<>();
        parents.add(root);

        for( int depth = 0; depth < 3; depth++ ) {
            List<Node> layer = new ArrayList<>();
            for(Node p : parents) {
                for (int i = 0; i < 10; i++) {
                    Node n = Node.newInstance();
                    n.setName("d=" + depth + ", i=" + i);
                    p.getChildren().add(n);
                    layer.add(n);
                }
            }
            parents = layer;
        }

        // setup nonrecursive listeners and event counter
        AtomicInteger nonRecursiveChanges = new AtomicInteger();
        root.vmf().changes().addListener(l -> {
            nonRecursiveChanges.incrementAndGet();
        }, false);

        // setup recursive listeners and event counter
        AtomicInteger recursiveChanges = new AtomicInteger();
        root.vmf().changes().addListener(l -> {
            recursiveChanges.incrementAndGet();
        }, true);

        // root node changes:

        // first change: the root name
        root.setName("root");

        Assert.assertEquals(1, nonRecursiveChanges.get());
        Assert.assertEquals(1, recursiveChanges.get());

        // second change: add child node to root
        root.getChildren().add(Node.newBuilder().withName("evt node").build());

        Assert.assertEquals(2, nonRecursiveChanges.get());
        Assert.assertEquals(2, recursiveChanges.get());

        nonRecursiveChanges.set(0);
        recursiveChanges.set(0);

        // child node changes:

        // first change: the name of the descendant
        Node descentant = root.getChildren().get(2).getChildren().get(7);
        descentant.setName("my new name");

        Assert.assertEquals(0, nonRecursiveChanges.get());
        Assert.assertEquals(1, recursiveChanges.get());

        // second change: add a child node to the descendant
        descentant.getChildren().add(Node.newBuilder().withName("evt node").build());

        Assert.assertEquals(0, nonRecursiveChanges.get());
        Assert.assertEquals(2, recursiveChanges.get());

        nonRecursiveChanges.set(0);
        recursiveChanges.set(0);

    }

    @Test
    public void liveListenersSurviveRepeatedRecordingSessions() {
        Node root = Node.newInstance();
        Node child = Node.newInstance();
        root.getChildren().add(child);
        var changes = root.vmf().changes();
        AtomicInteger recursiveEvents = new AtomicInteger();
        AtomicInteger rootEvents = new AtomicInteger();
        Subscription recursive = changes.addListener(event -> recursiveEvents.incrementAndGet(), true);
        Subscription rootOnly = changes.addListener(event -> rootEvents.incrementAndGet(), false);

        try {
            for (int session = 0; session < 2; session++) {
                root.setName("root before " + session);
                child.setName("child before " + session);
                changes.start();
                root.setName("root during " + session);
                child.setName("child during " + session);
                changes.stop();

                root.setName("root after " + session);
                child.setName("child after " + session);
                Assert.assertEquals(6 * (session + 1), recursiveEvents.get());
                Assert.assertEquals(3 * (session + 1), rootEvents.get());
                Assert.assertEquals(2, changes.all().size());
                Assert.assertEquals(1, changes.transactions().size());
            }
        } finally {
            changes.stop();
            recursive.unsubscribe();
            rootOnly.unsubscribe();
        }
    }

    @Test
    public void listenersCanBeAddedAndRemovedDuringRecording() {
        Node root = Node.newInstance();
        Node child = Node.newInstance();
        root.getChildren().add(child);
        var changes = root.vmf().changes();
        AtomicInteger removedEvents = new AtomicInteger();
        Subscription recursive = changes.addListener(event -> removedEvents.incrementAndGet(), true);
        Subscription rootOnly = changes.addListener(event -> removedEvents.incrementAndGet(), false);

        changes.start();
        recursive.unsubscribe();
        rootOnly.unsubscribe();
        child.setName("recorded with no public listeners");
        Assert.assertEquals(1, changes.all().size());

        AtomicInteger recursiveEvents = new AtomicInteger();
        AtomicInteger rootEvents = new AtomicInteger();
        Subscription newRecursive = changes.addListener(event -> recursiveEvents.incrementAndGet(), true);
        Subscription newRootOnly = changes.addListener(event -> rootEvents.incrementAndGet(), false);
        changes.stop();
        try {
            root.setName("root after recording");
            child.setName("child after recording");
            Assert.assertEquals(2, recursiveEvents.get());
            Assert.assertEquals(1, rootEvents.get());
            Assert.assertEquals(0, removedEvents.get());
            Assert.assertEquals(1, changes.all().size());
        } finally {
            newRecursive.unsubscribe();
            newRootOnly.unsubscribe();
        }

        root.setName("root after unsubscribe");
        child.setName("child after unsubscribe");
        Assert.assertEquals(2, recursiveEvents.get());
        Assert.assertEquals(1, rootEvents.get());
    }

    @Test
    public void reparentingKeepsObservationAtExistingNotificationBoundaries() {
        Node root = Node.newInstance();
        Node source = Node.newInstance();
        Node destination = Node.newInstance();
        Node subtree = Node.newInstance();
        Node leaf = Node.newInstance();
        root.getChildren().addAll(List.of(source, destination));
        source.getChildren().add(subtree);
        subtree.getChildren().add(leaf);

        List<String> delivery = new ArrayList<>();
        var changes = root.vmf().changes();
        var rootListener = changes.addListener(change -> {
            if (change.object() == source) {
                Assert.assertSame(source, subtree.getParent());
                delivery.add("source removal");
                leaf.setName("before detach");
            } else if (change.object() == destination) {
                Assert.assertSame(destination, subtree.getParent());
                delivery.add("destination addition");
                leaf.setName("before observation resumes");
            } else if (change.object() == leaf) {
                delivery.add("root saw " + change.propertyChange().orElseThrow().newValue());
            }
        });
        var localListener = subtree.vmf().reflect().propertyByName("parent").orElseThrow()
                .addChangeListener(change -> {
                    String phase = subtree.getParent() == null ? "detached" : "attached";
                    delivery.add(phase);
                    leaf.setName(phase);
                });

        try {
            changes.start();
            changes.startTransaction();
            destination.getChildren().add(subtree);
            leaf.setName("after move");
            changes.publishTransaction();
            changes.stop();

            Assert.assertEquals(List.of("source removal", "root saw before detach", "detached", "attached",
                    "destination addition", "root saw after move"), delivery);
            Assert.assertSame(destination, subtree.getParent());
            Assert.assertSame(subtree, leaf.getParent());
            Assert.assertTrue(source.getChildren().isEmpty());
            Assert.assertSame(subtree, destination.getChildren().get(0));
            var recorded = changes.transactions().get(0).changes();
            Assert.assertEquals(4, recorded.size());
            Assert.assertSame(leaf, recorded.get(0).object());
            Assert.assertSame(source, recorded.get(1).object());
            Assert.assertSame(destination, recorded.get(2).object());
            Assert.assertSame(leaf, recorded.get(3).object());
        } finally {
            changes.stop();
            localListener.unsubscribe();
            rootListener.unsubscribe();
        }
        leaf.setName("after unsubscribe");
        Assert.assertEquals(6, delivery.size());
    }

    @Test
    public void registerUnregisterSimpleProperties() {

        AtomicInteger changeCounter = new AtomicInteger();

        NodeNoContainment root = NodeNoContainment.newInstance();

        root.vmf().changes().addListener(change -> {
            if("name".equals(change.propertyName())) {
                changeCounter.incrementAndGet();
            }
        });

        NodeNoContainment n1 = NodeNoContainment.newInstance();

        root.setNode(n1);

        // no event because not contained, just regular property
        n1.setName("my name 0");

        Assert.assertEquals(0, changeCounter.get());
        changeCounter.set(0);

        root.getChildren().add(n1);

        // one event before releasing node as regular property (no containment, no cross-ref)
        n1.setName("my name 1");

        Assert.assertEquals(1, changeCounter.get());
        changeCounter.set(0);

        root.setNode(null);

        // should be fired if still present on the object graph (path to root exists)
        n1.setName("my name 2");
        n1.setName("my name 3");

        Assert.assertEquals(2, changeCounter.get());
        changeCounter.set(0);

        // should fire no event (not contained anymore)
        n1.setParent(null);

        n1.setName("my name 4");

        Assert.assertEquals(0, changeCounter.get());
        changeCounter.set(0);

    }
}
