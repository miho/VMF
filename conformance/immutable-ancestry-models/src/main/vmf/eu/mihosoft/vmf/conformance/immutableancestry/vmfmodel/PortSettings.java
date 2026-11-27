package eu.mihosoft.vmf.conformance.immutableancestry.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.ExternalType;
import eu.mihosoft.vmf.core.Immutable;

@ExternalType(pkgName = "eu.mihosoft.vmf.conformance.immutableancestry.host")
@Annotation(key = "vmf:csharp:external-value-type", value = "Eu.Mihosoft.Vmf.Conformance.Immutableancestry.PortMode")
interface PortMode {}

@Immutable
interface PortSettings extends WithDisplayLabel, WithTooltipText {
    @Override
    @DefaultValue("String.valueOf(115200)")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @Annotation(key = "vmf:jackson:rename", value = "port_label")
    String getLabel();

    @DefaultValue("eu.mihosoft.vmf.conformance.immutableancestry.host.PortMode.NORMAL")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    PortMode getMode();
}
