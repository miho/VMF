package eu.mihosoft.vmf.conformance.genericscalars.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;

import java.util.List;

@InterfaceOnly
interface Series<T> {
    @Annotation(key = "vmf:jackson:rename", value = "latest_value")
    T getLatest();

    List<T> getSamples();
    VMap<String, T> getNamed();
}

@InterfaceOnly
interface Reused<T> extends Series<T> { }

@InterfaceOnly
interface Numbered<T extends Number> extends Series<T> { }

interface Counts extends Series<Integer> { }

interface Labels extends Reused<String> { }

interface Flags extends Reused<Boolean> { }

interface Longs extends Numbered<Long> { }

interface Telemetry {
    @Contains Counts getCounts();
    @Contains Labels getLabels();
}
