package eu.mihosoft.vmf.conformance.immutablebehavior;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.immutablebehavior.impl.TextSummaryImpl;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

public final class ImmutableToStringOracle {
    private ImmutableToStringOracle() { }

    static void verify() {
        NullBinding.created = TextBinding.created = 0;
        var plain = PlainSummary.newBuilder().withLabel("plain-data").build();
        var empty = NullSummary.newBuilder().withLabel("still-data").build();
        var text = TextSummary.newBuilder().withLabel("alpha").build();
        var derived = InheritedSummary.newBuilder().withLabel("beta").build();
        check(NullBinding.created == 0 && TextBinding.created == 0, "Construction stays lazy");
        check(plain.toString().contains("plain-data"), "Undelegated immutable object uses its ordinary summary");
        check(((Object)empty).toString() == null && ((PlainSummary)empty).toString() == null && NullBinding.created == 1,
                "The real Java override preserves null through both wider views");
        check(((Object)text).toString().equals("alpha:1") && ((TextSummaryImpl)text).calls() == 1
                && text.asReadOnly().toString().equals("alpha:2") && TextBinding.created == 1,
                "Object override and ordinary method share a lazy provider");
        check(((Object)derived).toString().equals("beta:1") && TextBinding.created == 2,
                "Inherited delegation stays on the actual derived value");
        verifySnapshots(false);
        verifySnapshots(true);
    }

    private static void verifySnapshots(boolean references) {
        var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
        var value = TextSummary.newBuilder().withLabel("snapshot").build();
        int previousBindings = TextBinding.created;
        try {
            String before = mapper.writeValueAsString(value);
            var body = mapper.readTree(before);
            check("snapshot".equals(body.path("label").textValue()) && !body.has("calls")
                            && TextBinding.created == previousBindings,
                    "Snapshots persist data without accessing the delegate");
            check("snapshot:1".equals(value.toString()), "The source starts with a fresh provider");
            check(before.equals(mapper.writeValueAsString(value)), "Provider state does not change snapshots");
            var loaded = mapper.readValue(before, TextSummary.class);
            check(TextBinding.created == previousBindings + 1 && "snapshot:1".equals(loaded.toString())
                            && TextBinding.created == previousBindings + 2,
                    "Hydrated values initialize their own provider on demand");
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Immutable snapshot failed", error);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class NullBinding implements DelegatedBehavior<NullSummary> {
        static int created;
        public NullBinding() { created++; }
        @Override public String toString() { return null; }
    }

    public static final class TextBinding implements DelegatedBehavior<TextSummary> {
        static int created;
        private TextSummary caller;
        private int calls;
        public TextBinding() { created++; }
        @Override public void setCaller(TextSummary caller) { this.caller = caller; }
        @Override public String toString() { return caller.getLabel() + ":" + ++calls; }
        public int calls() { return calls; }
    }
}
