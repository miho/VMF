package eu.mihosoft.vmf.conformance.immutableancestry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.conformance.immutableancestry.host.PortMode;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;

import java.nio.file.Path;
import java.util.Map;

final class ImmutablePortSettingsExchange {
    private ImmutablePortSettingsExchange() { }

    static void verify(Path directory, boolean writing) throws Exception {
        var builder = PortSettings.newBuilder();
        var defaults = builder.build();
        WithDisplayLabel label = defaults;
        WithTooltipText tooltip = defaults;
        check(label.getLabel().equals("115200") && tooltip.getTooltip().isEmpty()
                && defaults.getMode() == PortMode.NORMAL && defaults.asReadOnly() == defaults,
                "The effective defaults are visible through both traits");
        var reflected = defaults.vmf().reflect();
        check(reflected.properties().size() == 3
                && reflected.propertyByName("mode").orElseThrow().getDefault() == PortMode.NORMAL
                && reflected.propertyByName("label").orElseThrow().getDefault().equals("115200"),
                "Reflection reports one field per property and the effective providers");

        for (var format : Map.of("json", new ObjectMapper(), "yaml", new YAMLMapper(), "xml", new XmlMapper()).entrySet()) {
            var mapper = format.getValue().registerModule(VMFJacksonModule
                    .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(VMFJacksonModule.ReferenceMode.OMIT));
            var explicit = builder.withLabel(null).withMode(PortMode.DIAGNOSTIC).build();
            var file = directory.resolve((writing ? "java" : "csharp") + "-immutable-port." + format.getKey()).toFile();
            if (writing) mapper.writeValue(file, explicit);
            var restored = mapper.readValue(file, PortSettings.class);
            check(restored.getLabel() == null && restored.getMode() == PortMode.DIAGNOSTIC,
                    "Explicit values survive the " + format.getKey() + " snapshot");
        }
        var omitted = new ObjectMapper().registerModule(VMFJacksonModule
                .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(VMFJacksonModule.ReferenceMode.OMIT))
                .readValue("{}", PortSettings.class);
        check(omitted.getLabel().equals("115200") && omitted.getMode() == PortMode.NORMAL,
                "Absent fields receive the declared defaults");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
