package eu.mihosoft.vmftest.complex.vflow;

import java.util.Objects;
import java.util.Collection;
import java.util.List;

import eu.mihosoft.vcollections.EventUtil;
import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.runtime.core.*;
import vjavax.observer.collection.CollectionChange;
import vjavax.observer.collection.CollectionChangeListener;

public class VFlowDelegate implements DelegatedBehavior<VFlow> {
    private VFlow caller;

    public void setCaller(VFlow caller) {
        this.caller = caller;
    }

    public void onVFlowInstantiated() {

        // prevent duplicates & set id
        caller.getNodes().addChangeListener(
                (CollectionChangeListener<VNode, Collection<VNode>, CollectionChange<VNode>>) evt -> {
                    for (VNode n : evt.added().elements()) {
                        // if(caller.getNodes().stream().filter(m->Objects.equals(n,m)).count()>1) {
                        if (caller.getNodes().stream().filter(m -> n == m).count() > 1) {
                            throw new RuntimeException("Duplicate nodes added: " + n);
                        }
                    }
                });
    }

    public ConnectionResult connect(Connector c1, Connector c2) {
        return c1.connect(c2);
    }

    public ConnectionResult tryConnect(Connector c1, Connector c2) {
        return c1.tryConnect(c2);
    }

    public ConnectionResult connect(VNode n1, VNode n2, String type) {
        return null;
    }

    public VNode newNode(Object o) {
        VNode node = VNode.newInstance();
        node.setValue(o);
        caller.getNodes().add(node);

        return node;
    }

    public VFlow newSubFlow(Object o) {
        VFlow node = VFlow.newInstance();
        node.setValue(o);
        caller.getNodes().add(node);

        return node;
    }
}
