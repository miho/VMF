package eu.mihosoft.vmf.conformance.immutableancestry;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

final class ImmutableDataBasesExchange {
    private ImmutableDataBasesExchange() { }

    static void verify(Path directory, boolean writing) throws Exception {
        var value = JoinedDataValue.newBuilder().withRank(4).withName("joined").build();
        PrimaryDataValue primary = value;
        SecondaryDataValue secondary = value;
        check(primary == (ComposedValueRoot) secondary && primary.getRank() == 4 && secondary.getWeight() == 13L
                && primary.getTitle().equals("shared"), "Both immutable parents expose one value and both defaults");
        check(value.asReadOnly() == value && primary.asReadOnly() == value && secondary.asReadOnly() == value,
                "Both read-only roles preserve immutable identity");
        var reflection = value.vmf().reflect();
        check(reflection.properties().size() == 4, "Reflection merges the shared ancestor field");
        var weight = reflection.propertyByName("weight").orElseThrow();
        check(Long.valueOf(13L).equals(weight.getDefault()) && !weight.isSet(),
                "An inherited primitive default uses its declared type, even from an int literal");

        var envelope = JoinedDataEnvelope.newBuilder().withPrimary(primary).withSecondary(secondary)
                .withSelections(Arrays.asList(secondary, null, secondary)).build();
        for (boolean references : new boolean[]{true, false}) {
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                    .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                    .withModelWireTypeAliases(JoinedDataEnvelope.class).withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
            String suffix = references ? "-joined-reference.json" : "-joined-by-value.json";
            String wire = mapper.writeValueAsString(envelope);
            if (writing) {
                Files.writeString(directory.resolve("java" + suffix), wire);
            } else {
                String nativeWire = Files.readString(directory.resolve("csharp" + suffix));
                check(mapper.readTree(wire).equals(mapper.readTree(nativeWire)), "Java and C# write the same composed data");
                wire = nativeWire;
            }
            var restored = mapper.readValue(wire, JoinedDataEnvelope.class);
            check(restored.getPrimary() instanceof JoinedDataValue && restored.getSecondary() instanceof JoinedDataValue
                    && restored.getSelections().get(0) instanceof JoinedDataValue && restored.getSelections().get(1) == null
                    && restored.getSelections().get(2) instanceof JoinedDataValue,
                    "Both parent declarations preserve the actual concrete subtype and null slots");
            check((restored.getPrimary() == (ComposedValueRoot) restored.getSecondary()) == references
                    && (restored.getSelections().get(0) == restored.getSelections().get(2)) == references,
                    "Reference-aware input preserves identity and by-value input creates separate occurrences");
            check(restored.getPrimary().getRank() == 4 && restored.getSecondary().getWeight() == 13L
                    && restored.getSecondary().getTitle().equals("shared"), "Inherited data survive both profiles");
        }
        verifyGetterOnlyDefaults(directory, writing);
    }

    private static void verifyGetterOnlyDefaults(Path directory, boolean writing) throws Exception {
        var mapper = new ObjectMapper().registerModule(
                VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(VMFJacksonModule.ReferenceMode.OMIT));
        var baseline = mapper.readValue("{}", DescribedValue.class);
        WithDisplayLabel label = baseline;
        WithTooltipText tooltip = baseline;
        check(label.getLabel().equals("item") && tooltip.getTooltip().isEmpty() && baseline.asReadOnly() == baseline,
                "The concrete default is visible through both getter-only traits");
        check(baseline.equals(DescribedValue.newBuilder().withTooltip("other").build())
                        && baseline.vmf().reflect().properties().size() == 2,
                "The local equality contract and one field per trait are retained");
        var path = directory.resolve(writing ? "java-redeclared-traits.json" : "csharp-redeclared-traits.json");
        if (writing) mapper.writeValue(path.toFile(), DescribedValue.newBuilder().withLabel(null).withTooltip("shown").build());
        var restored = mapper.readValue(path.toFile(), DescribedValue.class);
        check(restored.getLabel() == null && restored.getTooltip().equals("shown")
                        && mapper.readTree(path.toFile()).get("display_label").isNull(),
                "Snapshots use the effective field name and preserve explicit null");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
