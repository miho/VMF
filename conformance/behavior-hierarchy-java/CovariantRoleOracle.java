package eu.mihosoft.vmf.conformance.behaviorhierarchy;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class CovariantRoleOracle {
    private CovariantRoleOracle() { }

    public static void verify(Path exchange, boolean writing) throws IOException {
        Shared.binds = 0;
        var ordered = NarrowFirst.newInstance();
        ordered.setStored(value("ordered", 2));
        check(ordered.choose() == ordered.getStored() && ((WideRole) ordered).choose() == ordered.getStored()
                && ((NarrowRole) ordered).choose().getRank() == 2, "First parent narrowing across both roles");
        check(Arrays.stream(ordered.getClass().getDeclaredMethods()).anyMatch(method -> method.isBridge()
                && method.getName().equals("choose") && method.getReturnType() == Result.class),
                "javac emits the bridge for the wider contract");

        var competing = SecondaryNarrow.newInstance();
        competing.setStored(value("secondary", 4));
        check(competing.choose() == competing.getStored() && ((WideRole) competing).choose() == competing.getStored(),
                "Both secondary result contracts use the selected narrow binding");

        var shared = SharedNarrowFirst.newInstance();
        shared.setStored(value("shared", 5));
        check(((SharedWide) shared).sample() == shared.getStored()
                && ((SharedNarrow) shared).sample() == shared.getStored() && Shared.binds == 1,
                "One shared provider satisfies both result contracts");

        var replacement = value("replacement", 9);
        ordered.vmf().behavior().set(new Replacement(ordered, replacement));
        check(((WideRole) ordered).choose() == replacement && ((NarrowRole) ordered).choose() == replacement,
                "One replacement preserves its returned identity through both roles");
        var empty = SecondaryNarrow.newInstance();
        check(empty.choose() == null && ((WideRole) empty).choose() == null, "Both roles preserve a null result");

        var archive = CovariantRoleArchive.newBuilder().withItems(ordered, competing, shared).withSelected(ordered).build();
        for (boolean references : new boolean[] {false, true}) {
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                    .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(references));
            String expected = mapper.writeValueAsString(archive);
            Path path = exchange.resolve((writing ? "java" : "csharp") + "-covariant-roles-" + references + ".json");
            if (writing) Files.writeString(path, expected);
            String wire = Files.readString(path);
            check(mapper.readTree(expected).equals(mapper.readTree(wire)), "Existing snapshot data matches");
            var restored = mapper.readValue(wire, CovariantRoleArchive.class);
            var fresh = (NarrowFirst) restored.getItems().get(0);
            check(((WideRole) fresh).choose() == fresh.getStored() && fresh.choose().getRank() == 2
                    && ((SecondaryNarrow) restored.getItems().get(1)).choose().getRank() == 4
                    && ((SharedNarrowFirst) restored.getItems().get(2)).sample().getRank() == 5,
                    "Snapshots restore each default provider and its typed result");
            check(references ? restored.getSelected() == fresh : restored.getSelected() == null, "Selected role alias");
        }
    }

    private static RankedResult value(String name, int rank) { return RankedResult.newBuilder().withName(name).withRank(rank).build(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    public static final class Neutral implements DelegatedBehavior<NeutralRole> {
        public int baseline() { return 1; }
    }

    public static final class Wide implements DelegatedBehavior<WideRole> {
        public Result choose() { throw new AssertionError("The later wide provider was selected"); }
    }

    public static final class Narrow implements DelegatedBehavior<ResultData> {
        private ResultData owner;
        @Override public void setCaller(ResultData caller) { owner = caller; }
        public RankedResult choose() { return owner.getStored(); }
    }

    public static final class Shared implements DelegatedBehavior<ResultData> {
        static int binds;
        private ResultData owner;
        @Override public void setCaller(ResultData caller) { owner = caller; binds++; }
        public RankedResult sample() { return owner.getStored(); }
    }

    private static final class Replacement implements NarrowFirst.Behavior {
        private final NarrowFirst expected;
        private final RankedResult value;
        Replacement(NarrowFirst expected, RankedResult value) { this.expected = expected; this.value = value; }
        @Override public void setCaller(VObject caller) { check(caller == expected, "Actual replacement caller"); }
        @Override public RankedResult choose() { return value; }
    }
}
