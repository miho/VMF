package eu.mihosoft.vmf.portable.delegation;

import eu.mihosoft.vmf.runtime.core.VObject;

/** A replacement behavior for one Counter (portable contract module "behavior"). */
public class AlternativeCounter implements Counter.Behavior {
    private Counter caller;

    @Override
    public void setCaller(VObject caller) {
        this.caller = (Counter) caller;
    }

    @Override
    public int add(int amount) {
        return 100 * amount;
    }

    @Override
    public String label(String prefix) {
        return prefix + "@" + caller.getStart();
    }

    @Override
    public int sum(int... values) {
        return values.length;
    }

    @Override
    public Counter larger(Counter other) {
        return caller;
    }

    @Override
    public void reset() {
        caller.setStart(-1);
    }

    @Override
    public int calls() {
        return -1;
    }

    @Override
    public int startSeenByHook() {
        return -1;
    }

    @Override
    public int hooksSince() {
        return -1;
    }
}
