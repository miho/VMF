package eu.mihosoft.vmf.conformance.behaviorhierarchy;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import eu.mihosoft.vmf.runtime.core.VObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TrioRoleExchange {
    private static final List<String> HOOKS = new ArrayList<>();
    private TrioRoleExchange() { }

    public static void verify(Path exchange, boolean writing) throws IOException {
        HOOKS.clear();
        TrioSharedBinding.created = 0;
        TrioIndependentBinding.created = 0;
        var forward = TrioForward.newInstance();
        var reverse = TrioReverse.newInstance();
        var inherited = TrioInherited.newInstance();
        check(HOOKS.equals(List.of("first:0", "second:0", "first:0")) && TrioSharedBinding.created == 2
                && TrioIndependentBinding.created == 1, "Source order selects one hook before defaults");
        for (var owner : List.<VObject>of(forward, reverse, inherited)) {
            check(Arrays.stream(owner.getClass().getDeclaredFields()).filter(f -> f.getType() == TrioSharedBinding.class).count() == 1,
                    "Each model has one field for the two roles that share a Java class");
        }

        check(forward.evaluate(1) == 102 && ((TrioSecond) forward).evaluate(1) == 103
                && ((TrioThird) forward).evaluate(1) == 104 && forward.evaluate("x") == 305,
                "The first source wins the common signature through all roles and the distinct overload survives");
        check(forward.firstCalls() == 4 && forward.thirdCalls() == 4 && TrioIndependentBinding.created == 1,
                "The winning delegate and the third role share state, while the other binding remains lazy");
        check(forward.evaluate(2L) == 403 && ((TrioThird) forward).finish("x") == 1003L && forward.secondCalls() == 2,
                "The second role owns its overload and the matching signature first contributed by two secondaries");
        check(((TrioFirst) reverse).evaluate(1) == 202 && ((TrioThird) reverse).evaluate(1) == 203
                && reverse.evaluate("x") == 302 && reverse.firstCalls() == 1 && reverse.thirdCalls() == 1,
                "Reversing order selects a different first binding while retaining the shared lazy overload");
        check(inherited.evaluate(1) == 102 && inherited.firstCalls() == 1 && inherited.thirdCalls() == 1
                && HOOKS.size() == 3, "A descendant reuses resolution without duplicating any hook");

        forward.vmf().behavior().set(new Replacement(forward));
        check(((TrioFirst) forward).evaluate(1) == 901 && ((TrioSecond) forward).evaluate(2) == 902
                && ((TrioThird) forward).evaluate(3) == 903 && ((TrioSecond) forward).finish("x") == 9001L
                && ((TrioThird) forward).finish("xy") == 9002L, "One replacement covers every view of each common slot");

        var graph = TrioArchive.newBuilder().withItems(forward, reverse, inherited).withSelected(forward).build();
        for (boolean references : new boolean[]{true, false}) {
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                    .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                    .withModelWireTypeAliases(TrioArchive.class).withReferences(references));
            String suffix = references ? "-trio-role-reference.json" : "-trio-role-by-value.json";
            String expected = mapper.writeValueAsString(graph);
            String wire = expected;
            if (writing) {
                Files.writeString(exchange.resolve("java" + suffix), wire);
            } else {
                wire = Files.readString(exchange.resolve("csharp" + suffix));
                check(mapper.readTree(expected).equals(mapper.readTree(wire)), "Both targets retain the existing snapshot data");
            }

            var restored = mapper.readValue(wire, TrioArchive.class);
            var model = (TrioForward) restored.getItems().get(0);
            check(model.getFirstValue() == 11 && model.getSecondValue() == 22 && model.getThirdValue() == 33
                    && model.evaluate(1) == 102 && ((TrioReverse) restored.getItems().get(1)).evaluate(1) == 202
                    && ((TrioInherited) restored.getItems().get(2)).evaluate(1) == 102,
                    "Both profiles restore the model with fresh delegate states");
            check(references ? restored.getSelected() == model : restored.getSelected() == null,
                    "The existing reference profile retains the modeled cross-role link");
        }
    }

    private static final class Replacement implements TrioForward.Behavior {
        private final TrioForward expected;
        Replacement(TrioForward expected) { this.expected = expected; }
        @Override public void setCaller(VObject caller) { check(caller == expected, "Replacement binds the same object"); }
        @Override public int evaluate(int value) { return 900 + value; }
        @Override public int evaluate(long value) { return -1; }
        @Override public int evaluate(String value) { return -2; }
        @Override public long finish(String value) { return 9000L + value.length(); }
        @Override public int firstCalls() { return -3; }
        @Override public int secondCalls() { return -4; }
        @Override public int thirdCalls() { return -5; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class TrioSharedBinding implements DelegatedBehavior<VObject> {
        static int created;
        private VObject caller;
        private int calls;

        public TrioSharedBinding() { created++; }
        @Override public void setCaller(VObject caller) { this.caller = caller; }
        public void onTrioFirstInstantiated() { HOOKS.add("first:" + ((TrioFirst) caller).getFirstValue()); }
        public void onTrioThirdInstantiated() { HOOKS.add("third:" + ((TrioThird) caller).getThirdValue()); }
        public int evaluate(int value) { return 100 + value + ++calls; }
        public int evaluate(String value) { return 300 + value.length() + ++calls; }
        public long finish(String marker) { return 2000L + marker.length() + ++calls; }
        public int firstCalls() { return calls; }
        public int thirdCalls() { return calls; }
    }

    public static final class TrioIndependentBinding implements DelegatedBehavior<TrioSecond> {
        static int created;
        private TrioSecond caller;
        private int calls;

        public TrioIndependentBinding() { created++; }
        @Override public void setCaller(TrioSecond caller) { this.caller = caller; }
        public void onTrioSecondInstantiated() { HOOKS.add("second:" + caller.getSecondValue()); }
        public int evaluate(int value) { return 200 + value + ++calls; }
        public int evaluate(long value) { return 400 + (int) value + ++calls; }
        public long finish(String marker) { return 1000L + marker.length() + ++calls; }
        public int secondCalls() { return calls; }
    }
}
