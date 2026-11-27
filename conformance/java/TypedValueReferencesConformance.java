package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.conformance.traits.BadgeShowcase;
import eu.mihosoft.vmf.conformance.traits.FixedBadge;
import eu.mihosoft.vmf.conformance.traits.MutableBadge;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.ArchiveRoot;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.conformance.values.KeyedInventory;
import eu.mihosoft.vmf.example.workshops.Catalog;
import eu.mihosoft.vmf.example.workshops.Thermometer;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class TypedValueReferencesConformance {
    private static final List<String> FORMATS = List.of("json", "yaml", "xml");
    private static final String INLINE_JSON = "{\"head\":{\"name\":\"owned\",\"anchor\":{\"name\":\"inline\","
            + "\"anchor\":{\"name\":\"nested\"}},\"trail\":[{\"name\":\"copy\"},null,{\"name\":\"copy\"}]},"
            + "\"review\":[{\"name\":\"outside\"},{\"name\":\"outside\"}]}";
    private static final String INLINE_XML = "<eu.mihosoft.vmf.conformance.values.ArchiveRoot"
            + " xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'>"
            + "<head><name>owned</name><anchor><name>inline</name><anchor><name>nested</name></anchor></anchor>"
            + "<trail><item><name>copy</name></item><item xsi:nil='true'/><item><name>copy</name></item></trail></head>"
            + "<review><item><name>outside</name></item><item><name>outside</name></item></review>"
            + "</eu.mihosoft.vmf.conformance.values.ArchiveRoot>";

    private TypedValueReferencesConformance() { }

    public static void main(String[] args) throws IOException {
        if (args.length == 3 && args[0].equals("write")) {
            var directory = Files.createDirectories(Path.of(args[1]));
            write(directory, Path.of(args[2]));
            verify(directory, "java");
            System.out.println("Java qualified typed-by-value reference projection and legacy inline reads in JSON, YAML, and XML.");
        } else if (args.length == 2 && args[0].equals("read")) {
            verify(Path.of(args[1]), "csharp");
            System.out.println("Java restored C# typed-by-value JSON, YAML, and XML projections.");
        } else {
            throw new IllegalArgumentException("Usage: TypedValueReferencesConformance write <directory> <repoRoot> | read <directory>");
        }
    }

    private static void write(Path directory, Path repository) throws IOException {
        var references = mapper("json", true);
        var fixtures = repository.resolve("conformance/fixtures");
        var catalog = references.readValue(repository.resolve("examples/csharp-workshop/fixtures/catalog.json").toFile(), Catalog.class);
        var archive = references.readValue(fixtures.resolve("archive-references.json").toFile(), ArchiveRoot.class);
        var keyed = references.readValue(fixtures.resolve("keyed-inventory.json").toFile(), KeyedInventory.class);
        var collections = references.readValue(fixtures.resolve("scalar-collections.json").toFile(), CollectionProfile.class);
        var fixed = FixedBadge.newBuilder().build();
        var mixed = BadgeShowcase.newBuilder().withSelected(fixed).withChoices(fixed, null, MutableBadge.newInstance(), fixed).build();
        var immutable = BadgeShowcase.newBuilder().withSelected(fixed).withChoices(fixed, null, fixed).build();

        for (String format : FORMATS) {
            var io = mapper(format, false);
            // The typed-value documents deliberately leave out the non-owning mutable references.
            var omitting = mapper(format, VMFJacksonModule.ReferenceMode.OMIT);
            var graph = archive.vmf().content().deepCopy();
            var root = (ArchiveRoot) graph;
            rejects(() -> io.writeValueAsString(root), "ordered mutable reference set");
            root.getHead().getFeatured().clear();
            root.getHead().getPeers().add(ArchiveNode.newInstance());
            rejects(() -> io.writeValueAsString(root), "unordered mutable reference set");
            root.getHead().getPeers().clear();
            root.getHead().setAnchor(ArchiveNode.newInstance());
            rejects(() -> io.writeValueAsString(keyed), "mutable model-valued key");

            omitting.writeValue(file(directory, "java", "catalog", format).toFile(), catalog);
            omitting.writeValue(file(directory, "java", "archive", format).toFile(), root);
            omitting.writeValue(file(directory, "java", "mixed", format).toFile(), mixed);
            omitting.writeValue(file(directory, "java", "immutable", format).toFile(), immutable);
            omitting.writeValue(file(directory, "java", "collections", format).toFile(), collections);
            String inline = document(format, INLINE_JSON, INLINE_XML);
            Files.writeString(file(directory, "java", "inline", format), inline);
            verifyInline(io.readValue(inline, ArchiveRoot.class));

            var nullOnly = ArchiveRoot.newInstance();
            nullOnly.getReview().add(null);
            check(!io.readTree(io.writeValueAsString(nullOnly)).has("review"), "An all-null ordinary mutable list is omitted");
            String unique = document(format, INLINE_JSON.replace("trail", "featured"), INLINE_XML.replace("trail", "featured"));
            rejects(() -> io.readValue(unique, ArchiveRoot.class), "inline mutable unique reference");
            String ids = document(format, "{\"head\":{\"anchor\":\"123\"}}", "<eu.mihosoft.vmf.conformance.values.ArchiveRoot>"
                    + "<head><anchor>123</anchor></head></eu.mihosoft.vmf.conformance.values.ArchiveRoot>");
            rejects(() -> io.readValue(ids, ArchiveRoot.class), "ID in ordinary typed-by-value reference");
        }
    }

    private static void verify(Path directory, String author) throws IOException {
        for (String format : FORMATS) {
            var io = mapper(format, false);
            var catalogFile = file(directory, author, "catalog", format).toFile();
            var catalog = io.readValue(catalogFile, Catalog.class);
            check(catalog.getFeatured() == null && !io.readTree(catalogFile).has("featured")
                    && catalog.getProducts().size() == 2 && catalog.getProducts().get(0) instanceof Thermometer,
                    "Contained subtype survives while the non-owning mutable alias is omitted");

            var archiveFile = file(directory, author, "archive", format).toFile();
            var archiveWire = io.readTree(archiveFile);
            var headWire = archiveWire.path("head");
            var archive = io.readValue(archiveFile, ArchiveRoot.class);
            check(!archiveWire.has("review") && !headWire.has("anchor") && !headWire.has("trail")
                    && !headWire.has("featured") && !headWire.has("peers") && archive.getReview().isEmpty()
                    && archive.getHead().getChildren().get(0).getParent() == archive.getHead()
                    && archive.getHead().getInset().getInsetOf() == archive.getHead(), "Mutable ordinary positions and empty mutable sets are omitted, containment stays live");

            var mixedFile = file(directory, author, "mixed", format).toFile();
            var mixed = io.readValue(mixedFile, BadgeShowcase.class);
            check(!io.readTree(mixedFile).has("choices") && mixed.getChoices().isEmpty() && mixed.getSelected() instanceof FixedBadge,
                    "The whole mixed list is omitted while the independent immutable scalar persists");
            var values = io.readValue(file(directory, author, "immutable", format).toFile(), BadgeShowcase.class);
            var choices = values.getChoices();
            check(choices.size() == 3 && choices.get(0) instanceof FixedBadge && choices.get(1) == null
                    && choices.get(0) != choices.get(2) && choices.get(0) != values.getSelected(),
                    "Immutable values and nullable slots are copied by value without graph identity");

            var collections = io.readValue(file(directory, author, "collections", format).toFile(), CollectionProfile.class);
            check(collections.getLimits().get("limit") == Long.MAX_VALUE && collections.getLimits().containsKey("optional")
                    && collections.getLimits().get("optional") == null && Boolean.TRUE.equals(collections.getOptions().get("@vmf-id"))
                    && collections.getMarkers().get(-0.0) == '-' && collections.getMarkers().get(+0.0) == '+',
                    "Typed scalar maps and metadata-like string keys survive the reference-free projection");
        }
    }

    private static void verifyInline(ArchiveRoot result) {
        var head = result.getHead();
        check("inline".equals(head.getAnchor().getName()) && "nested".equals(head.getAnchor().getAnchor().getName())
                && head.getTrail().size() == 3 && head.getTrail().get(1) == null && head.getTrail().get(0) != head.getTrail().get(2)
                && result.getReview().size() == 2 && result.getReview().get(0) != result.getReview().get(1),
                "Explicit inline mutable references are fresh values, including nested references and null slots");
    }

    private static String document(String format, String json, String xml) throws IOException {
        if (format.equals("xml")) return xml;
        return format.equals("yaml") ? new YAMLMapper().writeValueAsString(new ObjectMapper().readTree(json)) : json;
    }

    private static ObjectMapper mapper(String format, boolean references) {
        return mapper(format, references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.REJECT);
    }

    private static ObjectMapper mapper(String format, VMFJacksonModule.ReferenceMode references) {
        var mapper = format.equals("xml") ? new XmlMapper() : format.equals("yaml") ? new YAMLMapper() : new ObjectMapper();
        return mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(references).withModelWireTypeAliases(Catalog.class).withModelWireTypeAliases(BadgeShowcase.class));
    }

    private static Path file(Path directory, String author, String name, String format) {
        return directory.resolve(author + "-" + name + "." + format);
    }

    private static void rejects(IoAction action, String behavior) throws IOException {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Unexpectedly accepted " + behavior);
    }

    private interface IoAction { void run() throws IOException; }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
