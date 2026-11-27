package eu.mihosoft.vmf.conformance.immutablebehavior;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.immutablebehavior.impl.ImmutableEqualityValueImpl;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternal;

import java.io.IOException;

public final class ImmutableEqualityOracle {
    private ImmutableEqualityOracle() { }

    static void verify() {
        CodeEquality.created = 0;
        var lower = value(107);
        var upper = value(207);
        var same = value(107);
        var view = lower.asReadOnly();
        check(CodeEquality.created == 0 && view == lower && lower.clone() == lower,
                "Immutable aliases preserve identity and laziness");
        check(((Object)view).equals(upper) && lower.hashCode() == 7 && upper.hashCode() == 7
                        && ((ImmutableEqualityValueImpl)lower).comparisons() == 1
                        && ((ImmutableEqualityValueImpl)upper).comparisons() == 0 && CodeEquality.created == 2,
                "Object and ordinary methods share the same lazy immutable provider");
        // The Java immutable Content wrapper inherits Object equality. Compare the generated structural methods.
        var internal = (VObjectInternal)lower;
        check(internal._vmf_equals(same) && !internal._vmf_equals(upper)
                        && internal._vmf_hashCode() == ((VObjectInternal)same)._vmf_hashCode(),
                "Model structural comparison stays distinct from its Object overrides");

        try {
            lower.vmf().behavior();
            throw new AssertionError("Immutable behavior replacement should be unavailable");
        } catch (UnsupportedOperationException expected) {
            check(lower.equals(upper), "The original immutable provider stays active");
        }
        verifySnapshot(false);
        verifySnapshot(true);
    }

    private static void verifySnapshot(boolean references) {
        var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
        var original = value(107);
        int previousBindings = CodeEquality.created;
        try {
            String snapshot = mapper.writeValueAsString(original.asReadOnly());
            check(snapshot.equals("{\"code\":107}") && CodeEquality.created == previousBindings,
                    "Serialization includes model data and leaves behavior lazy");
            check(original.hashCode() == 7 && snapshot.equals(mapper.writeValueAsString(original)),
                    "Using the provider leaves the wire document unchanged");
            var copy = mapper.readValue(snapshot, ImmutableEqualityValue.class);
            check(copy != original && CodeEquality.created == previousBindings + 1
                            && copy.hashCode() == 7 && CodeEquality.created == previousBindings + 2,
                    "Hydration creates a fresh lazy binding");
        } catch (IOException error) {
            throw new IllegalStateException("Immutable equality snapshot failed", error);
        }
    }

    private static ImmutableEqualityValue value(int code) {
        return ImmutableEqualityValue.newBuilder().withCode(code).build();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class CodeEquality implements DelegatedBehavior<ImmutableEqualityValue> {
        private static int created;
        private ImmutableEqualityValue caller;
        private int count;
        public CodeEquality() { created++; }
        @Override public void setCaller(ImmutableEqualityValue caller) { this.caller = caller; }
        @Override public boolean equals(Object other) {
            count++;
            return other instanceof ImmutableEqualityValue
                    && caller.getCode() % 100 == ((ImmutableEqualityValue)other).getCode() % 100;
        }
        @Override public int hashCode() { return caller.getCode() % 100; }
        public int comparisons() { return count; }
    }
}
