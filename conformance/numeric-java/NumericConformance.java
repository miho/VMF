package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.numeric.DoubleSeries;
import eu.mihosoft.vmf.conformance.numeric.LongSeries;
import eu.mihosoft.vmf.conformance.numeric.NumericSeries;
import eu.mihosoft.vmf.conformance.numeric.ReadOnlyNumericSeries;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NumericConformance {
    private static final ObjectMapper PLAIN = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL));
    private static final long EXACT_LONG = 9_007_199_254_740_993L;

    private NumericConformance() { }

    public static void main(String[] args) throws IOException {
        Path exchange = Path.of(args[1]);
        switch (args[0]) {
            case "write":
                Files.createDirectories(exchange);
                var floating = doubleSeries();
                var integral = LongSeries.newBuilder().withCurrent(EXACT_LONG)
                        .withByNumber(Map.of(EXACT_LONG, 3L)).build();
                PLAIN.writeValue(exchange.resolve("java-double.json").toFile(), floating);
                PLAIN.writeValue(exchange.resolve("java-long.json").toFile(), integral);
                verifyDouble(PLAIN.readValue(exchange.resolve("java-double.json").toFile(), DoubleSeries.class));
                verifyLong(PLAIN.readValue(exchange.resolve("java-long.json").toFile(), LongSeries.class));
                System.out.println("Java wrote and round-tripped both numeric generic specializations.");
                break;
            case "read":
                verifyDouble(PLAIN.readValue(exchange.resolve("csharp-double.json").toFile(), DoubleSeries.class));
                verifyLong(PLAIN.readValue(exchange.resolve("csharp-long.json").toFile(), LongSeries.class));
                System.out.println("Java restored both C# numeric generic specializations.");
                break;
            default:
                throw new IllegalArgumentException("Expected write or read");
        }
    }

    private static DoubleSeries doubleSeries() {
        var named = new LinkedHashMap<String, Double>();
        named.put("empty", null);
        named.put("limit", -0.0);
        var keyed = new LinkedHashMap<Double, Long>();
        keyed.put(0.0, 7L);
        keyed.put(-0.0, null);
        keyed.put(Double.NaN, Long.MAX_VALUE);
        return DoubleSeries.newBuilder().withSamples(Arrays.asList(1.0, null, 1.0)).withBounded(1.0, 2.0)
                .withByName(named).withByNumber(keyed).withThreshold(0.1).build();
    }

    private static void verifyDouble(DoubleSeries series) {
        NumericSeries<Double> mutable = series;
        ReadOnlyNumericSeries<Double> view = series.asReadOnly();
        var current = series.vmf().reflect().propertyByName("current").orElseThrow();
        check(view.getCurrent() == null && current.getDefault() == null && !current.isSet()
                && current.getType().getDescriptor().getName().equals(Double.class.getName()),
                "Double type and nullable generic default");
        check(view.getSamples().equals(Arrays.asList(1.0, null, 1.0)) && view.getBounded().equals(List.of(1.0, 2.0)),
                "Sample slots and checked collection contents");
        check(view.getByName().containsKey("empty") && view.getByName().get("empty") == null
                && !view.getByName().containsKey("absent")
                && Double.doubleToRawLongBits(view.getByName().get("limit")) == Long.MIN_VALUE,
                "String keys preserve present null, missing key and signed zero value");
        check(Long.valueOf(7).equals(view.getByNumber().get(0.0))
                && view.getByNumber().containsKey(-0.0) && view.getByNumber().get(-0.0) == null
                && Long.valueOf(Long.MAX_VALUE).equals(view.getByNumber().get(Double.NaN)),
                "Numeric keys preserve NaN and distinguish signed zero");
        var mapType = series.vmf().reflect().propertyByName("byNumber").orElseThrow().getType().getDescriptor();
        check(mapType.getArguments().get(0).getName().equals(Double.class.getName())
                && mapType.getArguments().get(1).getName().equals(Long.class.getName()),
                "Map reflection preserves distinct numeric key and value types");

        expectNullRejection(() -> mutable.getBounded().add(null), "Bounded scalar list rejects null slots");
        expectNullRejection(() -> mutable.getByNumber().put(null, 1L), "Typed map rejects null keys");
        check(view.getThreshold().equals(0.1) && series.vmf().content().validate().isValid(),
                "Inclusive lower decimal boundary");
        mutable.setThreshold(0.2);
        var issues = series.vmf().content().validate().getIssues();
        check(view.getThreshold().equals(0.2) && issues.size() == 1
                && issues.get(0).getCode().equals("vmf.range") && issues.get(0).getPath().equals("/threshold"),
                "Live read-only projection and exclusive upper decimal boundary");
    }

    private static void verifyLong(LongSeries series) {
        NumericSeries<Long> typed = series;
        var current = series.vmf().reflect().propertyByName("current").orElseThrow();
        check(Long.valueOf(EXACT_LONG).equals(typed.getCurrent())
                && Long.valueOf(3).equals(typed.getByNumber().get(EXACT_LONG))
                && !typed.getByNumber().containsKey(EXACT_LONG - 1)
                && current.getType().getDescriptor().getName().equals(Long.class.getName()),
                "Int64 generic scalar and typed map key preserve integer precision");
    }

    private static void expectNullRejection(Runnable edit, String detail) {
        try {
            edit.run();
        } catch (NullPointerException expected) {
            return;
        }
        throw new IllegalStateException(detail);
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException(detail);
    }
}
