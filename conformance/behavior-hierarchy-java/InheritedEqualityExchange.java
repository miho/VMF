package eu.mihosoft.vmf.conformance.behaviorhierarchy;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;

public final class InheritedEqualityExchange {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(VMFJacksonModule
            .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private InheritedEqualityExchange() { }

    public static void verify(Path exchange, boolean write) throws IOException {
        if (write) {
            var value = exercise();
            var path = exchange.resolve("java-inherited-equality.json").toFile();
            MAPPER.writeValue(path, value.asReadOnly());
            verifyRestored(MAPPER.readValue(path, InheritedEqualityBasket.class));
        } else {
            verifyRestored(MAPPER.readValue(exchange.resolve("csharp-inherited-equality.json").toFile(),
                    InheritedEqualityBasket.class));
        }
    }

    private static InheritedEqualityBasket exercise() throws IOException {
        CodeEquality.callers.clear();
        var lower = InheritedEqualityItem.newBuilder().withCode(107).withRank(9).build();
        var upper = InheritedEqualityItem.newBuilder().withCode(207).withRank(9).build();
        var same = InheritedEqualityItem.newBuilder().withCode(107).withRank(9).build();
        InheritedEqualityBase base = lower;
        var view = lower.asReadOnly();
        var basket = InheritedEqualityBasket.newInstance();
        check(basket.getItems().add(lower) && basket.getItems().add(upper)
                        && basket.getSelected().add(lower) && basket.getSelected().add(upper)
                        && !basket.getSelected().add(lower) && !basket.getSelected().contains(same)
                        && CodeEquality.callers.isEmpty(),
                "Collection identity does not call the inherited delegate");
        check(((Object)base).equals(upper) && view.equals(upper) && upper.equals(view)
                        && view.hashCode() == 7 && upper.hashCode() == 7 && base.hashCode() == 7
                        && lower.comparisons() == 2 && upper.comparisons() == 1,
                "Inherited Object methods and ordinary methods share their typed binding");
        check(CodeEquality.callers.size() == 2 && CodeEquality.callers.get(0) == lower
                        && CodeEquality.callers.get(1) == upper,
                "The provider receives each concrete derived caller once");
        check(base.vmf().content().equals(same) && !base.vmf().content().equals(upper)
                        && base.vmf().content().hashCode() == same.vmf().content().hashCode()
                        && basket.asReadOnly().getSelected().contains(view)
                        && !basket.asReadOnly().getSelected().contains(same.asReadOnly()),
                "Structural comparison and projected identity membership remain separate");

        lower.setCode(108);
        check(!view.equals(upper), "The existing view sees live changes");
        lower.setCode(107);
        String before = MAPPER.writeValueAsString(basket.asReadOnly());
        var override = new Replacement();
        lower.vmf().behavior().set(override);
        check(override.caller == lower && !base.equals(upper) && !view.equals(upper) && view.hashCode() == 17
                        && lower.comparisons() == 42 && upper.hashCode() == 7 && CodeEquality.callers.size() == 2,
                "A derived replacement is used by the existing base and read-only aliases");
        check(before.equals(MAPPER.writeValueAsString(basket.asReadOnly())),
                "Replacing equality does not change persisted model data");
        return basket;
    }

    private static void verifyRestored(InheritedEqualityBasket basket) {
        var lower = basket.getItems().get(0);
        var upper = basket.getItems().get(1);
        int previousBindings = CodeEquality.callers.size();
        check(lower != upper && lower.getRank() == 9 && upper.getRank() == 9
                        && basket.getSelected().size() == 2 && basket.getSelected().contains(lower)
                        && basket.getSelected().contains(upper) && lower.vmf().behavior().get() == null
                        && CodeEquality.callers.size() == previousBindings,
                "Loading restores distinct objects and reference identities without binding behavior");
        check(lower.asReadOnly().equals(upper) && lower.hashCode() == upper.hashCode()
                        && CodeEquality.callers.size() == previousBindings + 2
                        && CodeEquality.callers.get(previousBindings) == lower,
                "Loaded values have fresh inherited delegates");
    }

    private static Integer code(Object candidate) {
        if (candidate instanceof InheritedEqualityBase) return ((InheritedEqualityBase)candidate).getCode();
        if (candidate instanceof ReadOnlyInheritedEqualityBase) return ((ReadOnlyInheritedEqualityBase)candidate).getCode();
        return null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class CodeEquality implements DelegatedBehavior<InheritedEqualityBase> {
        private static final ArrayList<InheritedEqualityBase> callers = new ArrayList<>();
        private InheritedEqualityBase caller;
        private int comparisons;
        @Override public void setCaller(InheritedEqualityBase caller) { this.caller = caller; callers.add(caller); }
        @Override public boolean equals(Object other) {
            comparisons++;
            Integer candidate = code(other);
            return candidate != null && caller.getCode() % 100 == candidate % 100;
        }
        @Override public int hashCode() { return caller.getCode() % 100; }
        public int comparisons() { return comparisons; }
    }

    private static final class Replacement implements InheritedEqualityItem.Behavior {
        private InheritedEqualityItem caller;
        @Override public void setCaller(VObject caller) { this.caller = (InheritedEqualityItem)caller; }
        @Override public boolean equals(Object other) {
            Integer candidate = code(other);
            return candidate != null && caller.getCode() == candidate;
        }
        @Override public int hashCode() { return 17; }
        @Override public int comparisons() { return 42; }
    }
}
