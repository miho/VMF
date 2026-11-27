package eu.mihosoft.vmf.conformance.modelsummaries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class ModelSummaryConformance {
    private static final UUID EXTERNAL = UUID.fromString("980e13c6-84a0-49d6-821d-737269635aef");
    private static final String BADGE = "{\"@type\":\"SummaryToken\", \"text\": \"badge\"}";
    private static final String CHILD = "{\"@type\":\"SummaryNode\", \"anchor\": \"null\", \"children\": [],"
            + " \"name\": \"leaf\", \"peers\": [], \"ranked\": [], \"trail\": []}";

    private ModelSummaryConformance() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && (args.length != 2 || !(args[0].equals("write") || args[0].equals("read")))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>.");
        }

        String graph = graphSummary();
        String values = valuesSummary();
        if (args.length != 0) {
            Path directory = Path.of(args[1]);
            if (args[0].equals("write")) {
                Files.createDirectories(directory);
                Files.writeString(directory.resolve("java-graph.txt"), graph);
                Files.writeString(directory.resolve("java-values.txt"), values);
            } else {
                check(graph, Files.readString(directory.resolve("csharp-graph.txt")));
                check(values, Files.readString(directory.resolve("csharp-values.txt")));
            }
        }
        System.out.println("Java verified model summaries for mutable graphs and external and immutable values.");
    }

    private static String graphSummary() {
        var root = DerivedSummary.newBuilder().withName("quote\"line\nbreak").withHidden("hidden").withRank(7).build();
        var child = SummaryNode.newBuilder().withName("leaf").withHidden("child secret").build();
        root.setAnchor(root);
        root.getChildren().add(child);
        root.getPeers().add(child);
        root.getRanked().add(child);
        root.getTrail().addAll(Arrays.asList(null, child, root));
        String expected = "{\"@type\":\"DerivedSummary\", \"anchor\": \"{skipping recursion}\", \"children\": ["
                + CHILD + "], \"name\": \"quote\"line\nbreak\", \"peers\": [{skipping recursion}], \"rank\": \"7\","
                + " \"ranked\": [{skipping recursion}], \"trail\": [null, {skipping recursion}, {skipping recursion}]}";
        check(expected, root.toString());
        check(expected, root.asReadOnly().toString());
        check(CHILD, child.toString());
        return root.toString();
    }

    private static String valuesSummary() {
        var value = SummaryValues.newBuilder().withExternal(EXTERNAL).withMagnitude(Float.MIN_VALUE).build();
        value.getChecked().addAll(List.of(2, 1));
        value.getDoubles().addAll(List.of(0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY));
        var token = SummaryToken.newBuilder().withText("badge").withSecret("private").build();
        value.setFirst(token);
        value.getReadings().addAll(Arrays.asList(Double.MIN_VALUE, 1e20, 0.001, 1e7, null));
        value.getTokens().addAll(Arrays.asList(token, null, token));
        value.getValues().put("nil", null);
        value.getValues().put("large", 9007199254740993L);
        value.getWords().addAll(Arrays.asList("true", "quote\"word", null));

        String expected = "{\"@type\":\"SummaryValues\", \"checked\": \"[2, 1]\", \"doubles\": \"[0.0, -0.0, NaN, Infinity]\","
                + " \"external\": \"" + EXTERNAL + "\", \"first\": \"" + BADGE + "\", \"magnitude\": \"1.4E-45\","
                + " \"readings\": [4.9E-324, 1.0E20, 0.001, 1.0E7, null], \"tokens\": [" + BADGE + ", null, " + BADGE
                + "], \"values\": \"{nil=null, large=9007199254740993}\", \"words\": [true, quote\"word, null]}";
        check(expected, value.toString());
        check(BADGE, token.toString());
        return value.toString();
    }

    private static void check(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected:\n" + expected + "\nActual:\n" + actual);
        }
    }
}
