package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.values.ScalarBatch;
import eu.mihosoft.vmf.conformance.values.ScalarSample;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.ValidationIssue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

final class ScalarConformance {
    private static final ObjectMapper PLAIN = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL));
    private static final ObjectMapper REFERENCES = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private ScalarConformance() {}

    public static void main(String[] args) throws IOException {
        Path interchange = Path.of(args[1]);
        if (args[0].equals("read")) {
            verify(PLAIN.readValue(interchange.resolve("scalar-csharp-plain.json").toFile(), ScalarSample.class));
            verify(REFERENCES.readValue(interchange.resolve("scalar-csharp-references.json").toFile(), ScalarSample.class));
            System.out.println("Java restored the C# scalar snapshots in both JSON profiles.");
            return;
        }

        Path fixtures = Path.of(args[2]);
        var sample = PLAIN.readValue(fixtures.resolve("scalar-extremes.json").toFile(), ScalarSample.class);
        verify(sample);
        var defaults = PLAIN.readValue("{}", ScalarSample.class);
        check(!defaults.isActive() && defaults.getByteValue() == 0 && defaults.getShortValue() == 0
                && defaults.getIntValue() == 0 && defaults.getLongValue() == 0 && defaults.getCharValue() == '\0'
                && defaults.getFloatValue() == 0.0f && defaults.getDoubleValue() == 0.0,
                "Primitive values have Java defaults");
        check(defaults.getOptionalActive() == null && defaults.getOptionalByte() == null && defaults.getOptionalShort() == null
                && defaults.getOptionalInt() == null && defaults.getOptionalLong() == null && defaults.getOptionalChar() == null
                && defaults.getOptionalFloat() == null && defaults.getOptionalDouble() == null
                && defaults.getDescription() == null, "Boxed scalar defaults are null");

        Files.createDirectories(interchange);
        PLAIN.writeValue(interchange.resolve("scalar-java-plain.json").toFile(), sample);
        REFERENCES.writeValue(interchange.resolve("scalar-java-references.json").toFile(), sample);

        var validationCases = PLAIN.readTree(fixtures.resolve("scalar-validation.json").toFile());
        for (var testCase : validationCases) verifyValidation(testCase);
        System.out.println("Java verified all scalar defaults and " + validationCases.size() + " validation cases.");
    }

    private static void verify(ScalarSample sample) {
        check(sample.isActive() && Boolean.FALSE.equals(sample.getOptionalActive()), "Boolean values");
        check(sample.getByteValue() == Byte.MIN_VALUE && sample.getOptionalByte() == Byte.MAX_VALUE, "Signed byte bounds");
        check(sample.getShortValue() == Short.MIN_VALUE && sample.getOptionalShort() == Short.MAX_VALUE, "Int16 bounds");
        check(sample.getIntValue() == Integer.MIN_VALUE && sample.getOptionalInt() == Integer.MAX_VALUE, "Int32 bounds");
        check(sample.getLongValue() == Long.MIN_VALUE && sample.getOptionalLong() == Long.MAX_VALUE, "Int64 bounds");
        check(sample.getCharValue() == '中' && sample.getOptionalChar() == '\0', "Character values");
        check(sample.getFloatValue() == 0.1f && sample.getOptionalFloat() == Float.POSITIVE_INFINITY, "Float values");
        check(sample.getDoubleValue() == -1.2345678901234567e200 && Double.isNaN(sample.getOptionalDouble()),
                "Double values");
        check("Probe 🛠".equals(sample.getDescription()), "Unicode string");
        check(Double.doubleToRawLongBits(sample.getSignedZero()) == Long.MIN_VALUE, "Signed floating-point zero");
        check(sample.getTinyFloat() == Float.MIN_VALUE && sample.getTinyDouble() == Double.MIN_VALUE,
                "Minimum positive subnormal values");
        check(sample.getNegativeInfinity() == Double.NEGATIVE_INFINITY, "Negative infinity");
        check(sample.getSurrogateUnit() == (char) 0xD800, "Isolated UTF-16 Character");
    }

    private static void verifyValidation(JsonNode testCase) throws IOException {
        var batch = PLAIN.treeToValue(testCase.get("model"), ScalarBatch.class);
        List<String> expected = new ArrayList<>();
        for (JsonNode issue : testCase.get("issues")) {
            expected.add(issue.get("path").asText() + "|" + issue.get("code").asText() + "|" + issue.get("severity").asText());
        }
        expected.sort(String::compareTo);
        var mutable = issues(batch.vmf().content().validate().getIssues());
        var readOnly = issues(batch.asReadOnly().vmf().content().validate().getIssues());
        check(expected.equals(mutable) && expected.equals(readOnly), testCase.get("name").asText()
                + "\nexpected " + expected + "\nmutable " + mutable + "\nread-only " + readOnly);
    }

    private static List<String> issues(List<ValidationIssue> issues) {
        return issues.stream()
                .map(issue -> issue.getPath() + "|" + issue.getCode() + "|" + issue.getSeverity().name())
                .sorted()
                .collect(Collectors.toList());
    }

    private static void check(boolean condition, String problem) {
        if (!condition) throw new IllegalStateException(problem);
    }
}
