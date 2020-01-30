package eu.mihosoft.vmftest.diffing.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;

public interface TreeNode {
    String getName();

    @Container(opposite = "children")
    @Annotation(key = "vmf_container", value = "parent")
    TreeNode getParent();

    @Contains(opposite = "parent")
    @Annotation(key = "vmf_contains", value = "children")
    TreeNode[] getChildren();

    //Additional traits can be attached to an Object

    @Contains(opposite="affiliation")
    @Annotation(key="vmf_contains", value="trait")
    Trait[] getTraits();
}
