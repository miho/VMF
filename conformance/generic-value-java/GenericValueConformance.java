package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.genericvalues.BoundedHistory;
import eu.mihosoft.vmf.conformance.genericvalues.CollectionReview;
import eu.mihosoft.vmf.conformance.genericvalues.Counts;
import eu.mihosoft.vmf.conformance.genericvalues.Forwarded;
import eu.mihosoft.vmf.conformance.genericvalues.ReadOnlySlot;
import eu.mihosoft.vmf.conformance.genericvalues.Review;
import eu.mihosoft.vmf.conformance.genericvalues.Slot;
import eu.mihosoft.vmf.conformance.genericvalues.Tag;
import eu.mihosoft.vmf.conformance.genericvalues.Texts;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class GenericValueConformance {
    private static final String CHECKPOINT = "base";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "conformance.generic-values"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) {
            if (object instanceof Tag) return ((Tag) object).getKey();
            if (object instanceof CollectionReview) return ((CollectionReview) object).getId();
            return type(object);
        }
        @Override public String type(VObject object) {
            if (object instanceof Tag) return "tag";
            if (object instanceof CollectionReview) return "collection-review";
            if (object instanceof Review) return "review";
            throw new IllegalArgumentException("Unknown journal model type");
        }
        @Override public String propertyKey(VObject object, String name) { return name; }
        @Override public String propertyName(VObject object, String key) { return key; }
        @Override public VObject create(String type, String id) { throw new IllegalArgumentException("No creations expected"); }
    };

    private GenericValueConformance() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        Path directory = Path.of(args[1]);
        boolean write = args[0].equals("write");
        String author = write ? "java" : "csharp";
        var checkpointFile = directory.resolve(author + "-checkpoint.json");
        var journalFile = directory.resolve(author + "-journal.json");
        if (write) {
            verifyScalarSlots();
            verifyStructuralNullReplay();
            var first = Tag.newBuilder().withKey("a").withText("same").build();
            var second = Tag.newBuilder().withKey("b").withText("same").build();
            var source = Review.newBuilder().withCurrent(first).withHistory(Arrays.asList(first, null, second, first)).build();
            check(first != second && first.equals(second), "distinct equal immutable values");
            verifyImmutableSlot(source, first, second);
            Files.createDirectories(directory);
            MAPPER.writeValue(checkpointFile.toFile(), source);
            var journal = JournalRecorder.recordFrame(source, BINDING, CHECKPOINT, 0, model -> {
                model.setCurrent(second);
                model.getHistory().set(0, second);
            });
            Files.writeString(journalFile, VMFJournalCodec.write(journal));
        }

        var checkpoint = MAPPER.readValue(checkpointFile.toFile(), Review.class);
        var journal = VMFJournalCodec.read(Files.readString(journalFile));
        check(checkpoint.getCurrent() == checkpoint.getHistory().get(0) && checkpoint.getCurrent() == checkpoint.getHistory().get(3)
                && checkpoint.getCurrent() != checkpoint.getHistory().get(2) && checkpoint.getHistory().get(1) == null,
                "reference snapshot aliases");
        var restored = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, journal);
        check("a".equals(checkpoint.getCurrent().getKey()) && journal.wireVersion() == 1
                && "b".equals(restored.getCurrent().getKey()) && restored.getCurrent() == restored.getHistory().get(0)
                && restored.getCurrent() == restored.getHistory().get(2) && "a".equals(restored.getHistory().get(3).getKey()),
                "journal restore of immutable aliases");
        checkpoint.setCurrent(checkpoint.getHistory().get(2));
        try {
            JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, journal);
            throw new AssertionError("Stale equal twin checkpoint was accepted");
        } catch (IllegalArgumentException expected) {
            check(checkpoint.getCurrent() == checkpoint.getHistory().get(2), "failed replay preserves checkpoint");
        }
        verifySecondary(write, directory, author);
        System.out.println("Java verified the mixed generic value model and immutable reference journal from " + author + ".");
    }

    private static void verifySecondary(boolean write, Path directory, String author) throws IOException {
        var saved = directory.resolve(author + "-secondary-checkpoint.json");
        var edits = directory.resolve(author + "-secondary-journal.json");
        if (write) {
            var first = Tag.newBuilder().withKey("a").withText("same").build();
            var second = Tag.newBuilder().withKey("b").withText("same").build();
            var model = CollectionReview.newBuilder().withId("collection-review").withCurrent(first)
                    .withHistory(Arrays.asList(first, null, second, first)).build();
            Forwarded<Tag> role = model;
            var view = role.asReadOnly();
            check(view == model.asReadOnly() && view.getCurrent() == first && view.getHistory().get(0) == first
                    && model.clone().getCurrent() == first, "secondary projections and native copy share the immutable value");
            MAPPER.writeValue(saved.toFile(), model);
            var journal = JournalRecorder.recordFrame(model, BINDING, CHECKPOINT, 0, changed -> {
                Forwarded<Tag> edited = changed;
                edited.setCurrent(edited.getHistory().get(2));
                edited.getHistory().set(0, edited.getCurrent());
            });
            Files.writeString(edits, VMFJournalCodec.write(journal));
        }
        var original = MAPPER.readValue(saved.toFile(), CollectionReview.class);
        check(original.getCurrent() == original.getHistory().get(0) && original.getCurrent() == original.getHistory().get(3)
                && original.getHistory().get(1) == null, "secondary reference snapshot preserves identity and null positions");
        var changes = VMFJournalCodec.read(Files.readString(edits));
        var replay = JournalReplayer.replay(original, BINDING, CHECKPOINT, changes);
        check(changes.wireVersion() == 1 && "a".equals(original.getCurrent().getKey())
                && "b".equals(replay.getCurrent().getKey()) && replay.getCurrent() == replay.getHistory().get(0)
                && replay.getCurrent() == replay.getHistory().get(2) && replay.getCurrent() != replay.getHistory().get(3),
                "secondary journal replay preserves recorded immutable identities");
    }

    private static void verifyStructuralNullReplay() {
        var tag = Tag.newBuilder().withKey("tag").withText("tag").build();
        var source = Review.newBuilder().withHistory(Arrays.asList(null, tag, null)).build();
        var texts = Texts.newBuilder().withHistory(List.of("first", "kept", "last")).build();
        var bounded = BoundedHistory.newBuilder().withHistory(List.of(10, 20, 30)).build();
        var changes = source.vmf().changes();
        changes.start();
        source.getHistory().removeAll(Collections.singletonList(null));
        changes.stop();
        var removal = changes.all().get(0);
        var removed = new ArrayList<String>();
        var listener = texts.getHistory().addChangeListener(change -> {
            check(Arrays.equals(change.removed().indices(), new int[]{0, 2}), "Structural replay uses sparse positions");
            removed.addAll(change.removed().elements());
        });
        try {
            removal.apply(texts);
        } finally {
            listener.unsubscribe();
        }
        removal.apply(bounded);
        check(removed.equals(List.of("first", "last")) && texts.getHistory().equals(List.of("kept"))
                        && bounded.getHistory().equals(List.of(20)) && source.getHistory().get(0) == tag,
                "Structural replay observes native receiver values without changing its source");
        try {
            removal.apply(texts.asReadOnly());
            throw new AssertionError("A read-only receiver must reject replay");
        } catch (UnsupportedOperationException | ClassCastException | IllegalArgumentException expected) {
            check(texts.getHistory().equals(List.of("kept")), "Read-only replay leaves the underlying list unchanged");
        }

        changes.start();
        source.getHistory().add(0, null);
        changes.stop();
        var addition = changes.all().get(0);
        addition.apply(texts);
        check(texts.getHistory().equals(Arrays.asList(null, "kept")), "Other nullable element types accept insertion");
        try {
            addition.apply(bounded);
            throw new AssertionError("A bounded native list does not accept null values");
        } catch (NullPointerException expected) {
            check(bounded.getHistory().equals(List.of(20)), "Invalid insertion leaves bounded slots unchanged");
        }

        changes.start();
        source.getHistory().set(0, null);
        changes.stop();
        var replacement = changes.all().get(0);
        texts.getHistory().set(0, "replace");
        replacement.apply(texts);
        check(texts.getHistory().get(0) == null, "Null replacement works on another generated list type");

        changes.start();
        source.getHistory().set(0, tag);
        changes.stop();
        try {
            changes.all().get(0).apply(texts);
            throw new AssertionError("The actual model-valued payload must be rejected");
        } catch (IllegalArgumentException expected) {
            check(texts.getHistory().get(0) == null, "Model payload rejection precedes receiver mutation");
        }
    }

    private static void verifyScalarSlots() {
        Slot<Integer> numbers = Counts.newInstance();
        Slot<String> texts = Texts.newInstance();
        check(numbers.getCurrent() == null && texts.getCurrent() == null, "boxed integer and String start null");
        numbers.setCurrent(0);
        texts.setCurrent("ready");
        numbers.getHistory().add(null);
        texts.getHistory().add(null);
        check(numbers.asReadOnly().getCurrent() == 0 && texts.asReadOnly().getCurrent().equals("ready")
                && numbers.asReadOnly().getHistory().get(0) == null, "typed scalar and ordinary list projections");
    }

    private static void verifyImmutableSlot(Review source, Tag first, Tag second) {
        Slot<Tag> slot = source;
        ReadOnlySlot<Tag> view = slot.asReadOnly();
        check(view.getCurrent() == first && view.getHistory().get(2) == second && view.getHistory().indexOf(second) == 0,
                "typed immutable projection and value-based lookup");
        Review deep = source.vmf().content().deepCopy();
        check(deep != source && deep.getHistory() != source.getHistory() && deep.getCurrent() == first
                && deep.getHistory().get(2) == second, "deep copy retains immutable identity");
        Review shallow = source.vmf().content().shallowCopy();
        check(shallow.getCurrent() == first && shallow.getHistory() == source.getHistory(), "legacy shallow shares its list");
        var descriptor = source.vmf().reflect().propertyByName("current").orElseThrow().getType().getDescriptor();
        check(descriptor.getName().equals(Tag.class.getName()), "closed reflection identifies the model type");
    }

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
    }
}
