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

public final class RepeatedRoleOracle {
    private static final List<String> hooks = new ArrayList<>();
    private static final List<VObject> hookOwners = new ArrayList<>();

    private RepeatedRoleOracle() { }

    public static void verify(Path exchange, boolean writing) throws IOException {
        hooks.clear();
        hookOwners.clear();
        RootBinding.bound = FirstBinding.bound = SecondBinding.bound = 0;
        var left = RepeatedLeftWins.newInstance();
        var right = RepeatedRightWins.newInstance();
        var redundant = RepeatedDirectEdge.newInstance();
        verify(hooks.equals(List.of("root:0", "second:0", "root:0"))
                && hookOwners.equals(List.of(left, right, redundant)), "Selected deploy hooks");
        verify(RootBinding.bound == 2 && FirstBinding.bound == 0 && SecondBinding.bound == 1,
                "Only one selected hook allocates a provider per owner");
        verify(left.getCount() == 11 && right.getCount() == 11 && redundant.getCount() == 11,
                "Both paths address one model storage slot; hook preceded default");
        verify(((RepeatedRight) left).choose(1) == 102 && ((RepeatedLeft) left).choose(1) == 103
                && ((RepeatedRoot) left).rootCalls() == 2, "All views of repeated source dispatch to the left binding");
        verify(((RepeatedLeft) right).choose(1) == 302 && ((RepeatedRoot) right).choose(1) == 303
                && right.fromSecond() == 2 && right.rootCalls() == 0,
                "All views of repeated source dispatch to the right winner");
        verify(left.fromFirst() == 0 && left.fromSecond() == 0 && redundant.choose(1) == 102,
                "Other bindings independently initialize at first use");
        verify(RootBinding.bound == 3 && FirstBinding.bound == 1 && SecondBinding.bound == 2
                && Arrays.stream(left.getClass().getDeclaredFields()).filter(f -> f.getType() == RootBinding.class).count() == 1
                && Arrays.stream(right.getClass().getDeclaredFields()).filter(f -> f.getType() == RootBinding.class).count() == 1,
                "Repeated references to the same source role share exactly one provider in each concrete instance");
        left.vmf().behavior().set(new Replacement(left));
        verify(((RepeatedRight) left).choose(1) == 901 && ((RepeatedLeft) left).choose(2) == 902
                && ((RepeatedRoot) left).choose(3) == 903 && left.rootCalls() == 921, "Whole-owner replacement");
        verify(right.choose(1) == 304, "Replacement does not modify another instance");

        var archive = RepeatedRoleArchive.newBuilder().withItems(left, right, redundant).withSelected(left).build();
        for (boolean references : new boolean[] {false, true}) {
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                    .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
            String expected = mapper.writeValueAsString(archive);
            Path path = exchange.resolve((writing ? "java" : "csharp") + "-repeated-roles-" + references + ".json");
            if (writing) Files.writeString(path, expected);
            String wire = Files.readString(path);
            verify(mapper.readTree(expected).equals(mapper.readTree(wire)), "Shared wire format");
            var restored = mapper.readValue(wire, RepeatedRoleArchive.class);
            verify(restored.getItems().get(0).choose(1) == 102 && restored.getItems().get(1).choose(1) == 302
                    && restored.getItems().get(2).choose(1) == 102, "Restored delegates have fresh state");
            verify(references ? restored.getSelected() == restored.getItems().get(0) : restored.getSelected() == null,
                    "The reference-enabled profile preserves repeated-role aliases");
        }
    }

    private static void hook(VObject owner, String message) { hookOwners.add(owner); hooks.add(message); }
    private static void verify(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class RootBinding implements DelegatedBehavior<RepeatedRoot> {
        static int bound;
        private RepeatedRoot caller;
        private int calls;
        @Override public void setCaller(RepeatedRoot caller) { bound++; this.caller = caller; }
        public void onRepeatedRootInstantiated() { hook(caller, "root:" + caller.getCount()); }
        public int choose(int input) { return 100 + input + ++calls; }
        public int rootCalls() { return calls; }
    }

    public static final class FirstBinding implements DelegatedBehavior<RepeatedFirst> {
        static int bound;
        private RepeatedFirst caller;
        private int calls;
        @Override public void setCaller(RepeatedFirst caller) { bound++; this.caller = caller; }
        public void onRepeatedFirstInstantiated() { hook(caller, "first:0"); }
        public int choose(int input) { return 200 + input + ++calls; }
        public int fromFirst() { return calls; }
    }

    public static final class SecondBinding implements DelegatedBehavior<RepeatedSecond> {
        static int bound;
        private RepeatedSecond caller;
        private int calls;
        @Override public void setCaller(RepeatedSecond caller) { bound++; this.caller = caller; }
        public void onRepeatedSecondInstantiated() { hook(caller, "second:0"); }
        public int choose(int input) { return 300 + input + ++calls; }
        public int fromSecond() { return calls; }
    }

    private static final class Replacement implements RepeatedLeftWins.Behavior {
        private final RepeatedLeftWins actual;
        private Replacement(RepeatedLeftWins actual) { this.actual = actual; }
        @Override public void setCaller(VObject caller) { verify(caller == actual, "Actual replacement caller"); }
        @Override public int choose(int input) { return 900 + input; }
        @Override public int rootCalls() { return 921; }
        @Override public int fromFirst() { return 922; }
        @Override public int fromSecond() { return 923; }
    }
}
