package eu.mihosoft.vmf.conformance.nominalbounds.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;

@InterfaceOnly
interface WorkTitle { @GetterOnly String getTitle(); }

@InterfaceOnly
interface WorkRank { @GetterOnly int getRank(); }

@InterfaceOnly
interface RankedTitle extends WorkTitle, WorkRank { }

interface WorkItem extends RankedTitle {
    String getTitle();
    int getRank();
}

@InterfaceOnly
interface RankedSelection<T extends RankedTitle> {
    @Annotation(key = "vmf:jackson:rename", value = "selected_item")
    T getSelected();
}

interface WorkReview extends RankedSelection<WorkItem> { }

interface RankedWorkBook {
    @Contains WorkItem getWork();
    @Contains WorkReview getReview();
}
