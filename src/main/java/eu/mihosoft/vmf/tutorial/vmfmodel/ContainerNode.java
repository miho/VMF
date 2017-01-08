package eu.mihosoft.vmf.tutorial.vmfmodel;

import eu.mihosoft.vmf.core.Container;

/**
 * Created by miho on 08.01.2017.
 */
public interface ContainerNode extends ChildNode{
    @Container(opposite = "ChildNode.container")
    ChildNode getChild();

    String getName();


}
