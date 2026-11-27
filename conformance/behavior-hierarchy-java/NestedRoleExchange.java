package eu.mihosoft.vmf.conformance.behaviorhierarchy;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class NestedRoleExchange {
    private static final List<String> HOOKS = new ArrayList<>();
    private static final List<VObject> CALLERS = new ArrayList<>();

    private NestedRoleExchange() { }

    public static void verify(Path exchange, boolean writing) throws IOException {
        HOOKS.clear();
        CALLERS.clear();
        var primary = NestedPrimary.newInstance();
        var secondary = NestedSecondary.newInstance();
        var inner = NestedInnerPrimary.newInstance();
        var outer = NestedOuter.newInstance();
        check(HOOKS.equals(List.of("first:0", "third:0", "inner:0", "outer:0"))
                && CALLERS.equals(List.of(primary, secondary, inner, outer)),
                "The selected hook sees the actual owner before defaults");
        check(primary.getFirstValue() == 11 && secondary.getSecondValue() == 22
                && inner.getInnerValue() == 44 && outer.getThirdValue() == 33, "Defaults are initialized");

        check(((NestedSecond) primary).choose(1) == 102 && ((NestedThird) primary).choose(1) == 103
                && primary.firstCalls() == 2 && primary.secondCalls() == 0 && primary.thirdCalls() == 0,
                "The first branch and its first declaring role win from every role");
        check(primary.choose(2L) == 203 && primary.choose("x") == 302
                && primary.secondCalls() == 1 && primary.thirdCalls() == 1,
                "Noncompeting overloads borrow only their own binding");
        check(((NestedPair) secondary).choose(1) == 302 && ((NestedFirst) secondary).choose(1) == 303
                && secondary.firstCalls() == 0 && secondary.thirdCalls() == 2,
                "Putting a leaf first shadows the nested secondary for the common signature");
        check(((NestedThird) inner).choose(1) == 402 && inner.innerCalls() == 1
                && ((NestedInner) outer).choose(1) == 502 && outer.innerCalls() == 0,
                "Inner or outer local overrides occupy the effective shared signature");

        secondary.vmf().behavior().set(new Replacement(secondary));
        check(((NestedPair) secondary).choose(2) == 902 && ((NestedFirst) secondary).choose(2) == 902
                && ((NestedThird) secondary).choose(2) == 902 && secondary.choose(2L) == 802
                && secondary.choose("xy") == 702,
                "One dynamic replacement handles nested and direct callers");

        var root = NestedRoleArchive.newBuilder().withItems(primary, secondary, inner, outer).withSelected(secondary).build();
        for (boolean references : new boolean[] {false, true}) {
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                    .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
            String wire = mapper.writeValueAsString(root);
            var path = exchange.resolve((writing ? "java" : "csharp") + "-nested-roles-" + references + ".json");
            if (writing) Files.writeString(path, wire);
            String actual = Files.readString(path);
            check(mapper.readTree(wire).equals(mapper.readTree(actual)), "Both existing snapshot modes keep identical model data");
            var restored = mapper.readValue(actual, NestedRoleArchive.class);
            check(restored.getItems().get(0).choose(1) == 102 && restored.getItems().get(1).choose(1) == 302
                    && restored.getItems().get(2).choose(1) == 402 && restored.getItems().get(3).choose(1) == 502,
                    "Hydration restores fresh default bindings for all nested roles");
            check(references ? restored.getSelected() == restored.getItems().get(1) : restored.getSelected() == null,
                    "The selected cross-role alias only exists in the matching reference profile");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class First implements DelegatedBehavior<NestedFirst> {
        private NestedFirst caller;
        private int calls;
        @Override public void setCaller(NestedFirst caller) { this.caller = caller; }
        public void onNestedFirstInstantiated() { HOOKS.add("first:" + caller.getFirstValue()); CALLERS.add(caller); }
        public int choose(int input) { return 100 + input + ++calls; }
        public int firstCalls() { return calls; }
    }

    public static final class Second implements DelegatedBehavior<NestedSecond> {
        private NestedSecond caller;
        private int calls;
        @Override public void setCaller(NestedSecond caller) { this.caller = caller; }
        public void onNestedSecondInstantiated() { HOOKS.add("second:" + caller.getSecondValue()); CALLERS.add(caller); }
        public int choose(int input) { return 200 + input + ++calls; }
        public int choose(long input) { return 200 + (int) input + ++calls; }
        public int secondCalls() { return calls; }
    }

    public static final class Third implements DelegatedBehavior<NestedThird> {
        private NestedThird caller;
        private int calls;
        @Override public void setCaller(NestedThird caller) { this.caller = caller; }
        public void onNestedThirdInstantiated() { HOOKS.add("third:" + caller.getThirdValue()); CALLERS.add(caller); }
        public int choose(int input) { return 300 + input + ++calls; }
        public int choose(String input) { return 300 + input.length() + ++calls; }
        public int thirdCalls() { return calls; }
    }

    public static final class Inner implements DelegatedBehavior<NestedInner> {
        private NestedInner caller;
        private int calls;
        @Override public void setCaller(NestedInner caller) { this.caller = caller; }
        public void onNestedInnerInstantiated() { HOOKS.add("inner:" + caller.getInnerValue()); CALLERS.add(caller); }
        public int choose(int input) { return 400 + input + ++calls; }
        public int innerCalls() { return calls; }
    }

    public static final class Outer implements DelegatedBehavior<NestedOuter> {
        private NestedOuter caller;
        private int calls;
        @Override public void setCaller(NestedOuter caller) { this.caller = caller; }
        public void onNestedOuterInstantiated() { HOOKS.add("outer:" + caller.getThirdValue()); CALLERS.add(caller); }
        public int choose(int input) { return 500 + input + ++calls; }
    }

    private static final class Replacement implements NestedSecondary.Behavior {
        private final NestedSecondary owner;
        Replacement(NestedSecondary owner) { this.owner = owner; }
        @Override public void setCaller(VObject caller) { check(caller == owner, "Replacement gets the exact owner"); }
        @Override public int choose(int input) { return 900 + input; }
        @Override public int choose(long input) { return 800 + (int) input; }
        @Override public int choose(String input) { return 700 + input.length(); }
        @Override public int firstCalls() { return -1; }
        @Override public int secondCalls() { return -2; }
        @Override public int thirdCalls() { return -3; }
    }
}
