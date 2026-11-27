package eu.mihosoft.vmf.portable.documents.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;

// The JSON module: scalars, lists of values, containment, references, subtypes and wire names.
interface Catalog {
    String getTitle();

    @Annotation(key = "vmf:jackson:rename", value = "entry_count")
    int getCount();

    long getSerial();

    double getRatio();

    boolean getActive();

    Integer getOptional();

    String[] getTags();

    Long[] getSerials();

    @Contains
    Section getMain();

    @Contains
    Section[] getSections();

    Item getFavorite();

    Item[] getHighlights();
}

interface Section {
    String getName();

    @Contains
    Item[] getItems();
}

interface Item {
    String getName();
}

@Annotation(key = "vmf:wire:type", value = "tool")
interface Tool extends Item {
    int getSize();
}

interface Part extends Item {
    String getCode();
}
