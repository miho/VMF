package eu.mihosoft.vmf.conformance.numeric.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Range;

import java.util.List;

@InterfaceOnly
interface NumericSeries<T extends Number> {
    @DefaultValue("null") T getCurrent();
    List<T> getSamples();
    @Multiplicity(upper = 3) List<T> getBounded();
    VMap<String, T> getByName();
    VMap<T, Long> getByNumber();
    @Range(min = "0.1", max = "0.2", maxInclusive = false) T getThreshold();
}

@InterfaceOnly
interface ForwardedSeries<N extends Number> extends NumericSeries<N> { }

interface DoubleSeries extends ForwardedSeries<Double> { }

interface LongSeries extends NumericSeries<Long> { }
