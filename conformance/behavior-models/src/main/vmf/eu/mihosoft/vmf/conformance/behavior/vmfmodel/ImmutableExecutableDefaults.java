package eu.mihosoft.vmf.conformance.behavior.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.Immutable;

import java.util.Set;

@Immutable
interface ImmutableExecutableDefaults {
    @Annotation(key = "vmf:jackson:rename", value = "first")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.behavior.ImmutableDefaultTrace.alpha()")
    String getAlpha();

    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.behavior.ImmutableDefaultTrace.members()")
    Set<ImmutableDefaultMember> getMembers();

    ImmutableDefaultMember getSelected();

    ImmutableDefaultWitness getWitness();

    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.behavior.ImmutableDefaultTrace.zulu()")
    int getZulu();
}

@Immutable
interface ImmutableDefaultMember {
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.behavior.ImmutableDefaultTrace.label()")
    String getLabel();
}

@Immutable
interface ImmutableDefaultWitness {
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.behavior.ImmutableDefaultTrace.witness()")
    String getTitle();
}
