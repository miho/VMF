package eu.mihosoft.vmf.conformance.behavior;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class GaugeBehavior implements DelegatedBehavior<MutableGauge> {
    public static final List<Integer> HOOK_BASES = new ArrayList<>();
    private MutableGauge owner;

    @Override
    public void setCaller(MutableGauge caller) {
        owner = caller;
    }

    public void onMutableGaugeInstantiated() {
        HOOK_BASES.add(owner.getBase());
    }

    public int adjust(int amount) {
        return owner.getBase() + amount;
    }

    public Integer adjust(Integer amount) {
        return amount == null ? null : owner.getBase() - amount;
    }

    public CallResult compose(CallResult input, String suffix) {
        if (input == null) return null;
        return CallResult.newBuilder().withText(Objects.toString(input.getText(), "") + Objects.toString(suffix, "")).build();
    }

    public void submit(String text) {
        owner.setState(text);
    }

    public int sum(int... values) {
        return Arrays.stream(values).sum();
    }
}
