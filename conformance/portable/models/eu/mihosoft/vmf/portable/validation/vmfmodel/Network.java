package eu.mihosoft.vmf.portable.validation.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Length;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Pattern;
import eu.mihosoft.vmf.core.Range;

// From test-suite vmftest/modelvalidation (ModelValidationTest, 2026), extended to every kind of bound:
// multiplicities of single values and lists, ranges of int, long, float and double (inclusive, exclusive,
// beyond 2^53), lengths in code points and portable patterns, on a tree with a single and a list containment.
interface Network {
    @Multiplicity(lower = 1)
    @Length(min = 3, max = 8)
    String getName();

    @Contains(opposite = "network")
    @Multiplicity(lower = 1, upper = 3)
    Segment[] getSegments();

    @Contains
    Segment getMain();

    // A plain reference: validation does not follow it.
    Segment getRemote();
}

// A supertype with constraints; Segment's properties interleave with its own in reflection order.
interface Window {
    @Range(min = "0", max = "10", maxInclusive = false)
    double getStart();

    @Range(min = "0.1", minInclusive = false)
    Double getRatio();
}

interface Segment extends Window {
    @Container(opposite = "segments")
    Network getNetwork();

    @Multiplicity(lower = 1)
    @Length(min = 2, max = 8)
    @Pattern("^[a-z]+$")
    String getCode();

    @Range(min = "1", max = "4")
    int getLanes();

    @Range(min = "-9223372036854775808", max = "9223372036854775807", maxInclusive = false)
    long getSerial();

    @Contains(opposite = "segment")
    Part[] getParts();
}

interface Part {
    @Container(opposite = "parts")
    Segment getSegment();

    @Length(max = 4)
    @Pattern("^[A-Z]\\d?$")
    String getLabel();

    // Not anchored: a note needs a digit somewhere.
    @Pattern("\\d")
    String getNote();

    // \w is ASCII in every target.
    @Pattern("^\\w*$")
    String getKey();

    @Range(min = "9007199254740992", minInclusive = false)
    Long getBig();

    @Range(max = "0.1")
    float getLevel();
}
