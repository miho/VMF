package eu.mihosoft.vmf.portable.externals.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.ExternalType;

// External value types (contract module "externals"). Host types: Java eu.mihosoft.vmf.portable.externals
// (next to this package), C# conformance/portable/csharp/Hosts, TypeScript conformance/portable/typescript/hosts.

/** A Java enum: C# maps it to an enum, TypeScript to a union of its constant names. */
@ExternalType(pkgName = "eu.mihosoft.vmf.portable.externals")
@Annotation(key = "vmf:csharp:external-value-type", value = "Vmf.Portable.Hosts.Level")
@Annotation(key = "vmf:typescript:external-type", value = "\"LOW\" | \"MEDIUM\" | \"HIGH\"")
interface Level {
}

/** Built into every target: C# Guid, TypeScript a canonical UUID string. */
@ExternalType(pkgName = "java.util")
interface UUID {
}

/** A host value type with its own document form, {"meters": number}, and value equality. */
@ExternalType(pkgName = "eu.mihosoft.vmf.portable.externals")
@Annotation(key = "vmf:csharp:external-type", value = "Vmf.Portable.Hosts.Distance")
@Annotation(key = "vmf:typescript:external-type", value = "Distance")
@Annotation(key = "vmf:typescript:external-module", value = "../../../../../../../hosts/externals.js")
interface Distance {
}

interface Sensor {
    String getName();

    Level getLevel();

    @DefaultValue("Level.MEDIUM")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    Level getThreshold();

    Level[] getHistory();

    UUID getId();

    UUID[] getRelated();

    /** An untyped value: null, a string, a boolean, a number, a list or a map with string keys. */
    Object getPayload();

    Object[] getPayloads();

    Distance getRange();

    Distance[] getRanges();
}
