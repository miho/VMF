package eu.mihosoft.vmf.runtime.core;

import java.util.List;

public interface Diffing {
    void init(VObject targetObj);

    void setSource(VObject source);

    void printChanges();

    void applyChanges();

    List<Change> getChanges();
}
