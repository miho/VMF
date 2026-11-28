package eu.mihosoft.vmf.jackson.test.mapjournal.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.ExternalType;

@ExternalType(pkgName = "java.util")
@Annotation(key = "vmf:csharp:external-type", value = "Eu.Mihosoft.Vmf.Conformance.Hosts.JavaMap")
@Annotation(key = "vmf:typescript:external-type", value = "JavaMap")
@Annotation(key = "vmf:typescript:external-module", value = "@vmftest/hosts")
interface Map { }
