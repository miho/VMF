package eu.mihosoft.vmf.conformance.collectiondefaults.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.List;
import java.util.Set;

interface DefaultCollections {
    @Contains ManagedSamples getMutable();
    FrozenSamples getFrozen();
}

interface ManagedSamples {
    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.collectiondefaults.CollectionDefaults.numbers()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getNumbers();

    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET, upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.collectiondefaults.CollectionDefaults.labels()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<String> getLabels();

    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.collectiondefaults.CollectionDefaults.codes()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    Set<Long> getCodes();

    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.collectiondefaults.CollectionDefaults.options()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    VMap<String, Integer> getOptions();
}

@Immutable
interface FrozenSamples {
    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.collectiondefaults.CollectionDefaults.numbers()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getNumbers();

    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET, upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.collectiondefaults.CollectionDefaults.labels()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<String> getLabels();

    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.collectiondefaults.CollectionDefaults.codes()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    Set<Long> getCodes();

    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.collectiondefaults.CollectionDefaults.options()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    VMap<String, Integer> getOptions();
}

@Immutable
interface LiteralSamples {
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    @DefaultValue("java.util.List.of(\"a,b\", \"c\\\\d\", \"e\\\"f\")")
    List<String> getStages();

    @DefaultValue("java.util.Map.of(\"port\", 0x50)")
    VMap<String, Integer> getPorts();
}

interface SchemaLiteralSamples {
    @Multiplicity(lower = 1, upper = 3)
    @DefaultValue("java.util.List.of(7, 11)")
    List<Integer> getValues();

    @Multiplicity(lower = 1, upper = 2)
    @DefaultValue("java.util.Set.of(2L)")
    Set<Long> getCodes();

    @Multiplicity(lower = 1, upper = 2)
    @DefaultValue("java.util.Map.of(4, \"ready\")")
    VMap<Integer, String> getChoices();

    @Multiplicity(lower = 1, upper = 3)
    @DefaultValue("java.util.List.of()")
    List<Integer> getNeedsWire();
}
