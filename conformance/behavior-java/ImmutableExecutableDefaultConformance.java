package eu.mihosoft.vmf.conformance.behavior;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ImmutableExecutableDefaultConformance {
    private static final List<String> STAGING = List.of("members", "label", "alpha", "zulu");

    private ImmutableExecutableDefaultConformance() { }

    public static void verifyConstruction() {
        ImmutableDefaultTrace.phases.clear();
        var builder = ImmutableExecutableDefaults.newBuilder();
        var first = builder.build();
        var second = builder.build();
        require(ImmutableDefaultTrace.phases.equals(STAGING), "Builder stages the reference defaults before scalar providers");
        require(first.getMembers().iterator().next() == second.getMembers().iterator().next(),
                "Repeated builds share the identity captured by this builder");
    }

    public static void exchange(Path directory, boolean write, boolean references, String format, ObjectMapper mapper)
            throws IOException {
        String prefix = (write ? "java" : "csharp") + "-immutable-executable-" + (references ? "refs" : "plain");
        for (String state : List.of("omitted", "empty", "nested")) {
            Path file = directory.resolve(prefix + "-" + state + "." + format);
            if (write) Files.writeString(file, specimen(state, format, mapper));
            for (int repeat = 0; repeat < 2; repeat++) {
                ImmutableDefaultTrace.phases.clear();
                verify(mapper.readValue(file.toFile(), ImmutableExecutableDefaults.class), state, references);
            }
        }
        if (format.equals("json") && references) {
            ImmutableDefaultTrace.phases.clear();
            var reverse = mapper.readValue("{\"witness\":{\"title\":\"wire-witness\"},\"selected\":\"member\","
                    + "\"members\":[{\"@vmf-id\":\"member\",\"label\":\"wire-member\"}]}",
                    ImmutableExecutableDefaults.class);
            require(reverse.getMembers().iterator().next() == reverse.getSelected(),
                    "The forward alias resolves to the same immutable instance");
            require(ImmutableDefaultTrace.phases.equals(List.of("members", "label", "alpha", "zulu", "witness", "label")),
                    "Nested constructors follow the incoming field order after their parent builder");
        }
        if (format.equals("json")) {
            ImmutableDefaultTrace.nullAlpha = true;
            try {
                var value = ImmutableExecutableDefaults.newBuilder().build();
                ImmutableDefaultTrace.phases.clear();
                require(!mapper.readTree(mapper.writeValueAsString(value)).has("first")
                                && ImmutableDefaultTrace.phases.equals(List.of("alpha")),
                        "A null provider result does not require an explicit null field");
            } finally {
                ImmutableDefaultTrace.nullAlpha = false;
            }
        }
    }

    private static String specimen(String state, String format, ObjectMapper mapper) throws IOException {
        if (state.equals("omitted")) {
            return format.equals("xml") ? "<" + ImmutableExecutableDefaults.class.getName() + "/>" : "{}";
        }
        ImmutableExecutableDefaults value;
        if (state.equals("empty")) {
            value = ImmutableExecutableDefaults.newBuilder().withAlpha("alpha-wire").withMembers(List.of()).withZulu(23).build();
        } else {
            var child = ImmutableDefaultMember.newBuilder().withLabel("wire-member").build();
            var witness = ImmutableDefaultWitness.newBuilder().withTitle("wire-witness").build();
            value = ImmutableExecutableDefaults.newBuilder().withAlpha(null).withMembers(List.of(child)).withSelected(child)
                    .withWitness(witness).withZulu(29).build();
        }
        ImmutableDefaultTrace.phases.clear();
        var document = mapper.writeValueAsString(value);
        require(ImmutableDefaultTrace.phases.equals(state.equals("nested") ? List.of("alpha") : List.of()),
                "Only writing an actual null invokes a declared provider");
        return document;
    }

    private static void verify(ImmutableExecutableDefaults value, String state, boolean references) {
        var expected = new ArrayList<>(STAGING);
        if (state.equals("nested")) {
            expected.add("label");
            if (!references) expected.add("label");
            expected.add("witness");
            var member = value.getMembers().iterator().next();
            require(value.getAlpha() == null && value.getZulu() == 29 && value.getMembers().size() == 1
                            && "wire-member".equals(member.getLabel()) && "wire-member".equals(value.getSelected().getLabel())
                            && (member == value.getSelected()) == references && "wire-witness".equals(value.getWitness().getTitle()),
                    "Supplied children, null and aliases survive staged readers");
        } else if (state.equals("empty")) {
            require("alpha-wire".equals(value.getAlpha()) && value.getZulu() == 23 && value.getMembers().isEmpty(),
                    "Explicit wire fields replace the staged values");
        } else {
            require("alpha-default".equals(value.getAlpha()) && value.getZulu() == 19 && value.getMembers().size() == 1
                            && "default-member".equals(value.getMembers().iterator().next().getLabel()),
                    "Omitted fields use the scalar and reference defaults of one builder");
        }
        var field = value.vmf().reflect().propertyByName("members").orElseThrow();
        var captured = (List<?>) field.getDefault();
        require(captured.size() == 1 && "default-member".equals(((ImmutableDefaultMember) captured.get(0)).getLabel()),
                "An explicit set override preserves the builder's captured default");
        if (state.equals("omitted")) {
            require(captured.get(0) == value.getMembers().iterator().next(),
                    "The omitted set uses the captured default identity");
        }
        require(ImmutableDefaultTrace.phases.equals(expected),
                "All stages run exactly once and precede nested definitions");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message + ": " + ImmutableDefaultTrace.phases);
    }
}
