package eu.mihosoft.vmftest.diffing.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Container;

public interface Trait {
    String getName();
    String getTrait();

    @Container(opposite="traits")
    @Annotation(key="vmf_container", value="affiliation")
    TreeNode getAffiliation();
}
