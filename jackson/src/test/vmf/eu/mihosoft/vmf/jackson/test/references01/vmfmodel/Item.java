package eu.mihosoft.vmf.jackson.test.references01.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Refers;

public interface Item {
    String getId();

    @Container(opposite = "items")
    Document getDocument();

    @Refers(opposite = "selectedItem")
    Document getSelectedBy();
}
