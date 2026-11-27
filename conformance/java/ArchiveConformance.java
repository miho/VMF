package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.ArchiveOwnedLeaf;
import eu.mihosoft.vmf.conformance.values.ArchiveOwner;
import eu.mihosoft.vmf.conformance.values.ArchiveRoot;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class ArchiveConformance {
    private static final ObjectMapper REFERENCES = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private ArchiveConformance() {}

    public static void main(String[] args) throws IOException {
        Path interchange = Path.of(args[1]);
        if (args[0].equals("read")) {
            verify(REFERENCES.readValue(interchange.resolve("archive-csharp.json").toFile(), ArchiveRoot.class));
            System.out.println("Java restored C# recursive containment, forward aliases, and sequence slots.");
            return;
        }

        Path fixture = Path.of(args[2]);
        var root = REFERENCES.readValue(fixture.toFile(), ArchiveRoot.class);
        verify(root);
        checkLiveEdits(REFERENCES.readValue(fixture.toFile(), ArchiveRoot.class));
        checkInheritedOpposite();
        checkBuilderTransfer();
        checkBuilderJournal();
        String json = Files.readString(fixture);
        rejectSnapshot(json.replace("\"featured\": [\"left\", \"right\"]", "\"featured\": [\"left\", \"left\"]"));
        rejectSnapshot(json.replace("\"featured\": [\"left\", \"right\"]", "\"featured\": [\"left\", null]"));
        Files.createDirectories(interchange);
        REFERENCES.writeValue(interchange.resolve("archive-java.json").toFile(), root);
        System.out.println("Java verified recursive graph snapshots and live edits.");
    }

    private static void verify(ArchiveRoot root) {
        var head = root.getHead();
        var left = head.getChildren().get(0);
        var right = head.getInset();
        check("main".equals(head.getName()) && "left".equals(left.getName()) && "right".equals(right.getName()),
                "Nested payloads restore");
        check(head.getChildren().size() == 1 && left.getParent() == head && left.getInsetOf() == null
                && right.getInsetOf() == head && right.getParent() == null, "Links point to the correct owning property");
        var trail = head.getTrail();
        check(trail.size() == 5 && trail.get(0) == right && trail.get(1) == null && trail.get(2) == left
                && trail.get(3) == right && trail.get(4) == head, "Sequence preserves order, null, repeats, and self-reference");
        var featured = head.getFeatured();
        check(featured.size() == 2 && featured.get(0) == left && featured.get(1) == right,
                "Ordered identity set preserves order and aliases");
        check(root.getReview().size() == 3 && root.getReview().get(0) == left && root.getReview().get(1) == null
                && root.getReview().get(2) == right, "Root sequence keeps nullable slots");
        check(root.vmf().content().validate().isValid(), "Restored graph is valid");
    }

    private static void checkLiveEdits(ArchiveRoot root) {
        var head = root.getHead();
        var left = head.getChildren().get(0);
        var right = head.getInset();
        var view = head.asReadOnly();
        check(view.getChildren().get(0) == view.getFeatured().get(0) && view.getInset() == view.getTrail().get(0)
                && view.getTrail().get(1) == null && view == view.getTrail().get(4), "Live read-only identity");
        reject(() -> right.getChildren().add(head), "adding an ancestor");
        reject(() -> left.setInset(head), "setting an ancestor");
        reject(() -> head.setInset(head), "containing itself");
        checkLiveGraphUnchanged(root, head, left, right);

        check(!head.getFeatured().add(left), "Duplicate membership is a no-op");
        reject(() -> head.getFeatured().add(0, right), "duplicate indexed set insertion");
        head.getFeatured().move(1, 0);
        check(view.getFeatured().get(0) == right.asReadOnly(), "Read-only set sees a move");

        left.setInsetOf(head);
        check(head.getInset() == left && left.getInsetOf() == head && left.getParent() == null
                && right.getInsetOf() == null && head.getChildren().isEmpty(), "Reparent and replace from the inverse");
        left.setParent(head);
        check(head.getInset() == null && left.getInsetOf() == null && left.getParent() == head
                && head.getChildren().get(0) == left, "Reparent from a single slot to a collection");
        left.setParent(null);
        check(left.getParent() == null && head.getChildren().isEmpty(), "Clearing an inverse detaches the target");
        root.setHead(null);
        check(root.vmf().content().validate().getIssues().stream().anyMatch(issue ->
                "vmf.multiplicity".equals(issue.getCode()) && "/head".equals(issue.getPath())), "Required containment validation");
    }

    private static void checkBuilderTransfer() {
        var sourceOwner = named("sourceOwner");
        var source = named("source");
        var child = named("child");
        var inset = named("inset");
        var alias = named("alias");
        sourceOwner.getChildren().add(source);
        source.getChildren().add(child);
        source.setInset(inset);
        source.setAnchor(alias);
        source.getTrail().add(alias);

        var staged = ArchiveNode.newBuilder().applyFrom(source);
        check(source.getParent() == sourceOwner && child.getParent() == source
                && inset.getInsetOf() == source, "import only stages");
        var built = staged.build();
        check(built.getParent() == sourceOwner && sourceOwner.getChildren().get(1) == built,
                "build attaches via imported inverse");
        check(built.getChildren().get(0) == child && child.getParent() == built && source.getChildren().isEmpty(),
                "build transfers owning set members");
        check(built.getInset() == inset && inset.getInsetOf() == built && source.getInset() == null,
                "build transfers the singular owning slot");
        check(built.getAnchor() == alias && built.getTrail().get(0) == alias, "aliases stay shared");

        var targetOwner = named("targetOwner");
        var target = named("target");
        var targetInset = named("targetInset");
        var obsolete1 = named("obsolete1");
        var obsolete2 = named("obsolete2");
        var keep1 = named("keep1");
        var keep2 = named("keep2");
        var appended = named("appended");
        targetOwner.getChildren().add(target);
        target.getChildren().addAll(List.of(obsolete1, keep1, obsolete2, keep2));
        target.setInset(targetInset);
        var changes = new ArrayList<String>();
        var sub = target.vmf().changes().addPropertyListener("children", change -> {
            var edit = change.referenceSetChange().orElseThrow();
            changes.add(edit.indices() + ": +" + edit.added().size() + " -" + edit.removed().size());
        });
        try {
            ArchiveNode.newBuilder().applyFrom(built).appendCollections(false)
                    .withChildren(List.of(keep2, appended, keep1)).applyTo(target);
        } finally {
            sub.unsubscribe();
        }
        check(target.getChildren().get(0) == keep2 && target.getChildren().get(1) == appended
                && target.getChildren().get(2) == keep1, "apply replaces order and retains member identity");
        check(keep1.getParent() == target && keep2.getParent() == target && appended.getParent() == target
                && obsolete1.getParent() == null && obsolete2.getParent() == null, "target inverses match membership");
        check(target.getInset() == targetInset && target.getParent() == targetOwner,
                "apply leaves singular ownership and inverse ownership alone");
        check(target.getAnchor() == alias && target.getTrail().get(0) == alias, "apply uses native reference slots");
        check(changes.equals(List.of("[0, 2]: +0 -2", "[0, 1]: +2 -2", "[1]: +1 -0")),
                "native ordered changes " + changes);
    }

    private static void checkBuilderJournal() throws IOException {
        var target = named("target");
        var first = named("first");
        var second = named("second");
        target.getChildren().addAll(List.of(named("a"), first, named("b"), second));
        String checkpoint = REFERENCES.writeValueAsString(target);
        var binding = new JournalBinding() {
            @Override public String modelId() { return "conformance.ordered-builder"; }
            @Override public int modelVersion() { return 1; }
            @Override public String id(VObject node) { return ((ArchiveNode) node).getName(); }
            @Override public String type(VObject node) { return "node"; }
            @Override public VObject create(String type, String id) { return named(id); }
        };
        try (var recorder = JournalRecorder.start(target, binding, "base", 0)) {
            ArchiveNode.newBuilder().withName("target").appendCollections(false)
                    .withChildren(List.of(second, named("added"), first)).applyTo(target);
            var journal = recorder.finish();
            var operations = journal.transactions().get(0).operations();
            check(operations.size() == 3 && operations.get(0).kind() == ChangeJournal.Kind.LIST_REMOVE
                    && operations.get(1).kind() == ChangeJournal.Kind.LIST_SET
                    && operations.get(2).kind() == ChangeJournal.Kind.LIST_ADD, "ordered owning builder V1 operations");
            for (var format : VMFDocumentFormat.values()) {
                var fresh = REFERENCES.readValue(checkpoint, ArchiveNode.class);
                var wire = VMFJournalCodec.read(VMFJournalCodec.write(journal, format), format);
                var replayed = JournalReplayer.replay(fresh, binding, "base", wire);
                check(replayed.getChildren().size() == 3 && replayed.getChildren().get(0).getName().equals("second")
                        && replayed.getChildren().get(1).getName().equals("added")
                        && replayed.getChildren().get(2).getName().equals("first")
                        && replayed.getChildren().stream().allMatch(child -> child.getParent() == replayed),
                        "V1 replays the builder's ordered owning slots and their inverse links");
            }
        }
    }

    private static ArchiveNode named(String name) {
        return ArchiveNode.newBuilder().withName(name).build();
    }

    private static void checkInheritedOpposite() {
        var owner = ArchiveOwner.newInstance();
        var leaf = ArchiveOwnedLeaf.newInstance();
        leaf.setOwner(owner);
        check(owner.getNodes().get(0) == leaf && leaf.getOwner() == owner, "Exact inherited opposite");
        check(owner.vmf().content().validate().getIssues().stream().anyMatch(issue ->
                "vmf.multiplicity".equals(issue.getCode()) && "/nodes/0/label".equals(issue.getPath())),
                "Inherited containment invokes actual subtype validation");
        leaf.setLabel("ready");
        check(owner.vmf().content().validate().isValid(), "Subtype validation clears when the label is set");
        leaf.setOwner(null);
        check(owner.getNodes().isEmpty() && leaf.getOwner() == null, "Inherited inverse detaches");
    }

    private static void checkLiveGraphUnchanged(ArchiveRoot root, ArchiveNode head, ArchiveNode left, ArchiveNode right) {
        check(root.getHead() == head && head.getInset() == right && head.getChildren().size() == 1
                && head.getChildren().get(0) == left && left.getInset() == null
                && right.getChildren().isEmpty(), "Rejected edits must preserve every owning slot");
        verify(root);
    }

    private static void rejectSnapshot(String json) throws IOException {
        try {
            REFERENCES.readValue(json, ArchiveRoot.class);
        } catch (JsonProcessingException expected) {
            return;
        }
        throw new IllegalStateException("Accepted an invalid ordered identity set.");
    }

    private static void reject(Runnable edit, String operation) {
        try {
            edit.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new IllegalStateException("Accepted " + operation);
    }

    private static void check(boolean condition, String problem) {
        if (!condition) throw new IllegalStateException(problem);
    }
}
