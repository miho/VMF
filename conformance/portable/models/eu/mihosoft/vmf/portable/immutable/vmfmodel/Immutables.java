package eu.mihosoft.vmf.portable.immutable.vmfmodel;

import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;

// From test-suite vmftest/immutabletypes (2019) and vmftest/getteronly (2019).
@Immutable
interface ImmutableType {
    String getName();
}

@Immutable
interface ImmutableTypeWithList {
    String[] getNames();
}

@InterfaceOnly
interface WithName {
    @GetterOnly
    String getName();
}

@Immutable
interface ImmutableObj extends WithName {
}

interface MutableObj extends WithName {
}

// A getter-only trait that extends another one, implemented by an immutable and a mutable type.
@InterfaceOnly
interface WithTitle extends WithName {
    @GetterOnly
    String getTitle();
}

@Immutable
interface Label extends WithTitle {
}

interface MutableLabel extends WithTitle {
}

// A mutable owner that refers to immutable values.
interface Palette {
    String getTitle();

    ImmutableType getPrimary();
}
