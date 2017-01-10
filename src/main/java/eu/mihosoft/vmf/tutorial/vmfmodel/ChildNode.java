package eu.mihosoft.vmf.tutorial.vmfmodel;

import eu.mihosoft.vmf.core.Contains;

/**
 * Created by miho on 08.01.2017.
 */
public interface ChildNode {
    @Contains(opposite = "ContainerNode.child")
    ContainerNode getContainer();

    String getName();
}
