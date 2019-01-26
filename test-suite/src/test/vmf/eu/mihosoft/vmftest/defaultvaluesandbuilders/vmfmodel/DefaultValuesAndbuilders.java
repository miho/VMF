package eu.mihosoft.vmftest.defaultvaluesandbuilders.vmfmodel;

import eu.mihosoft.vmf.core.DefaultValue;

interface WithDefaultValues {
    @DefaultValue(value="true")
    boolean isVisible();

    @DefaultValue(value="\"my name\"")
    String getName();
}
