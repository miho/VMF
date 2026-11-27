package eu.mihosoft.vmf.jackson.test.external_types02.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.ExternalType;

@ExternalType(pkgName = "java.util")
@Annotation(key = "vmf:csharp:external-type", value = "Eu.Mihosoft.Vmf.Conformance.Hosts.JavaMap")
interface Map { }
