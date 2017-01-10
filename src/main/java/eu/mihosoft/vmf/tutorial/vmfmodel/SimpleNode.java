package eu.mihosoft.vmf.tutorial.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.IgnoreEquals;
import eu.mihosoft.vmf.core.Required;

import java.util.List;

/**
 * Created by miho on 04.01.2017.
 */
public interface SimpleNode {

    @Required
    String getName();

    @Required
    @IgnoreEquals
    int getMyId();

    @Contains(opposite = "parent")
    @IgnoreEquals
    List<SimpleNode> getChildren();

    @Container(opposite = "children")
    @IgnoreEquals
    SimpleNode getParent();

    int[] getIds();

    @Container(opposite = "slave")
    @IgnoreEquals
    SimpleNode getMaster();

    @Contains(opposite = "master")
    @IgnoreEquals
    SimpleNode getSlave();
}
