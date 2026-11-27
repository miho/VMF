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

public final class RoleContestExchange {
    private static final List<String> HOOKS = new ArrayList<>();
    private RoleContestExchange() { }

    public static void verify(Path exchange, boolean writing) throws IOException {
        HOOKS.clear();
        PrimaryBinding.created = 0;
        RoleBinding.created = 0;
        var primary = ContestPrimaryWins.newInstance();
        var reversed = ContestRoleWins.newInstance();
        var local = ContestOverride.newInstance();
        check(HOOKS.equals(List.of("primary:null", "role:null", "local:null:null"))
                && PrimaryBinding.created == 1 && RoleBinding.created == 1,
                "Only the first parent's or the local hook runs before defaults");

        ContestRole asRole = primary;
        check(primary.evaluate(1) == 102 && asRole.evaluate(1) == 103
                && primary.primaryCalls() == 2 && RoleBinding.created == 1,
                "Both nominal roles use the first source for the overlapping signature");
        check(primary.evaluate("x") == 202 && primary.evaluate(2L) == 404
                && primary.roleCalls() == 2 && RoleBinding.created == 2,
                "Independent same-name overloads lazily share the losing role's binding");
        check(((ContestPrimary) reversed).evaluate(1) == 202 && reversed.primaryCalls() == 0,
                "Reversing the parent order reverses selection through both roles");
        check(((ContestRole) local).evaluate(1) == 301 && ((ContestPrimary) local).evaluate(2) == 302
                && local.evaluate("x") == 202, "A local override wins and leaves the other overloads");

        primary.vmf().behavior().set(new Replacement(primary));
        check(asRole.evaluate(1) == 901 && ((ContestPrimary) primary).evaluate(2) == 902
                && primary.evaluate("x") == 801, "The model-wide replacement binds one shared signature against the actual caller");

        var graph = ContestArchive.newBuilder().withItems(primary, reversed, local).withSelected(primary).build();
        for (boolean references : new boolean[]{true, false}) {
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                    .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                    .withModelWireTypeAliases(ContestArchive.class).withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
            String suffix = references ? "-role-contest-reference.json" : "-role-contest-by-value.json";
            String expected = mapper.writeValueAsString(graph);
            String wire = expected;
            if (writing) {
                Files.writeString(exchange.resolve("java" + suffix), wire);
            } else {
                wire = Files.readString(exchange.resolve("csharp" + suffix));
                check(mapper.readTree(expected).equals(mapper.readTree(wire)), "Both targets write the same model data");
            }
            var restored = mapper.readValue(wire, ContestArchive.class);
            var copy = (ContestPrimaryWins) restored.getItems().get(0);
            var reverse = (ContestRoleWins) restored.getItems().get(1);
            var override = (ContestOverride) restored.getItems().get(2);
            check(copy.evaluate(1) == 102 && reverse.evaluate(1) == 202 && override.evaluate(1) == 301
                    && copy.getTitle().equals("main") && copy.getLabel().equals("side"),
                    "The wire profile restores subtype, defaults and fresh behavior");
            check(references ? restored.getSelected() == copy : restored.getSelected() == null,
                    "Cross-role links obey the existing snapshot format");
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    public static final class PrimaryBinding implements DelegatedBehavior<ContestPrimary> {
        static int created;
        private ContestPrimary caller;
        private int calls;
        public PrimaryBinding() { created++; }
        @Override public void setCaller(ContestPrimary caller) { this.caller = caller; }
        public void onContestPrimaryInstantiated() { HOOKS.add("primary:" + caller.getTitle()); }
        public int evaluate(int value) { return 100 + value + ++calls; }
        public int primaryCalls() { return calls; }
    }

    public static final class RoleBinding implements DelegatedBehavior<ContestRole> {
        static int created;
        private ContestRole caller;
        private int calls;
        public RoleBinding() { created++; }
        @Override public void setCaller(ContestRole caller) { this.caller = caller; }
        public void onContestRoleInstantiated() { HOOKS.add("role:" + caller.getLabel()); }
        public int evaluate(int value) { return 200 + value + ++calls; }
        public int evaluate(long value) { return 400 + (int) value + ++calls; }
        public int evaluate(String value) { return 200 + value.length() + ++calls; }
        public int roleCalls() { return calls; }
    }

    public static final class OverrideBinding implements DelegatedBehavior<ContestOverride> {
        private ContestOverride caller;
        @Override public void setCaller(ContestOverride caller) { this.caller = caller; }
        public void onContestOverrideInstantiated() { HOOKS.add("local:" + caller.getTitle() + ":" + caller.getLabel()); }
        public int evaluate(int value) { return 300 + value; }
    }

    private static final class Replacement implements ContestPrimaryWins.Behavior {
        private final ContestPrimaryWins expected;
        Replacement(ContestPrimaryWins expected) { this.expected = expected; }
        @Override public void setCaller(VObject caller) { check(expected == caller, "Replacement receives the actual object"); }
        @Override public int evaluate(int value) { return 900 + value; }
        @Override public int evaluate(long value) { return 700 + (int) value; }
        @Override public int evaluate(String value) { return 800 + value.length(); }
        @Override public int primaryCalls() { return -1; }
        @Override public int roleCalls() { return -2; }
    }
}
