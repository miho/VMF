package eu.mihosoft.vmf.conformance.externalbinding.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.ExternalType;
import eu.mihosoft.vmf.core.InterfaceOnly;

import java.util.List;

@ExternalType(pkgName = "java.net")
@Annotation(key = "vmf:csharp:external-type", value = "Eu.Mihosoft.Vmf.Conformance.Externalbinding.Host.LexicalUri")
interface URI {}

@ExternalType(pkgName = "java.util")
interface UUID {}

@ExternalType(pkgName = "eu.mihosoft.vmf.conformance.externalbinding.host")
@Annotation(key = "vmf:csharp:external-value-type", value = "Eu.Mihosoft.Vmf.Conformance.Externalbinding.Host.Distance")
interface Distance {}

@ExternalType(pkgName = "eu.mihosoft.vmf.conformance.externalbinding.host")
@Annotation(key = "vmf:csharp:external-value-type", value = "Eu.Mihosoft.Vmf.Conformance.Externalbinding.Host.DispatchMode")
interface DispatchMode {}

@ExternalType(pkgName = "java.lang.Thread")
@Annotation(key = "vmf:csharp:external-value-type", value = "Eu.Mihosoft.Vmf.Conformance.Externalbinding.Host.ThreadPhase")
interface State {}

@ExternalType(pkgName = "java.lang")
@Annotation(key = "vmf:csharp:external-type", value = "System.Text.StringBuilder")
interface StringBuilder { }

interface Endpoint {
    String getId();
    URI getLocation();
    URI[] getLinks();
    Distance getMeasurement();
    Distance[] getHistory();
}

interface PresetEndpoint {
    @DefaultValue("java.net.URI.create(\"https://example.invalid/default\")")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    URI getLocation();

    @DefaultValue("java.util.UUID.fromString(\"980e13c6-84a0-49d6-821d-737269635aef\")")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    UUID getKey();
}

interface ModeProfile {
    @DefaultValue("eu.mihosoft.vmf.conformance.externalbinding.host.DispatchMode.READY")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    DispatchMode getPreferred();

    DispatchMode[] getHistory();
}

@InterfaceOnly
interface ModeSamples<T> {
    T getLatest();
    List<T> getSamples();
}

@InterfaceOnly
interface ForwardModeSamples<T> extends ModeSamples<T> { }

interface GenericModeProfile extends ForwardModeSamples<DispatchMode> {
    String getId();
}

interface GenericThreadProfile extends ForwardModeSamples<State> { }

interface TextBuffers {
    StringBuilder getTitle();
    StringBuilder[] getLines();
}
