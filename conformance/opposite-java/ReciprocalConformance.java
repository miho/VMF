package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.mihosoft.vmf.conformance.opposites.Endpoint;
import eu.mihosoft.vmf.conformance.opposites.Partner;
import eu.mihosoft.vmf.conformance.opposites.PartnerGraph;
import eu.mihosoft.vmf.conformance.opposites.ReciprocalGraph;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Frame;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Kind;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Operation;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Value;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.ValueKind;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class ReciprocalConformance {
    private static final String MODEL = "conformance.reciprocal";
    private static final String CHECKPOINT = "opposite-checkpoint-10";
    private static final String ROOT = "graph";
    private static final long BASE_SEQUENCE = 10L;
    private static final ObjectMapper REFERENCES = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return MODEL; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) { return object instanceof Endpoint ? ((Endpoint) object).getId() : ROOT; }
        @Override public String type(VObject object) { return object instanceof Endpoint ? "endpoint" : ROOT; }
        @Override public VObject create(String type, String id) { throw new AssertionError("No nodes are created by these edits"); }
    };

    private ReciprocalConformance() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        Path exchange = Path.of(args[1]);
        if (args[0].equals("write")) {
            Files.createDirectories(exchange);
            verifyTransferCallbacks();
            verifyMixedMembership();
            write(exchange);
            verify(exchange, "java");
            System.out.println("Java verified reciprocal reference edits, snapshots, and membership journal replay.");
        } else {
            verify(exchange, "csharp");
            System.out.println("Java restored the C# reciprocal snapshot and replayed the membership journal.");
        }
        verifyClassNamedReference(exchange, args[0].equals("write"));
        OrderedReferenceBuilderConformance.exchange(exchange, args[0].equals("write"));
        verifyReentrantSourceJournals(exchange, args[0].equals("write"));
        verifyPrimaryListJournals(exchange, args[0].equals("write"));
        ReciprocalPropertyHistoryConformance.exchange(exchange, args[0].equals("write"));
        verifyReciprocalCheckpointJournals(exchange, args[0].equals("write"));
    }

    private static void verifyReciprocalCheckpointJournals(Path exchange, boolean write) throws IOException {
        var expected = new ChangeJournal(MODEL, 1, ROOT, CHECKPOINT, BASE_SEQUENCE,
                List.of(new Frame(BASE_SEQUENCE + 1, List.of(
                        edit(Kind.LIST_ADD, "a", "groups", List.of(1), List.of(), List.of(new Value(ValueKind.NULL, null)))))));
        for (boolean reverse : new boolean[]{false, true}) {
            if (write) verifyReciprocalCheckpointCopy(reverse);
            for (String scope : List.of("direct", "inverse")) {
                var journal = write ? recordAfterReciprocalCommit(scope, reverse) : null;
                for (var format : VMFDocumentFormat.values()) {
                    var name = (write ? "java-" : "csharp-") + "reciprocal-checkpoint-" + scope
                            + (reverse ? "-reverse" : "") + "-journal." + format.name().toLowerCase(Locale.ROOT);
                    var path = exchange.resolve(name);
                    if (write) Files.writeString(path, VMFJournalCodec.write(journal, format));
                    var recorded = VMFJournalCodec.read(Files.readString(path), format);
                    check(recorded.wireVersion() == 1 && REFERENCES.readTree(VMFJournalCodec.write(recorded))
                                    .equals(REFERENCES.readTree(VMFJournalCodec.write(expected))),
                            "Only the edit made after opening the recorder belongs to the journal");
                    var checkpoint = reciprocalCheckpoint(reverse);
                    node(checkpoint, "a").getGroups().add(node(checkpoint, "b"));
                    var replay = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, recorded);
                    checkReciprocalSlots(replay, 2);
                    checkReciprocalSlots(checkpoint, 1);
                }
            }
        }
    }

    private static void verifyReciprocalCheckpointCopy(boolean reverse) {
        var graph = reciprocalCheckpoint(reverse);
        var b = node(graph, "b");
        node(graph, "a").getGroups().addAll(Arrays.asList(b, null, b));
        var copy = graph.clone();
        check(node(copy, "a") != node(graph, "a") && node(copy, "b") != b,
                "Copy must allocate distinct endpoints");
        checkReciprocalSlots(copy, 3);
        checkReciprocalSlots(graph, 3);
    }

    private static ChangeJournal recordAfterReciprocalCommit(String scope, boolean reverse) {
        var graph = reciprocalCheckpoint(reverse);
        var a = node(graph, "a");
        var b = node(graph, "b");
        var recorder = new JournalRecorder[1];
        var recursive = graph.vmf().changes().addRecursiveListener(change -> {});
        Runnable open = () -> {
            checkReciprocalSlots(graph, 1);
            recorder[0] = JournalRecorder.start(graph, BINDING, CHECKPOINT, BASE_SEQUENCE);
            a.getGroups().add(null);
        };
        var observer = scope.equals("inverse")
                ? b.vmf().changes().addPropertyListener("members", change -> open.run())
                : a.getGroups().addChangeListener(change -> {
                    if (change.added().elements().stream().anyMatch(value -> value == b)) open.run();
                });
        try {
            a.getGroups().add(b);
            check(recorder[0] != null, "Expected to open the recorder during the reciprocal edit");
            checkReciprocalSlots(graph, 2);
            return recorder[0].finish();
        } finally {
            if (recorder[0] != null) recorder[0].close();
            observer.unsubscribe();
            recursive.unsubscribe();
        }
    }

    private static ReciprocalGraph reciprocalCheckpoint(boolean reverse) {
        var a = endpoint("a");
        var b = endpoint("b");
        return ReciprocalGraph.newBuilder().withNodes(reverse ? List.of(b, a) : List.of(a, b)).build();
    }

    private static Endpoint node(ReciprocalGraph graph, String id) {
        return graph.getNodes().stream().filter(value -> id.equals(value.getId())).findFirst().orElseThrow();
    }

    private static void checkReciprocalSlots(ReciprocalGraph graph, int count) {
        var a = node(graph, "a");
        var b = node(graph, "b");
        var list = a.getGroups();
        check(list.size() == count && list.get(0) == b
                        && (count < 2 || list.get(1) == null) && (count < 3 || list.get(2) == b),
                "Ordinary reciprocal slots retain their exact positions and identities");
        check(b.getMembers().size() == 1 && b.getMembers().iterator().next() == a,
                "Inverse identity set contains exactly the copied or replayed owner");
    }

    private static void verifyPrimaryListJournals(Path exchange, boolean write) throws IOException {
        var expected = new ChangeJournal(MODEL, 1, ROOT, CHECKPOINT, BASE_SEQUENCE,
                List.of(new Frame(BASE_SEQUENCE + 1, List.of(
                        edit(Kind.LIST_ADD, "a", "groups", List.of(0), List.of(), List.of(reference("b"))),
                        edit(Kind.LIST_ADD, "a", "groups", List.of(1), List.of(), List.of(new Value(ValueKind.NULL, null)))))));
        for (String scope : List.of("direct", "inverse")) {
            var journal = write ? recordPrimaryListEdit(scope) : null;
            for (var format : VMFDocumentFormat.values()) {
                var name = (write ? "java-" : "csharp-") + "reciprocal-primary-list-" + scope + "-journal."
                        + format.name().toLowerCase(Locale.ROOT);
                var path = exchange.resolve(name);
                if (write) Files.writeString(path, VMFJournalCodec.write(journal, format));
                var recorded = VMFJournalCodec.read(Files.readString(path), format);
                check(recorded.wireVersion() == 1 && REFERENCES.readTree(VMFJournalCodec.write(recorded))
                                .equals(REFERENCES.readTree(VMFJournalCodec.write(expected))),
                        "The primary slot is written before either callback's dependent slot");
                var checkpoint = primaryListCheckpoint();
                var replay = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, recorded);
                var a = replay.getNodes().get(0);
                var b = replay.getNodes().get(1);
                check(a.getGroups().equals(Arrays.asList(b, null)) && b.getMembers().contains(a)
                                && checkpoint.getNodes().get(0).getGroups().isEmpty(),
                        "Fresh primary replay preserves the two slots and recreates the inverse");
            }
        }
    }

    private static ReciprocalGraph primaryListCheckpoint() {
        return ReciprocalGraph.newBuilder().withNodes(endpoint("a"), endpoint("b")).build();
    }

    private static ChangeJournal recordPrimaryListEdit(String scope) {
        var graph = primaryListCheckpoint();
        var a = graph.getNodes().get(0);
        var b = graph.getNodes().get(1);
        var groups = a.getGroups();
        var listener = scope.equals("inverse")
                ? b.vmf().changes().addPropertyListener("members", change -> groups.add(null))
                : groups.addChangeListener(edit -> {
                    if (edit.added().elements().stream().anyMatch(item -> item == b)) groups.add(null);
                });
        try (var recorder = JournalRecorder.start(graph, BINDING, CHECKPOINT, BASE_SEQUENCE)) {
            groups.add(b);
            var history = graph.vmf().changes().all();
            check(history.size() == 2 && history.get(0).listChange().orElseThrow().added().elements().get(0) == null
                            && history.get(1).listChange().orElseThrow().added().elements().get(0) == b,
                    "Native history keeps the nested slot first, and the mirror set contributes no record");
            return recorder.finish();
        } finally {
            listener.unsubscribe();
        }
    }

    private static void verifyReentrantSourceJournals(Path exchange, boolean write) throws IOException {
        for (String trigger : List.of("scalar", "list")) {
            boolean scalar = trigger.equals("scalar");
            for (String scope : List.of("local", "recursive", "direct")) {
                var journal = write ? recordReentrantSource(scalar, scope) : null;
                for (var format : VMFDocumentFormat.values()) {
                    var name = (write ? "java-" : "csharp-") + "reciprocal-source-" + trigger + "-" + scope + "-journal."
                            + format.name().toLowerCase(Locale.ROOT);
                    var path = exchange.resolve(name);
                    if (write) Files.writeString(path, VMFJournalCodec.write(journal, format));
                    var recorded = VMFJournalCodec.read(Files.readString(path), format);
                    var expected = List.of(
                            edit(Kind.LIST_REMOVE, "a", "reports", List.of(0), List.of(reference("b")), List.of()),
                            edit(Kind.LIST_SET, "a", "reports", List.of(0), List.of(reference("d")), List.of(reference("e"))),
                            scalar ? edit(Kind.SET, "b", "supervisor", List.of(),
                                    List.of(new Value(ValueKind.NULL, null)), List.of(reference("c")))
                                    : edit(Kind.LIST_ADD, "c", "reports", List.of(0), List.of(), List.of(reference("b"))));
                    var expectedJournal = new ChangeJournal(MODEL, 1, ROOT, CHECKPOINT, BASE_SEQUENCE,
                            List.of(new Frame(BASE_SEQUENCE + 1, expected)));
                    check(recorded.wireVersion() == 1 && REFERENCES.readTree(VMFJournalCodec.write(recorded))
                                    .equals(REFERENCES.readTree(VMFJournalCodec.write(expectedJournal))),
                            "V1 source removal precedes its callback and keeps the reassignment's existing primary endpoint");
                    var checkpoint = reentrantSourceCheckpoint();
                    var replayed = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, recorded);
                    verifyReentrantSourceState(replayed);
                    check(checkpoint.getNodes().get(0).getReports().equals(List.of(
                                    checkpoint.getNodes().get(1), checkpoint.getNodes().get(3))),
                            "replay leaves the original checkpoint graph independent");
                }
            }
        }
    }

    private static ReciprocalGraph reentrantSourceCheckpoint() {
        var a = endpoint("a");
        var b = endpoint("b");
        var d = endpoint("d");
        var graph = ReciprocalGraph.newBuilder().withNodes(a, b, endpoint("c"), d, endpoint("e")).build();
        a.getReports().addAll(List.of(b, d));
        return graph;
    }

    private static ChangeJournal recordReentrantSource(boolean scalar, String scope) {
        var graph = reentrantSourceCheckpoint();
        var nodes = graph.getNodes();
        var a = nodes.get(0);
        var b = nodes.get(1);
        var c = nodes.get(2);
        var d = nodes.get(3);
        var e = nodes.get(4);
        var trace = new ArrayList<String>();
        boolean[] entered = { false };
        Runnable replaceShifted = () -> {
            if (entered[0]) return;
            entered[0] = true;
            check(a.getReports().size() == 1 && a.getReports().get(0) == d
                            && b.getSupervisor() == (scalar ? null : c),
                    "source observer sees shifted positions and the current inverse phase");
            trace.add("source callback");
            a.getReports().set(0, e);
            trace.add("nested returned");
        };
        var origin = scope.equals("direct") ? a.getReports().addChangeListener(event -> replaceShifted.run())
                : scope.equals("local") ? a.vmf().changes().addPropertyListener("reports", change -> replaceShifted.run())
                : graph.vmf().changes().addRecursiveListener(change -> {
                    if (change.object() == a && change.propertyName().equals("reports")) replaceShifted.run();
                });
        var inverse = b.vmf().changes().addPropertyListener("supervisor", change -> trace.add(
                change.propertyChange().orElseThrow().newValue() == null ? "b clear" : "b assign"));
        var destination = c.vmf().changes().addPropertyListener("reports", change -> trace.add("destination"));
        try (var recorder = JournalRecorder.start(graph, BINDING, CHECKPOINT, BASE_SEQUENCE)) {
            if (scalar) b.setSupervisor(c);
            else c.getReports().add(b);
            check(trace.equals(scalar
                            ? List.of("b clear", "source callback", "nested returned", "b assign", "destination")
                            : List.of("source callback", "nested returned", "b assign", "destination")),
                    "native source, inverse, and destination delivery keep their respective phases");
            var history = graph.vmf().changes().all();
            check(history.size() == 3 && history.get(0).listChange().orElseThrow().added().elements().get(0) == e
                            && history.get(1).listChange().orElseThrow().removed().elements().get(0) == b
                            && history.get(2).object() == (scalar ? b : c),
                    "native history keeps both reentrant source edits and the original destination history shape");
            verifyReentrantSourceState(graph);
            return recorder.finish();
        } finally {
            origin.unsubscribe();
            inverse.unsubscribe();
            destination.unsubscribe();
        }
    }

    private static void verifyReentrantSourceState(ReciprocalGraph graph) {
        var nodes = graph.getNodes();
        var a = nodes.get(0);
        var b = nodes.get(1);
        var c = nodes.get(2);
        var d = nodes.get(3);
        var e = nodes.get(4);
        check(a.getReports().equals(List.of(e)) && e.getSupervisor() == a && d.getSupervisor() == null
                        && c.getReports().equals(List.of(b)) && b.getSupervisor() == c,
                "the shifted report and reassignment retain their correct reciprocal endpoints");
    }

    private static void verifyClassNamedReference(Path directory, boolean write) throws IOException {
        Path file = directory.resolve(write ? "java-partners.json" : "csharp-partners.json");
        if (write) {
            var one = Partner.newBuilder().withName("one").build();
            var two = Partner.newBuilder().withName("two").build();
            var three = Partner.newBuilder().withName("three").build();
            var graph = PartnerGraph.newBuilder().withPartners(one, two, three).build();
            one.setPartner(two);
            check(two.getPartner() == one, "class-named inverse is symmetric");
            three.setPartner(two);
            check(two.getPartner() == three && one.getPartner() == null, "class-named inverse is displaced");
            one.setPartner(one);
            Files.writeString(file, REFERENCES.writeValueAsString(graph));
        }
        var graph = REFERENCES.readValue(file.toFile(), PartnerGraph.class);
        var one = graph.getPartners().get(0);
        var two = graph.getPartners().get(1);
        var three = graph.getPartners().get(2);
        check(one.getPartner() == one && two.getPartner() == three && three.getPartner() == two,
                "class-named aliases cross the original wire field");
        var wire = REFERENCES.readTree(file.toFile()).get("partners").get(0);
        check(wire.has("partner") && !wire.has("partnerReference"), "retains the Java model field name");
    }

    private static ReciprocalGraph checkpoint() {
        var a = endpoint("a");
        var b = endpoint("b");
        var c = endpoint("c");
        var d = endpoint("d");
        var graph = ReciprocalGraph.newBuilder().withNodes(a, b, c, d).build();
        a.setPeer(b);
        c.setPeer(d);
        a.getReports().addAll(List.of(b, d));
        a.getLinks().add(d);
        return graph;
    }

    private static void verifyTransferCallbacks() {
        var graph = checkpoint();
        var a = graph.getNodes().get(0);
        var b = graph.getNodes().get(1);
        var c = graph.getNodes().get(2);
        var d = graph.getNodes().get(3);
        var other = endpoint("other");
        var events = new ArrayList<String>();
        var first = new IllegalStateException("displaced owner");
        var second = new IllegalStateException("initiating owner");
        a.vmf().changes().addPropertyListener("peer", change -> {
            check(a.getPeer() == null && b.getPeer() == a && c.getPeer() == d, "Displaced primary observes the old reciprocal state");
            events.add("displaced");
            throw first;
        });
        d.vmf().changes().addPropertyListener("peer", change -> {
            check(d.getPeer() == null && c.getPeer() == b && b.getPeer() == a, "Cleared inverse observes new initiating value");
            events.add("old inverse");
        });
        c.vmf().changes().addPropertyListener("peer", change -> {
            check(c.getPeer() == b && b.getPeer() == a, "Initiating observer runs before final inverse");
            rejects(IllegalArgumentException.class, () -> other.setPeer(b));
            rejects(IllegalArgumentException.class, () -> b.setPeer(other));
            rejects(IllegalArgumentException.class, () -> c.setPeer(other));
            events.add("source");
            throw second;
        });
        b.vmf().changes().addPropertyListener("peer", change -> {
            check(b.getPeer() == c && c.getPeer() == b, "Final inverse observer sees the completed pair");
            events.add("new inverse");
        });
        try {
            c.setPeer(b);
            throw new AssertionError("Missing observer failure");
        } catch (IllegalStateException thrown) {
            check(thrown == first && Arrays.equals(thrown.getSuppressed(), new Throwable[]{second}),
                    "First observer failure propagates with later errors suppressed");
        }
        check(events.equals(List.of("displaced", "old inverse", "source", "new inverse"))
                && other.getPeer() == null && a.getPeer() == null && d.getPeer() == null && c.getPeer() == b && b.getPeer() == c,
                "Throwing and reentrant observers leave consistent pairs");
        other.setPeer(other);
        check(other.getPeer() == other, "Self-opposite self-reference occupies one logical endpoint");
        other.setPeer(null);
        check(other.getPeer() == null, "Self-opposite self-reference can be cleared");

        var fresh = checkpoint();
        var rec = fresh.vmf().changes();
        rec.start();
        rec.startTransaction();
        fresh.getNodes().get(2).setPeer(fresh.getNodes().get(1));
        rec.publishTransaction();
        rec.stop();
        check(rec.all().size() == 2 && rec.all().get(0).object() == fresh.getNodes().get(0)
                && rec.all().get(1).object() == fresh.getNodes().get(2), "One-to-one takeover records both primary endpoints and no inverses");
        rec.transactions().get(0).undo();
        verifyBase(fresh);
        rec.all().forEach(change -> change.apply(change.object()));
        check(fresh.getNodes().get(2).getPeer() == fresh.getNodes().get(1), "Native replay restores the takeover");
    }

    private static void verifyMixedMembership() {
        var a = endpoint("one");
        var c = endpoint("two");
        a.getGroups().addAll(Arrays.asList(c, null, c));
        check(c.getMembers().size() == 1 && c.getMembers().contains(a), "Repeated ordinary occurrences share one inverse set member");
        a.getGroups().remove(0);
        check(c.getMembers().contains(a), "Removing one occurrence preserves the inverse membership");
        a.getGroups().add(0, c);
        var events = new ArrayList<String>();
        var list = a.getGroups().addChangeListener(edit -> {
            check(a.getGroups().equals(Arrays.asList((Endpoint) null)), "All duplicate occurrences have already been removed");
            events.add(Arrays.toString(edit.removed().indices()));
        });
        var set = c.vmf().changes().addPropertyListener("members", edit -> events.add("set"));
        try {
            check(c.getMembers().remove(a) && events.equals(List.of("[0, 2]", "set")),
                    "A unique inverse emits one indexed removal for all occurrences before the primary set callback");
        } finally {
            list.unsubscribe();
            set.unsubscribe();
        }
        check(c.getMembers().add(a) && a.getGroups().get(1) == c && !c.getMembers().add(a),
                "Adding the unique end appends one ordinary occurrence and suppresses duplicate membership");
    }

    private static void write(Path exchange) throws IOException {
        var graph = checkpoint();
        REFERENCES.writeValue(exchange.resolve("java-checkpoint.json").toFile(), graph);
        var a = graph.getNodes().get(0);
        var b = graph.getNodes().get(1);
        var c = graph.getNodes().get(2);
        var d = graph.getNodes().get(3);
        var events = new ArrayList<String>();
        b.vmf().changes().addPropertyListener("supervisor", edit -> {
            var value = edit.propertyChange().orElseThrow();
            check(value.oldValue() == a, "The observer retains the old logical scalar endpoint");
            events.add(value.newValue() == null ? "b inverse clear" : "b assign");
        });
        d.vmf().changes().addPropertyListener("supervisor", edit -> {
            check(d.getSupervisor() == c, "List-driven move updates the scalar before observers run");
            events.add("d inverse");
        });
        a.vmf().changes().addPropertyListener("reports", edit -> events.add("a remove"));
        c.vmf().changes().addPropertyListener("reports", edit -> events.add("c add"));
        try (var recorder = JournalRecorder.start(graph, BINDING, CHECKPOINT, BASE_SEQUENCE)) {
            c.setPeer(b);
            b.setSupervisor(c);
            a.getLinks().add(c);
            check(events.equals(List.of("b inverse clear", "a remove", "b assign", "c add")), "Scalar-driven reassignment order");
            check(recorder.publishFrame().orElseThrow().sequence() == 11, "First frame");
            events.clear();
            d.getPanels().remove(a);
            c.getReports().add(d);
            check(events.equals(List.of("a remove", "d inverse", "c add")), "List-driven reassignment order");
            Files.writeString(exchange.resolve("java-journal.json"), VMFJournalCodec.write(recorder.finish()));
        }
        verifyFinal(graph);

        var mixed = checkpoint();
        var source = mixed.getNodes().get(0);
        var first = mixed.getNodes().get(2);
        var second = mixed.getNodes().get(3);
        source.getGroups().addAll(Arrays.asList(first, null, first, second));
        REFERENCES.writeValue(exchange.resolve("java-snapshot.json").toFile(), mixed);
    }

    private static void verify(Path exchange, String author) throws IOException {
        var snapshot = exchange.resolve(author + "-snapshot.json").toFile();
        verifySnapshot(REFERENCES.readValue(snapshot, ReciprocalGraph.class));
        var omittingInverses = REFERENCES.readTree(snapshot);
        ((ObjectNode) omittingInverses.path("nodes").path(1)).remove("peer");
        ((ObjectNode) omittingInverses.path("nodes").path(2)).remove("members");
        verifySnapshot(REFERENCES.treeToValue(omittingInverses, ReciprocalGraph.class));

        var checkpoint = REFERENCES.readValue(exchange.resolve(author + "-checkpoint.json").toFile(), ReciprocalGraph.class);
        verifyBase(checkpoint);
        check(checkpoint.getNodes().stream().allMatch(node -> node.getGroups().isEmpty() && node.getMembers().isEmpty()),
                "Journal checkpoint has no mixed list/set memberships");
        String wire = Files.readString(exchange.resolve(author + "-journal.json"));
        var wireTree = REFERENCES.readTree(wire);
        var journal = VMFJournalCodec.read(wire);
        check(journal.wireVersion() == 1 && wireTree.equals(REFERENCES.readTree(VMFJournalCodec.write(expected()))),
                "Journal operations must keep exact endpoint IDs, true positional removals, and normalized old values: " + wire);
        var replayed = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, journal);
        verifyFinal(replayed);
        check(replayed != checkpoint && replayed.getNodes().get(0) != checkpoint.getNodes().get(0),
                "Replay creates independent instances with local reciprocal aliases");
        var stale = wireTree.deepCopy();
        ((ObjectNode) stale.path("transactions").path(0).path("operations").path(0).path("before").path(0)).put("text", "d");
        var staleJournal = VMFJournalCodec.read(stale.toString());
        var rejection = rejects(IllegalArgumentException.class,
                () -> JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, staleJournal));
        check(rejection.getMessage().contains("Old value differs for peer"), "The valid but stale peer ID fails its old-value check");
        verifyBase(checkpoint);
    }

    private static ChangeJournal expected() {
        var none = new Value(ValueKind.NULL, null);
        var a = reference("a");
        var b = reference("b");
        var c = reference("c");
        var d = reference("d");
        return new ChangeJournal(MODEL, 1, ROOT, CHECKPOINT, BASE_SEQUENCE, List.of(
                new Frame(11, List.of(
                        edit(Kind.SET, "a", "peer", List.of(), List.of(b), List.of(none)),
                        edit(Kind.SET, "c", "peer", List.of(), List.of(d), List.of(b)),
                        edit(Kind.LIST_REMOVE, "a", "reports", List.of(0), List.of(b), List.of()),
                        edit(Kind.SET, "b", "supervisor", List.of(), List.of(none), List.of(c)),
                        edit(Kind.LIST_ADD, "a", "links", List.of(1), List.of(), List.of(c)))),
                new Frame(12, List.of(
                        edit(Kind.REF_SET_EDIT, "d", "panels", List.of(), List.of(a), List.of()),
                        edit(Kind.LIST_REMOVE, "a", "reports", List.of(0), List.of(d), List.of()),
                        edit(Kind.LIST_ADD, "c", "reports", List.of(1), List.of(), List.of(d))))));
    }

    private static Value reference(String id) {
        return new Value(ValueKind.REFERENCE, id);
    }

    private static Operation edit(Kind kind, String target, String property, List<Integer> indices,
                                  List<Value> before, List<Value> after) {
        return new Operation(kind, target, property, indices, before, after, List.of());
    }

    private static void verifySnapshot(ReciprocalGraph graph) {
        verifyBase(graph);
        var a = graph.getNodes().get(0);
        var c = graph.getNodes().get(2);
        var d = graph.getNodes().get(3);
        check(a.getGroups().equals(Arrays.asList(c, null, c, d)) && c.getMembers().contains(a)
                && d.getMembers().contains(a), "Reciprocal snapshot preserves repeated identities, a null slot, and unique inverse memberships");
    }

    private static void verifyBase(ReciprocalGraph graph) {
        var nodes = graph.getNodes();
        check(nodes.size() == 4 && nodes.get(0).getId().equals("a") && nodes.get(1).getId().equals("b")
                && nodes.get(2).getId().equals("c") && nodes.get(3).getId().equals("d"), "Checkpoint containment and IDs");
        var a = nodes.get(0);
        var b = nodes.get(1);
        var c = nodes.get(2);
        var d = nodes.get(3);
        check(a.getPeer() == b && b.getPeer() == a && c.getPeer() == d && d.getPeer() == c,
                "Checkpoint reciprocal scalar pairs");
        check(a.getReports().equals(List.of(b, d)) && b.getSupervisor() == a && d.getSupervisor() == a
                && c.getReports().isEmpty() && a.getLinks().size() == 1 && a.getLinks().get(0) == d && d.getPanels().contains(a),
                "Checkpoint reports and ordered/set edges");
    }

    private static void verifyFinal(ReciprocalGraph graph) {
        var a = graph.getNodes().get(0);
        var b = graph.getNodes().get(1);
        var c = graph.getNodes().get(2);
        var d = graph.getNodes().get(3);
        check(a.getPeer() == null && d.getPeer() == null && b.getPeer() == c && c.getPeer() == b,
                "Occupied one-to-one transfer restores only the requested pair");
        check(a.getReports().isEmpty() && c.getReports().equals(List.of(b, d))
                && b.getSupervisor() == c && d.getSupervisor() == c, "Both routes move the indexed one-to-many edges");
        check(a.getLinks().size() == 1 && a.getLinks().get(0) == c && c.getPanels().contains(a) && d.getPanels().isEmpty(),
                "Ordered and unordered opposite edits restore both memberships");
    }

    private static Endpoint endpoint(String id) {
        return Endpoint.newBuilder().withId(id).build();
    }

    private static <T extends RuntimeException> T rejects(Class<T> expected, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException failure) {
            if (expected.isInstance(failure)) return expected.cast(failure);
            throw failure;
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
