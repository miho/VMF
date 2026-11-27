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

public final class DeepRoleOracle {
    private static final List<String> hooks = new ArrayList<>();
    private static final List<VObject> owners = new ArrayList<>();

    private DeepRoleOracle() { }

    public static void verify(Path exchange, boolean writing) throws IOException {
        hooks.clear();
        owners.clear();
        RootBinding.created = ConfigureBinding.created = SpecialBinding.created = PeerBinding.created = 0;
        var deep = SpecializedWins.newInstance();
        var peer = PeerWins.newInstance();
        var labeled = LabeledWins.newInstance();
        var peerLabel = PeerWithLabel.newInstance();
        check(hooks.equals(List.of("specialized:0", "peer:0", "configured:0", "peer:0")),
                "Only the selected hook runs before defaults " + hooks);
        check(owners.equals(List.of(deep, peer, labeled, peerLabel)), "Hooks receive the actual graph owner");
        check(deep.getRootValue() == 11 && deep.getSetting() == 22 && deep.getLabel() == 33
                && deep.getSpecialty() == 44 && deep.getPeerValue() == 55, "Deep role data survives composition");
        check(RootBinding.created == 0 && ConfigureBinding.created == 1 && SpecialBinding.created == 1
                && PeerBinding.created == 2, "Other declarations stay lazy");

        check(((RootService) deep).choose(1) == 402 && ((PeerService) deep).choose(1) == 403
                && deep.specializedCalls() == 2 && deep.rootCalls() == 0, "The inherited specialized override wins on the first path");
        check(((SpecializedService) peer).choose(1) == 302 && ((LabeledService) peer).choose(1) == 303
                && peer.peerCalls() == 2 && peer.specializedCalls() == 0, "Deep secondary uses the first role for overlap");
        check(((ConfiguredService) peer).tune(3) == 204 && ((LabeledService) peer).configuredCalls() == 1
                && ConfigureBinding.created == 2, "Inherited secondary provider uses the original owner and state");
        check(((PeerService) labeled).choose(1) == 102 && ((LabeledService) peerLabel).choose(1) == 302,
                "An undecorated intermediary preserves the inherited provider and parent order");

        peer.vmf().behavior().set(new Replacement(peer));
        check(((SpecializedService) peer).choose(1) == 901 && ((RootService) peer).choose(2) == 902
                && ((ConfiguredService) peer).tune(3) == 803, "Replacement reaches every role depth");
        check(deep.specializedCalls() == 2, "Replacement stays on the actual model");

        var archive = DeepRoleArchive.newBuilder().withItems(deep, peer, labeled, peerLabel).withSelected(peer).build();
        for (boolean references : new boolean[] {false, true}) {
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                    .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
            String expected = mapper.writeValueAsString(archive);
            Path path = exchange.resolve((writing ? "java" : "csharp") + "-deep-roles-" + references + ".json");
            if (writing) Files.writeString(path, expected);
            String wire = Files.readString(path);
            check(mapper.readTree(expected).equals(mapper.readTree(wire)), "Existing JSON model data");
            var restored = mapper.readValue(wire, DeepRoleArchive.class);
            check(restored.getItems().get(0).choose(1) == 402 && restored.getItems().get(1).choose(1) == 302
                    && restored.getItems().get(2).choose(1) == 102 && restored.getItems().get(3).choose(1) == 302,
                    "Restored role defaults keep the same behavior selection");
            check(references ? restored.getSelected() == restored.getItems().get(1) : restored.getSelected() == null,
                    "Selected role alias");
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void hook(VObject owner, String name, int value) { hooks.add(name + ":" + value); owners.add(owner); }

    public static final class RootBinding implements DelegatedBehavior<RootService> {
        static int created;
        private RootService owner;
        private int calls;
        public RootBinding() { created++; }
        @Override public void setCaller(RootService owner) { this.owner = owner; }
        public void onRootServiceInstantiated() { hook(owner, "root", owner.getRootValue()); }
        public int choose(int value) { return 100 + value + ++calls; }
        public int rootCalls() { return calls; }
    }

    public static final class ConfigureBinding implements DelegatedBehavior<ConfiguredService> {
        static int created;
        private ConfiguredService owner;
        private int calls;
        public ConfigureBinding() { created++; }
        @Override public void setCaller(ConfiguredService owner) { this.owner = owner; }
        public void onConfiguredServiceInstantiated() { hook(owner, "configured", owner.getSetting()); }
        public int tune(int value) { return 200 + value + ++calls; }
        public int configuredCalls() { return calls; }
    }

    public static final class SpecialBinding implements DelegatedBehavior<SpecializedService> {
        static int created;
        private SpecializedService owner;
        private int calls;
        public SpecialBinding() { created++; }
        @Override public void setCaller(SpecializedService owner) { this.owner = owner; }
        public void onSpecializedServiceInstantiated() { hook(owner, "specialized", owner.getSpecialty()); }
        public int choose(int value) { return 400 + value + ++calls; }
        public int specializedCalls() { return calls; }
    }

    public static final class PeerBinding implements DelegatedBehavior<PeerService> {
        static int created;
        private PeerService owner;
        private int calls;
        public PeerBinding() { created++; }
        @Override public void setCaller(PeerService owner) { this.owner = owner; }
        public void onPeerServiceInstantiated() { hook(owner, "peer", owner.getPeerValue()); }
        public int choose(int value) { return 300 + value + ++calls; }
        public int peerCalls() { return calls; }
    }

    private static final class Replacement implements PeerWins.Behavior {
        private final PeerWins expected;
        private Replacement(PeerWins expected) { this.expected = expected; }
        @Override public void setCaller(VObject caller) { check(caller == expected, "Replacement owner"); }
        @Override public int choose(int value) { return 900 + value; }
        @Override public int tune(int value) { return 800 + value; }
        @Override public int rootCalls() { return -1; }
        @Override public int configuredCalls() { return -2; }
        @Override public int specializedCalls() { return -3; }
        @Override public int peerCalls() { return -4; }
    }
}
