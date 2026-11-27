package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.collectionredeclarations.ArrayDeclarationConformance;
import eu.mihosoft.vmf.conformance.collectionredeclarations.CollectionBasis;
import eu.mihosoft.vmf.conformance.collectionredeclarations.CollectionCatalog;
import eu.mihosoft.vmf.conformance.collectionredeclarations.CollectionItem;
import eu.mihosoft.vmf.conformance.collectionredeclarations.CollectionVariant;
import eu.mihosoft.vmf.conformance.collectionredeclarations.FrozenBasis;
import eu.mihosoft.vmf.conformance.collectionredeclarations.FrozenToken;
import eu.mihosoft.vmf.conformance.collectionredeclarations.FrozenVariant;
import eu.mihosoft.vmf.conformance.collectionredeclarations.IntegralSeries;
import eu.mihosoft.vmf.conformance.collectionredeclarations.NumericSeries;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.Multiplicity;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.internal.PreparedMembershipConformance;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class CollectionRedeclarationConformance {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    private static final JournalBinding SCALAR_RELAY = new JournalBinding() {
        @Override public String modelId() { return "conformance.collection-relay"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject node) {
            if (node instanceof CollectionCatalog) return "root";
            if (node instanceof CollectionBasis) return "basis";
            return "child";
        }
        @Override public String type(VObject node) { return node.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new AssertionError("No new nodes"); }
    };

    private CollectionRedeclarationConformance() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        boolean write = args[0].equals("write");
        var directory = Path.of(args[1]);
        var membershipCapability = directory.resolve("java-prepared-membership.txt");
        if (write) Files.deleteIfExists(membershipCapability);
        boolean prepared = PreparedMembershipConformance.available();
        if (write) {
            Files.createDirectories(directory);
            var specimen = specimen();
            verifyLive(specimen);
            var file = directory.resolve("java-collection-redeclarations.json");
            MAPPER.writeValue(file.toFile(), specimen);
            verifyWire(file);
            System.out.println("Java verified exact mutable and immutable collection redeclarations and their reference snapshot.");
        } else {
            verifyWire(directory.resolve("csharp-collection-redeclarations.json"));
            System.out.println("Java restored reordered sets, containment and reference aliases in the C# collection redeclarations.");
        }
        ArrayDeclarationConformance.verify(write, directory);
        var relayProperties = write && !prepared ? List.of("numbers", "labels")
                : List.of("numbers", "labels", "counters", "codes");
        for (var property : relayProperties) {
            for (boolean ancestorFirst : List.of(false, true)) {
                verifyScalarRelay(directory, write, property, ancestorFirst);
            }
        }
        if (!write || prepared) {
            for (var property : List.of("counters", "codes")) {
                if (write) {
                    verifyDirectMembershipCapture(property, false);
                    verifyDirectMembershipCapture(property, true);
                }
                verifyReentrantMembership(directory, write, property);
            }
        }
        verifyMembershipNativeRelay("counters", false);
        verifyMembershipNativeRelay("counters", true);
        verifyMembershipNativeRelay("codes", false);
        verifyMembershipNativeRelay("codes", true);
        if (write) Files.writeString(membershipCapability, prepared ? "prepared" : "legacy");
        System.out.println("Java verified scalar collection relay timing and V1 replay in JSON, XML, and YAML.");
    }

    private static void verifyMembershipNativeRelay(String property, boolean ancestorFirst) {
        var root = scalarRelayCheckpoint();
        var basis = root.getExamples().get(0);
        var recording = root.vmf().changes();
        var visible = new ArrayList<String>();
        if (ancestorFirst) recording.start();
        var observer = basis.vmf().changes().addPropertyListener(property, edit -> {
            recording.all().forEach(change -> visible.add(change.propertyName()));
            basis.getOwned().get(0).setName("nested");
        });
        if (!ancestorFirst) recording.start();
        try {
            editCollection(property, basis);
            // The basis' own listener runs before the recording ancestor (portable contract E8); the nested edit
            // is recorded after its cause (H4).
            check(visible.equals(List.of()), "Native membership history visibility in the basis' own callback");
            var names = recording.all().stream().map(edit -> edit.propertyName()).collect(Collectors.toList());
            check(names.equals(List.of(property, "name")), "Native map and set edits use the same order as scalar edits");
        } finally {
            observer.unsubscribe();
            recording.stop();
        }
        var transaction = recording.transactions().get(0);
        check(transaction.isUndoable(), "The two independently edited slots support undo");
        transaction.undo();
        check(basis.getCounters().isEmpty() && basis.getCodes().isEmpty()
                && "initial".equals(basis.getOwned().get(0).getName()), "Native undo restores the map, set, and scalar");
    }

    private static void verifyScalarRelay(Path directory, boolean write, String property, boolean ancestorFirst)
            throws IOException {
        ChangeJournal journal = write ? recordScalarRelay(property, ancestorFirst) : null;
        String category = property.equals("counters") || property.equals("codes") ? "membership" : "list";
        String suffix = "-scalar-" + category + "-relay-" + property + "-" + (ancestorFirst ? "ancestor-first" : "local-first");
        for (var format : VMFDocumentFormat.values()) {
            var file = directory.resolve((write ? "java" : "csharp") + suffix + "." + format.name().toLowerCase(java.util.Locale.ROOT));
            if (write) Files.writeString(file, VMFJournalCodec.write(journal, format));
            var restored = VMFJournalCodec.read(Files.readString(file), format);
            check(restored.wireVersion() == 1 && restored.transactions().size() == 1, "Expected one V1 frame");
            var edits = restored.transactions().get(0).operations();
            check(edits.size() == 2 && edits.get(0).kind() == membershipKind(property)
                    && edits.get(0).property().equals(property) && edits.get(1).kind() == ChangeJournal.Kind.SET
                    && "name".equals(edits.get(1).property()), "V1 records the collection commit before its nested scalar");
            var checkpoint = scalarRelayCheckpoint();
            var replayed = JournalReplayer.replay(checkpoint, SCALAR_RELAY, "initial", restored);
            checkRelayState(property, checkpoint, false);
            checkRelayState(property, replayed, true);
        }
    }

    private static ChangeJournal recordScalarRelay(String property, boolean ancestorFirst) {
        var root = scalarRelayCheckpoint();
        var basis = root.getExamples().get(0);
        var recording = root.vmf().changes();
        var trace = new ArrayList<String>();
        var visible = new ArrayList<String>();
        var before = ancestorFirst ? JournalRecorder.start(root, SCALAR_RELAY, "initial", 0) : null;
        var early = ancestorFirst ? recording.addRecursiveListener(change -> trace.add("ancestor " + change.propertyName())) : null;
        var local = basis.vmf().changes().addPropertyListener(property, change -> {
            trace.add("local " + property);
            recording.all().forEach(edit -> visible.add(edit.propertyName()));
            basis.getOwned().get(0).setName("nested");
        });
        try (var recorder = ancestorFirst ? before : JournalRecorder.start(root, SCALAR_RELAY, "initial", 0)) {
            var late = ancestorFirst ? null : recording.addRecursiveListener(change -> trace.add("ancestor " + change.propertyName()));
            try {
                editCollection(property, basis);
                // The basis' own listener runs before the recording ancestor (portable contract E8), and the nested
                // edit is delivered at once (E9) but recorded after its cause (H4).
                check(visible.equals(List.of()), "Native collection visibility in the basis' own callback");
                check(trace.equals(List.of("local " + property, "ancestor name", "ancestor " + property)), "Collection callback ordering");
                var nativeNames = recording.all().stream().map(edit -> edit.propertyName()).collect(Collectors.toList());
                check(nativeNames.equals(List.of(property, "name")), "Native collection history order");
            } finally {
                local.unsubscribe();
                if (early != null) early.unsubscribe();
                if (late != null) late.unsubscribe();
            }
            var journal = recorder.finish();
            var transaction = recording.transactions().get(0);
            check(transaction.isUndoable(), "Both separately edited slots support undo");
            transaction.undo();
            checkRelayState(property, root, false);
            return journal;
        }
    }

    private static void verifyDirectMembershipCapture(String property, boolean lateStart) {
        var root = scalarRelayCheckpoint();
        var basis = root.getExamples().get(0);
        var checkpoint = new CollectionCatalog[] {root.clone()};
        var active = new JournalRecorder[1];
        Runnable callback = () -> {
            if (lateStart) {
                checkpoint[0] = root.clone();
                active[0] = JournalRecorder.start(root, SCALAR_RELAY, "initial", 0);
            }
            basis.getOwned().get(0).setName("nested");
        };
        var direct = property.equals("counters") ? basis.getCounters().addChangeListener(event -> callback.run())
                : basis.getCodes().addChangeListener(event -> callback.run());
        var recording = root.vmf().changes();
        var observer = recording.addRecursiveListener(change -> {});
        try {
            if (!lateStart) active[0] = JournalRecorder.start(root, SCALAR_RELAY, "initial", 0);
            editCollection(property, basis);
            var nativeNames = recording.all().stream().map(edit -> edit.propertyName()).collect(Collectors.toList());
            check(nativeNames.equals(lateStart ? List.of("name") : List.of("name", property)), "Direct callbacks preserve native relay order");
            var journal = active[0].finish();
            var wireNames = journal.transactions().get(0).operations().stream().map(ChangeJournal.Operation::property)
                    .collect(Collectors.toList());
            check(wireNames.equals(lateStart ? List.of("name") : List.of(property, "name")), "Direct callbacks preserve the V1 checkpoint boundary");
            checkRelayState(property, JournalReplayer.replay(checkpoint[0], SCALAR_RELAY, "initial", journal), true);
        } finally {
            direct.unsubscribe();
            observer.unsubscribe();
            if (active[0] != null) active[0].close();
        }
    }

    private static void verifyReentrantMembership(Path directory, boolean write, String property) throws IOException {
        var root = scalarRelayCheckpoint();
        var basis = root.getExamples().get(0);
        boolean map = property.equals("counters");
        ChangeJournal journal = null;
        if (write) {
            var callbacks = new int[1];
            Runnable callback = () -> {
                if (++callbacks[0] != 1) return;
                if (map) basis.getCounters().put("added", 3);
                else basis.getCodes().add(3L);
            };
            var direct = map ? basis.getCounters().addChangeListener(event -> callback.run())
                    : basis.getCodes().addChangeListener(event -> callback.run());
            try (var recorder = JournalRecorder.start(root, SCALAR_RELAY, "initial", 0)) {
                editCollection(property, basis);
                check(callbacks[0] == 2, "Both committed collection edits reach the direct observer");
                journal = recorder.finish();
            } finally {
                direct.unsubscribe();
            }
        }
        for (var format : VMFDocumentFormat.values()) {
            var extension = format.name().toLowerCase(java.util.Locale.ROOT);
            var file = directory.resolve((write ? "java" : "csharp") + "-reentrant-" + property + "." + extension);
            if (write) Files.writeString(file, VMFJournalCodec.write(journal, format));
            var recorded = VMFJournalCodec.read(Files.readString(file), format);
            check(recorded.wireVersion() == 1 && recorded.transactions().size() == 1, "Expected one reentrant V1 frame");
            var edits = recorded.transactions().get(0).operations();
            check(edits.size() == 2 && edits.stream().allMatch(edit -> edit.kind() == membershipKind(property)
                    && edit.property().equals(property)), "Expected both edits of the same collection");
            var outer = edits.get(0);
            var inner = edits.get(1);
            if (map) {
                var two = new ChangeJournal.Value(ChangeJournal.ValueKind.INT, "2");
                var three = new ChangeJournal.Value(ChangeJournal.ValueKind.INT, "3");
                check(outer.beforeEntries().isEmpty() && outer.afterEntries().equals(Map.of("added", two))
                        && inner.beforeEntries().equals(Map.of("added", two))
                        && inner.afterEntries().equals(Map.of("added", three)), "Map V1 preserves the two transitions of the key");
            } else {
                var two = new ChangeJournal.Value(ChangeJournal.ValueKind.LONG, "2");
                var three = new ChangeJournal.Value(ChangeJournal.ValueKind.LONG, "3");
                check(outer.before().isEmpty() && outer.after().equals(List.of(two))
                        && inner.before().isEmpty() && inner.after().equals(List.of(three)),
                        "Set V1 records the outer addition before the reentrant addition");
            }
            var checkpoint = scalarRelayCheckpoint();
            var original = checkpoint.getExamples().get(0);
            var result = JournalReplayer.replay(checkpoint, SCALAR_RELAY, "initial", recorded).getExamples().get(0);
            check(original.getCounters().isEmpty() && original.getCodes().isEmpty(), "Replay preserves its checkpoint");
            check(map ? result.getCounters().equals(Map.of("added", 3))
                    : result.getCodes().equals(java.util.Set.of(2L, 3L)), "Replay restores both reentrant edits");
        }
    }

    private static ChangeJournal.Kind membershipKind(String property) {
        switch (property) {
            case "counters": return ChangeJournal.Kind.MAP_EDIT;
            case "codes": return ChangeJournal.Kind.SET_EDIT;
            default: return ChangeJournal.Kind.LIST_ADD;
        }
    }

    private static void editCollection(String property, CollectionBasis basis) {
        switch (property) {
            case "numbers": basis.getNumbers().add(2); break;
            case "labels": basis.getLabels().add("added"); break;
            case "counters": basis.getCounters().put("added", 2); break;
            case "codes": basis.getCodes().add(2L); break;
            default: throw new AssertionError(property);
        }
    }

    private static CollectionCatalog scalarRelayCheckpoint() {
        var basis = CollectionBasis.newBuilder().withNumbers(1).withOwned(item("initial")).build();
        return CollectionCatalog.newBuilder().withExamples(basis).build();
    }

    private static void checkRelayState(String property, CollectionCatalog root, boolean edited) {
        var basis = root.getExamples().get(0);
        check(basis.getNumbers().equals(edited && "numbers".equals(property) ? List.of(1, 2) : List.of(1))
                && basis.getLabels().equals(edited && "labels".equals(property) ? List.of("added") : List.of())
                && basis.getCodes().equals(edited && "codes".equals(property) ? java.util.Set.of(2L) : java.util.Set.of())
                && basis.getCounters().equals(edited && "counters".equals(property) ? Map.of("added", 2) : Map.of())
                && (edited ? "nested" : "initial").equals(basis.getOwned().get(0).getName()), "Scalar relay graph state");
    }

    private static CollectionCatalog specimen() {
        var original = CollectionBasis.newBuilder().withNumbers(9).build();
        var alpha = item("alpha");
        var beta = item("beta");
        var variant = CollectionVariant.newBuilder().withNumbers(1, 2).withLabels("a", "b").withCodes(19L)
                .withCounters(counters()).withOwned(alpha, beta).withFeatured(beta, alpha)
                .withTrail(Arrays.asList(alpha, null, alpha, beta)).withFavorites(alpha, beta).build();
        var first = FrozenToken.newBuilder().withLabel("first").build();
        var second = FrozenToken.newBuilder().withLabel("second").build();
        var numbers = new ArrayList<>(List.of(7, 8));
        var frozen = FrozenVariant.newBuilder().withNumbers(numbers).withLabels("x", "y").withCodes(29L)
                .withCounters(counters()).withTrail(Arrays.asList(first, null, first, second))
                .withFeatured(second, first).withFavorites(first, second).build();
        numbers.clear();
        var marks = new LinkedHashMap<String, Long>();
        marks.put("limit", Long.MAX_VALUE);
        marks.put("unknown", null);
        var integral = IntegralSeries.newBuilder().withReadings(Long.MIN_VALUE, 0L).withMarks(marks).build();
        return CollectionCatalog.newBuilder().withExamples(original, variant).withFrozen(frozen).withNumeric(integral).build();
    }

    private static Map<String, Integer> counters() {
        var entries = new LinkedHashMap<String, Integer>();
        entries.put("known", 5);
        entries.put("empty", null);
        return entries;
    }

    private static CollectionItem item(String name) {
        return CollectionItem.newBuilder().withName(name).build();
    }

    private static void verifyLive(CollectionCatalog catalog) {
        var variant = (CollectionVariant) catalog.getExamples().get(1);
        CollectionBasis base = variant;
        check(base.getNumbers() == variant.getNumbers() && base.getCounters() == variant.getCounters()
                && base.getOwned() == variant.getOwned() && base.getFeatured() == variant.getFeatured()
                && base.getTrail() == variant.getTrail() && base.getFavorites() == variant.getFavorites(),
                "Base and redeclared getters address one live collection per field");
        var reflected = base.vmf().reflect().propertyByName("numbers").orElseThrow();
        check(reflected.getMultiplicity().equals(Multiplicity.of(1, 3, true, false))
                && base.vmf().reflect().properties().stream().filter(property -> property.getName().equals("numbers")).count() == 1,
                "The redeclaration retains one bounded reflected property");
        check(base.vmf().reflect().propertyByName("owned").orElseThrow().getMultiplicity()
                .equals(Multiplicity.of(0, 2, true, true)), "The redeclared containment remains ordered, unique and bounded");
        var view = base.asReadOnly();
        var edits = new ArrayList<String>();
        var listener = base.vmf().changes().addPropertyListener("numbers", change -> edits.add(change.propertyName()));
        try {
            base.getNumbers().add(3);
            check(view.getNumbers().equals(List.of(1, 2, 3)), "A read-only base view follows concrete edits");
            reject(IllegalArgumentException.class, () -> variant.getNumbers().add(4));
            base.getNumbers().remove(2);
            check(edits.equals(List.of("numbers", "numbers")), "Each successful mutation emits exactly one event");
            check(!base.getLabels().add("a"), "Ordered scalar set retains uniqueness");
            reject(IllegalArgumentException.class, () -> base.getLabels().add("c"));
            check(base.getCodes().add(20L), "Scalar set remains live");
            reject(IllegalArgumentException.class, () -> base.getCodes().add(21L));
            base.getCodes().remove(20L);
            reject(IllegalArgumentException.class, () -> base.getCounters().put("unlisted", 18));
            check(base.getCounters().containsKey("empty") && base.getCounters().get("empty") == null,
                    "Rejected map mutation preserves present-null");
            check(view.findOwnedByName("alpha").isPresent(), "Read-only adapter exposes the inherited keyed lookup");
            var extra = item("gamma");
            check(variant.getOwned().add(extra) && extra.getBasis() == variant
                    && variant.vmf().content().validate().getIssues().stream()
                    .anyMatch(issue -> issue.getPath().equals("/owned") && issue.getCode().equals("vmf.multiplicity")),
                    "Owned-member overflow is detected by model validation after the ordinary attachment");
            base.getOwned().remove(extra);
            check(extra.getBasis() == null, "Removing through the base getter detaches the effective inverse");
        } finally {
            listener.unsubscribe();
        }

        FrozenBasis frozen = catalog.getFrozen();
        check(frozen.getNumbers().equals(List.of(7, 8)) && frozen.asReadOnly() == frozen,
                "The immutable redeclaration copied its caller-owned collection");
        reject(UnsupportedOperationException.class, () -> frozen.getNumbers().add(9));
        reject(UnsupportedOperationException.class, () -> frozen.getCodes().add(30L));
        reject(UnsupportedOperationException.class, () -> frozen.getCounters().put("known", 0));
        reject(UnsupportedOperationException.class, () -> frozen.getFavorites().clear());

        NumericSeries<Long> series = catalog.getNumeric();
        check(series.getReadings() == catalog.getNumeric().getReadings()
                && series.getMarks() == catalog.getNumeric().getMarks(), "Closed generic redeclarations retain the typed base storage");
    }

    private static void verifyWire(Path file) throws IOException {
        var wire = MAPPER.readTree(file.toFile());
        var examples = wire.path("examples");
        check(examples.path(0).path("base_numbers").path(0).asInt() == 9
                && !examples.path(0).has("numbers_v2") && examples.path(1).path("numbers_v2").size() == 2
                && !examples.path(1).has("base_numbers") && examples.path(1).path("owned_v2").size() == 2
                && !examples.path(1).has("owned"), "Effective aliases distinguish base and redeclared fields");
        check(wire.path("frozen").path("numbers").size() == 2 && !wire.path("frozen").has("previous_numbers"),
                "An unannotated immutable redeclaration uses its logical name");

        var restored = MAPPER.readValue(file.toFile(), CollectionCatalog.class);
        var variant = (CollectionVariant) restored.getExamples().get(1);
        var alpha = variant.getOwned().get(0);
        var beta = variant.getOwned().get(1);
        check(restored.getExamples().get(0).getClass() != variant.getClass()
                && variant.getNumbers().equals(List.of(1, 2)) && variant.getLabels().equals(List.of("a", "b"))
                && variant.getCodes().contains(19L) && variant.getCounters().containsKey("empty")
                && variant.getCounters().get("empty") == null, "Native scalar collection shapes and subtype round-trip");
        check(variant.findOwnedByName("alpha").orElseThrow() == alpha && alpha.getBasis() == variant && beta.getBasis() == variant,
                "Keyed owning collection preserves inherited containment");
        check(variant.getFeatured().get(0) == beta && variant.getFeatured().get(1) == alpha
                && alpha.getSelectedBy() == variant && beta.getSelectedBy() == variant
                && variant.getFavorites().contains(beta) && variant.getTrail().get(0) == alpha
                && variant.getTrail().get(1) == null && variant.getTrail().get(2) == alpha && variant.getTrail().get(3) == beta,
                "Redeclared reference sequence and sets preserve inverse, positions, nulls and aliases");
        var frozen = (FrozenVariant) restored.getFrozen();
        var first = frozen.getTrail().get(0);
        var second = frozen.getTrail().get(3);
        check(frozen.getNumbers().equals(List.of(7, 8)) && frozen.getLabels().equals(List.of("x", "y"))
                && frozen.getCodes().contains(29L) && frozen.getCounters().equals(counters())
                && frozen.getFeatured().get(0) == second && frozen.getFeatured().get(1) == first
                && frozen.getTrail().get(1) == null && frozen.getTrail().get(2) == first
                && frozen.getFavorites().contains(first) && frozen.getFavorites().contains(second),
                "Immutable values retain every typed collection and reference alias");
        var numeric = restored.getNumeric();
        check(numeric.getReadings().equals(List.of(Long.MIN_VALUE, 0L)) && numeric.getMarks().get("limit") == Long.MAX_VALUE
                && numeric.getMarks().containsKey("unknown") && numeric.getMarks().get("unknown") == null,
                "Exact closed generic redeclarations retain their native numeric width and nullable map values");
    }

    private static void reject(Class<? extends RuntimeException> kind, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            if (kind.isInstance(failure)) return;
            throw failure;
        }
        throw new AssertionError("Expected " + kind.getSimpleName());
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
