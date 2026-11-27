package eu.mihosoft.vmf.conformance.immutablebehavior;

import eu.mihosoft.vmf.conformance.immutablebehavior.impl.HookTraitValueImpl;
import eu.mihosoft.vmf.conformance.immutablebehavior.impl.HookValueImpl;
import eu.mihosoft.vmf.conformance.immutablebehavior.impl.IndependentValueImpl;
import eu.mihosoft.vmf.conformance.immutablebehavior.impl.MethodValueImpl;
import eu.mihosoft.vmf.conformance.immutablebehavior.impl.TraitValueImpl;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.util.Arrays;

public final class ImmutableBehaviorConformance {
    private static int methodBindings;
    private static int hookBindings;
    private static int hooks;
    private static int traitBindings;
    private static int localTraitBindings;
    private static int hookTraitBindings;
    private static int traitHooks;

    private ImmutableBehaviorConformance() { }

    public static void main(String[] args) throws Exception {
        var independent = (IndependentValueImpl) IndependentValue.newBuilder().withText("same name").build();
        check(independent.first() == 101 && independent.second() == 201
                        && independent.firstAgain() == 102 && independent.second() == 202,
                "Class-qualified delegates keep independent providers and share callbacks within each class");
        check(((IndependentValueImpl) IndependentValue.newBuilder().build()).first() == 101,
                "A separate value starts with fresh providers");

        MethodValue first = MethodValue.newBuilder().withText("alpha").build();
        MethodValue second = MethodValue.newBuilder().withText("beta").build();
        HookValue hooked = HookValue.newBuilder().withText("four").build();
        TraitValue trait = TraitValue.newBuilder().withBase(3).withCode("trait").build();
        HookTraitValue hookedTrait = HookTraitValue.newBuilder().withBase(4).withCode("hook").build();

        check(methodBindings == 0 && hookBindings == 0 && hooks == 0 && traitBindings == 0
                && localTraitBindings == 0 && hookTraitBindings == 0 && traitHooks == 0, "Immutable behavior bindings start lazy");
        check(!hasMethod(MethodValue.class, "render") && !hasMethod(HookValue.class, "score")
                && !hasMethod(TraitValue.class, "metric") && !hasMethod(ReadOnlyMetricTrait.class, "metric")
                && !hasMethod(ReadOnlyHookTrait.class, "measure"), "Direct immutable and read-only contracts expose data");
        check(hasMethod(HookTraitValue.class, "measure"),
                "Java's method-free getter-only bridge inherits the ancestor's nominal method");
        check(!MetricTrait.class.isInstance(trait) && ReadOnlyMetricTrait.class.isInstance(trait),
                "An immutable trait implementation uses the read-only Java interface");

        var implementation = (MethodValueImpl) first;
        check("!alpha".equals(implementation.render("!")) && "alp".equals(implementation.render(3))
                && implementation.sum(2, 3, 5) == 10 && implementation.calls() == 2 && methodBindings == 1,
                "An implementation downcast reaches the hidden model method and reuses the same named delegate");
        check(((MethodValueImpl) second).calls() == 0 && methodBindings == 2,
                "Each value constructs its own binding");
        check(first.asReadOnly() == first && first.clone() == first
                && ((MethodValueImpl) first.asReadOnly()).calls() == 2, "Immutable aliases retain their binding");

        check(((HookValueImpl) hooked).score(3) == 7 && hookBindings == 1 && hooks == 0,
                "An immutable class-level binding supplies methods");
        var traitImplementation = (TraitValueImpl) trait;
        check(traitImplementation.metric(2) == 5 && traitBindings == 1 && localTraitBindings == 0,
                "The immutable implementation inherits a named trait binding");
        check("trait!".equals(traitImplementation.describe("!")) && localTraitBindings == 1 && traitBindings == 1,
                "A local method uses a separate binding");
        check(traitImplementation.metric(3) == 6 && traitBindings == 1 && localTraitBindings == 1,
                "Each method keeps its binding provenance");
        check(hookedTrait.measure(2) == 8 && ((HookTraitValueImpl) hookedTrait).measure(3) == 12
                && hookTraitBindings == 1 && traitHooks == 0,
                "An inherited class-level binding supplies methods");
        var mutable = MutableTraitValue.newBuilder().withBase(9).build();
        check(mutable.metric(2) == 11 && traitBindings == 2, "The same host binding also handles mutable trait callers");
        check(HookOnlyValue.newBuilder().withCount(8).build().getCount() == 8 && hooks == 0,
                "A class-level declaration without callable methods stays data-only");
        ImmutableToStringOracle.verify();
        ImmutableEqualityOracle.verify();

        try {
            hooked.vmf().behavior();
            throw new AssertionError("Expected immutable behavior replacement to be unavailable.");
        } catch (UnsupportedOperationException expected) {
            check(hooks == 0 && traitHooks == 0, "Construction hooks stay unevaluated");
        }
        ImmutableCovariantOracle.verify();
        ImmutableSecondaryOracle.verify();
        CombinedBehaviorOracle.verify();
        System.out.println("Java verified immutable hidden delegation and inherited getter-only trait bindings.");
    }

    private static boolean hasMethod(Class<?> type, String name) {
        return Arrays.stream(type.getMethods()).anyMatch(method -> method.getName().equals(name));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class First {
        public static final class Twin implements DelegatedBehavior<IndependentValue> {
            private int calls;
            public int first() { return 100 + ++calls; }
            public int firstAgain() { return 100 + ++calls; }
        }
    }

    public static final class Second {
        public static final class Twin implements DelegatedBehavior<IndependentValue> {
            private int calls;
            public int second() { return 200 + ++calls; }
        }
    }

    public static final class MethodBinding implements DelegatedBehavior<MethodValue> {
        private MethodValue caller;
        private int timesCalled;

        @Override public void setCaller(MethodValue caller) {
            this.caller = caller;
            methodBindings++;
        }

        public String render(String prefix) {
            timesCalled++;
            return prefix + caller.getText();
        }

        public String render(int length) {
            timesCalled++;
            return caller.getText().substring(0, length);
        }

        public int calls() { return timesCalled; }

        public int sum(int... values) { return Arrays.stream(values).sum(); }
    }

    public static final class UnusedHookBinding implements DelegatedBehavior<HookOnlyValue> {
        public UnusedHookBinding() { throw new AssertionError("An immutable hook-only binding is not constructed."); }

        public void onHookOnlyValueInstantiated() { hooks++; }
    }

    public static final class HookBinding implements DelegatedBehavior<HookValue> {
        private HookValue caller;

        @Override public void setCaller(HookValue caller) {
            this.caller = caller;
            hookBindings++;
        }

        public void onHookValueInstantiated() { hooks++; }

        public int score(int increment) { return caller.getText().length() + increment; }
    }

    public static final class TraitBinding implements DelegatedBehavior<VObject> {
        private VObject caller;

        @Override public void setCaller(VObject caller) {
            this.caller = caller;
            traitBindings++;
        }

        public int metric(int delta) {
            int base = caller instanceof MetricTrait ? ((MetricTrait) caller).getBase()
                    : ((ReadOnlyMetricTrait) caller).getBase();
            return base + delta;
        }
    }

    public static final class HookTraitBinding implements DelegatedBehavior<ReadOnlyHookTrait> {
        private ReadOnlyHookTrait caller;

        @Override public void setCaller(ReadOnlyHookTrait caller) {
            this.caller = caller;
            hookTraitBindings++;
        }

        public void onHookTraitInstantiated() { traitHooks++; }

        public int measure(int factor) { return caller.getBase() * factor; }
    }

    public static final class LocalTraitBinding implements DelegatedBehavior<TraitValue> {
        private TraitValue caller;

        @Override public void setCaller(TraitValue caller) {
            this.caller = caller;
            localTraitBindings++;
        }

        public String describe(String suffix) { return caller.getCode() + suffix; }
    }
}
