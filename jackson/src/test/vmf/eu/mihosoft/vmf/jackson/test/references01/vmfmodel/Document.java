package eu.mihosoft.vmf.jackson.test.references01.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Refers;

/**
 * The model of miho/VMF#93: a containment list and a selection (cross reference with opposite).
 */
public interface Document {
    String getId();

    @Contains(opposite = "document")
    Item[] getItems();

    @Refers(opposite = "selectedBy")
    Item getSelectedItem();
}
