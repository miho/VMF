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

public final class LocalBindingExchange {
    private static final List<String> HOOKS = new ArrayList<>();
    private static final List<VObject> CALLERS = new ArrayList<>();

    private LocalBindingExchange() { }

    public static void verify(Path exchange, boolean writing) throws IOException {
        HOOKS.clear();
        CALLERS.clear();
        SharedDelegate.created = HotDelegate.created = ColdDelegate.created = 0;
        var first = LocalComposite.newInstance();
        var child = LocalCompositeChild.newInstance();
        check(HOOKS.equals(List.of("cold:0:0:0", "cold:0:0:0")) && ColdDelegate.created == 2
                && HotDelegate.created == 0 && SharedDelegate.created == 0 && CALLERS.equals(List.of(first, child)),
                "Only the selected local hook eagerly binds to the actual model");
        check(first.getLeftValue() == 11 && first.getRightValue() == 22 && first.getLocalValue() == 33,
                "Defaults initialize after the selected hook");
        for (var owner : List.of(first, child)) {
            for (var binding : List.of(ColdDelegate.class, HotDelegate.class, SharedDelegate.class)) {
                check(Arrays.stream(owner.getClass().getDeclaredFields()).filter(field -> field.getType() == binding).count() == 1,
                        "Each Java implementation allocates one member per binding");
            }
            check(owner.cool() == 101 && owner.heat() == 1 && owner.warm() == 2
                    && owner.left() == 1 && owner.joined() == 2 && owner.right() == 3,
                    "Methods share local or inherited state only when their Java class is identical");
        }
        check(ColdDelegate.created == 2 && HotDelegate.created == 2 && SharedDelegate.created == 2,
                "Only one instance of each class is created per owner");

        first.vmf().behavior().set(new Replacement(first));
        check(first.cool() == 201 && first.heat() == 202 && first.warm() == 203
                && first.joined() == 204 && first.left() == 205 && first.right() == 206,
                "One dynamic behavior replaces all local and inherited calls");
        check(child.warm() == 3 && child.right() == 4 && child.cool() == 102,
                "Another owner's default state is unchanged");

        var root = LocalBundle.newBuilder().withItems(first, child).withSelected(child).build();
        for (boolean references : new boolean[] {false, true}) {
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                    .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(references));
            String expected = mapper.writeValueAsString(root);
            String suffix = "-local-delegate-bindings-" + references + ".json";
            Path file = exchange.resolve((writing ? "java" : "csharp") + suffix);
            if (writing) Files.writeString(file, expected);
            String actual = Files.readString(file);
            check(mapper.readTree(expected).equals(mapper.readTree(actual)), "Existing snapshots contain the same model data");
            var restored = mapper.readValue(actual, LocalBundle.class);
            var value = (LocalComposite) restored.getItems().get(0);
            var derived = (LocalCompositeChild) restored.getItems().get(1);
            check(value.vmf().behavior().get() == null && value.cool() == 101 && value.warm() == 1
                    && derived.warm() == 1 && derived.right() == 1 && derived.left() == 2,
                    "Snapshots restore fresh delegate state and omit native replacements");
            check(references ? restored.getSelected() == derived : restored.getSelected() == null,
                    "Existing reference profile preserves the modeled alias");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class SharedDelegate implements DelegatedBehavior<VObject> {
        static int created;
        private VObject caller;
        private int state;

        public SharedDelegate() { created++; }
        @Override public void setCaller(VObject caller) { this.caller = caller; }
        public void onLocalLeftInstantiated() { HOOKS.add("left"); }
        public void onLocalRightInstantiated() { HOOKS.add("right"); }
        public int left() { check(caller instanceof LocalLeft, "left caller"); return ++state; }
        public int right() { check(caller instanceof LocalRight, "right caller"); return ++state; }
        public int joined() { check(caller instanceof LocalComposite, "joined caller"); return ++state; }
    }

    public static final class ColdDelegate implements DelegatedBehavior<LocalComposite> {
        static int created;
        private LocalComposite caller;
        private int state;

        public ColdDelegate() { created++; }
        @Override public void setCaller(LocalComposite caller) { this.caller = caller; }
        public void onLocalCompositeInstantiated() {
            CALLERS.add(caller);
            HOOKS.add("cold:" + caller.getLeftValue() + ":" + caller.getRightValue() + ":" + caller.getLocalValue());
            state = 100;
        }
        public int cool() { check(caller != null, "cool caller"); return ++state; }
    }

    public static final class HotDelegate implements DelegatedBehavior<LocalComposite> {
        static int created;
        private LocalComposite caller;
        private int state;

        public HotDelegate() { created++; }
        @Override public void setCaller(LocalComposite caller) { this.caller = caller; }
        public int heat() { check(caller != null, "heat caller"); return ++state; }
        public int warm() { check(caller != null, "warm caller"); return ++state; }
    }

    private static final class Replacement implements LocalComposite.Behavior {
        private LocalComposite caller;
        Replacement(LocalComposite caller) { this.caller = caller; }
        @Override public void setCaller(VObject caller) { check(caller == this.caller, "replacement caller"); }
        @Override public int cool() { return 201; }
        @Override public int heat() { return 202; }
        @Override public int warm() { return 203; }
        @Override public int joined() { return 204; }
        @Override public int left() { return 205; }
        @Override public int right() { return 206; }
    }
}
