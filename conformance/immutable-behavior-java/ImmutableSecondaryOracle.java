package eu.mihosoft.vmf.conformance.immutablebehavior;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.immutablebehavior.impl.ComposedChildImpl;
import eu.mihosoft.vmf.conformance.immutablebehavior.impl.HookedComposedImpl;
import eu.mihosoft.vmf.conformance.immutablebehavior.impl.HookedSecondaryImpl;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.conformance.immutablebehavior.impl.ComposedValueImpl;
import eu.mihosoft.vmf.conformance.immutablebehavior.impl.SecondaryBehaviorImpl;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import java.util.ArrayList;
import java.util.List;

public final class ImmutableSecondaryOracle {
    private static final List<SecondaryBehavior> owners = new ArrayList<>();
    private static final List<Counter> providers = new ArrayList<>();

    private static final List<HookedSecondary> hookedOwners = new ArrayList<>();
    private static int hooks;

    private ImmutableSecondaryOracle() { }

    static void verify() throws Exception {
        owners.clear();
        providers.clear();
        var value = ComposedValue.newBuilder().withName("composed").withMetric(5).build();
        var secondary = SecondaryBehavior.newBuilder().withMetric(10).build();
        var child = ComposedChild.newBuilder().withName("child").withMetric(15).build();
        SecondaryBehavior projected = value;
        check(projected == value && projected.getMetric() == 5 && value.getName().equals("composed"),
                "The secondary data interface views the actual value");
        check(owners.isEmpty(), "Reading either data interface leaves delegate binding lazy");
        check(((ComposedValueImpl)value).counter() == 6 && ((ComposedValueImpl)projected.clone()).counter() == 7,
                "Both aliases use one initialized provider");
        check(((SecondaryBehaviorImpl)secondary).counter() == 11 && ((ComposedChildImpl)child).counter() == 16,
                "Each actual value has its own provider and data");
        check(owners.size() == 3 && owners.get(0) == value && owners.get(1) == secondary && owners.get(2) == child
                        && providers.get(0) != providers.get(1) && providers.get(0) != providers.get(2),
                "Java binds the exact actual value once per provider");
        verifyClassLevelBinding();
    }

    private static void verifyClassLevelBinding() throws Exception {
        hookedOwners.clear();
        hooks = 0;
        var value = HookedComposed.newBuilder().withName("composed").withScale(5).build();
        HookedSecondary data = value;
        var separate = HookedSecondary.newBuilder().withScale(10).build();
        check(data.asReadOnly() == value && data.getScale() == 5 && hookedOwners.isEmpty() && hooks == 0,
                "The class-level immutable provider stays lazy on the composed data view");
        check(((HookedComposedImpl)value).counter() == 6 && ((HookedComposedImpl)data.asReadOnly()).counter() == 7
                        && ((HookedSecondaryImpl)separate).counter() == 11,
                "Inherited class-level behavior shares state only for aliases of the same immutable value");
        check(hookedOwners.size() == 2 && hookedOwners.get(0) == value && hookedOwners.get(1) == separate && hooks == 0,
                "Explicit callable dispatch receives each actual immutable value without activating construction hooks");

        var envelope = HookedValueEnvelope.newBuilder().withFirst(data).withSecond(value).build();
        for (boolean references : List.of(false, true)) {
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                    .withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
            var copy = mapper.readValue(mapper.writeValueAsString(envelope.asReadOnly()), HookedValueEnvelope.class);
            check(copy.getFirst() instanceof HookedComposed && copy.getFirst().getScale() == 5
                            && copy.getSecond().getName().equals("composed")
                            && (copy.getFirst() == copy.getSecond()) == references,
                    "Both snapshot profiles retain the concrete subtype and reference mode retains the alias");
        }
        check(hookedOwners.size() == 2 && hooks == 0, "Snapshots leave immutable delegation dormant");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static final class Counter implements DelegatedBehavior<SecondaryBehavior> {
        private SecondaryBehavior actual;
        private int calls;
        @Override public void setCaller(SecondaryBehavior actual) {
            this.actual = actual;
            owners.add(actual);
            providers.add(this);
        }
        public int counter() { return actual.getMetric() + ++calls; }
    }

    public static final class HookCounter implements DelegatedBehavior<HookedSecondary> {
        private HookedSecondary caller;
        private int calls;

        @Override public void setCaller(HookedSecondary caller) {
            this.caller = caller;
            hookedOwners.add(caller);
        }

        public void onHookedSecondaryInstantiated() { hooks++; }
        public void onHookedComposedInstantiated() { hooks++; }
        public int counter() { return caller.getScale() + ++calls; }
    }
}
