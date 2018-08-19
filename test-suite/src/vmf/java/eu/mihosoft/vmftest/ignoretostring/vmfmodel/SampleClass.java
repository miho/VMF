package eu.mihosoft.vmftest.ignoretostring.vmfmodel;

import eu.mihosoft.vmf.core.IgnoreToString;

public interface SampleClass {
    String getName();

    @IgnoreToString
    String getIgnoredProp();
}
