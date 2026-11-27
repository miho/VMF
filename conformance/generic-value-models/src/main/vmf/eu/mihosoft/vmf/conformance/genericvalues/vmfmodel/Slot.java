package eu.mihosoft.vmf.conformance.genericvalues.vmfmodel;

import eu.mihosoft.vmf.core.IgnoreEquals;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.VMFEquals;

import java.util.List;

@Immutable
@VMFEquals(VMFEquals.EqualsType.ALL)
interface Tag {
    @IgnoreEquals String getKey();
    String getText();
}

@InterfaceOnly
interface Slot<T> {
    T getCurrent();
    List<T> getHistory();
}

@InterfaceOnly
interface Forwarded<T> extends Slot<T> { }

interface Review extends Forwarded<Tag> { }

interface Texts extends Slot<String> { }

interface Counts extends Slot<Integer> { }

interface BoundedHistory {
    @Multiplicity(upper = 3) List<Integer> getHistory();
}
