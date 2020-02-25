package eu.mihosoft.vmftest.complex.vflow;

import eu.mihosoft.vmf.runtime.core.VIterator;
import org.junit.Assert;
import org.junit.Test;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

public class VFlowGlobalListenerTest {
    @Test
    /**
     * Verifies <a href="https://github.com/miho/VMF/issues/36">https://github.com/miho/VMF/issues/36</a>.
     */
    public void testGlobalListener() {

        VFlow flow = VFlow.newInstance();

        AtomicInteger nodesEvtCounter = new AtomicInteger(0);

        flow.vmf().changes().addListener((change)->{
            System.out.println("change: " + change.propertyName());

            if("nodes".equals(change.propertyName())) {
                nodesEvtCounter.incrementAndGet();
            }
        });

        VNode n1 = VNode.newBuilder().withName("my-name 1").build();
        VNode n2 = VNode.newBuilder().withName("my-name 2").build();
        VNode n3 = VNode.newBuilder().withName("my-name 3").build();

        flow.getNodes().add(n1);
        flow.getNodes().add(n2);
        flow.getNodes().add(n3);

        Assert.assertEquals(3, nodesEvtCounter.get());

        nodesEvtCounter.set(0);

        flow.getNodes().remove(n1);
        flow.getNodes().remove(n2);
        flow.getNodes().remove(n3);

        Assert.assertEquals(3, nodesEvtCounter.get());
        
    }
}