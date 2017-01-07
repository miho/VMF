package eu.mihosoft.vmf.tutorial.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Required;
import eu.mihosoft.vmf.core.Contained;

import java.util.List;

/**
 * Created by miho on 04.01.2017.
 */
public interface SimpleNode {

    @Required
    String getName();

    @Required
    int getMyId();

    @Contained(opposite = "parent")
    List<SimpleNode> getChildren();

    @Container(opposite = "children")
    SimpleNode getParent();

    int[] getIds();

    @Container(opposite = "slave")
    SimpleNode getMaster();

    @Contained(opposite = "master")
    SimpleNode getSlave();
}
