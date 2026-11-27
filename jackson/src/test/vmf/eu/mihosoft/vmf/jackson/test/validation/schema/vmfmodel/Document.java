package eu.mihosoft.vmf.jackson.test.validation.schema.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.Length;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Pattern;
import eu.mihosoft.vmf.core.Range;
import eu.mihosoft.vmf.core.Refers;
import eu.mihosoft.vmf.core.Required;

import java.util.Set;

interface Document {
    @Contains(opposite = "document")
    @Multiplicity(lower = 1, upper = 2)
    Entry[] getEntries();

    @Required @DefaultValue("\"draft\"") String getStatus();
    int getRevision();
    @Multiplicity(upper = 0) String getDisabled();
    @Multiplicity(lower = 1) @DefaultValue("java.util.Set.of(\"system\")") Set<String> getTags();
    @Multiplicity(lower = 1, upper = 2) VMap<String, String> getAttributes();
    @Multiplicity(lower = 1, upper = 2) VMap<Integer, String> getLabels();
    @Range(min = "0.125", max = "42.5", maxInclusive = false) Double getRate();
    @Range(min = "2", max = "100") Byte getCount();
    @Pattern(value = "\\A[a-z]+\\z", portable = false)
    @Annotation(key = "vmf:pattern:csharp", value = "\\A[a-z]+\\z")
    @Annotation(key = "vmf:pattern:typescript", value = "^[a-z]+$")
    String getJavaOnly();
    @Length(min = 1, max = 1) String getGlyph();
    @Pattern("^[A-Z]+$") String getAnchored();

    @Contains
    @Annotation(key = "vmf:jackson:schema:constraint", value = "maxProperties=1")
    Detail getPrimary();
}

interface Entry {
    @Required @Container(opposite = "entries") Document getDocument();

    @Required
    @Length(min = 2, max = 4)
    @Pattern("[A-Z]")
    @Annotation(key = "vmf:jackson:rename", value = "entry-name")
    @Annotation(key = "vmf:jackson:schema:constraint", value = "minLength=1")
    String getName();

    String getDetail();
}

interface Detail {
    String getTitle();
    String getNote();
}

interface ReferenceDocument {
    @Contains Station[] getStations();
    @Contains Worker[] getWorkers();
}

interface Station {
    @Refers(opposite = "station")
    @Multiplicity(lower = 1, upper = 1)
    Worker[] getWorkers();

    @Required Worker getSupervisor();
}

interface Worker {
    @Required @Refers(opposite = "workers") Station getStation();
}
