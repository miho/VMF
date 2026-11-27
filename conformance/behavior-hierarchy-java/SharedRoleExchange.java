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

public final class SharedRoleExchange {
    private static final List<String> HOOKS = new ArrayList<>();
    private static final List<VObject> HOOK_OWNERS = new ArrayList<>();
    private SharedRoleExchange() { }

    public static void verify(Path exchange, boolean writing) throws IOException {
        HOOKS.clear();
        HOOK_OWNERS.clear();
        SharedBinding.created = 0;
        var standalone = SharedPrimary.newInstance();
        var joined = SharedJoined.newInstance();
        var reversed = SharedReversed.newInstance();
        var local = SharedLocal.newInstance();
        check(HOOKS.equals(List.of("role:null", "role:null", "local:0:null")) && SharedBinding.created == 3
                && HOOK_OWNERS.get(0) == joined && HOOK_OWNERS.get(1) == reversed && HOOK_OWNERS.get(2) == local,
                "Only the selected eager hook initializes the shared binding with the actual owner");
        for (var owner : List.of(standalone, joined, reversed, local)) {
            check(Arrays.stream(owner.getClass().getDeclaredFields()).filter(field -> field.getType() == SharedBinding.class).count() == 1,
                    "Each generated owner has exactly one field for the shared Java class");
        }

        SharedPrimary primary = joined;
        SharedRole role = joined;
        check(primary.one() == 1 && role.two() == 2 && primary.evaluate(10) == 13
                && role.evaluate("x") == 5 && role.evaluate(10) == 15 && SharedBinding.created == 3,
                "Both roles and both overloads use one counter created by the hook");
        check(reversed.two() == 1 && ((SharedPrimary) reversed).one() == 2 && reversed.evaluate(10) == 13,
                "Reversed ancestry retains the same binding with independent per-owner state");
        check(((SharedRole) local).evaluate(10) == 11 && local.two() == 2 && local.one() == 3 && local.evaluate("ok") == 6,
                "A local declaration of the same Java binding shares the selected hook's state");
        check(standalone.one() == 1 && standalone.evaluate(0) == 2 && SharedBinding.created == 4,
                "The standalone role lazily creates a separate binding");

        var graph = SharedArchive.newBuilder().withItems(joined, reversed, local).withSelected(joined).build();
        for (boolean references : new boolean[]{true, false}) {
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                    .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                    .withModelWireTypeAliases(SharedArchive.class).withReferences(references));
            String suffix = references ? "-shared-behavior-reference.json" : "-shared-behavior-by-value.json";
            String expected = mapper.writeValueAsString(graph);
            String wire = expected;
            if (writing) {
                Files.writeString(exchange.resolve("java" + suffix), wire);
            } else {
                wire = Files.readString(exchange.resolve("csharp" + suffix));
                check(mapper.readTree(expected).equals(mapper.readTree(wire)), "Both targets write the same model data");
            }
            var restored = mapper.readValue(wire, SharedArchive.class);
            var copy = (SharedJoined) restored.getItems().get(0);
            var reverseCopy = (SharedReversed) restored.getItems().get(1);
            var localCopy = (SharedLocal) restored.getItems().get(2);
            check(copy.one() == 1 && copy.two() == 2 && reverseCopy.two() == 1
                    && localCopy.evaluate(10) == 11 && localCopy.one() == 2,
                    "Each snapshot restores fresh shared binding state");
            check(references ? restored.getSelected() == copy : restored.getSelected() == null,
                    "The existing reference profile retains the modeled cross-role link");
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    public static final class SharedBinding implements DelegatedBehavior<VObject> {
        static int created;
        private VObject caller;
        private int calls;

        public SharedBinding() { created++; }
        @Override public void setCaller(VObject caller) { this.caller = caller; }
        public void onSharedRoleInstantiated() {
            HOOKS.add("role:" + ((SharedRole) caller).getLabel());
            HOOK_OWNERS.add(caller);
        }
        public void onSharedLocalInstantiated() {
            var local = (SharedLocal) caller;
            HOOKS.add("local:" + local.getLevel() + ":" + local.getLabel());
            HOOK_OWNERS.add(caller);
        }
        public int one() { return ++calls; }
        public int two() { return ++calls; }
        public int evaluate(int value) { return value + ++calls; }
        public int evaluate(String value) { return value.length() + ++calls; }
    }
}
