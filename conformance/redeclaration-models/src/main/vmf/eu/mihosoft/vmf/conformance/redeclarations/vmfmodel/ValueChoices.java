package eu.mihosoft.vmf.conformance.redeclarations.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;

@InterfaceOnly
interface ValueChoice extends PayloadCarrier {
    @GetterOnly Object getValue();
}

interface TextOption extends ValueChoice {
    @DefaultValue("null") String getValue();
}

interface NumberOption extends ValueChoice {
    @DefaultValue("null") Double getValue();
}

interface FlagOption extends ValueChoice {
    @DefaultValue("null") Boolean getValue();
}

interface ObjectOption extends ValueChoice {
    OptionObject getValue();
}

interface ArrayOption extends ValueChoice {
    OptionArray getValue();
}

interface NullOption extends ValueChoice {
    Object getValue();
}

interface OptionObject {
    String getLabel();
}

interface OptionArray {
    String[] getLabels();
}

interface OptionDocument {
    @Contains ValueChoice[] getOptions();
    @Contains OptionObject getObjectTarget();
    @Contains OptionArray getArrayTarget();
}
