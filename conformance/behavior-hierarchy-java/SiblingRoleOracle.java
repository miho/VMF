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

public final class SiblingRoleOracle {
    private static final List<String> hooks = new ArrayList<>();
    private static final List<VObject> hookOwners = new ArrayList<>();

    private SiblingRoleOracle() { }

    public static void verify(Path exchange, boolean writing) throws IOException {
        hooks.clear();
        hookOwners.clear();
        RootBinding.binds = SidesBinding.binds = OverrideBinding.binds = 0;
        var plain = SiblingPlain.newInstance();
        var reversed = SiblingReversed.newInstance();
        var first = SiblingFirstPath.newInstance();
        var overrideFirst = SiblingSecondPath.newInstance();
        var local = SiblingLocal.newInstance();
        check(hooks.equals(List.of("left:0", "right:0", "left:0", "override:0", "local:0")), "hooks " + hooks);
        check(hookOwners.equals(List.of(plain, reversed, first, overrideFirst, local)), "Actual owners of constructor hooks");
        check(SidesBinding.binds == 3 && OverrideBinding.binds == 1 && RootBinding.binds == 0, "Only chosen hooks bind");

        check(plain.left() == 1 && plain.right() == 2 && reversed.right() == 1 && reversed.left() == 2,
                "Two distinct declaration edges on the same Java class share one state per owner");
        check(((SiblingRight) plain).shared(10) == 113 && ((SiblingLeft) plain).shared(10) == 114,
                "An exact callable declared in both siblings uses the same provider as their other calls");
        check(SidesBinding.binds == 3, "Callable binding uses actual owner and source binding class identity");
        check(((SiblingLeft) plain).choose(1) == 102 && ((SiblingRight) plain).choose(1) == 103
                && ((SiblingRoot) plain).rootCalls() == 2, "Both repeated root paths share one delegate");
        check(RootBinding.binds == 1 && countField(plain, RootBinding.class) == 1 && countField(plain, SidesBinding.class) == 1,
                "One storage field per fully qualified source binding class per model implementation");

        int firstResult = first.choose(1);
        int reversedResult = overrideFirst.choose(1);
        int localResult = local.choose(1);
        check(firstResult == 102 && reversedResult == 202 && localResult == 302,
                "A branch override does not displace the first path unless on that path");
        check(((SiblingRoot) overrideFirst).choose(1) == 203 && ((SiblingLeft) overrideFirst).choose(1) == 204
                && overrideFirst.overrideCalls() == 3 && overrideFirst.rootCalls() == 0, "All casts use selected flattened slot");
        check(!hooks.contains("root:0") && local.getValue() == 11, "Dormant hooks never run and defaults remain");

        plain.vmf().behavior().set(new Replacement(plain));
        check(((SiblingLeft) plain).shared(10) == 810 && ((SiblingRight) plain).shared(10) == 810
                && ((SiblingRoot) plain).choose(1) == 901 && plain.left() == 81 && plain.right() == 82,
                "One replacement covers the base and both nominal siblings");
        check(reversed.left() == 3, "The other actual owner retains its provider");

        var archive = SiblingRoleArchive.newBuilder().withItems(plain, reversed, first, overrideFirst, local)
                .withSelected(plain).build();
        for (boolean references : new boolean[] {false, true}) {
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                    .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(references));
            String expected = mapper.writeValueAsString(archive);
            Path path = exchange.resolve((writing ? "java" : "csharp") + "-sibling-roles-" + references + ".json");
            if (writing) Files.writeString(path, expected);
            String wire = Files.readString(path);
            check(mapper.readTree(expected).equals(mapper.readTree(wire)), "Shared JSON model data");
            var copy = mapper.readValue(wire, SiblingRoleArchive.class);
            var firstCopy = (SiblingPlain) copy.getItems().get(0);
            check(firstCopy.choose(1) == 102 && firstCopy.left() == 1 && firstCopy.right() == 2
                    && copy.getItems().get(3).choose(1) == 202 && copy.getItems().get(4).choose(1) == 302,
                    "Restored defaults use fresh providers and the original branch order");
            check(references ? copy.getSelected() == firstCopy : copy.getSelected() == null, "Selected sibling alias");
        }
    }

    private static long countField(Object owner, Class<?> binding) {
        return Arrays.stream(owner.getClass().getDeclaredFields()).filter(field -> field.getType() == binding).count();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void hook(VObject owner, String value) { hookOwners.add(owner); hooks.add(value); }

    public static final class RootBinding implements DelegatedBehavior<SiblingRoot> {
        static int binds;
        private SiblingRoot owner;
        private int calls;
        @Override public void setCaller(SiblingRoot owner) { this.owner = owner; binds++; }
        public void onSiblingRootInstantiated() { hook(owner, "root:" + owner.getValue()); }
        public int choose(int value) { return 100 + value + ++calls; }
        public int rootCalls() { return calls; }
    }

    public static final class SidesBinding implements DelegatedBehavior<SiblingRoot> {
        static int binds;
        private SiblingRoot owner;
        private int calls;
        @Override public void setCaller(SiblingRoot owner) { this.owner = owner; binds++; }
        public void onSiblingLeftInstantiated() { hook(owner, "left:" + owner.getValue()); }
        public void onSiblingRightInstantiated() { hook(owner, "right:" + owner.getValue()); }
        public int left() { return ++calls; }
        public int right() { return ++calls; }
        public int shared(int value) { return 100 + value + ++calls; }
    }

    public static final class OverrideBinding implements DelegatedBehavior<SiblingRightOverride> {
        static int binds;
        private SiblingRightOverride owner;
        private int calls;
        @Override public void setCaller(SiblingRightOverride owner) { this.owner = owner; binds++; }
        public void onSiblingRightOverrideInstantiated() { hook(owner, "override:" + owner.getValue()); }
        public int choose(int value) { return 200 + value + ++calls; }
        public int overrideCalls() { return calls; }
    }

    public static final class LocalBinding implements DelegatedBehavior<SiblingLocal> {
        private SiblingLocal owner;
        @Override public void setCaller(SiblingLocal owner) { this.owner = owner; }
        public void onSiblingLocalInstantiated() { hook(owner, "local:" + owner.getValue()); }
        public int choose(int value) { return 301 + value; }
    }

    private static final class Replacement implements SiblingPlain.Behavior {
        private final SiblingPlain actual;
        private Replacement(SiblingPlain actual) { this.actual = actual; }
        @Override public void setCaller(VObject caller) { check(caller == actual, "Actual replacement caller"); }
        @Override public int choose(int value) { return 900 + value; }
        @Override public int shared(int value) { return 800 + value; }
        @Override public int rootCalls() { return -1; }
        @Override public int left() { return 81; }
        @Override public int right() { return 82; }
    }
}
