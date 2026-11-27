package eu.mihosoft.vmf.conformance.behavior.vmfmodel;

import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.List;

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behavior.GaugeBehavior")
interface MutableGauge {
    @DefaultValue("8") int getBase();
    @DefaultValue("\"ready\"") String getState();

    int adjust(int amount);
    Integer adjust(Integer amount);
    CallResult compose(CallResult input, String suffix);
    void submit(String text);
    int sum(int... values);

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behavior.GaugeDiagnostics")
    String diagnose(boolean verbose);
}

interface SignalGauge {
    @DefaultValue("4") int getLevel();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behavior.SignalBehavior")
    int signal(int increment);
}

@Immutable
interface CallResult {
    String getText();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behavior.JournalCheckpointBehavior")
interface HookedJournalCheckpoint {
    @Multiplicity(upper = 3) List<Integer> getSlots();
    int invocation();
}
