package eu.mihosoft.vmf.conformance.genericscalars.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.InterfaceOnly;

@InterfaceOnly
interface ScalarHookChannel<T> {
    T getSample();
}

@InterfaceOnly
interface ForwardedScalarHookChannel<U> extends ScalarHookChannel<U> { }

interface TextScalarReading extends ForwardedScalarHookChannel<String> { }

interface IntegerScalarReading extends ScalarHookChannel<Integer> { }

@DelegateTo(className = "eu.mihosoft.vmf.conformance.genericscalars.ScalarReadingBehavior")
interface HookedScalarReading extends IntegerScalarReading {
    int hookRuns();
}

interface ScalarHookReadings {
    @Contains HookedScalarReading getNumber();
    @Contains TextScalarReading getText();
}
