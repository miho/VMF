package eu.mihosoft.vmf.conformance.immutablebehavior;

import eu.mihosoft.vmf.conformance.immutablebehavior.impl.CombinedBehaviorValueImpl;
import eu.mihosoft.vmf.conformance.immutablebehavior.impl.ReversedBehaviorValueImpl;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

public final class CombinedBehaviorOracle {
    private CombinedBehaviorOracle() { }

    public static void main(String[] args) {
        verify();
    }

    static void verify() {
        var first = (CombinedBehaviorValueImpl) CombinedBehaviorValue.newBuilder().withLeft(3).withRight(7).build();
        check(first.leftCall() == 104 && first.preferred() == 105 && first.rightCall() == 208
                        && first.preferred() == 106,
                "The first role handles the common call while each provider keeps independent state");
        var reversed = (ReversedBehaviorValueImpl) ReversedBehaviorValue.newBuilder().withLeft(3).withRight(7).build();
        check(reversed.leftCall() == 104 && reversed.preferred() == 208
                        && reversed.rightCall() == 209 && reversed.preferred() == 210,
                "Parent order selects the common call and each value gets fresh providers");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class Left implements DelegatedBehavior<LeftBehaviorValue> {
        private LeftBehaviorValue owner;
        private int calls;
        @Override public void setCaller(LeftBehaviorValue owner) { this.owner = owner; }
        public int leftCall() { return 100 + owner.getLeft() + ++calls; }
        public int preferred() { return leftCall(); }
    }

    public static final class Right implements DelegatedBehavior<RightBehaviorValue> {
        private RightBehaviorValue owner;
        private int calls;
        @Override public void setCaller(RightBehaviorValue owner) { this.owner = owner; }
        public int rightCall() { return 200 + owner.getRight() + ++calls; }
        public int preferred() { return rightCall(); }
    }
}
