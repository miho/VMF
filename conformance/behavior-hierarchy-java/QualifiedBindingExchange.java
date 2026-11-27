package eu.mihosoft.vmf.conformance.behaviorhierarchy;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class QualifiedBindingExchange {
    private QualifiedBindingExchange() { }

    public static void verify(Path exchange, boolean writing) throws IOException {
        Cold.Binding.created = Hot.Binding.created = 0;
        var local = QualifiedLocal.newInstance();
        var shared = QualifiedShared.newBuilder().withName("qualified").build();
        check(local.getLevel() == 7 && Cold.Binding.created == 1 && Hot.Binding.created == 0,
                "Only the locally selected binding runs during construction");
        check(local.coolLocal() == 101 && local.warmLocal() == 1 && local.coolLocal() == 102
                && local.warmLocal() == 2, "Same-name local classes have separate state");
        check(shared.cool() == 1 && shared.warmAgain() == 1 && shared.coolAgain() == 2
                && shared.warm() == 2 && shared.cool() == 3,
                "Composed shared groups each share only their own binding");
        check(Cold.Binding.created == 2 && Hot.Binding.created == 2, "Each binding is created once per owner");

        var archive = QualifiedBindingArchive.newBuilder().withLocal(local).withShared(shared).withSelected(shared).build();
        for (boolean references : new boolean[] {false, true}) {
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                    .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
            String expected = mapper.writeValueAsString(archive);
            Path file = exchange.resolve((writing ? "java" : "csharp") + "-qualified-bindings-" + references + ".json");
            if (writing) Files.writeString(file, expected);
            String actual = Files.readString(file);
            check(mapper.readTree(expected).equals(mapper.readTree(actual)), "Snapshot contains only model data");
            var restored = mapper.readValue(actual, QualifiedBindingArchive.class);
            check(restored.getLocal().coolLocal() == 101 && restored.getLocal().warmLocal() == 1
                    && restored.getShared().coolAgain() == 1 && restored.getShared().warm() == 1,
                    "Snapshot creates fresh binding state");
            check(references ? restored.getSelected() == restored.getShared() : restored.getSelected() == null,
                    "Reference-aware snapshot keeps the selected alias");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class Cold {
        public static final class Binding implements DelegatedBehavior<VObject> {
            static int created;
            private VObject caller;
            private int state;

            public Binding() { created++; }
            @Override public void setCaller(VObject caller) { this.caller = caller; }

            public void onQualifiedLocalInstantiated() {
                check(((QualifiedLocal) caller).getLevel() == 0, "Hook sees raw field");
                state = 100;
            }

            public int coolLocal() { check(caller instanceof QualifiedLocal, "Local cold caller"); return ++state; }
            public int cool() { check(caller instanceof QualifiedCold, "Cold role caller"); return ++state; }
            public int coolAgain() { check(caller instanceof QualifiedColdRole, "Cold peer caller"); return ++state; }
        }
    }

    public static final class Hot {
        public static final class Binding implements DelegatedBehavior<VObject> {
            static int created;
            private VObject caller;
            private int state;

            public Binding() { created++; }
            @Override public void setCaller(VObject caller) { this.caller = caller; }
            public int warmLocal() { check(caller instanceof QualifiedLocal, "Local warm caller"); return ++state; }
            public int warm() { check(caller instanceof QualifiedHot, "Hot role caller"); return ++state; }
            public int warmAgain() { check(caller instanceof QualifiedHotRole, "Hot peer caller"); return ++state; }
        }
    }
}
