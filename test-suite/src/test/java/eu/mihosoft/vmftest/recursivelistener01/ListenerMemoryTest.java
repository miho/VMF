package eu.mihosoft.vmftest.recursivelistener01;

import org.junit.Assert;
import org.junit.Test;

import java.lang.ref.WeakReference;

/**
 * Observing a model must not keep it alive once nothing else refers to it, also when observers at several levels
 * order their relays (portable contract E8) and a recording stamps events (H4).
 */
public class ListenerMemoryTest {

    @Test
    public void observedModelsCanBeCollected() throws InterruptedException {
        WeakReference<Node> root = observedTree();
        for (int attempt = 0; attempt < 50 && root.get() != null; attempt++) {
            System.gc();
            Thread.sleep(20);
        }
        Assert.assertNull("An observed model that nothing refers to any more must be collectable", root.get());
    }

    private static WeakReference<Node> observedTree() {
        Node root = Node.newInstance();
        Node child = Node.newInstance();
        Node leaf = Node.newInstance();
        root.getChildren().add(child);
        child.getChildren().add(leaf);
        // Observers at every level, the farthest one first, as VMF-Text's converter adds them.
        root.vmf().changes().addListener(change -> { });
        child.vmf().changes().addListener(change -> { });
        leaf.vmf().changes().addListener(change -> { }, false);
        root.vmf().changes().start();
        leaf.setName("edited");
        root.vmf().changes().stop();
        Assert.assertEquals(1, root.vmf().changes().all().size());
        return new WeakReference<>(root);
    }
}
