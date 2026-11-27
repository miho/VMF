package eu.mihosoft.vmf.conformance.referencedefaults;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ReferenceDefaultConformance {
    private static final String MODEL = "conformance.reference-defaults";
    private static final String CHECKPOINT = "reference-defaults-40";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return MODEL; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) {
            if (object instanceof SelectionTarget) return ((SelectionTarget) object).getCode();
            if (object instanceof SelectionToken) return ((SelectionToken) object).getKey();
            if (object instanceof MutablePicks) return "mutable";
            if (object instanceof FrozenPicks) return "frozen";
            if (object instanceof ReferenceDefaultDocument) return "document";
            throw new IllegalArgumentException("Unregistered reference-default object");
        }

        @Override public String type(VObject object) {
            return object.vmf().reflect().type().getName();
        }

        @Override public VObject create(String type, String id) {
            throw new IllegalArgumentException("This journal uses only checkpoint references");
        }
    };

    private ReferenceDefaultConformance() { }

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("live")) {
            verifyGeneratedDefaults();
            verifyFrozenBuilderImports();
            MixedDefaultConformance.verify();
            InheritedReferenceDefaultsConformance.verify(MAPPER);
            InheritedMixedDefaultsConformance.verify();
            HookReferenceDefaultsConformance.verify();
            RootHookDefaultsConformance.verify();
            ComposedReferenceDefaultsConformance.verify();
            ProjectedDefaultsConformance.verify();
            HookedProjectionConformance.verify();
            System.out.println("Java verified the existing generated reference-default identity and copy semantics.");
            return;
        }
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Expected live or (write|read) <directory>");
        }
        var exchange = Path.of(args[1]);
        if (args[0].equals("write")) {
            Files.createDirectories(exchange);
            verifyGeneratedDefaults();
            verifyFrozenBuilderImports();
            MixedDefaultConformance.verify();
            InheritedReferenceDefaultsConformance.verify(MAPPER);
            InheritedMixedDefaultsConformance.verify();
            HookReferenceDefaultsConformance.verify();
            RootHookDefaultsConformance.verify();
            ComposedReferenceDefaultsConformance.verify();
            ProjectedDefaultsConformance.verify();
            HookedProjectionConformance.verify();
            verifyMutableReader();
            var specimen = specimen();
            MAPPER.writeValue(file(exchange, "java", "checkpoint.json").toFile(), specimen);
            try (var recorder = JournalRecorder.start(specimen, BINDING, CHECKPOINT, 40)) {
                var mutable = specimen.getMutable();
                mutable.getFavorites().remove(specimen.getTargets().get(0));
                mutable.getFavorites().add(specimen.getTargets().get(1));
                mutable.getFeatured().move(1, 0);
                var journal = recorder.finish();
                check(journal.wireVersion() == 1, "Reference membership uses journal version 1");
                Files.writeString(file(exchange, "java", "journal.json"), VMFJournalCodec.write(journal));
                Files.writeString(file(exchange, "java", "journal.xml"), VMFJournalCodec.write(journal, VMFDocumentFormat.XML));
            }
            verifyExchange(exchange, "java");
            System.out.println("Java verified explicit reference-default document fields and membership journal replay in JSON and XML.");
        } else {
            verifyExchange(exchange, "csharp");
            System.out.println("Java replayed the C# reference-default checkpoint and reference membership journals.");
        }
    }

    private static ReferenceDefaultDocument specimen() {
        var alpha = ReferenceDefaultBindings.target("alpha");
        var beta = ReferenceDefaultBindings.target("beta");
        var first = ReferenceDefaultBindings.token("token-first", "explicit");
        var second = ReferenceDefaultBindings.token("token-second", "explicit");
        var mutable = MutablePicks.newBuilder().withFavorites(alpha).withFeatured(alpha, beta).build();
        var frozen = FrozenPicks.newBuilder().withFavorites(first, second).withFeatured(second, first).build();
        return ReferenceDefaultDocument.newBuilder().withTargets(alpha, beta).withMutable(mutable).withFrozen(frozen).build();
    }

    private static void verifyGeneratedDefaults() {
        int before = ReferenceDefaultBindings.mutableCalls;
        var source = MutablePicks.newInstance();
        check(ReferenceDefaultBindings.mutableCalls == before + 2, "An owner evaluates one provider per property");
        var favorite = field(source, "favorites");
        var featured = field(source, "featured");
        List<SelectionTarget> snapshot = defaultMembers(favorite);
        var original = snapshot.get(0);
        var linked = ReferenceDefaultConformance.<SelectionTarget>defaultMembers(featured).get(0);
        original.setLink(linked);
        linked.setLink(original);
        check(snapshot == favorite.getDefault() && original == source.getFavorites().iterator().next()
                && !favorite.isSet() && ReferenceDefaultBindings.mutableCalls == before + 2,
                "Reflection and live membership share the provider's captured identity");
        expect(UnsupportedOperationException.class, snapshot::clear);
        var view = field(source.asReadOnly(), "favorites");
        check(((List<?>) view.getDefault()).get(0) == original.asReadOnly(), "Read-only reflection projects the cached member");

        MutablePicks deep = source.vmf().content().deepCopy();
        MutablePicks shallow = source.vmf().content().shallowCopy();
        check(ReferenceDefaultBindings.mutableCalls == before + 2 && field(shallow, "favorites").getDefault() == snapshot
                && shallow.getFavorites() != source.getFavorites(), "Copies skip the provider and shallow copies share the frozen fallback only");
        var copied = deep.getFavorites().iterator().next();
        check(copied != original && copied == defaultMembers(field(deep, "favorites")).get(0)
                && copied.getLink() == deep.getFeatured().get(0) && copied.getLink().getLink() == copied,
                "Deep copy uses one identity map for live, declared and linked model values");

        source.getFavorites().clear();
        source.getFeatured().clear();
        var configured = ReferenceDefaultBindings.target("override");
        configured.setLink(original);
        favorite.setDefault(List.of(configured));
        MutablePicks emptyCopy = source.vmf().content().deepCopy();
        var copiedDefault = ReferenceDefaultConformance.<SelectionTarget>defaultMembers(field(emptyCopy, "favorites")).get(0);
        check(copiedDefault != configured && copiedDefault.getLink() != original && emptyCopy.getFavorites().isEmpty(),
                "Deep copy follows refs reachable solely through the instance override");
        var copiedFavorite = field(emptyCopy, "favorites");
        copiedFavorite.setDefault(null);
        check(defaultMembers(copiedFavorite).get(0) == copiedDefault.getLink(), "Clearing the override restores the remapped declared cache");
        copiedFavorite.unset();
        check(emptyCopy.getFavorites().iterator().next() == copiedDefault.getLink()
                && ReferenceDefaultBindings.mutableCalls == before + 2, "Reset applies the cached fallback without running the provider");

        before = ReferenceDefaultBindings.mutableCalls;
        var builder = MutablePicks.newBuilder().withFavorites(List.of());
        var first = builder.build();
        var second = builder.build();
        check(ReferenceDefaultBindings.mutableCalls == before + 2 && first.getFavorites().isEmpty()
                && defaultMembers(field(first, "favorites")) == defaultMembers(field(second, "favorites")),
                "Builder captures once, retains explicit empty values, and shares the immutable fallback across results");

        before = ReferenceDefaultBindings.mutableCalls;
        var inner = SelectionTarget.newBuilder().withCode("nested");
        var lazy = MutablePicks.newBuilder().withFavorites(inner).withFeatured(List.<SelectionTarget.Builder>of());
        check(ReferenceDefaultBindings.mutableCalls == before + 2, "Staging captures each fallback once");
        var targetPicks = MutablePicks.newInstance();
        var targetMember = targetPicks.getFavorites().iterator().next();
        lazy.applyTo(targetPicks);
        check(targetPicks.getFavorites().iterator().next() == targetMember && targetPicks.getFeatured().size() == 2,
                "Applying lazy append keeps the target membership");
        lazy.appendCollections(false).applyTo(targetPicks);
        check(targetPicks.getFavorites().isEmpty() && targetPicks.getFeatured().isEmpty(),
                "Applying lazy replacement clears the target membership");

        var lazyFirst = lazy.build();
        var lazySecond = lazy.build();
        var nestedTarget = lazyFirst.getFavorites().iterator().next();
        check(nestedTarget.getCode().equals("nested") && nestedTarget != lazySecond.getFavorites().iterator().next()
                && lazyFirst.getFeatured().isEmpty()
                && defaultMembers(field(lazyFirst, "favorites")) == defaultMembers(field(lazySecond, "favorites"))
                && ReferenceDefaultBindings.mutableCalls == before + 4,
                "Only builds materialize lazy membership and preserve the captured defaults");
        check(lazy.applyFrom(lazyFirst).build().getFavorites().iterator().next() == nestedTarget
                && ReferenceDefaultBindings.mutableCalls == before + 4, "Import reuses direct identity and the builder fallback");

        before = ReferenceDefaultBindings.immutableCalls;
        var frozen = FrozenPicks.newBuilder().build();
        var tokens = defaultMembers(field(frozen, "featured"));
        check(ReferenceDefaultBindings.immutableCalls == before + 2 && tokens.get(0) == frozen.getFeatured().get(0)
                && tokens.get(0) != tokens.get(1) && tokens.get(0).equals(tokens.get(1))
                && frozen.asReadOnly() == frozen, "Immutable ordered and unordered caches use identity and expose frozen values");
        expect(UnsupportedOperationException.class, frozen.getFavorites()::clear);

        before = ReferenceDefaultBindings.immutableCalls;
        var nested = SelectionToken.newBuilder().withKey("nested").withLabel("explicit");
        var staged = FrozenPicks.newBuilder().withFavorites(nested).withFeatured(List.<SelectionToken.Builder>of());
        var firstBuilt = staged.build();
        var secondBuilt = staged.build();
        var member = firstBuilt.getFavorites().iterator().next();
        var fallback = defaultMembers(field(firstBuilt, "favorites"));
        check(ReferenceDefaultBindings.immutableCalls == before + 2 && firstBuilt.getFeatured().isEmpty()
                && secondBuilt.getFeatured().isEmpty() && member.getKey().equals("nested")
                && member != secondBuilt.getFavorites().iterator().next(), "Nested builders replace visible defaults only");
        check(fallback == defaultMembers(field(secondBuilt, "favorites")) && fallback.stream().noneMatch(value -> value == member)
                && defaultMembers(field(firstBuilt, "featured")) == defaultMembers(field(secondBuilt, "featured")),
                "Nested-built values retain the original fallback identity for repeated builds");

        ReferenceDefaultBindings.duplicateSeed = true;
        try {
            expect(IllegalArgumentException.class, MutablePicks::newInstance);
            expect(IllegalArgumentException.class, MutablePicks::newBuilder);
        } finally {
            ReferenceDefaultBindings.duplicateSeed = false;
        }
    }

    private static void verifyFrozenBuilderImports() {
        int before = ReferenceDefaultBindings.immutableCalls;
        var builder = FrozenPicks.newBuilder();
        var seed = builder.build();
        var favorite = seed.getFavorites().iterator().next();
        var featured = seed.getFeatured().get(0);
        var incoming = ReferenceDefaultBindings.token("incoming", "seed");
        check(favorite != incoming && favorite.equals(incoming), "Import includes a distinct equal token");
        var source = FrozenPicks.newBuilder().withFavorites(favorite, incoming).withFeatured(featured, incoming).build();

        var appended = builder.applyFrom(source).build();
        check(appended.getFavorites().size() == 3 && seed.getFavorites().stream().allMatch(appended.getFavorites()::contains)
                && appended.getFavorites().contains(incoming), "Append starts with cached default identities");
        check(appended.getFeatured().size() == 3 && appended.getFeatured().get(0) == featured
                && appended.getFeatured().get(1) == seed.getFeatured().get(1) && appended.getFeatured().get(2) == incoming,
                "Append retains default order, removes a repeated identity and preserves the equal-valued token");
        check(defaultMembers(field(seed, "favorites")) == defaultMembers(field(appended, "favorites"))
                && defaultMembers(field(seed, "featured")) == defaultMembers(field(appended, "featured")),
                "Visible membership changes keep the same cached fallback");

        var replaced = builder.appendCollections(false).applyFrom(source).build();
        check(replaced.getFavorites().size() == 2 && replaced.getFeatured().get(1) == incoming,
                "Replacement imports only the source membership");
        var nested = SelectionToken.newBuilder().withKey("lazy");
        var afterNested = builder.appendCollections(true).withFavorites(nested).withFeatured(nested).applyFrom(source).build();
        check(afterNested.getFavorites().size() == 2 && afterNested.getFeatured().size() == 2
                && afterNested.getFeatured().get(1) == incoming
                && defaultMembers(field(seed, "featured")) == defaultMembers(field(afterNested, "featured")),
                "Import supersedes pending nested builders without restoring visible defaults");
        check(ReferenceDefaultBindings.immutableCalls == before + 4 && seed.getFeatured().size() == 2,
                "The providers run only for the two builders and already built values stay untouched");
    }


    private static void verifyMutableReader() throws IOException {
        var absent = MAPPER.readValue("{}", MutablePicks.class);
        var empty = MAPPER.readValue("{\"picked\":[],\"featured\":[]}", MutablePicks.class);
        check(!absent.getFavorites().isEmpty() && absent.getFeatured().size() == 2 && empty.getFavorites().isEmpty()
                && empty.getFeatured().isEmpty() && defaultMembers(field(empty, "favorites")).size() == 1,
                "The Jackson compatibility fix distinguishes omitted and explicitly empty defaulted fields");
        propertyResetTargets(empty);

        var member = LegacyMember.newInstance();
        var group = LegacyGroup.newBuilder().withMembers(member).build();
        var normal = MAPPER.writeValueAsString(LegacyReciprocalDocument.newBuilder().withGroup(group).withMember(member).build());
        var contradictory = (ObjectNode) MAPPER.readTree(normal);
        ((ObjectNode) contradictory.path("member")).putArray("groups");
        try {
            MAPPER.readValue(contradictory.toString(), LegacyReciprocalDocument.class);
            throw new AssertionError("Expected contradictory reciprocal membership rejection");
        } catch (IOException expected) {
            check(expected.getMessage().contains("Contradictory reference memberships"), "Legacy reciprocal disagreement is still rejected");
        }
    }

    private static void propertyResetTargets(MutablePicks owner) {
        var field = field(owner, "favorites");
        var cached = defaultMembers(field).get(0);
        field.unset();
        check(owner.getFavorites().iterator().next() == cached, "Explicit clears leave the cached reset target intact");
    }

    private static void verifyExchange(Path directory, String author) throws IOException {
        for (var format : List.of(VMFDocumentFormat.JSON, VMFDocumentFormat.XML)) {
            var checkpoint = MAPPER.readValue(file(directory, author, "checkpoint.json").toFile(), ReferenceDefaultDocument.class);
            var alpha = checkpoint.getTargets().get(0);
            var beta = checkpoint.getTargets().get(1);
            var picks = checkpoint.getMutable();
            check(picks.getFavorites().size() == 1 && picks.getFavorites().contains(alpha)
                    && picks.getFeatured().get(0) == alpha && picks.getFeatured().get(1) == beta,
                    "Explicit live memberships resolve the actual contained objects");
            var reset = ReferenceDefaultConformance.<SelectionTarget>defaultMembers(field(picks, "favorites")).get(0);
            check(reset != alpha && reset != beta && reset.getCode().equals("default-favorite"), "Wire data leaves cached reset members process-local");
            reset.setCode("qualified-cache");
            var frozen = checkpoint.getFrozen();
            var first = frozen.getFeatured().get(1);
            var second = frozen.getFeatured().get(0);
            check(first.equals(second) && first != second && frozen.getFavorites().contains(first) && frozen.getFavorites().contains(second),
                    "Immutable aliases and distinct equal values survive the reference document");
            var suffix = "journal." + (format == VMFDocumentFormat.XML ? "xml" : "json");
            var journal = VMFJournalCodec.read(Files.readString(file(directory, author, suffix)), format);
            int calls = ReferenceDefaultBindings.mutableCalls;
            var replayed = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, journal);
            var replayMutable = replayed.getMutable();
            check(ReferenceDefaultBindings.mutableCalls == calls && replayMutable.getFavorites().contains(replayed.getTargets().get(1))
                    && replayMutable.getFeatured().get(0) == replayed.getTargets().get(1) && picks.getFavorites().contains(alpha),
                    "Replay copies the checkpoint and preserves ordered edits");
            var replayReset = ReferenceDefaultConformance.<SelectionTarget>defaultMembers(field(replayMutable, "favorites")).get(0);
            check(replayReset != reset && replayReset.getCode().equals("qualified-cache"), "Replay remaps the checkpoint-local reset target");
        }
    }

    private static Path file(Path base, String author, String suffix) { return base.resolve(author + "-reference-default-" + suffix); }

    private static Property field(VObject model, String name) { return model.vmf().reflect().propertyByName(name).orElseThrow(); }

    @SuppressWarnings("unchecked")
    private static <T extends VObject> List<T> defaultMembers(Property property) { return (List<T>) property.getDefault(); }

    static void expect(Class<? extends RuntimeException> type, Runnable action) {
        try { action.run(); }
        catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (type.isInstance(cause)) return;
            }
            throw failure;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
