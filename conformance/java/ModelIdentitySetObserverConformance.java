package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.ordinarycontainment.ReadingRoom;
import eu.mihosoft.vmf.conformance.ordinarycontainment.Shelf;
import eu.mihosoft.vmf.conformance.ordinarycontainment.Volume;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.ArchiveRoot;
import eu.mihosoft.vmf.conformance.values.KeyedInventory;
import eu.mihosoft.vmf.conformance.values.KeyedItem;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.Changes;
import eu.mihosoft.vmf.runtime.core.RefSet;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import vjavax.observer.Subscription;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

final class ModelIdentitySetObserverConformance {
    private static final JournalBinding JOURNAL = new JournalBinding() {
        @Override public String modelId() { return "conformance.set-anchors"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject node) {
            if (node instanceof Shelf) return ((Shelf) node).getName();
            if (node instanceof Volume) return ((Volume) node).getName();
            if (node instanceof ArchiveNode) return ((ArchiveNode) node).getName();
            if (node instanceof ReadingRoom || node instanceof ArchiveRoot) return "root";
            throw new AssertionError("Unexpected journal node " + node.getClass());
        }
        @Override public String type(VObject node) { return node.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new AssertionError("Unexpected creation"); }
    };

    private ModelIdentitySetObserverConformance() {}

    public static void main(String[] args) throws IOException {
        var expected = new ObjectMapper().readValue(Path.of(args[0]).toFile(),
                new TypeReference<Map<String, List<String>>>() {});
        for (boolean owning : new boolean[]{false, true}) {
            verifyMaterialization(create(owning), expected, false);
            verifyMaterialization(create(owning), expected, true);
            verifyDormantRelay(create(owning), expected, false);
            verifyDormantRelay(create(owning), expected, true);
        }
        verifyInverseActivation(expected);
        verifyPlainKeyedProjection(expected);
        verifyOwningJournal(expected);
        verifyUnopposedJournal(expected);
        System.out.println("Java verified 12 shared identity-set and keyed list relay cases, including active V1 replay.");
    }

    private static void verifyMaterialization(Fixture f, Map<String, List<String>> expected, boolean observeEarly) {
        var trace = new ArrayList<String>();
        f.watch(false, false, () -> trace.add("M0"));
        f.readOnly.get();
        f.collectionView.get();
        if (observeEarly) f.watch(true, false, () -> trace.add("R0"));
        f.watch(false, true, () -> trace.add("M1"));
        if (!observeEarly) f.watch(true, false, () -> trace.add("R0"));
        f.watch(true, true, () -> trace.add("R1"));
        f.edit.run();
        expect(expected, observeEarly ? "early-projected-modeled" : "public-getter-alone", trace);
    }

    private static void verifyDormantRelay(Fixture f, Map<String, List<String>> expected, boolean reentrant) {
        var trace = new ArrayList<String>();
        f.watch(false, false, () -> {
            trace.add("M0");
            if (reentrant) f.watch(true, false, () -> trace.add("Rnew"));
        });
        f.watch(true, false, () -> { throw new AssertionError("Disposed listener was reactivated"); }).unsubscribe();
        f.watch(false, true, () -> trace.add("M1"));
        if (!reentrant) f.watch(true, false, () -> trace.add("Rnew"));
        f.edit.run();
        expect(expected, "reactivated-modeled", trace);
    }

    private static void verifyInverseActivation(Map<String, List<String>> expected) {
        var shelf = Shelf.newInstance();
        var child = Volume.newInstance();
        var fixture = owning(shelf, child);
        var trace = new ArrayList<String>();
        fixture.watch(true, false, () -> { throw new AssertionError("Disposed listener was reactivated"); }).unsubscribe();
        child.vmf().changes().addListener(change -> {
            if ("shelf".equals(change.propertyName())) {
                trace.add("inverse");
                fixture.watch(true, false, () -> trace.add("projected"));
            }
        }, false);
        fixture.edit.run();
        expect(expected, "inverse-activation", trace);
    }

    private static void verifyPlainKeyedProjection(Map<String, List<String>> expected) {
        var trace = new ArrayList<String>();
        var root = KeyedInventory.newInstance();
        var item = KeyedItem.newBuilder().withSku("A").build();
        var raw = root.getProducts();
        raw.addChangeListener(edit -> trace.add("M0"));
        var ownerView = root.asReadOnly();
        raw.addChangeListener(edit -> trace.add("M1"));
        var view = ownerView.getProducts();
        raw.addChangeListener(edit -> trace.add("M2"));
        view.addChangeListener(edit -> {
            if (edit.source() != view || edit.added().elements().get(0) != item.asReadOnly()
                    || edit.added().indices()[0] != 0 || ownerView.findProductsBySku("A").orElseThrow() != item.asReadOnly()) {
                throw new AssertionError("Plain keyed callback lost projection identity, index or key");
            }
            trace.add("P0");
        });
        var changes = ownerView.vmf().changes();
        changes.addListener(edit -> trace.add("R0"), false);
        root.vmf().changes().addListener(edit -> trace.add("C0"), false);
        changes.addListener(edit -> trace.add("R1"), true);
        raw.add(item);
        expect(expected, "plain-keyed-list-getter", trace);
    }

    private static void verifyOwningJournal(Map<String, List<String>> expected) throws IOException {
        var source = Shelf.newBuilder().withName("source").build();
        var destination = Shelf.newBuilder().withName("dest").build();
        var child = Volume.newBuilder().withName("volume").build();
        source.getVolumes().add(child);
        var root = ReadingRoom.newBuilder().withShelves(source, destination).withFeatured(child).build();
        var checkpoint = root.clone();
        var trace = new ArrayList<String>();
        var projected = destination.asReadOnly().vmf().changes().addListener(change -> trace.add("projected"), false);
        var recursive = root.vmf().changes().addRecursiveListener(change -> {
            if ("volumes".equals(change.propertyName())) trace.add(change.object() == source ? "source" : "dest");
        });
        try (var recorder = JournalRecorder.start(root, JOURNAL, "base", 0)) {
            destination.getVolumes().add(child);
            expect(expected, "owning-journal", trace);
            var journal = recorder.finish();
            for (var format : VMFDocumentFormat.values()) {
                var restored = roundTrip(journal, format, List.of("source.volumes", "dest.volumes"));
                var replayed = JournalReplayer.replay(checkpoint, JOURNAL, "base", restored);
                var owner = replayed.findShelvesByName("dest").orElseThrow();
                if (replayed == checkpoint || owner.getVolumes().size() != 1
                        || owner.getVolumes().iterator().next() != replayed.getFeatured()
                        || replayed.getFeatured().getShelf() != owner
                        || !checkpoint.findShelvesByName("dest").orElseThrow().getVolumes().isEmpty()
                        || checkpoint.getFeatured().getShelf() != checkpoint.findShelvesByName("source").orElseThrow()) {
                    throw new AssertionError("Replay must preserve the owning and featured aliases and leave the checkpoint unchanged");
                }
            }
        } finally {
            projected.unsubscribe();
            recursive.unsubscribe();
        }
    }

    private static void verifyUnopposedJournal(Map<String, List<String>> expected) throws IOException {
        var peer = ArchiveNode.newBuilder().withName("peer").build();
        var head = ArchiveNode.newBuilder().withName("head").withChildren(peer).build();
        var root = ArchiveRoot.newBuilder().withHead(head).build();
        var checkpoint = root.clone();
        var trace = new ArrayList<String>();
        var projected = head.asReadOnly().vmf().changes().addListener(change -> trace.add("projected"), false);
        var recursive = root.vmf().changes().addRecursiveListener(change -> {
            if ("peers".equals(change.propertyName())) trace.add("root");
        });
        try (var recorder = JournalRecorder.start(root, JOURNAL, "base", 0)) {
            head.getPeers().add(peer);
            expect(expected, "unopposed-journal", trace);
            var journal = recorder.finish();
            for (var format : VMFDocumentFormat.values()) {
                var restored = roundTrip(journal, format, List.of("head.peers"));
                var replayed = JournalReplayer.replay(checkpoint, JOURNAL, "base", restored);
                var rebuiltHead = replayed.getHead();
                if (replayed == checkpoint || rebuiltHead.getPeers().size() != 1
                        || rebuiltHead.getPeers().iterator().next() != rebuiltHead.getChildren().get(0)
                        || !checkpoint.getHead().getPeers().isEmpty()) {
                    throw new AssertionError("Replay must preserve the peer alias and leave the checkpoint unchanged");
                }
            }
        } finally {
            projected.unsubscribe();
            recursive.unsubscribe();
        }
    }

    private static ChangeJournal roundTrip(ChangeJournal journal, VMFDocumentFormat format, List<String> targets)
            throws IOException {
        var restored = VMFJournalCodec.read(VMFJournalCodec.write(journal, format), format);
        if (restored.wireVersion() != 1 || restored.transactions().size() != 1) {
            throw new AssertionError("Expected one V1 transaction");
        }
        var operations = restored.transactions().get(0).operations();
        var actual = new ArrayList<String>();
        for (var edit : operations) {
            if (edit.kind() != ChangeJournal.Kind.REF_SET_EDIT || !edit.indices().isEmpty()) {
                throw new AssertionError("Expected an unindexed V1 set edit");
            }
            actual.add(edit.target() + "." + edit.property());
        }
        if (!targets.equals(actual)) throw new AssertionError("Expected V1 operations " + targets + ", got " + actual);
        return restored;
    }

    private static Fixture create(boolean owning) {
        if (owning) return owning(Shelf.newInstance(), Volume.newInstance());
        var root = ArchiveNode.newInstance();
        var peer = ArchiveNode.newInstance();
        return new Fixture(root, root::asReadOnly, "peers", root.getPeers(), () -> root.asReadOnly().getPeers(),
                peer, peer::asReadOnly, () -> root.getPeers().add(peer));
    }

    private static Fixture owning(Shelf shelf, Volume child) {
        return new Fixture(shelf, shelf::asReadOnly, "volumes", shelf.getVolumes(), () -> shelf.asReadOnly().getVolumes(),
                child, child::asReadOnly, () -> {
                    shelf.getVolumes().add(child);
                    if (child.getShelf() != shelf) throw new AssertionError("Missing reciprocal owner");
                });
    }

    private static void expect(Map<String, List<String>> expected, String key, List<String> actual) {
        if (!actual.equals(expected.get(key))) {
            throw new AssertionError(key + ": expected " + expected.get(key) + ", got " + actual);
        }
    }

    private static final class Fixture {
        final VObject owner;
        final Supplier<VObject> readOnly;
        final String property;
        final RefSet<?> members;
        final Supplier<RefSet<?>> collectionView;
        final VObject target;
        final Supplier<VObject> targetView;
        final Runnable edit;
        private Changes projectedChanges;

        Fixture(VObject owner, Supplier<VObject> readOnly, String property, RefSet<?> members,
                Supplier<RefSet<?>> collectionView, VObject target, Supplier<VObject> targetView, Runnable edit) {
            this.owner = owner;
            this.readOnly = readOnly;
            this.property = property;
            this.members = members;
            this.collectionView = collectionView;
            this.target = target;
            this.targetView = targetView;
            this.edit = edit;
        }

        Subscription watch(boolean projected, boolean recursive, Runnable callback) {
            if (projected && projectedChanges == null) projectedChanges = readOnly.get().vmf().changes();
            Changes stream = projected ? projectedChanges : owner.vmf().changes();
            return stream.addListener(change -> {
                if (!property.equals(change.propertyName())) return;
                var delta = change.referenceSetChange().orElseThrow();
                if (change.object() != (projected ? readOnly.get() : owner)
                        || delta.source() != (projected ? collectionView.get() : members)
                        || delta.added().size() != 1 || delta.added().get(0) != (projected ? targetView.get() : target)
                        || !delta.removed().isEmpty() || !delta.indices().isEmpty()) {
                    throw new AssertionError("Expected unordered membership and canonical event identities");
                }
                callback.run();
            }, recursive);
        }
    }
}
