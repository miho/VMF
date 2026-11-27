package eu.mihosoft.vmf.conformance.ordinarycontainment;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.ObservableObject;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import vjavax.observer.Subscription;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class SharedInverseConformance {
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "shared-inverse"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject model) {
            return model instanceof ForestLayout ? "forest" : ((BranchNode) model).getCode();
        }
        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) {
            throw new UnsupportedOperationException("This journal edits existing objects only.");
        }
    };

    private SharedInverseConformance() { }

    static void verify(boolean write, Path exchange) throws IOException {
        if (write) {
            verifyLive();
            verifyDirectDetach(false);
            verifyDirectDetach(true);
        }
        var journal = write ? record() : null;
        var detachJournal = write ? recordDetach() : null;
        for (var format : VMFDocumentFormat.values()) {
            String extension = format.name().toLowerCase(Locale.ROOT);
            for (boolean references : List.of(true, false)) {
                ObjectMapper io = OrdinaryContainmentConformance.mapper(format, references);
                String filename = (write ? "java-" : "csharp-") + "shared-inverse-" + (references ? "refs" : "values") + "." + extension;
                Path file = exchange.resolve(filename);
                if (write) Files.writeString(file, io.writeValueAsString(checkpoint()));
                checkCheckpoint(io.readValue(file.toFile(), ForestLayout.class));
            }
            Path file = exchange.resolve((write ? "java-" : "csharp-") + "shared-inverse-journal." + extension);
            if (write) Files.writeString(file, VMFJournalCodec.write(journal, format));
            var read = VMFJournalCodec.read(Files.readString(file), format);
            check(read.wireVersion() == 1, "shared inverse edits retain V1 journal records");
            checkMoved(JournalReplayer.replay(checkpoint(), BINDING, "checkpoint", read));

            Path detachFile = exchange.resolve((write ? "java-" : "csharp-") + "scalar-detach-journal." + extension);
            if (write) Files.writeString(detachFile, VMFJournalCodec.write(detachJournal, format));
            verifyDetachJournal(VMFJournalCodec.read(Files.readString(detachFile), format));
        }
        System.out.println("Java verified shared read-only inverses, snapshots and V1 journal replay.");
    }

    private static ForestLayout detachCheckpoint() {
        var model = checkpoint();
        var first = model.getRoots().get(0);
        first.setStaging(first.getLeft());
        return model;
    }

    private static void verifyDirectDetach(boolean anonymous) {
        var model = anonymous ? detachCheckpoint() : checkpoint();
        var owner = model.getRoots().get(0);
        var child = anonymous ? owner.getStaging() : owner.getLeft();
        String property = anonymous ? "staging" : "left";
        var view = owner.asReadOnly();
        var childView = child.asReadOnly();
        var direct = new ArrayList<PropertyChangeEvent>();
        PropertyChangeListener bean = direct::add;
        var local = new ArrayList<Change>();
        var named = new ArrayList<Change>();
        var recursive = new ArrayList<Change>();
        var inverse = new ArrayList<Change>();
        var readOnly = new ArrayList<Change>();
        var readOnlyAncestor = new ArrayList<Change>();
        var changes = model.vmf().changes();
        var viewChanges = view.vmf().changes();
        var subscriptions = List.of(owner.vmf().changes().addLocalListener(local::add),
                owner.vmf().changes().addPropertyListener(property, named::add),
                changes.addRecursiveListener(recursive::add),
                child.vmf().changes().addLocalListener(inverse::add),
                viewChanges.addRecursiveListener(readOnly::add),
                model.asReadOnly().vmf().changes().addRecursiveListener(readOnlyAncestor::add));
        ((ObservableObject) owner).addPropertyChangeListener(bean);
        changes.start();
        viewChanges.start();
        try {
            if (anonymous) owner.setStaging(null);
            else owner.setLeft(null);
        } finally {
            subscriptions.forEach(Subscription::unsubscribe);
            ((ObservableObject) owner).removePropertyChangeListener(bean);
            viewChanges.stop();
            changes.stop();
        }
        var routes = List.of(local, named, recursive, readOnly, changes.all(), viewChanges.all(),
                changes.transactions().get(0).changes(), viewChanges.transactions().get(0).changes());
        var counts = new ArrayList<Integer>();
        counts.add(direct.size());
        for (var route : routes) counts.add(route.size());
        check(counts.stream().allMatch(count -> count == 1) && readOnlyAncestor.isEmpty(),
                "Direct, local, filtered, recursive, view, and recorded detach counts on " + property + ": " + counts);
        var raw = direct.get(0);
        check(raw.getSource() == owner && raw.getPropertyName().equals(property) && raw.getOldValue() == child
                && raw.getNewValue() == null, "The real owning-property event carries the detached child");
        for (var route : List.of(local, named, recursive, changes.all())) checkRemoval(route.get(0), owner, child, property);
        for (var route : List.of(readOnly, viewChanges.all())) checkRemoval(route.get(0), view, childView, property);
        check(inverse.size() == 1, "The child still reports its one inverse removal");
        checkRemoval(inverse.get(0), child, owner, anonymous ? "__vmf_prop_unnamed_container" : "parent");
        check(owner.getLeft() == null && owner.getStaging() == null && child.getParent() == null,
                "The model has no remaining named or unnamed ownership in the cleared slot");
        var group = changes.transactions().get(0);
        check(group.isUndoable(), "The one recorded removal can be undone");
        group.undo();
        check(anonymous ? owner.getStaging() == child && child.getParent() == null
                        : owner.getLeft() == child && child.getParent() == owner,
                "Undo restores the original owning edge and child identity");
    }

    private static void checkRemoval(Change edit, VObject owner, VObject child, String property) {
        var change = edit.propertyChange().orElseThrow();
        check(edit.object() == owner && edit.propertyName().equals(property) && change.oldValue() == child
                        && change.newValue() == null,
                "A projected or mutable removal preserves its old child identity");
    }

    private static ChangeJournal recordDetach() {
        var model = detachCheckpoint();
        try (var recorder = JournalRecorder.start(model, BINDING, "checkpoint", 0)) {
            model.getRoots().get(0).setStaging(null);
            return recorder.finish();
        }
    }

    private static void verifyDetachJournal(ChangeJournal journal) {
        check(journal.wireVersion() == 1 && journal.transactions().size() == 1, "Detach uses a single V1 frame");
        var frame = journal.transactions().get(0);
        var operations = frame.operations();
        check(operations.size() == 1, "Detach records exactly one owning SET");
        var value = new ChangeJournal.Value(ChangeJournal.ValueKind.NULL, null);
        var operation = operations.get(0);
        check(operation.kind() == ChangeJournal.Kind.SET && operation.target().equals("first")
                        && operation.property().equals("staging") && operation.before().equals(
                            List.of(new ChangeJournal.Value(ChangeJournal.ValueKind.REFERENCE, "twig")))
                        && operation.after().equals(List.of(value)),
                "The V1 record describes the real source removal");
        checkDetached(JournalReplayer.replay(detachCheckpoint(), BINDING, "checkpoint", journal));

        var noop = new ChangeJournal.Operation(ChangeJournal.Kind.SET, "first", "staging", List.of(),
                List.of(value), List.of(value), List.of());
        var withLegacyNoop = new ChangeJournal(journal.model(), journal.modelVersion(), journal.root(),
                journal.checkpoint(), journal.baseSequence(), List.of(new ChangeJournal.Frame(frame.sequence(),
                List.of(operation, noop))));
        checkDetached(JournalReplayer.replay(detachCheckpoint(), BINDING, "checkpoint", withLegacyNoop));
    }

    private static void checkDetached(ForestLayout model) {
        var owner = model.getRoots().get(0);
        check(owner.getStaging() == null && owner.getLeft() == null && owner.getBranches().isEmpty(),
                "The restored graph retains the detached owning state");
    }

    private static ForestLayout checkpoint() {
        var twig = Twig.newBuilder().withCode("twig").build();
        var first = Bough.newBuilder().withCode("first").withLeft(twig).build();
        var second = Bough.newBuilder().withCode("second").build();
        return ForestLayout.newBuilder().withRoots(first, second).build();
    }

    private static void verifyLive() {
        var model = checkpoint();
        var first = model.getRoots().get(0);
        var second = model.getRoots().get(1);
        var twig = (Twig) first.getLeft();
        check(Arrays.stream(Twig.class.getMethods()).noneMatch(method -> method.getName().equals("setParent")),
                "dynamic inverse only has a getter");
        var events = new ArrayList<String>();
        var subscription = twig.vmf().changes().addListener(change -> events.add(change.propertyName() + ":"
                + (twig.getParent() == null ? "-" : twig.getParent().getCode())));
        first.setRight(twig);
        check(first.getLeft() == null && twig.getParent() == first, "moves between fields of the same owner");
        second.getBranches().add(twig);
        check(first.getRight() == null && twig.getParent() == second, "moves between different owners");
        first.setStaging(twig);
        check(second.getBranches().isEmpty() && twig.getParent() == null, "anonymous containment clears the named inverse");
        first.getBranches().add(twig);
        check(first.getStaging() == null && twig.getParent() == first, "named inverse restored");
        subscription.unsubscribe();
        check(events.equals(List.of("parent:-", "parent:first", "parent:-", "parent:second", "parent:-",
                "__vmf_prop_unnamed_container:-", "__vmf_prop_unnamed_container:-", "parent:first")), "inverse events: " + events);
        var view = model.asReadOnly().getRoots().get(0);
        check(view.getBranches().get(0).getParent() == view, "read-only view of current parent identity");
        var parent = twig.vmf().reflect().propertyByName("parent").orElseThrow();
        check(parent.get() == first, "reflected current parent identity");
        try {
            parent.set(second);
            throw new AssertionError("reflection wrote a read-only inverse");
        } catch (RuntimeException expected) {
            check(twig.getParent() == first, "failed reflected write preserves ownership");
        }
    }

    private static ChangeJournal record() {
        var model = checkpoint();
        var first = model.getRoots().get(0);
        var second = model.getRoots().get(1);
        var twig = first.getLeft();
        try (var recorder = JournalRecorder.start(model, BINDING, "checkpoint", 0)) {
            first.setRight(twig);
            second.getBranches().add(twig);
            checkMoved(model);
            return recorder.finish();
        }
    }

    private static void checkCheckpoint(ForestLayout model) {
        var first = model.getRoots().get(0);
        check(first.getLeft() instanceof Twig && first.getLeft().getParent() == first, "snapshot restores shared inverse");
    }

    private static void checkMoved(ForestLayout model) {
        var first = model.getRoots().get(0);
        var second = model.getRoots().get(1);
        check(first.getLeft() == null && first.getRight() == null && second.getBranches().size() == 1,
                "replay moves the child between declared owning slots");
        var twig = second.getBranches().get(0);
        check(twig instanceof Twig && "twig".equals(twig.getCode()) && twig.getParent() == second,
                "replay resolves the new shared inverse");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
