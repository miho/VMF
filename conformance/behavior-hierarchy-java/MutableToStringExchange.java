package eu.mihosoft.vmf.conformance.behaviorhierarchy;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.io.IOException;
import java.nio.file.Path;

public final class MutableToStringExchange {
    private MutableToStringExchange() { }

    public static void verify(Path exchange, boolean write) throws IOException {
        for (boolean references : new boolean[]{false, true}) {
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                    .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
            String suffix = references ? "-caption-reference.json" : "-caption-by-value.json";
            if (write) {
                var value = exercise(mapper);
                String persisted = mapper.writeValueAsString(value.asReadOnly());
                var document = mapper.readTree(persisted);
                check("beta".equals(document.path("label").textValue()) && !document.has("calls"),
                        "Only caption data is serialized");
                mapper.writeValue(exchange.resolve("java" + suffix).toFile(), value.asReadOnly());
                verifyRestored(mapper.readValue(persisted, MutableCaption.class));
            } else {
                verifyRestored(mapper.readValue(exchange.resolve("csharp" + suffix).toFile(), MutableCaption.class));
            }
        }
    }

    private static void verifyRestored(MutableCaption loaded) {
        int previousBindings = CaptionBinding.created;
        check("beta".equals(loaded.getLabel()) && loaded.vmf().behavior().get() == null
                        && "beta:1".equals(loaded.asReadOnly().toString()) && loaded.calls() == 1
                        && CaptionBinding.created == previousBindings + 1,
                "A loaded caption starts with its own lazy default binding");
    }

    private static MutableCaption exercise(ObjectMapper mapper) throws IOException {
        CaptionBinding.created = NullBinding.created = 0;
        var plain = PlainCaption.newBuilder().withLabel("plain-data").build();
        var unrelated = UnrelatedCaption.newBuilder().withLabel("unrelated-data").build();
        var empty = NullCaption.newBuilder().withLabel("still-data").build();
        var value = MutableCaption.newBuilder().withLabel("alpha").build();
        var derived = InheritedCaption.newBuilder().withLabel("delta").build();
        var view = value.asReadOnly();
        var nullView = empty.asReadOnly();
        check(CaptionBinding.created == 0 && NullBinding.created == 0, "Read-only projection does not evaluate lazy behavior");
        check(plain.toString().contains("plain-data") && plain.asReadOnly().toString().equals(plain.toString())
                && unrelated.toString().contains("unrelated-data"), "Unrelated and undelegated base summaries");
        check(((Object)nullView).toString() == null && ((Object)empty).toString() == null
                && NullBinding.created == 1, "Null reaches the direct object and its read-only alias");
        check(((Object)view).toString().equals("alpha:1") && value.calls() == 1
                && ((PlainCaption)value).toString().equals("alpha:2") && CaptionBinding.created == 1,
                "Read-only and base dispatch reuse the same provider as ordinary delegate calls");

        value.setLabel("beta");
        check(view.getLabel().equals("beta") && view.toString().equals("beta:3"), "The view sees the live caller");
        check(((MutableCaption)derived).toString().equals("delta:1")
                && derived.asReadOnly().toString().equals("delta:2") && CaptionBinding.created == 2,
                "Inheritance keeps one default provider per live caller");

        String before = mapper.writeValueAsString(view);
        var replacement = new Replacement();
        value.vmf().behavior().set(replacement);
        check(replacement.caller == value && value.toString() == null && view.toString() == null
                && value.calls() == 42 && CaptionBinding.created == 2 && derived.calls() == 2,
                "Per-object behavior replacement updates both existing dispatch surfaces");
        check(before.equals(mapper.writeValueAsString(view)), "The active replacement does not affect the snapshot");
        return value;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class CaptionBinding implements DelegatedBehavior<MutableCaption> {
        static int created;
        private MutableCaption caller;
        private int calls;
        public CaptionBinding() { created++; }
        @Override public void setCaller(MutableCaption caller) { this.caller = caller; }
        @Override public String toString() { return caller.getLabel() + ":" + ++calls; }
        public int calls() { return calls; }
    }

    public static final class NullBinding implements DelegatedBehavior<NullCaption> {
        static int created;
        public NullBinding() { created++; }
        @Override public String toString() { return null; }
    }

    private static final class Replacement implements MutableCaption.Behavior {
        private MutableCaption caller;
        @Override public void setCaller(VObject caller) { this.caller = (MutableCaption)caller; }
        @Override public String toString() { return null; }
        @Override public int calls() { return 42; }
    }
}
