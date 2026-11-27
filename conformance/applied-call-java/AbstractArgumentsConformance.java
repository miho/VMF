package eu.mihosoft.vmf.conformance.appliedcalls;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class AbstractArgumentsConformance {
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "abstract-parameters"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject node) {
            if (node instanceof AbstractArgumentProject) return "project";
            if (node instanceof PrimaryArgument) return "primary";
            if (node instanceof SecondaryArgument) return "secondary";
            if (node instanceof ArgumentCallHost) return "host";
            throw new IllegalArgumentException(node.vmf().reflect().type().getName());
        }
        @Override public String type(VObject node) { return node.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new IllegalArgumentException("No created nodes expected."); }
    };

    private AbstractArgumentsConformance() { }

    static void verify(boolean write, Path directory) throws Exception {
        var rename = ArgumentCallHost.class.getMethod("rename", PrimaryArgument.class, String.class);
        var relabel = ArgumentCallHost.class.getMethod("relabel", SecondaryArgument.class, String.class);
        check(rename.getReturnType() == PrimaryArgument.class && relabel.getReturnType() == SecondaryArgument.class,
                "Java preserves the exact named argument and result types");
        var expected = JournalRecorder.recordFrame(specimen(), BINDING, "initial", 0,
                AbstractArgumentsConformance::invoke);
        String prefix = write ? "java-" : "csharp-";
        for (var format : VMFDocumentFormat.values()) {
            String suffix = "." + format.name().toLowerCase(Locale.ROOT);
            var snapshot = directory.resolve(prefix + "abstract-arguments-checkpoint" + suffix);
            var journal = directory.resolve(prefix + "abstract-arguments-journal" + suffix);
            var mapper = mapper(format);
            if (write) {
                Files.writeString(snapshot, mapper.writeValueAsString(specimen()));
                Files.writeString(journal, VMFJournalCodec.write(expected, format));
            }
            var checkpoint = mapper.readValue(snapshot.toFile(), AbstractArgumentProject.class);
            check(checkpoint.getMain() instanceof PrimaryArgumentLeaf && checkpoint.getDetail() instanceof ComposedArgument,
                    "Restores concrete instances of both abstract arguments");
            check("before".equals(checkpoint.getMain().getName()) && "unset".equals(checkpoint.getDetail().getLabel()),
                    "Restores the checkpoint before either call");
            var recorded = VMFJournalCodec.read(Files.readString(journal), format);
            check(recorded.wireVersion() == 1, "Uses the existing journal profile");
            var replayed = JournalReplayer.replay(checkpoint, BINDING, "initial", recorded);
            check("after".equals(replayed.getMain().getName()) && "tagged".equals(replayed.getDetail().getLabel())
                    && "before".equals(checkpoint.getMain().getName()) && "unset".equals(checkpoint.getDetail().getLabel()),
                    "Replays both delegated edits on a fresh graph");
        }
        System.out.println("Java verified named abstract behavior arguments and V1 journal interchange in JSON, YAML and XML.");
    }

    private static void invoke(AbstractArgumentProject project) {
        PrimaryArgument primary = project.getMain();
        SecondaryArgument secondary = project.getDetail();
        var host = project.getSkill();
        check(primary == host.rename(primary, "after") && "after".equals(primary.getName()), "Preserves the primary argument identity");
        check(secondary == host.relabel(secondary, "tagged") && "tagged".equals(secondary.getLabel()),
                "Preserves the secondary argument identity");
        check(host.rename(null, "unused") == null && host.relabel(null, "unused") == null, "Accepts null arguments");
    }

    private static AbstractArgumentProject specimen() {
        return AbstractArgumentProject.newBuilder()
                .withMain(PrimaryArgumentLeaf.newBuilder().withName("before").withData("data").build())
                .withDetail(ComposedArgument.newBuilder().withTitle("header").withLabel("unset").build())
                .withSkill(ArgumentCallHost.newInstance()).build();
    }

    private static ObjectMapper mapper(VMFDocumentFormat format) {
        ObjectMapper mapper;
        switch (format) {
            case XML: mapper = new XmlMapper(); break;
            case YAML: mapper = new YAMLMapper(); break;
            default: mapper = new ObjectMapper();
        }
        return mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class Binding implements DelegatedBehavior<ArgumentCallHost> {
        private ArgumentCallHost caller;

        @Override public void setCaller(ArgumentCallHost caller) {
            this.caller = caller;
        }

        public PrimaryArgument rename(PrimaryArgument value, String next) {
            if (caller == null) throw new IllegalStateException("Missing caller");
            if (value != null) value.setName(next);
            return value;
        }

        public SecondaryArgument relabel(SecondaryArgument value, String next) {
            if (caller == null) throw new IllegalStateException("Missing caller");
            if (value != null) value.setLabel(next);
            return value;
        }
    }
}
