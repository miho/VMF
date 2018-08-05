package eu.mihosoft.eu.mihosoft.recursivelistener01;


import eu.mihosoft.vmftest.recursivelistener01.Node;
import org.junit.Assert;
import org.junit.Test;

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
}
