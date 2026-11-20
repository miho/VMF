/* SPDX-License-Identifier: Apache-2.0 */
package eu.mihosoft.vmftest.recursivelistener01;

import eu.mihosoft.vmf.runtime.core.ChangeListener;
import eu.mihosoft.vmf.runtime.core.Changes;
import org.junit.Test;
import vjavax.observer.Subscription;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ContainmentSequenceTest {
    @Test
    public void containmentAndRecursiveDeliveryAgreeAfterEveryEdit() {
        for (long seed : new long[]{19, 43, 97, 211}) runSequence(seed);
    }

    private void runSequence(long seed) {
        Random random = new Random(seed);
        Node[] parents = {Node.newInstance(), Node.newInstance()};
        Node[] leaves = new Node[6];
        int[] owner = new int[leaves.length];
        Arrays.fill(owner, -1);
        for (int i = 0; i < leaves.length; i++) {
            leaves[i] = Node.newBuilder().withName("leaf-" + i).build();
        }

        int[] deliveries = new int[parents.length];
        Subscription[] subscriptions = new Subscription[parents.length];
        ChangeListener[] counters = new ChangeListener[parents.length];
        boolean[] failOnList = {false};
        RuntimeException failure = new IllegalStateException("observer failure");
        for (int p = 0; p < parents.length; p++) {
            final int index = p;
            counters[p] = change -> deliveries[index]++;
            parents[p].getChildren().addChangeListener(event -> {
                if (failOnList[0]) throw failure;
            });
            subscriptions[p] = observe(parents[p], counters[p]);
        }

        for (int step = 0; step < 240; step++) {
            int p = random.nextInt(parents.length);
            int i = random.nextInt(leaves.length);
            int op = random.nextInt(6);
            String context = "seed=" + seed + ", step=" + step + ", operation=" + op;
            failOnList[0] = random.nextInt(4) == 0;
            try {
                switch (op) {
                    case 0:
                    case 1:
                        if (owner[i] == p) break;
                        owner[i] = p;
                        if (op == 0) parents[p].getChildren().add(leaves[i]);
                        else leaves[i].setParent(parents[p]);
                        break;
                    case 2:
                        if (owner[i] < 0) break;
                        Node previous = parents[owner[i]];
                        owner[i] = -1;
                        if (random.nextBoolean()) previous.getChildren().remove(leaves[i]);
                        else leaves[i].setParent(null);
                        break;
                    case 3:
                        List<Node> incoming = new ArrayList<>();
                        for (int j = 0; j < leaves.length; j++) {
                            if (owner[j] != p && random.nextBoolean()) {
                                owner[j] = p;
                                incoming.add(leaves[j]);
                            }
                        }
                        if (!incoming.isEmpty()) parents[p].getChildren().addAll(incoming);
                        break;
                    case 4:
                        for (int j = 0; j < leaves.length; j++) if (owner[j] == p) owner[j] = -1;
                        parents[p].getChildren().clear();
                        break;
                    default:
                        if (subscriptions[p] == null) {
                            subscriptions[p] = observe(parents[p], counters[p]);
                        } else {
                            subscriptions[p].unsubscribe();
                            subscriptions[p] = null;
                        }
                }
            } catch (RuntimeException ex) {
                assertSame(context, failure, ex);
                assertTrue(context, failOnList[0]);
            }
            failOnList[0] = false;
            for (int j = 0; j < leaves.length; j++) {
                Node leaf = leaves[j];
                assertSame(context, owner[j] < 0 ? null : parents[owner[j]], leaf.getParent());
                Arrays.fill(deliveries, 0);
                leaf.setName(step + "-" + j);
                for (int root = 0; root < parents.length; root++) {
                    long occurrences = parents[root].getChildren().stream().filter(node -> node == leaf).count();
                    assertEquals(context, owner[j] == root ? 1 : 0, occurrences);
                    assertEquals(context, owner[j] == root && subscriptions[root] != null ? 1 : 0, deliveries[root]);
                }
            }
        }
        for (Subscription subscription : subscriptions) if (subscription != null) subscription.unsubscribe();
    }

    private Subscription observe(Node root, ChangeListener counter) {
        return root.vmf().changes().addPropertyListener("name", counter, Changes.Scope.RECURSIVE);
    }
}
