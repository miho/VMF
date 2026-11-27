package eu.mihosoft.vmf.conformance.values.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.Length;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Range;
import eu.mihosoft.vmf.core.Required;

@Annotation(key = "purpose", value = "base configuration")
interface DefaultBase {
    @DefaultValue("\"idle\\nready\"")
    @Annotation(key = "vmf:jackson:rename", value = "display_name")
    @Annotation(key = "role", value = "label")
    @Annotation(key = "role", value = "text")
    String getName();

    @DefaultValue("7")
    Integer getLimit();
}

@Annotation(key = "purpose", value = "runtime configuration")
@Annotation(key = "tag", value = "first")
@Annotation(key = "tag", value = "second")
interface DefaultSettings extends DefaultBase {
    @DefaultValue("-0.0")
    double getBaseline();

    @DefaultValue("0x1.4p-2f")
    Float getRatio();

    @DefaultValue("'中'")
    char getMode();

    @DefaultValue("9007199254740993L")
    Long getSequence();

    @DefaultValue("true")
    Boolean getFlag();

    @DefaultValue("null")
    Short getUnsetValue();

    String getNote();

    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET, upper = 3)
    String[] getStages();

    @Contains
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    DefaultEntry[] getEntries();
}

interface DefaultEntry {
    @DefaultValue("\"new\"")
    String getLabel();
}

interface CheckedOptionalDefaults {
    @DefaultValue("\"new\"")
    @Length(min = 1, max = 4)
    String getLabel();

    @DefaultValue("7")
    @Range(min = "0", max = "10")
    Integer getLimit();

    @DefaultValue("true")
    Boolean getEnabled();

    @Range(min = "0")
    int getPrimitive();

    @Required
    @DefaultValue("true")
    Boolean getMandatory();
}
