package eu.mihosoft.vmftest.immutabletypes.vmfmodel;

import eu.mihosoft.vmf.core.Immutable;

@Immutable
interface ImmutableType {
    String getName();
}

// should compile, see https://github.com/miho/VMF/issues/48
@Immutable
interface ImmutableTypeWithList {
    String[] getNames();
}