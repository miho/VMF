package eu.mihosoft.vmf.conformance.modelsummaries.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.ExternalType;
import eu.mihosoft.vmf.core.IgnoreToString;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.List;
import java.util.Set;

interface SummaryFields {
    @IgnoreToString
    String getHidden();

    @Annotation(key = "vmf:jackson:rename", value = "caption")
    String getName();
}

interface SummaryNode extends SummaryFields {
    SummaryNode getAnchor();

    @Contains(opposite = "owner")
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    SummaryNode[] getChildren();

    @Container(opposite = "children")
    SummaryNode getOwner();

    Set<SummaryNode> getPeers();

    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    SummaryNode[] getRanked();

    SummaryNode[] getTrail();
}

interface DerivedSummary extends SummaryNode {
    int getRank();
}

@ExternalType(pkgName = "java.util")
interface UUID { }

@Immutable
interface SummaryToken {
    @IgnoreToString
    String getSecret();

    String getText();
}

interface SummaryValues {
    @Multiplicity(upper = 2)
    List<Integer> getChecked();

    Set<Double> getDoubles();

    UUID getExternal();

    SummaryToken getFirst();

    Float getMagnitude();

    Double[] getReadings();

    SummaryToken[] getTokens();

    VMap<String, Long> getValues();

    String[] getWords();
}
