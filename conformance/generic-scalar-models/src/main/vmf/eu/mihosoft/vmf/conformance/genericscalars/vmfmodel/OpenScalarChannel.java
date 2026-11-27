package eu.mihosoft.vmf.conformance.genericscalars.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.InterfaceOnly;

import java.util.List;

@InterfaceOnly
interface KeyedChannel<K> {
    K getChannelKey();
    List<K> getPreviousKeys();
}

@InterfaceOnly
interface PayloadChannel<V> {
    V getLatestPayload();
    VMap<String, V> getNamedPayloads();
}

@InterfaceOnly
interface RevisionChannel<N extends Number> {
    N getRevision();
}

@InterfaceOnly
interface OpenScalarChannel<K, V, N extends Number> extends KeyedChannel<K>, PayloadChannel<V>, RevisionChannel<N> {}

interface MeasurementChannel extends OpenScalarChannel<String, Double, Long> {}

interface TextChannel extends OpenScalarChannel<Integer, String, Integer> {}
