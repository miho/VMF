package eu.mihosoft.vmf.conformance.namedinverse;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class NamedInverseConformance {
    private static final String CHECKPOINT = "named-inverse-6";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "conformance.named-inverse"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject value) {
            if (value instanceof Grammar) return "grammar";
            if (value instanceof Rule) return ((Rule) value).getName();
            if (value instanceof Member) return ((Member) value).getName();
            throw new IllegalArgumentException("Unknown named-inverse object");
        }
        @Override public String type(VObject value) { return value.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) {
            throw new IllegalArgumentException("The journal uses only checkpoint objects");
        }
    };

    private NamedInverseConformance() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !("write".equals(args[0]) || "read".equals(args[0]))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        var directory = Path.of(args[1]);
        if ("write".equals(args[0])) {
            verifyLive();
            Files.createDirectories(directory);
            var grammar = specimen();
            MAPPER.writeValue(path(directory, "java", "checkpoint.json").toFile(), grammar);
            var journal = JournalRecorder.recordFrame(grammar, BINDING, CHECKPOINT, 6, root ->
                    root.getRules().get(0).getCustomProperties().get(0).setParent(root.getRules().get(1)));
            for (var format : VMFDocumentFormat.values()) {
                Files.writeString(path(directory, "java", journalFile(format)), VMFJournalCodec.write(journal, format));
            }
            verify(directory, "java");
            ProjectedRelationshipsConformance.write(directory, MAPPER);
            System.out.println("Java verified alternate owning fields, projected connector relationships, and V1 replay.");
        } else {
            verify(directory, "csharp");
            ProjectedRelationshipsConformance.verify(directory, "csharp", MAPPER);
            System.out.println("Java restored C# named inverses and connector relationships and replayed V1 journals in JSON, XML, and YAML.");
        }
    }

    private static Grammar specimen() {
        var member = Member.newBuilder().withName("member").build();
        var first = Rule.newBuilder().withName("first").withCustomProperties(member).build();
        var second = Rule.newBuilder().withName("second").build();
        return Grammar.newBuilder().withRules(first, second).build();
    }

    private static void verifyLive() {
        var grammar = specimen();
        var first = grammar.getRules().get(0);
        var second = grammar.getRules().get(1);
        var member = first.getCustomProperties().get(0);
        check(member.getParent() == first && member.asReadOnly().getParent() == first.asReadOnly()
                && member.vmf().reflect().propertyByName("parent").orElseThrow().get() == first,
                "The alternate field sets the typed, read-only and reflected parent");
        member.setParent(first);
        check(first.getCustomProperties().size() == 1 && first.getProperties().isEmpty(),
                "Assigning the same parent preserves the physical slot");
        member.setParent(second);
        check(first.getCustomProperties().isEmpty() && second.getProperties().get(0) == member,
                "A different parent uses the declared canonical owning field");
        member.setParent(null);
        check(second.getProperties().isEmpty() && member.getParent() == null, "Clearing the canonical owner detaches");
        first.getCustomProperties().add(member);
        member.setParent(null);
        check(first.getCustomProperties().isEmpty() && member.getParent() == null, "Clearing the alternate owner detaches");
    }

    private static void verify(Path directory, String producer) throws IOException {
        String checkpoint = Files.readString(path(directory, producer, "checkpoint.json"));
        var custom = MAPPER.readTree(checkpoint).path("rules").get(0).path("customProperties").get(0);
        check(!custom.has("parent"), "The inverse is reconstructed from the owning field");
        for (var format : VMFDocumentFormat.values()) {
            var initial = MAPPER.readValue(checkpoint, Grammar.class);
            var oldOwner = initial.getRules().get(0);
            check(oldOwner.getCustomProperties().get(0).getParent() == oldOwner, "Snapshot restores the alternate inverse");
            var journal = VMFJournalCodec.read(Files.readString(path(directory, producer, journalFile(format))), format);
            var edits = journal.transactions().get(0).operations();
            check(journal.wireVersion() == 1 && edits.size() == 2
                    && edits.get(0).property().equals("customProperties") && edits.get(1).property().equals("properties"),
                    "Journal records both physical owning edges");
            var replay = JournalReplayer.replay(initial, BINDING, CHECKPOINT, journal);
            var newOwner = replay.getRules().get(1);
            check(replay.getRules().get(0).getCustomProperties().isEmpty()
                    && newOwner.getProperties().get(0).getParent() == newOwner && oldOwner.getCustomProperties().size() == 1,
                    "Replay attaches to the canonical field in the independent graph");
        }
    }

    private static String journalFile(VMFDocumentFormat format) { return "journal." + format.name().toLowerCase(Locale.ROOT); }
    private static Path path(Path dir, String producer, String suffix) { return dir.resolve(producer + "-" + suffix); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
