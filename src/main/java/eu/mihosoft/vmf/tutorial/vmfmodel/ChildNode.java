package eu.mihosoft.vmf.tutorial.vmfmodel;

import eu.mihosoft.vmf.core.Contained;

/**
 * Created by miho on 08.01.2017.
 */
public interface ChildNode {
    @Contained(opposite = "ContainerNode.child")
    ContainerNode getContainer();

    String getName();
}
