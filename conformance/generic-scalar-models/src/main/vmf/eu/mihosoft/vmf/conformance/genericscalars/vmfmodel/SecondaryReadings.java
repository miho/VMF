package eu.mihosoft.vmf.conformance.genericscalars.vmfmodel;

import eu.mihosoft.vmf.core.InterfaceOnly;

import java.util.List;

@InterfaceOnly
interface SampleValues<T> {
    T getLatest();
    List<T> getHistory();
}

@InterfaceOnly
interface ForwardValues<U> extends SampleValues<U> { }

@InterfaceOnly
interface IntegerReadings extends ForwardValues<Integer> { }

@InterfaceOnly
interface TextReadings extends ForwardValues<String> { }

interface ReadingDocument { String getId(); }

interface ComposedMeasurements extends ReadingDocument, IntegerReadings { }

interface ComposedLabels extends ReadingDocument, TextReadings { }

interface PrimaryMeasurements extends IntegerReadings { }

interface DirectMeasurements extends ReadingDocument, ForwardValues<Integer> { }
