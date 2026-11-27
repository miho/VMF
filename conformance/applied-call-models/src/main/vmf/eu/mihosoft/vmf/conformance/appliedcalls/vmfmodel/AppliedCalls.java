package eu.mihosoft.vmf.conformance.appliedcalls.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.ExternalType;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;

interface Entry {
    String getName();
}

interface SpecialEntry extends Entry {
    int getRank();
}

@InterfaceOnly
interface Holder<T extends Entry> {
    T getItem();
}

interface SpecialHolder extends Holder<SpecialEntry> {
}

@InterfaceOnly
interface Series<T> {
    T getValue();
}

interface Counts extends Series<Integer> {
}

@InterfaceOnly
interface Numbered<T extends Number> {
    T getReading();
}

interface Measurements extends Numbered<Double> {
}

@InterfaceOnly
interface Pair<T extends Entry, V> {
    T getItem();
    V getValue();
}

interface RankedCount extends Pair<SpecialEntry, Integer> { }

@ExternalType(pkgName = "java.net")
@Annotation(key = "vmf:csharp:external-type", value = "System.Uri")
interface URI { }

@ExternalType(pkgName = "java.math")
@Annotation(key = "vmf:csharp:external-value-type", value = "System.Int32")
interface BigInteger { }

@DelegateTo(className = "eu.mihosoft.vmf.conformance.appliedcalls.AppliedCallsConformance.AliasBinding")
interface AliasCallHost {
    String classify(String input);
    String classify(URI input);
    int classifyNumber(int input);
    int classifyNumber(BigInteger input);
}

@Immutable
@DelegateTo(className = "eu.mihosoft.vmf.conformance.appliedcalls.AppliedCallsConformance.BaseQueryBinding")
interface AppliedQuery {
    String getCaption();
    Holder<SpecialEntry> forward(Holder<SpecialEntry> input);
    Holder<SpecialEntry>[] batch(Holder<SpecialEntry>[] inputs);
    Holder<SpecialEntry>[] collect(Holder<SpecialEntry>... inputs);
    Series<Integer> boxed(Series<Integer> input);
    Numbered<Double> numeric(Numbered<Double> input);
    Pair<SpecialEntry, Integer> pair(Pair<SpecialEntry, Integer> input);
    URI echoUri(URI input);
    java.util.UUID echoId(java.util.UUID input);
}

@Immutable
@DelegateTo(className = "eu.mihosoft.vmf.conformance.appliedcalls.AppliedCallsConformance.ReverseQueryBinding")
interface ReverseQuery extends AppliedQuery {
    @Override
    Holder<SpecialEntry>[] collect(Holder<SpecialEntry>... inputs);
}

interface Projector {
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.appliedcalls.AppliedCallsConformance.ProjectorBinding")
    Holder<SpecialEntry> view(Holder<SpecialEntry> input);
}

interface UpdatedProjector extends Projector {
    @Override
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.appliedcalls.AppliedCallsConformance.UpdatedProjectorBinding")
    Holder<SpecialEntry> view(Holder<SpecialEntry> input);
}

interface QueryEnvelope {
    AppliedQuery getQuery();
}

interface JaggedCallHost {
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.appliedcalls.AppliedCallsConformance.JaggedBinding")
    int mark(int[][] rows);

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.appliedcalls.AppliedCallsConformance.JaggedBinding")
    Entry first(Entry[][] rows);

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.appliedcalls.AppliedCallsConformance.JaggedBinding")
    int countRows(int[]... rows);
}
