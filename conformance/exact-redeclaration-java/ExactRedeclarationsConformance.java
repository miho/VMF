package eu.mihosoft.vmf.conformance.exactredeclarations;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.Property;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class ExactRedeclarationsConformance {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(VMFJacksonModule
            .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(false));

    private ExactRedeclarationsConformance() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2 || !(arguments[0].equals("write") || arguments[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>.");
        }
        var record = exercise();
        String wire = MAPPER.writeValueAsString(RecordEnvelope.newBuilder().withRecord(record).build());
        var directory = Path.of(arguments[1]);
        if (arguments[0].equals("write")) {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("java-record.json"), wire);
        } else {
            String nativeWire = Files.readString(directory.resolve("csharp-record.json"));
            check(MAPPER.readTree(wire).equals(MAPPER.readTree(nativeWire)), "Both targets expose the same aliased fields");
            var restored = MAPPER.readValue(nativeWire, RecordEnvelope.class).getRecord();
            check(restored instanceof CombinedRecord && restored.getCaption().equals("changed")
                    && restored.getAssets().get(0).getId().equals("asset")
                    && ((WithMarkers) restored).getMarkers().equals(Arrays.asList("first", null, "first")),
                    "The secondary-typed slot restores the composed record and effective field values");
        }
        verifyLocalWireName(directory, arguments[0].equals("write"));
        System.out.println("Java verified exact redeclarations, single storage, and snapshot exchange.");
    }

    private static void verifyLocalWireName(Path directory, boolean produce) throws Exception {
        var value = LocallyNamedRecord.newInstance();
        WithLegacyName first = value;
        WithLegacyRank second = value;
        first.setName("first");
        check(second.getName().equals("first"), "The secondary role sees the primary role's edit");
        second.setName("winner");
        value.setRank(3);
        check(first.getName().equals("winner"), "The primary role sees the secondary role's edit");
        check(value.vmf().reflect().properties().size() == 2
                        && value.vmf().reflect().propertyByName("name").orElseThrow()
                        .annotationByKey("vmf:jackson:rename").isEmpty(),
                "Reflection describes the locally redeclared slot once");
        for (boolean references : List.of(false, true)) {
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                    .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(references));
            var expected = mapper.writeValueAsString(value);
            var fields = mapper.readTree(expected);
            check(fields.has("name") && !fields.has("label"), "Only the local wire name is used");
            var sibling = LegacyNamedRecord.newBuilder().withName("sibling").build();
            var inherited = mapper.readTree(mapper.writeValueAsString(sibling));
            check(inherited.has("label") && !inherited.has("name"), "The sibling keeps its inherited name");
            var path = directory.resolve((produce ? "java-" : "csharp-") + "local-name-" + references + ".json");
            if (produce) Files.writeString(path, expected);
            var wire = Files.readString(path);
            check(mapper.readTree(wire).equals(fields), "Both targets use the local name in JSON");
            var loaded = mapper.readValue(wire, LocallyNamedRecord.class);
            check(loaded.getName().equals("winner") && loaded.getRank() == 3, "The local fields round-trip");
        }
    }

    private static CombinedRecord exercise() {
        var combined = CombinedRecord.newInstance();
        FirstRecord first = combined;
        SecondRecord second = combined;
        WithMarkers markers = combined;
        check(first.getCaption().equals("record") && second.getCaption().equals("record"),
                "A redeclaration retains its shared literal default");
        var properties = combined.vmf().reflect().properties().stream().map(Property::getName).collect(Collectors.toSet());
        check(properties.equals(java.util.Set.of("caption", "assets", "markers"))
                && properties.size() == combined.vmf().reflect().properties().size(),
                "Reflection lists each effective field once");

        var asset = Asset.newBuilder().withId("asset").build();
        var observed = new ArrayList<Change>();
        var subscription = combined.vmf().changes().addLocalListener(observed::add);
        try {
            second.setCaption("changed");
            second.getAssets().add(asset);
            markers.getMarkers().addAll(Arrays.asList("first", null, "first"));
        } finally {
            subscription.unsubscribe();
        }
        check(first.getCaption().equals("changed") && first.getAssets() == second.getAssets()
                && first.getAssets().get(0) == asset && markers.getMarkers() == combined.getMarkers(),
                "All nominal views write the same physical properties");
        check(observed.stream().map(Change::propertyName).collect(Collectors.toList())
                .equals(List.of("caption", "assets", "markers"))
                && observed.get(2).getType() == Change.ChangeType.LIST, "One change per physical mutation");
        return combined;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
