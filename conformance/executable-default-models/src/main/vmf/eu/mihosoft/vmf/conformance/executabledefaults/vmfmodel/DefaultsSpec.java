package eu.mihosoft.vmf.conformance.executabledefaults.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.Immutable;

interface MutableDefaults {
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.executabledefaults.ExecutableDefaults.fallbackLabel()")
    String getLabel();

    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.executabledefaults.ExecutableDefaults.computeQuota()")
    int getQuota();
}

@Immutable
interface ImmutableDefaults {
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.executabledefaults.ExecutableDefaults.fallbackLabel()")
    String getLabel();

    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.executabledefaults.ExecutableDefaults.scale()")
    Double getScale();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.executabledefaults.DefaultBehavior")
interface HookedDefaults {
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.executabledefaults.ExecutableDefaults.fallbackLabel()")
    String getLabel();

    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.executabledefaults.ExecutableDefaults.hookPriority()")
    int getPriority();
}

interface AncestorDefaults {
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.executabledefaults.ExecutableDefaults.sample(\"base\")")
    String getLabel();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.executabledefaults.JoinedDefaultBehavior")
interface JoinedDefaults extends AncestorDefaults {
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.executabledefaults.ExecutableDefaults.sample(\"alias\")")
    String getAlias();
}

interface ReplacedDefaults extends AncestorDefaults {
    @Override
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @Annotation(key = "vmf:jackson:rename", value = "short_label")
    @DefaultValue("eu.mihosoft.vmf.conformance.executabledefaults.ExecutableDefaults.sample(\"replacement\")")
    String getLabel();
}

interface LiteralChildDefaults extends AncestorDefaults {
    @Override
    @DefaultValue("\"literal\"")
    String getLabel();
}

interface ClearedChildDefaults extends AncestorDefaults {
    @Override
    String getLabel();
}

@Immutable
interface AncestorValueDefaults {
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.executabledefaults.ExecutableDefaults.sample(\"base-value\")")
    String getLabel();
}

@Immutable
interface InheritedValueDefaults extends AncestorValueDefaults {}

@Immutable
interface ReplacedValueDefaults extends AncestorValueDefaults {
    @Override
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @Annotation(key = "vmf:jackson:rename", value = "fixed_label")
    @DefaultValue("eu.mihosoft.vmf.conformance.executabledefaults.ExecutableDefaults.sample(\"derived-value\")")
    String getLabel();
}

@Immutable
interface ClearedValueDefaults extends AncestorValueDefaults {
    @Override
    String getLabel();
}
