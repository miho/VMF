package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.behavior.CallResult;
import eu.mihosoft.vmf.conformance.behavior.GaugeBehavior;
import eu.mihosoft.vmf.conformance.behavior.GaugeDiagnostics;
import eu.mihosoft.vmf.conformance.behavior.HookedJournalCheckpoint;
import eu.mihosoft.vmf.conformance.behavior.JournalCheckpointBehavior;
import eu.mihosoft.vmf.conformance.behavior.MutableGauge;
import eu.mihosoft.vmf.conformance.behavior.ReadOnlyMutableGauge;
import eu.mihosoft.vmf.conformance.behavior.SignalBehavior;
import eu.mihosoft.vmf.conformance.behavior.SignalGauge;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

final class BehaviorConformance {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL));

    private BehaviorConformance() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        Path exchange = Path.of(args[1]);
        if (args[0].equals("write")) {
            Files.createDirectories(exchange);
            var gauge = exercise();
            verifyJournalHook();
            var file = exchange.resolve("java-gauge.json").toFile();
            MAPPER.writeValue(file, gauge);
            verifyRestored(MAPPER.readValue(file, MutableGauge.class));
            SnapshotCollectionConformance.exchange(exchange, true);
            System.out.println("Java verified typed delegation, per-instance bindings, and constructor/reset behavior.");
        } else {
            verifyRestored(MAPPER.readValue(exchange.resolve("csharp-gauge.json").toFile(), MutableGauge.class));
            SnapshotCollectionConformance.exchange(exchange, false);
            System.out.println("Java restored the C# gauge with its default behavior binding.");
        }
    }

    private static void verifyJournalHook() {
        int baseline = JournalCheckpointBehavior.hookCount();
        var original = HookedJournalCheckpoint.newInstance();
        var field = original.vmf().reflect().propertyByName("slots").orElseThrow();
        check(original.getSlots().equals(List.of(1)), "The hook seeds the managed field");
        field.setDefault(List.of(2));
        field.unset();
        check(original.invocation() == 1 && original.invocation() == 2, "The original delegate keeps its state");
        var binding = new JournalBinding() {
            @Override public String modelId() { return "hook-checkpoint"; }
            @Override public int modelVersion() { return 1; }
            @Override public String id(VObject model) { return "root"; }
            @Override public String type(VObject model) { return "hook-checkpoint"; }
            @Override public VObject create(String type, String id) { throw new AssertionError("No created objects"); }
        };
        var empty = new ChangeJournal("hook-checkpoint", 1, "root", "base", 0, List.of());
        var copy = JournalReplayer.replay(original, binding, "base", empty);
        check(JournalCheckpointBehavior.hookCount() == baseline + 2 && copy.asReadOnly().getSlots().equals(List.of(2))
                        && copy.invocation() == 1 && original.invocation() == 3,
                "Replay creates a fresh delegate and replaces its hook's seed with checkpoint values");
        ChangeJournal journal;
        try (var recorder = JournalRecorder.start(original, binding, "base", 0)) {
            check(JournalCheckpointBehavior.hookCount() == baseline + 3, "Recorder startup runs one fresh hook");
            original.getSlots().add(3);
            journal = recorder.finish();
        }
        check(journal.wireVersion() == 1 && journal.transactions().size() == 1, "Recorder emits one V1 frame");
        var restored = JournalReplayer.replay(copy, binding, "base", journal);
        var restoredField = restored.vmf().reflect().propertyByName("slots").orElseThrow();
        check(JournalCheckpointBehavior.hookCount() == baseline + 4 && restored.getSlots().equals(List.of(2, 3))
                        && restored.invocation() == 1 && copy.getSlots().equals(List.of(2)) && restoredField.isSet(),
                "V1 replay uses the independently configured clone and its managed override");
        restoredField.unset();
        check(restored.getSlots().equals(List.of(2)) && original.getSlots().equals(List.of(2, 3)),
                "Reset uses the copied override without modifying the source");
    }

    private static MutableGauge exercise() {
        var defaulted = MutableGauge.newInstance();
        var gauge = MutableGauge.newBuilder().withBase(13).build();
        check(GaugeBehavior.HOOK_BASES.equals(List.of(0, 0)) && defaulted.getBase() == 8
                && gauge.getBase() == 13 && "ready".equals(gauge.getState()),
                "Class hook runs before declaration defaults and supplied builder values");
        check(gauge.adjust(2) == 15 && gauge.adjust(Integer.valueOf(2)) == 11
                && gauge.adjust((Integer) null) == null && gauge.sum(2, 3, 5) == 10,
                "Primitive and boxed overloads retain their types, including null and variable arguments");
        var input = CallResult.newBuilder().withText("input").build();
        check("input!".equals(gauge.compose(input, "!").getText()) && gauge.compose(null, "!") == null,
                "Delegation accepts and returns typed model objects and null");
        check(GaugeDiagnostics.instances == 0, "A separate method-only delegate starts lazy");
        var updates = new ArrayList<Object>();
        var subscription = gauge.vmf().changes().addPropertyListener("state", change ->
                updates.add(change.propertyChange().orElseThrow().newValue()));
        gauge.submit("submitted");
        check("submitted".equals(gauge.diagnose(true)) && "ok".equals(gauge.diagnose(false))
                && GaugeDiagnostics.instances == 1, "Separate method calls reuse the delegate with its typed owner");

        var override = new LiveOverride();
        gauge.vmf().behavior().set(override);
        check(override.owner == gauge && gauge.adjust(2) == 200 && gauge.adjust((Integer) null) == 99
                && "runtime".equals(gauge.diagnose(true)) && defaulted.adjust(2) == 10,
                "A concrete per-instance replacement covers all callable methods without changing other instances");
        gauge.submit("x");
        subscription.unsubscribe();
        check(updates.equals(List.of("submitted", "runtime:x")), "Behavior-driven edits use ordinary model observation");
        var copied = (MutableGauge) gauge.vmf().content().deepCopy();
        check(copied != gauge, "Deep copy creates an independent mutable model");
        verifyRestored(copied);
        check(gauge.adjust(2) == 200, "Deep copy preserves the original active replacement");
        check(Arrays.stream(ReadOnlyMutableGauge.class.getMethods()).noneMatch(method -> method.getName().equals("adjust")),
                "Java read-only projections expose data only");

        var signal = SignalGauge.newBuilder().withLevel(10).build();
        var other = SignalGauge.newInstance();
        check(SignalBehavior.instances == 0, "Method-only behavior does not bind during construction");
        check(signal.signal(3) == 14 && signal.signal(3) == 15 && SignalBehavior.instances == 1
                && other.signal(3) == 8 && SignalBehavior.instances == 2,
                "The lazy delegate keeps separate per-instance state");
        return gauge;
    }

    private static void verifyRestored(MutableGauge gauge) {
        check(gauge.getBase() == 13 && "runtime:x".equals(gauge.getState()) && gauge.adjust(2) == 15
                && gauge.vmf().behavior().get() == null, "Persisted data is restored with a fresh default binding");
    }

    private static final class LiveOverride implements MutableGauge.Behavior {
        private MutableGauge owner;

        @Override public void setCaller(VObject caller) { owner = (MutableGauge) caller; }
        @Override public int adjust(int amount) { return 100 * amount; }
        @Override public Integer adjust(Integer amount) { return amount == null ? 99 : 100 * amount; }
        @Override public CallResult compose(CallResult input, String suffix) { return input; }
        @Override public String diagnose(boolean verbose) { return "runtime"; }
        @Override public int sum(int... values) { return values.length; }
        @Override public void submit(String text) { owner.setState("runtime:" + Objects.toString(text, "")); }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
