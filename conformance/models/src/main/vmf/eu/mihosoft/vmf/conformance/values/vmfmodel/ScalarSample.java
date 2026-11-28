package eu.mihosoft.vmf.conformance.values.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Length;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Pattern;
import eu.mihosoft.vmf.core.Range;
import eu.mihosoft.vmf.core.Required;

import java.util.Set;

interface ScalarSample {
    boolean getActive();
    Boolean getOptionalActive();
    byte getByteValue();
    Byte getOptionalByte();
    short getShortValue();
    Short getOptionalShort();
    int getIntValue();
    Integer getOptionalInt();
    long getLongValue();
    Long getOptionalLong();
    char getCharValue();
    Character getOptionalChar();
    float getFloatValue();
    Float getOptionalFloat();
    double getDoubleValue();
    Double getOptionalDouble();
    String getDescription();
    Double getSignedZero();
    Float getTinyFloat();
    Double getTinyDouble();
    Double getNegativeInfinity();
    Character getSurrogateUnit();
}

interface ScalarBatch {
    @Multiplicity(lower = 1)
    @Length(min = 1, max = 1)
    String getTitle();

    @Contains
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    ScalarEntry[] getEntries();
}

interface ScalarBounds {
    @Range(min = "9007199254740993", max = "9223372036854775807")
    Long getSequence();

    @Range(min = "0.1", max = "0.5", maxInclusive = false)
    Float getRatio();

    @Range(min = "1e23")
    Double getLargeBound();

    @Range(min = "1.4e-45", max = "1.4e-45")
    Float getSmallFloat();

    @Range(min = "4.9e-324", max = "4.9e-324")
    Double getSmallDouble();

    @Range(min = "1e400")
    Double getAboveFiniteDouble();
}

interface ScalarEntry extends ScalarBounds {
    @Required
    @Length(min = 2, max = 32)
    @Pattern("[A-Z]-\\d{2}")
    @Annotation(key = "vmf:jackson:rename", value = "status_label")
    String getStatusLabel();

    @Pattern("^[\\w]+$")
    String getNotes();
}

interface PatternedLabel {
    @Length(min = 4, max = 8)
    @Pattern(value = "\\A[A-Z&&[^IO]]+[0-9]{2}\\z", portable = false)
    @Annotation(key = "vmf:pattern:csharp", value = "\\A[A-Z-[IO]]+[0-9]{2}\\z")
    @Annotation(key = "vmf:pattern:typescript", value = "^[A-HJ-NP-Z]+[0-9]{2}$")
    String getLabel();
}

interface NonzeroPatternedLabel extends PatternedLabel {
    @Override
    @Pattern(value = "(?<!0)0\\z|[1-9]\\z", portable = false)
    @Annotation(key = "vmf:pattern:csharp", value = "(?<!0)0\\z|[1-9]\\z")
    @Annotation(key = "vmf:pattern:typescript", value = "(?<!0)0$|[1-9]$")
    @Annotation(key = "vmf:jackson:rename", value = "tracking_label")
    String getLabel();
}

interface InstrumentRack {
    @Contains(opposite = "rack")
    InstrumentProbe[] getProbes();
}

interface InstrumentProbe {
    @Container(opposite = "probes")
    @Required
    InstrumentRack getRack();

    @Multiplicity(upper = 0)
    @Annotation(key = "vmf:jackson:rename", value = "reserved_slot")
    String getReserved();
}

interface SignalMatrix {
    @Contains SignalNode[] getNodes();

    @Multiplicity(lower = 1, upper = 2)
    Set<SignalNode> getSelected();

    @Multiplicity(upper = 0)
    SignalNode getDisabledLink();

    @Contains(opposite = "disabledOwner")
    @Multiplicity(upper = 0)
    SignalNode getDisabledOwned();
}

interface SignalNode {
    String getName();

    @Container(opposite = "disabledOwned")
    @Multiplicity(upper = 0)
    SignalMatrix getDisabledOwner();
}
