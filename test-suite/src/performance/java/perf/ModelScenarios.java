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
 * Computing and Visualization in Science, 2013, 16(4).
 * http://doi.org/10.1007/s00791-014-0230-y
 */
package perf;

import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.ChangeListener;
import eu.mihosoft.vmf.runtime.core.Changes;
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternal;
import eu.mihosoft.vmftest.performance.TreeNode;
import vjavax.observer.Subscription;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeListenerProxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

final class ModelScenarios {
    private static final int SCALARS = 64;
    private static final int SIBLINGS = 32;
    private static final int DEPTH = 6;
    private static final int OBSERVER_FANOUT = 16;
    private static final Comparator<TreeNode> BY_VALUE = Comparator.comparingInt(TreeNode::getValue);

    private ModelScenarios() { }

    static List<Scenario> cases() {
        return List.of(
                new Scenario("typed-scalar-get-set", 65_536, () -> scalarAccess(false)),
                new Scenario("reflective-scalar-get-set", 16_384, () -> scalarAccess(true)),
                new Scenario("containment-reparent", 511, () -> reparent(false)),
                new Scenario("containment-reparent-observed", 255, () -> reparent(true)),
                new Scenario("containment-swap", 1_023, ModelScenarios::swap),
                new Scenario("containment-sort", 255, ModelScenarios::sort),
                new Scenario("opposite-reference-replace", 8_191, ModelScenarios::replaceReference),
                new Scenario("root-scalar-event", 8_192, () -> scalarEvents(false)),
                new Scenario("deep-scalar-event", 8_192, () -> scalarEvents(true)),
                new Scenario("deep-list-event", 4_095, ModelScenarios::listEvents),
                new Scenario("recursive-listener-lifecycle", 64, ModelScenarios::listenerLifecycle),
                new Scenario("native-record-publish", 127, ModelScenarios::record),
                new Scenario("native-undo-redo", 512, ModelScenarios::undoRedo),
                new Scenario("clone-linked-graph", 64, ModelScenarios::cloneGraph)
        );
    }

    static List<Scenario> observerCases() {
        return List.of(
                observer("setter", Observer.NONE, 0, Selection.SPARSE, false),
                observer("beans-manual-1", Observer.BEANS_MANUAL, 1, Selection.SPARSE, false),
                observer("beans-named-1", Observer.BEANS_NAMED, 1, Selection.SPARSE, false),
                observer("native-manual-1", Observer.NATIVE_MANUAL, 1, Selection.SPARSE, false),
                observer("native-named-1", Observer.NATIVE_NAMED, 1, Selection.SPARSE, false),
                observer("native-predicate-1", Observer.NATIVE_PREDICATE, 1, Selection.SPARSE, false),
                observer("native-manual-16", Observer.NATIVE_MANUAL, OBSERVER_FANOUT, Selection.SPARSE, false),
                observer("native-named-16", Observer.NATIVE_NAMED, OBSERVER_FANOUT, Selection.SPARSE, false),
                observer("native-predicate-16", Observer.NATIVE_PREDICATE, OBSERVER_FANOUT, Selection.SPARSE, false),
                observer("native-mixed-16", Observer.NATIVE_MIXED, OBSERVER_FANOUT, Selection.SPARSE, false),
                observer("native-mixed-16", Observer.NATIVE_MIXED, OBSERVER_FANOUT, Selection.SPARSE, true),
                observer("native-named-1", Observer.NATIVE_NAMED, 1, Selection.MATCH, false),
                observer("native-named-16", Observer.NATIVE_NAMED, OBSERVER_FANOUT, Selection.MATCH, false),
                observer("native-named-16", Observer.NATIVE_NAMED, OBSERVER_FANOUT, Selection.MISS, false),
                observer("native-named-1", Observer.NATIVE_NAMED, 1, Selection.SPARSE, true),
                observer("native-named-1", Observer.NATIVE_NAMED, 1, Selection.MATCH, true),
                new Scenario("observer-reparent-named-match", 255, () -> reparent(true, "children", false)),
                new Scenario("observer-reparent-named-miss", 255, () -> reparent(true, "value", false)),
                new Scenario("observer-reparent-named-miss-recording", 255, () -> reparent(true, "value", true))
        );
    }

    private enum Observer { NONE, BEANS_MANUAL, BEANS_NAMED, NATIVE_MANUAL, NATIVE_NAMED, NATIVE_PREDICATE, NATIVE_MIXED }

    private enum Selection {
        SPARSE, MATCH, MISS;

        boolean writesValue(int operation) {
            return this == MATCH || (this == SPARSE && (operation & 7) == 0);
        }

        int matches(int operations) {
            if (this == MATCH) return operations;
            return this == SPARSE ? operations / 8 : 0;
        }
    }

    private static Scenario observer(String name, Observer method, int subscribers, Selection selection, boolean record) {
        String selectivity = selection.name().toLowerCase(Locale.ROOT);
        return new Scenario("observer-" + name + "-" + selectivity + (record ? "-recording" : ""),
                4_096, () -> scalarObserver(method, subscribers, selection, record));
    }

    private static Scenario.Batch scalarObserver(Observer method, int subscribers, Selection selection, boolean record) {
        var root = node("root", 0);
        var leaf = chain(root);
        var changes = root.vmf().changes();
        var counters = new ArrayList<Count>();
        var subscriptions = new ArrayList<Subscription>();
        for (int i = 0; i < subscribers; i++) {
            var counter = new Count();
            counters.add(counter);
            if (isBroadObserver(method, i)) {
                subscriptions.add(changes.addListener(change -> counter.observe(change, leaf, Change.ChangeType.PROPERTY), true));
            } else {
                subscriptions.add(subscribeObserver(method, changes, leaf, counter));
            }
        }
        if (record) changes.start();

        return batch(iterations -> {
            for (int i = 1; i <= iterations; i++) {
                if (selection.writesValue(i)) leaf.setValue(i);
                else leaf.setWeight(i);
            }
        }, iterations -> {
            int lastValue = selection == Selection.SPARSE ? iterations / 8 * 8 : selection.matches(iterations);
            int lastWeight = selection == Selection.MATCH ? 0 : iterations - (selection.writesValue(iterations) ? 1 : 0);
            require(leaf.getValue() == lastValue && leaf.getWeight() == lastWeight, "Observer workload lost a scalar edit");
            for (int i = 0; i < counters.size(); i++) {
                counters.get(i).verify(isBroadObserver(method, i) ? iterations : selection.matches(iterations));
            }
            subscriptions.forEach(Subscription::unsubscribe);
            if (record) {
                require(changes.all().size() == iterations, "Recording lost an edit filtered from delivery");
                changes.stop();
                require(changes.transactions().size() == 1 && changes.transactions().get(0).changes().size() == iterations,
                        "Recording did not publish all edits");
            }
        });
    }

    private static boolean isBroadObserver(Observer method, int index) {
        return method == Observer.NATIVE_MIXED && index == 0;
    }

    private static Subscription subscribeObserver(Observer method, Changes changes, TreeNode leaf, Count counter) {
        ChangeListener handler = change -> {
            if (!"value".equals(change.propertyName())) counter.unexpected++;
            counter.observe(change, leaf, Change.ChangeType.PROPERTY);
        };
        switch (method) {
            case BEANS_MANUAL:
            case BEANS_NAMED:
                PropertyChangeListener callback = event -> {
                    if (event.getSource() != leaf) counter.unexpected++;
                    if ("value".equals(event.getPropertyName())) counter.increment();
                    else if (method == Observer.BEANS_NAMED) counter.unexpected++;
                };
                PropertyChangeListener bean = method == Observer.BEANS_NAMED
                        ? new PropertyChangeListenerProxy("value", callback) : callback;
                var observable = (VObjectInternal) leaf;
                observable.addPropertyChangeListener(bean);
                return () -> observable.removePropertyChangeListener(bean);
            case NATIVE_MANUAL:
                return changes.addListener(change -> {
                    if ("value".equals(change.propertyName())) handler.onChange(change);
                }, true);
            case NATIVE_NAMED:
            case NATIVE_MIXED:
                return changes.addPropertyListener("value", handler, Changes.Scope.RECURSIVE);
            case NATIVE_PREDICATE:
                return changes.addFilteredListener(change -> "value".equals(change.propertyName()),
                        handler, Changes.Scope.RECURSIVE);
            default:
                throw new IllegalArgumentException("An observer is required for each subscriber");
        }
    }

    private static Scenario.Batch scalarAccess(boolean reflect) {
        TreeNode[] nodes = siblings("scalar");
        IntConsumer writes = reflect ? iterations -> {
            for (int i = 0; i < iterations; i++) {
                var property = nodes[i & (SCALARS - 1)].vmf().reflect().propertyByName("value").orElseThrow();
                property.set((Integer) property.get() + 1);
            }
        } : iterations -> {
            for (int i = 0; i < iterations; i++) {
                var node = nodes[i & (SCALARS - 1)];
                node.setValue(node.getValue() + 1);
            }
        };
        return batch(writes, iterations -> {
            for (int i = 0; i < nodes.length; i++) {
                int writesToNode = (iterations + SCALARS - 1 - i) / SCALARS;
                require(nodes[i].getValue() == i + writesToNode, "Unexpected scalar value");
            }
        });
    }

    private static Scenario.Batch reparent(boolean observed) {
        return reparent(observed, null, false);
    }

    private static Scenario.Batch reparent(boolean observed, String propertyName, boolean record) {
        var root = node("root", 0);
        var left = node("left", 0);
        var right = node("right", 0);
        root.getChildren().addAll(List.of(left, right));
        var leftChildren = children(left);
        var rightChildren = children(right);
        var moved = node("moved", 0);
        var leaf = chain(moved);
        int middle = SIBLINGS / 2;
        left.getChildren().add(middle, moved);

        var listEvents = new Count();
        var leafEvents = new Count();
        var changes = observed || record ? root.vmf().changes() : null;
        Subscription subscription;
        if (observed) {
            ChangeListener handler = change -> {
                if (change.getType() == Change.ChangeType.LIST
                        && (change.object() == left || change.object() == right)) listEvents.increment();
                if (change.object() == leaf) leafEvents.increment();
            };
            subscription = propertyName == null ? changes.addListener(handler, true)
                    : changes.addPropertyListener(propertyName, handler, Changes.Scope.RECURSIVE);
        } else {
            subscription = null;
        }
        if (record) changes.start();

        return batch(iterations -> {
            for (int i = 0; i < iterations; i++) {
                TreeNode destination = (i & 1) == 0 ? right : left;
                destination.getChildren().add(middle, moved);
            }
        }, iterations -> {
            boolean back = (iterations & 1) == 0;
            var expectedLeft = new ArrayList<>(leftChildren);
            var expectedRight = new ArrayList<>(rightChildren);
            (back ? expectedLeft : expectedRight).add(middle, moved);
            verifyChildren(left, expectedLeft);
            verifyChildren(right, expectedRight);
            var descendant = moved;
            for (int depth = 0; depth < DEPTH; depth++) {
                require(descendant.getChildren().size() == 1, "Moved subtree lost a descendant");
                var child = descendant.getChildren().get(0);
                require(child.getParent() == descendant, "Moved descendant lost its parent");
                descendant = child;
            }
            require(descendant == leaf, "Moved subtree lost its original leaf");
            if (record) {
                require(changes.all().size() == 2 * iterations, "Recording lost a containment edit filtered from delivery");
                changes.stop();
                require(changes.transactions().size() == 1
                                && changes.transactions().get(0).changes().size() == 2 * iterations,
                        "Recording did not publish all containment edits");
            }
            if (subscription != null) {
                boolean observesContainment = propertyName == null || "children".equals(propertyName);
                boolean observesLeaf = propertyName == null || "value".equals(propertyName);
                require(listEvents.value == (observesContainment ? 2 * iterations : 0),
                        "Recursive listener received an incorrect count of containment edits");
                leaf.setValue(1);
                require(leafEvents.value == (observesLeaf ? 1 : 0), "Incorrect delivery from reparented descendant");
                subscription.unsubscribe();
                leaf.setValue(2);
                require(leafEvents.value == (observesLeaf ? 1 : 0), "Removed recursive listener still receives events");
            }
        });
    }

    private static Scenario.Batch swap() {
        var parent = node("parent", 0);
        var initial = children(parent);
        return batch(iterations -> {
            for (int i = 0; i < iterations; i++) Collections.swap(parent.getChildren(), 4, 27);
        }, iterations -> {
            if ((iterations & 1) != 0) Collections.swap(initial, 4, 27);
            verifyChildren(parent, initial);
        });
    }

    private static Scenario.Batch sort() {
        var parent = node("parent", 0);
        var initial = children(parent);
        var descending = BY_VALUE.reversed();
        return batch(iterations -> {
            for (int i = 0; i < iterations; i++) {
                parent.getChildren().sort((i & 1) == 0 ? descending : BY_VALUE);
            }
        }, iterations -> {
            if ((iterations & 1) != 0) Collections.reverse(initial);
            verifyChildren(parent, initial);
        });
    }

    private static Scenario.Batch replaceReference() {
        var source = node("source", 0);
        var first = node("first", 0);
        var second = node("second", 0);
        source.setLink(first);
        return batch(iterations -> {
            for (int i = 0; i < iterations; i++) source.setLink((i & 1) == 0 ? second : first);
        }, iterations -> {
            boolean back = (iterations & 1) == 0;
            var linked = back ? first : second;
            var released = back ? second : first;
            require(source.getLink() == linked && linked.getLinkedFrom() == source
                    && released.getLinkedFrom() == null, "Reference or opposite is inconsistent");
        });
    }

    private static Scenario.Batch scalarEvents(boolean deep) {
        var root = node("root", 0);
        var leaf = chain(root);
        var target = deep ? leaf : root;
        var received = new Count();
        var subscription = root.vmf().changes().addListener(
                change -> received.observe(change, target, Change.ChangeType.PROPERTY), deep);
        return batch(iterations -> {
            for (int i = 1; i <= iterations; i++) target.setValue(i);
        }, iterations -> {
            require(target.getValue() == iterations, "Incorrect scalar state");
            received.verify(iterations);
            subscription.unsubscribe();
        });
    }

    private static Scenario.Batch listEvents() {
        var root = node("root", 0);
        var leaf = chain(root);
        leaf.getTags().add("first");
        var received = new Count();
        var subscription = root.vmf().changes().addListener(
                change -> received.observe(change, leaf, Change.ChangeType.LIST), true);
        return batch(iterations -> {
            for (int i = 0; i < iterations; i++) leaf.getTags().set(0, (i & 1) == 0 ? "second" : "first");
        }, iterations -> {
            require(leaf.getTags().equals(List.of((iterations & 1) == 0 ? "first" : "second")),
                    "Incorrect list state");
            received.verify(iterations);
            subscription.unsubscribe();
        });
    }

    private static Scenario.Batch listenerLifecycle() {
        var root = node("root", 0);
        var leaves = new ArrayList<TreeNode>();
        tree(root, 3, leaves);
        var leaf = leaves.get(0);
        var received = new Count();
        return batch(iterations -> {
            for (int i = 1; i <= iterations; i++) {
                var listener = root.vmf().changes().addListener(
                        change -> received.observe(change, leaf, Change.ChangeType.PROPERTY), true);
                leaf.setValue(i);
                listener.unsubscribe();
            }
        }, iterations -> {
            leaf.setValue(iterations + 1);
            received.verify(iterations);
        });
    }

    private static Scenario.Batch record() {
        var root = node("root", 0);
        var leaf = chain(root);
        leaf.getTags().add("first");
        var changes = root.vmf().changes();
        return batch(iterations -> {
            for (int i = 1; i <= iterations; i++) {
                changes.start();
                changes.startTransaction();
                leaf.setValue(i);
                leaf.getTags().set(0, (i & 1) == 0 ? "first" : "second");
                changes.publishTransaction();
                changes.stop();
            }
        }, iterations -> {
            require(changes.transactions().size() == 1 && changes.all().size() == 2,
                    "Expected one transaction with two edits");
            var transaction = changes.transactions().get(0);
            require(transaction.changes().size() == 2 && leaf.getValue() == iterations
                    && leaf.getTags().get(0).equals((iterations & 1) == 0 ? "first" : "second"),
                    "Native transaction did not retain the scalar and list edits");
        });
    }

    private static Scenario.Batch undoRedo() {
        var root = node("root", 0);
        var initial = children(root);
        var changes = root.vmf().changes();
        changes.start();
        changes.startTransaction();
        root.setValue(3);
        Collections.swap(root.getChildren(), 4, 27);
        changes.publishTransaction();
        changes.stop();
        var transaction = changes.transactions().get(0);
        require(transaction.changes().size() == 3, "Undo/redo requires a scalar and two list edits");
        var undoResults = new Count();
        return batch(iterations -> {
            for (int i = 0; i < iterations; i++) {
                transaction.undo();
                if (root.getValue() == 0 && root.getChildren().get(4) == initial.get(4)) undoResults.increment();
                for (Change change : transaction.changes()) change.apply(change.object());
            }
        }, iterations -> {
            require(undoResults.value == iterations && root.getValue() == 3, "Undo or redo has the wrong state");
            Collections.swap(initial, 4, 27);
            verifyChildren(root, initial);
        });
    }

    private static Scenario.Batch cloneGraph() {
        var root = node("root", 0);
        var leaves = new ArrayList<TreeNode>();
        tree(root, 3, leaves);
        for (int i = 0; i < leaves.size(); i++) {
            leaves.get(i).setLink(leaves.get((i + 17) % leaves.size()));
        }
        TreeNode[] last = new TreeNode[1];
        return batch(iterations -> {
            for (int i = 0; i < iterations; i++) last[0] = root.clone();
        }, iterations -> {
            Map<TreeNode, TreeNode> mapped = new IdentityHashMap<>();
            collectClones(root, last[0], null, mapped);
            require(mapped.size() == 85, "Expected an 85-node clone");
            mapped.forEach((source, clone) -> require(clone.getLink() == mapped.get(source.getLink())
                    && clone.getLinkedFrom() == mapped.get(source.getLinkedFrom()),
                    "Clone has a missing or shared reference"));
        });
    }

    private static void collectClones(TreeNode source, TreeNode clone, TreeNode parent, Map<TreeNode, TreeNode> mapped) {
        require(clone != source && source.getId().equals(clone.getId()) && clone.getParent() == parent
                && source.getChildren().size() == clone.getChildren().size()
                && source.getValue() == clone.getValue(), "Clone has a mismatched node");
        mapped.put(source, clone);
        for (int i = 0; i < source.getChildren().size(); i++) {
            collectClones(source.getChildren().get(i), clone.getChildren().get(i), clone, mapped);
        }
    }

    private static TreeNode[] siblings(String prefix) {
        TreeNode[] result = new TreeNode[SCALARS];
        for (int i = 0; i < result.length; i++) result[i] = node(prefix + "-" + i, i);
        return result;
    }

    private static TreeNode chain(TreeNode root) {
        TreeNode parent = root;
        for (int i = 0; i < DEPTH; i++) {
            TreeNode child = node(parent.getId() + "-child", 0);
            parent.getChildren().add(child);
            parent = child;
        }
        return parent;
    }

    private static void tree(TreeNode root, int remainingDepth, List<TreeNode> leaves) {
        if (remainingDepth == 0) {
            leaves.add(root);
            return;
        }
        for (int i = 0; i < 4; i++) {
            var child = node(root.getId() + "-" + i, i);
            root.getChildren().add(child);
            tree(child, remainingDepth - 1, leaves);
        }
    }

    private static List<TreeNode> children(TreeNode parent) {
        List<TreeNode> result = new ArrayList<>();
        for (int i = 0; i < SIBLINGS; i++) result.add(node(parent.getId() + "-" + i, i));
        parent.getChildren().addAll(result);
        return result;
    }

    private static TreeNode node(String id, int value) {
        return TreeNode.newBuilder().withId(id).withValue(value).build();
    }

    private static void verifyChildren(TreeNode parent, List<TreeNode> expected) {
        var actual = parent.getChildren();
        require(actual.size() == expected.size(), "Containment has the wrong number of children");
        for (int i = 0; i < expected.size(); i++) {
            require(actual.get(i) == expected.get(i) && actual.get(i).getParent() == parent,
                    "Containment has the wrong identity, order, or parent");
        }
    }

    private static Scenario.Batch batch(IntConsumer work, IntConsumer verify) {
        return new Scenario.Batch() {
            @Override public void run(int iterations) { work.accept(iterations); }
            @Override public void verify(int iterations) { verify.accept(iterations); }
        };
    }

    private static final class Count {
        private int value;
        private int unexpected;

        void increment() { value++; }

        void observe(Change change, TreeNode target, Change.ChangeType type) {
            if (change.object() != target || change.getType() != type) unexpected++;
            increment();
        }

        void verify(int expected) {
            require(value == expected && unexpected == 0, "Wrong event count, source, or type");
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
