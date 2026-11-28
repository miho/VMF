package eu.mihosoft.vmf.portable.delegation;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

import java.util.Arrays;

/** The default behavior of Counter (portable contract module "behavior"); each object has its own instance. */
public class CounterBehavior implements DelegatedBehavior<Counter> {
    private static int hooks;

    private Counter caller;
    private int calls;
    private int seenStart = -1;
    private int sequence;

    @Override
    public void setCaller(Counter caller) {
        this.caller = caller;
    }

    /** Runs before the declared defaults: it sees start 0, and the default replaces the 99 it sets. */
    public void onCounterInstantiated() {
        seenStart = caller.getStart();
        caller.setNote("hooked");
        caller.setStart(99);
        sequence = ++hooks;
    }

    public int add(int amount) {
        calls++;
        return caller.getStart() + amount;
    }

    public String label(String prefix) {
        return prefix + ":" + caller.getNote();
    }

    public int sum(int... values) {
        return Arrays.stream(values).sum();
    }

    public Counter larger(Counter other) {
        return other != null && other.getStart() > caller.getStart() ? other : caller;
    }

    public void reset() {
        caller.setStart(0);
    }

    public int calls() {
        return calls;
    }

    public int startSeenByHook() {
        return seenStart;
    }

    public int hooksSince() {
        return hooks - sequence;
    }
}
