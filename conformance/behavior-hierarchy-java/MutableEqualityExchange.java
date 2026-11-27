package eu.mihosoft.vmf.conformance.behaviorhierarchy;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

public final class MutableEqualityExchange {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(VMFJacksonModule
            .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private MutableEqualityExchange() { }

    public static void verify(Path exchange, boolean write) throws IOException {
        if (write) {
            var model = exercise();
            var path = exchange.resolve("java-delegated-equality.json").toFile();
            MAPPER.writeValue(path, model.asReadOnly());
            verifyRestored(MAPPER.readValue(path, EqualityBasket.class));
        } else {
            verifyRestored(MAPPER.readValue(exchange.resolve("csharp-delegated-equality.json").toFile(), EqualityBasket.class));
        }
    }

    private static EqualityBasket exercise() throws IOException {
        CodeEquality.created = 0;
        var lower = EqualityItem.newBuilder().withCode("ab").build();
        var upper = EqualityItem.newBuilder().withCode("AB").build();
        var same = EqualityItem.newBuilder().withCode("ab").build();
        var view = lower.asReadOnly();
        var basket = EqualityBasket.newInstance();
        check(basket.getItems().add(lower) && basket.getItems().add(upper)
                        && basket.getSelected().add(lower) && basket.getSelected().add(upper)
                        && !basket.getSelected().add(lower) && !basket.getSelected().contains(same)
                        && CodeEquality.created == 0,
                "Owning and reference sets track identity without evaluating delegates");
        check(((Object)lower).equals(upper) && view.equals(upper) && upper.equals(view)
                        && view.hashCode() == upper.hashCode() && lower.hashCode() == 3105
                        && !lower.equals(null) && CodeEquality.created == 2,
                "The writable object and read-only alias use the same per-value delegates");
        check(!lower.vmf().content().equals(upper) && lower.vmf().content().equals(same)
                        && lower.vmf().content().hashCode() == same.vmf().content().hashCode(),
                "Structural equality and hashing remain separate");
        check(basket.asReadOnly().getSelected().contains(view)
                        && !basket.asReadOnly().getSelected().contains(same.asReadOnly()) && CodeEquality.created == 2,
                "Read-only reference membership preserves identity");
        lower.setCode("changed");
        check(!view.equals(upper), "The alias compares the live value");
        lower.setCode("ab");

        var before = MAPPER.writeValueAsString(basket.asReadOnly());
        var replacement = new Replacement();
        lower.vmf().behavior().set(replacement);
        check(replacement.caller == lower && !view.equals(upper) && view.equals(same)
                        && view.hashCode() == 17 && upper.hashCode() == 3105
                        && lower.vmf().content().equals(same) && CodeEquality.created == 2,
                "Replacement updates exactly the original value and its alias");
        check(before.equals(MAPPER.writeValueAsString(basket.asReadOnly())),
                "Object equality and hash implementations do not affect snapshots");
        return basket;
    }

    private static void verifyRestored(EqualityBasket basket) {
        int previousBindings = CodeEquality.created;
        var lower = basket.getItems().get(0);
        var upper = basket.getItems().get(1);
        check(lower != upper && basket.getSelected().size() == 2
                        && basket.getSelected().contains(lower) && basket.getSelected().contains(upper)
                        && lower.vmf().behavior().get() == null && CodeEquality.created == previousBindings,
                "Snapshots preserve membership identity and start with an uninitialized default delegate");
        check(lower.asReadOnly().equals(upper) && lower.hashCode() == upper.hashCode()
                        && CodeEquality.created == previousBindings + 2, "Restored values get fresh defaults");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String code(Object candidate) {
        if (candidate instanceof EqualityItem) return ((EqualityItem)candidate).getCode();
        if (candidate instanceof ReadOnlyEqualityItem) return ((ReadOnlyEqualityItem)candidate).getCode();
        return null;
    }

    public static final class CodeEquality implements DelegatedBehavior<EqualityItem> {
        private static int created;
        private EqualityItem caller;
        public CodeEquality() { created++; }
        @Override public void setCaller(EqualityItem caller) { this.caller = caller; }
        @Override public boolean equals(Object other) { return caller.getCode().equalsIgnoreCase(code(other)); }
        @Override public int hashCode() { return caller.getCode().toLowerCase(Locale.ROOT).hashCode(); }
    }

    private static final class Replacement implements EqualityItem.Behavior {
        private EqualityItem caller;
        @Override public void setCaller(VObject caller) { this.caller = (EqualityItem)caller; }
        @Override public boolean equals(Object other) { return Objects.equals(caller.getCode(), code(other)); }
        @Override public int hashCode() { return 17; }
    }
}
