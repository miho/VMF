package eu.mihosoft.vmf;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Required;
import eu.mihosoft.vmf.core.Container;

import java.util.List;

/**
 * Created by miho on 04.01.2017.
 */
public interface SimpleNode {

    @Required
    String getName();

    @Required
    int getMyId();

    @Container(opposite = "parent")
    List<SimpleNode> getChildren();

    @Contains(opposite = "children")
    SimpleNode getParent();

    int[] getIds();
}
