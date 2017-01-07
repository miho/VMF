package eu.mihosoft.vmf;

import eu.mihosoft.vmf.core.Required;

/**
 * Created by miho on 04.01.2017.
 */
public interface SimpleNode {

    @Required
    String getName();

    @Required
    int getMyId();
}
