package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.immutables.ExtendedPortfolio;
import eu.mihosoft.vmf.conformance.immutables.ImmutableBadge;
import eu.mihosoft.vmf.conformance.immutables.PortfolioSelection;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

final class CollectionCreationJournalConformance {
    private static final String CHECKPOINT = "start";
    private static final ObjectMapper REFERENCES = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "portfolio-creation"; }
        @Override public int modelVersion() { return 1; }

        @Override public String id(VObject value) {
            if (value instanceof PortfolioSelection) return "selection";
            if (value instanceof ExtendedPortfolio) return "portfolio";
            if (value instanceof ImmutableBadge) return ((ImmutableBadge) value).getText();
            throw new IllegalArgumentException("Unknown portfolio journal object");
        }

        @Override public String type(VObject value) {
            return value.vmf().reflect().type().getName();
        }

        @Override public VObject create(String type, String id) {
            if (type.equals(ExtendedPortfolio.class.getName())) return ExtendedPortfolio.newBuilder().build();
            if (type.equals(ImmutableBadge.class.getName())) return ImmutableBadge.newBuilder().withText(id).build();
            throw new IllegalArgumentException("Unknown portfolio journal creation type " + type);
        }
    };

    private CollectionCreationJournalConformance() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: CollectionCreationJournalConformance (write|read) <directory>");
        }
        var directory = Path.of(args[1]);
        if (args[0].equals("write")) {
            Files.createDirectories(directory);
            write(directory);
            verify(directory, "java");
            System.out.println("Java recorded and replayed its immutable creation with maps and sets.");
        } else if (args[0].equals("read")) {
            verify(directory, "csharp");
            System.out.println("Java replayed the C# immutable creation with maps and sets.");
        } else {
            throw new IllegalArgumentException("Unknown collection creation journal command " + args[0]);
        }
    }

    private static void write(Path directory) throws IOException {
        var working = PortfolioSelection.newInstance();
        REFERENCES.writeValue(directory.resolve("java-checkpoint.json").toFile(), working);
        var limits = new LinkedHashMap<String, Long>();
        limits.put("high", Long.MAX_VALUE);
        limits.put("pending", null);
        var markers = new LinkedHashMap<Double, Character>();
        markers.put(-0.0, '-');
        markers.put(+0.0, null);
        var badge = ImmutableBadge.newBuilder().withText("badge").build();
        var portfolio = ExtendedPortfolio.newBuilder()
                .withStatus(null)
                .withAliases("a", null, "a")
                .withLevels(+0.0, -0.0, Double.NaN)
                .withLimits(limits)
                .withMarkers(markers)
                .withCurrent(badge)
                .withTrail(badge, null, badge)
                .withPreferred(badge)
                .withPool(badge)
                .build();
        try (var recorder = JournalRecorder.start(working, BINDING, CHECKPOINT, 0)) {
            working.setPortfolio(portfolio);
            Files.writeString(directory.resolve("java-journal.json"), VMFJournalCodec.write(recorder.finish()));
        }
    }

    private static void verify(Path directory, String author) throws IOException {
        var initial = REFERENCES.readValue(directory.resolve(author + "-checkpoint.json").toFile(), PortfolioSelection.class);
        var journal = VMFJournalCodec.read(Files.readString(directory.resolve(author + "-journal.json")));
        check(journal.wireVersion() == 1 && journal.transactions().size() == 1
                && journal.transactions().get(0).sequence() == 1, "The journal contains one creation frame");
        var frame = journal.transactions().get(0);
        check(frame.operations().size() == 1, "One operation introduces the immutable portfolio");
        var operation = frame.operations().get(0);
        check(operation.kind() == ChangeJournal.Kind.SET && "portfolio".equals(operation.property())
                && operation.created().size() == 2 && "badge".equals(operation.created().get(0).id())
                && "portfolio".equals(operation.created().get(1).id()), "Creation records name both immutable definitions");
        var definition = operation.created().get(1);
        check(definition.mapProperties().keySet().equals(Set.of("limits"))
                && definition.typedMapProperties().keySet().equals(Set.of("markers"))
                && definition.setProperties().keySet().equals(Set.of("levels")), "The creation has the corresponding wire sections");

        var copy = JournalReplayer.replay(initial, BINDING, CHECKPOINT, journal);
        check(copy != initial && initial.getPortfolio() == null && copy.getPortfolio() instanceof ExtendedPortfolio,
                "Replay keeps the original root and restores the derived type");
        var restored = (ExtendedPortfolio) copy.getPortfolio();
        check(restored.getStatus() == null && restored.getAliases().equals(Arrays.asList("a", null, "a")),
                "Nullable scalar default and repeated list slots");
        check(restored.getLevels().size() == 3 && restored.getLevels().containsAll(List.of(+0.0, -0.0, Double.NaN)),
                "Set retains both signed zeros and NaN");
        check(List.copyOf(restored.getLimits().keySet()).equals(List.of("high", "pending"))
                && Long.valueOf(Long.MAX_VALUE).equals(restored.getLimits().get("high"))
                && restored.getLimits().get("pending") == null, "String mapping retains full width, order, and present null");
        check(List.copyOf(restored.getMarkers().keySet()).equals(List.of(-0.0, +0.0))
                && Character.valueOf('-').equals(restored.getMarkers().get(-0.0))
                && restored.getMarkers().get(+0.0) == null, "Typed mapping retains signed zeros and present null");
        var badge = restored.getCurrent();
        check("badge".equals(badge.getText()) && restored.getTrail().size() == 3
                && restored.getTrail().get(0) == badge && restored.getTrail().get(1) == null
                && restored.getTrail().get(2) == badge && restored.getPreferred().size() == 1
                && restored.getPreferred().get(0) == badge && restored.getPool().size() == 1
                && restored.getPool().iterator().next() == badge, "All immutable references resolve to the same instance");
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
