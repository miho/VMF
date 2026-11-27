package eu.mihosoft.vmf.conformance.immutablebehavior;

import eu.mihosoft.vmf.conformance.immutablebehavior.impl.BaseValueImpl;
import eu.mihosoft.vmf.conformance.immutablebehavior.impl.ChildValueImpl;
import eu.mihosoft.vmf.conformance.immutablebehavior.impl.GrandchildValueImpl;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import java.util.Arrays;

public final class ImmutableCovariantOracle {
    private ImmutableCovariantOracle() { }

    static void verify() {
        BaseBinding.count = ChildBinding.count = 0;
        var wide = ValueResult.newBuilder().withId("wide").build();
        var narrow = NarrowValueResult.newBuilder().withId("narrow").withRank(9).build();
        var base = BaseValue.newBuilder().withStored(wide).build();
        var child = ChildValue.newBuilder().withStored(wide).withNarrowStored(narrow).build();
        var grandchild = GrandchildValue.newBuilder().withStored(wide).withNarrowStored(narrow).build();
        if (BaseBinding.count != 0 || ChildBinding.count != 0) throw new AssertionError("Lazy callbacks");
        if (((BaseValueImpl)base).evaluate() != wide || ((ChildValueImpl)child).evaluate() != narrow
                || ((ChildValueImpl)child.clone()).evaluate().getRank() != 9
                || ((GrandchildValueImpl)grandchild).evaluate() != narrow || BaseBinding.count != 1 || ChildBinding.count != 2) {
            throw new AssertionError("Covariant body returned wrong identity or reused the binding across values");
        }
        if (!hasResult(BaseValueImpl.class, ValueResult.class) || !hasResult(ChildValueImpl.class, NarrowValueResult.class)
                || !hasResult(GrandchildValueImpl.class, NarrowValueResult.class)) {
            throw new AssertionError("Return type did not narrow");
        }
        if (Arrays.stream(ChildValue.class.getMethods()).anyMatch(method -> method.getName().equals("evaluate"))) {
            throw new AssertionError("The immutable Java data interface leaked the behavior method");
        }
    }

    private static boolean hasResult(Class<?> type, Class<?> result) {
        return Arrays.stream(type.getDeclaredMethods()).anyMatch(method ->
                method.getName().equals("evaluate") && method.getReturnType() == result);
    }
    public static final class BaseBinding implements DelegatedBehavior<BaseValue> {
        static int count;
        private BaseValue actual;
        @Override public void setCaller(BaseValue actual) { this.actual = actual; count++; }
        public ValueResult evaluate() { return actual.getStored(); }
    }
    public static final class ChildBinding implements DelegatedBehavior<ChildValue> {
        static int count;
        private ChildValue actual;
        @Override public void setCaller(ChildValue actual) { this.actual = actual; count++; }
        public NarrowValueResult evaluate() { return actual.getNarrowStored(); }
    }
}
