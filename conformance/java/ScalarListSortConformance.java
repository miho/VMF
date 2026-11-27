package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.conformance.values.ReadOnlyCollectionProfile;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

final class ScalarListSortConformance {
    private static final String CHECKPOINT = "scalar-sort-0";
    private static final Comparator<String> MESSAGES = Comparator.comparingInt(value ->
            value == null ? 1 : value.equals("a") ? 0 : 2);
    private static final List<Integer> SPARSE = List.of(0, 2);
    private static final List<Integer> RANGE = List.of(0, 1, 2);
    private static final ObjectMapper SNAPSHOT = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "conformance.scalar-sort"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject model) { return "profile"; }
        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new AssertionError("No creations."); }
    };

    private ScalarListSortConformance() {}

    static void verifyNative() {
        verifyNative(CollectionProfile::getMessages, ReadOnlyCollectionProfile::getMessages,
                Arrays.asList("c", null, "a"), Arrays.asList("a", null, "c"),
                List.of("x", "retained", "y"), List.of("a", "retained", "c"), MESSAGES, SPARSE);
        verifyNative(CollectionProfile::getPhases, ReadOnlyCollectionProfile::getPhases,
                List.of("c", "b", "a"), List.of("a", "b", "c"),
                List.of("x", "replaced", "y"), List.of("a", "b", "c"), Comparator.naturalOrder(), RANGE);
        verifyNative(CollectionProfile::getCounts, ReadOnlyCollectionProfile::getCounts,
                List.of(3, 2, 1), List.of(1, 2, 3),
                List.of(6, 5, 4), List.of(1, 2, 3), Comparator.naturalOrder(), RANGE);

        var messages = CollectionProfile.newInstance().getMessages();
        var first = new String("x");
        var second = new String("x");
        messages.addAll(Arrays.asList(first, null, second));
        messages.sort(Comparator.nullsLast(Comparator.naturalOrder()));
        check(messages.get(0) == first && messages.get(1) == second, "The comparator preserves ties");
        messages.setAll(0, Arrays.asList(first, null, second));
        var events = observe(messages);
        messages.sort(Comparator.comparingInt(value -> value == second ? 0 : value == null ? 1 : 2));
        check(messages.get(0) == second && messages.get(2) == first && events.equals(List.of(SPARSE)),
                "Ordinary sort observes distinct strings that compare equal");

        long nan1 = 0x7ff8000000000001L;
        long nan2 = 0x7ff8000000000002L;
        var readings = CollectionProfile.newInstance().getReadings();
        readings.addAll(Arrays.asList(+0.0, null, -0.0, Double.longBitsToDouble(nan1), Double.longBitsToDouble(nan2)));
        var floatingEdits = observe(readings);
        readings.sort(Comparator.comparingInt(value -> {
            if (value == null) return 1;
            long bits = Double.doubleToRawLongBits(value);
            return bits == Long.MIN_VALUE ? 0 : bits == 0 ? 2 : bits == nan2 ? 3 : 4;
        }));
        check(Double.doubleToRawLongBits(readings.get(0)) == Long.MIN_VALUE && readings.get(1) == null
                        && Double.doubleToRawLongBits(readings.get(2)) == 0
                        && Double.doubleToRawLongBits(readings.get(3)) == nan2
                        && Double.doubleToRawLongBits(readings.get(4)) == nan1
                        && floatingEdits.equals(List.of(List.of(0, 2, 3, 4))),
                "An ordinary sort preserves signed zeros and NaN payloads");
    }

    private static <T> void verifyNative(Function<CollectionProfile, VList<T>> property,
            Function<ReadOnlyCollectionProfile, VList<T>> projection,
            List<T> original, List<T> sorted, List<T> targetValues, List<T> expectedTarget,
            Comparator<T> comparator, List<Integer> positions) {
        var source = CollectionProfile.newInstance();
        var target = CollectionProfile.newInstance();
        var list = property.apply(source);
        var view = projection.apply(source.asReadOnly());
        var targetList = property.apply(target);
        list.addAll(original);
        targetList.addAll(targetValues);
        var sourceEdits = observe(list);
        var projectedEdits = observe(view);
        var targetEdits = observe(targetList);
        var history = source.vmf().changes();
        history.start();
        list.sort(comparator);
        list.sort(comparator);
        history.stop();
        check(list.equals(sorted) && sourceEdits.equals(List.of(positions)) && projectedEdits.equals(List.of(positions))
                        && history.all().size() == 1,
                "Native sort records one change and repeating an identical sort is a no-op");
        var edit = history.all().get(0);
        var replayPositions = positions.equals(SPARSE) ? List.of(List.of(0), List.of(2)) : List.of(RANGE);

        sourceEdits.clear();
        projectedEdits.clear();
        check(edit.isUndoable(), "Native sort is undoable");
        edit.undo();
        check(list.equals(original) && sourceEdits.equals(replayPositions) && projectedEdits.equals(replayPositions),
                "Native undo restores the original slots");
        sourceEdits.clear();
        projectedEdits.clear();
        edit.apply(source);
        check(list.equals(sorted) && sourceEdits.equals(replayPositions) && projectedEdits.equals(replayPositions),
                "Native redo reapplies the recorded slots");
        edit.apply(target);
        check(targetList.equals(expectedTarget) && targetEdits.equals(replayPositions) && list.equals(sorted),
                "Applying to a compatible owner preserves unrecorded target slots");
    }

    private static <T> List<List<Integer>> observe(VList<T> list) {
        var events = new ArrayList<List<Integer>>();
        list.addChangeListener(edit -> events.add(Arrays.stream(edit.added().indices()).boxed()
                .collect(java.util.stream.Collectors.toList())));
        return events;
    }

    static void exchange(Path directory, boolean write) throws IOException {
        String author = write ? "java" : "csharp";
        var checkpoint = directory.resolve(author + "-scalar-sort-checkpoint.json");
        ChangeJournal recorded = null;
        if (write) {
            var model = CollectionProfile.newInstance();
            model.getMessages().addAll(Arrays.asList("c", null, "a"));
            model.getPhases().addAll(List.of("c", "b", "a"));
            model.getCounts().addAll(List.of(3, 2, 1));
            model.getLevels().add(0.0);
            model.getLimits().put("limit", 1L);
            SNAPSHOT.writeValue(checkpoint.toFile(), model);
            try (var recorder = JournalRecorder.start(model, BINDING, CHECKPOINT, 0)) {
                model.getMessages().sort(MESSAGES);
                model.getPhases().sort(Comparator.naturalOrder());
                model.getCounts().sort(Comparator.naturalOrder());
                recorded = recorder.finish();
            }
        }

        for (var format : VMFDocumentFormat.values()) {
            var path = directory.resolve(author + "-scalar-sort." + format.name().toLowerCase(Locale.ROOT));
            if (write) Files.writeString(path, VMFJournalCodec.write(recorded, format));
            var journal = VMFJournalCodec.read(Files.readString(path), format);
            check(journal.wireVersion() == 1 && journal.transactions().size() == 1, "One V1 frame records the three sorts");
            var edits = journal.transactions().get(0).operations();
            check(edits.size() == 3 && edits.stream().allMatch(edit -> edit.kind() == ChangeJournal.Kind.LIST_SET)
                    && edits.get(0).indices().equals(SPARSE) && edits.get(1).indices().equals(RANGE)
                    && edits.get(2).indices().equals(RANGE), "V1 keeps the exact recorded sort indices");
            var original = SNAPSHOT.readValue(checkpoint.toFile(), CollectionProfile.class);
            var sorted = JournalReplayer.replay(original, BINDING, CHECKPOINT, journal);
            check(sorted.getMessages().equals(Arrays.asList("a", null, "c"))
                    && sorted.getPhases().equals(List.of("a", "b", "c"))
                    && sorted.getCounts().equals(List.of(1, 2, 3)), "V1 replay reconstructs sorted lists");
            check(original.getMessages().equals(Arrays.asList("c", null, "a"))
                    && original.getPhases().equals(List.of("c", "b", "a"))
                    && original.getCounts().equals(List.of(3, 2, 1)), "Replay preserves the independent checkpoint");
        }
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
