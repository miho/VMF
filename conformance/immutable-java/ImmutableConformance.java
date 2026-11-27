package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.immutables.ExtendedPortfolio;
import eu.mihosoft.vmf.conformance.immutables.IdentityPortfolio;
import eu.mihosoft.vmf.conformance.immutables.ImmutableBadge;
import eu.mihosoft.vmf.conformance.immutables.ImmutablePortfolio;
import eu.mihosoft.vmf.conformance.immutables.PortfolioSelection;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ImmutableConformance {
    private static final ObjectMapper PLAIN = mapper(false);
    private static final ObjectMapper REFERENCES = mapper(true);

    private ImmutableConformance() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Usage: ImmutableConformance (write|read) <interchange-directory>");
        }
        Path directory = Path.of(args[1]);
        if (args[0].equals("write")) {
            Files.createDirectories(directory);
            var current = specimen();
            verifyReference(current);
            verifyFrozen(current);
            verifyEquality();
            verifyBuilderValidation();
            verifyBuilderImports();
            verifyNestedBuilders();
            verifyLazyBuilderImports();
            verifyDefaults(PLAIN);
            verifyDefaults(REFERENCES);
            var selection = PortfolioSelection.newBuilder().withPortfolio(current).build();
            REFERENCES.writeValue(directory.resolve("java-reference.json").toFile(), selection);
            PLAIN.writeValue(directory.resolve("java-values.json").toFile(), selection);
            verifyExchange(directory, "java");
            System.out.println("Java verified immutable collection construction, inheritance, equality, and both JSON profiles.");
        } else {
            verifyExchange(directory, "csharp");
            System.out.println("Java restored C# immutable collections, subtype, and reference aliases.");
        }
    }

    private static ObjectMapper mapper(boolean references) {
        return new ObjectMapper().registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
    }

    private static void verifyBuilderImports() {
        var firstBadge = badge("same");
        var secondBadge = badge("same");
        var first = ExtendedPortfolio.newBuilder()
                .withStatus("first").withAliases("first").withCounts(1, 2)
                .withSteps("one", "shared").withLimits(Map.of("shared", 1L, "retained", 2L))
                .withCurrent(firstBadge).withTrail(firstBadge).withPreferred(firstBadge).withPool(firstBadge).build();
        var second = ExtendedPortfolio.newBuilder()
                .withStatus(null).withDefaultProbe(null).withAliases(Arrays.asList(null, "second"))
                .withCounts(3).withSteps("shared", "two").withLimits(Map.of("shared", 3L, "new", 4L))
                .withCurrent(secondBadge).withTrail(firstBadge, secondBadge)
                .withPreferred(firstBadge, secondBadge).withPool(firstBadge, secondBadge).build();

        var staged = ExtendedPortfolio.newBuilder().applyFrom(first).applyFrom(second);
        var merged = staged.build();
        check(merged.getStatus() == null && merged.getDefaultProbe() == null, "imports retain explicit nulls");
        check(merged.getAliases().equals(Arrays.asList("first", null, "second")), "legacy list append");
        check(merged.getCounts().equals(List.of(1, 2, 3)), "bounded list append");
        check(merged.getSteps().equals(List.of("one", "shared", "two")), "ordered value set append");
        check(merged.getLimits().equals(Map.of("shared", 3L, "retained", 2L, "new", 4L)), "map merge by key");
        check(merged.getCurrent() == secondBadge && merged.getTrail().get(0) == firstBadge
                && merged.getTrail().get(1) == firstBadge && merged.getTrail().get(2) == secondBadge
                && merged.getPreferred().get(0) == firstBadge && merged.getPreferred().get(1) == secondBadge
                && merged.getPool().size() == 2, "reference append preserves identity and aliases");

        reject(IllegalArgumentException.class, () -> staged.applyFrom(second));
        check(staged.build().getCounts().equals(List.of(1, 2, 3)), "failed list append preserves its staged values");

        var replaced = staged.appendCollections(false).applyFrom((ImmutablePortfolio) second).build();
        check(replaced.getAliases().equals(Arrays.asList(null, "second")), "ancestor list replacement");
        check(replaced.getCounts().equals(List.of(3)), "ancestor bounded list replacement");
        check(replaced.getSteps().equals(List.of("shared", "two")), "ancestor value set replacement");
        check(replaced.getLimits().equals(Map.of("shared", 3L, "new", 4L)), "ancestor map replacement");
        check(replaced.getCurrent() == secondBadge && replaced.getTrail().size() == 3
                && replaced.getPreferred().size() == 2, "ancestor import preserves derived slots");
        check(merged.getCounts().equals(List.of(1, 2, 3)), "previous builds are immutable");
    }

    private static void verifyNestedBuilders() {
        var badgeBuilder = ImmutableBadge.newBuilder().withText("staged");
        var trail = new ArrayList<>(Arrays.asList(badgeBuilder, null, badgeBuilder));
        var staged = ExtendedPortfolio.newBuilder().withCurrent(badgeBuilder).withTrail(trail)
                .withPreferred(badgeBuilder, badgeBuilder).withPool(badgeBuilder, badgeBuilder);
        trail.clear();
        badgeBuilder.withText("first");
        var first = staged.build();
        badgeBuilder.withText("second");
        var second = staged.build();
        verifyNestedBuild(first, "first");
        verifyNestedBuild(second, "second");
        check(first.getCurrent() != second.getCurrent(), "a repeated build materializes new values");

        var direct = badge("direct");
        var replaced = staged.withCurrent(direct).withTrail(direct, null, direct)
                .withPreferred(direct).withPool(direct).build();
        check(replaced.getCurrent() == direct && replaced.getTrail().get(0) == direct
                && replaced.getTrail().get(2) == direct && replaced.getPreferred().get(0) == direct
                && replaced.getPool().iterator().next() == direct, "direct values replace staged builders");
    }

    private static void verifyNestedBuild(ExtendedPortfolio model, String text) {
        check(model.getCurrent().getText().equals(text) && model.getTrail().size() == 3
                && model.getTrail().get(1) == null, "builder sees the current nested state and nullable slots");
        var nested = model.getTrail().get(0);
        check(nested.getText().equals(text) && nested != model.getCurrent() && nested != model.getTrail().get(2),
                "the same builder is evaluated for each occurrence");
        check(model.getPreferred().size() == 2 && model.getPool().size() == 2
                && model.getPreferred().stream().allMatch(item -> item.getText().equals(text))
                && model.getPool().stream().allMatch(item -> item.getText().equals(text)), "sets retain distinct built identities");
    }

    private static void verifyLazyBuilderImports() {
        var imported = badge("imported");
        var source = ExtendedPortfolio.newBuilder().withCurrent(imported)
                .withTrail(new ImmutableBadge[]{imported, null, imported}).build();
        var empty = ExtendedPortfolio.newBuilder().build();
        for (boolean append : List.of(false, true)) {
            var staged = ExtendedPortfolio.newBuilder().appendCollections(append)
                    .withCurrent(ImmutableBadge.newBuilder().withText("lazy"))
                    .withTrail(ImmutableBadge.newBuilder().withText("lazy"))
                    .applyFrom(source);
            var built = staged.build();
            check(built.getCurrent() == imported && built.getTrail().size() == 3
                    && built.getTrail().get(0) == imported && built.getTrail().get(1) == null
                    && built.getTrail().get(2) == imported, "immutable import replaces lazy model values and keeps aliases");

            var cleared = staged.withCurrent(ImmutableBadge.newBuilder().withText("lazy"))
                    .withTrail(ImmutableBadge.newBuilder().withText("lazy")).applyFrom(empty).build();
            check(cleared.getCurrent() == null && cleared.getTrail().isEmpty(),
                    "immutable empty import clears the pending builders");
        }
    }

    static ExtendedPortfolio specimen() {
        var first = badge("same");
        var second = badge("same");
        var aliases = new ArrayList<>(Arrays.asList("tag", null, "tag"));
        var counts = new ArrayList<>(List.of(1, 2));
        var trail = new ArrayList<>(Arrays.asList(first, null, first, second));
        var preferred = new ArrayList<>(List.of(first, second));
        var pool = new ArrayList<>(List.of(second, first));
        var limits = new LinkedHashMap<String, Long>();
        limits.put("known", 9L);
        limits.put("empty", null);
        var markers = new LinkedHashMap<Double, Character>();
        markers.put(+0.0, '+');
        markers.put(-0.0, '-');
        var result = ExtendedPortfolio.newBuilder().withAliases(aliases).withCounts(counts)
                .withSteps("pick", "place").withLevels(+0.0, -0.0, Double.NaN).withLimits(limits).withMarkers(markers)
                .withCurrent(first).withTrail(trail).withPreferred(preferred).withPool(pool).build();
        // These caller-owned collections must not alter the finished model.
        aliases.clear();
        counts.clear();
        trail.clear();
        preferred.clear();
        pool.clear();
        limits.clear();
        markers.clear();
        return result;
    }

    private static void verifyExchange(Path directory, String producer) throws IOException {
        var wrapped = REFERENCES.readValue(directory.resolve(producer + "-reference.json").toFile(), PortfolioSelection.class);
        check(wrapped.getPortfolio() instanceof ExtendedPortfolio, "base-typed member restores the exact immutable subtype");
        var reference = (ExtendedPortfolio) wrapped.getPortfolio();
        verifyReference(reference);
        verifyFrozen(reference);
        check(wrapped.asReadOnly().getPortfolio() == reference, "mutable wrapper shares the same immutable instance in its view");

        var byValue = PLAIN.readValue(directory.resolve(producer + "-values.json").toFile(), PortfolioSelection.class);
        check(byValue.getPortfolio() instanceof ExtendedPortfolio, "by-value polymorphic member restores the immutable subtype");
        verifyByValue((ExtendedPortfolio) byValue.getPortfolio(), reference);
        if (producer.equals("csharp")) {
            var plain = PLAIN.readValue(directory.resolve("csharp-plain.json").toFile(), ExtendedPortfolio.class);
            verifyByValue(plain, reference);
        }
    }

    static void verifyByValue(ExtendedPortfolio plain, ExtendedPortfolio reference) {
        verifyValues(plain);
        check(plain.getCurrent().equals(plain.getTrail().get(0)) && plain.getCurrent() != plain.getTrail().get(0)
                && plain.getTrail().get(0) != plain.getTrail().get(2) && !plain.getPool().contains(plain.getCurrent()),
                "inline occurrences are restored by value, with separate identities");
        check(reference.equals(plain) && plain.equals(reference) && reference.hashCode() == plain.hashCode(),
                "both profiles preserve the same immutable structural value");
    }

    private static void verifyValues(ExtendedPortfolio value) {
        check(value.asReadOnly() == value && "ready".equals(value.getStatus())
                && Double.doubleToRawLongBits(value.getDefaultProbe()) == Long.MIN_VALUE,
                "inherited scalar defaults and the immutable view retain exact values");
        check(value.getAliases().equals(Arrays.asList("tag", null, "tag")) && value.getCounts().equals(List.of(1, 2))
                && value.getSteps().equals(List.of("pick", "place")), "ordered scalar collections retain sequence and null slots");
        check(value.getLevels().size() == 3 && value.getLevels().contains(+0.0) && value.getLevels().contains(-0.0)
                && value.getLevels().contains(Double.NaN), "scalar set distinguishes signed zeros");
        check(value.getLimits().size() == 2 && value.getLimits().get("known") == 9L && value.getLimits().containsKey("empty")
                && value.getLimits().get("empty") == null, "immutable map distinguishes missing and present-null entries");
        check(value.getMarkers().size() == 2 && value.getMarkers().get(+0.0) == '+' && value.getMarkers().get(-0.0) == '-',
                "immutable typed map keys retain signed zero");
        check(value.getTrail().size() == 4 && value.getTrail().get(1) == null && value.getPreferred().size() == 2
                && value.getPool().size() == 2 && "same".equals(value.getCurrent().getText()),
                "immutable model collections preserve nullable slots and identity-set cardinality");
    }

    private static void verifyReference(ExtendedPortfolio value) {
        verifyValues(value);
        var first = value.getCurrent();
        var second = value.getTrail().get(3);
        check(first != second && first.equals(second) && value.getTrail().get(0) == first && value.getTrail().get(2) == first
                && value.getPreferred().get(0) == first && value.getPreferred().get(1) == second
                && value.getPool().contains(first) && value.getPool().contains(second) && !value.getPool().contains(badge("same")),
                "reference profile resolves aliases and set membership by identity");
    }

    private static void verifyFrozen(ExtendedPortfolio value) {
        reject(UnsupportedOperationException.class, () -> value.getAliases().set(0, "other"));
        reject(UnsupportedOperationException.class, () -> value.getCounts().clear());
        reject(UnsupportedOperationException.class, () -> value.getSteps().set(0, "other"));
        reject(UnsupportedOperationException.class, () -> value.getLevels().add(22.0));
        reject(UnsupportedOperationException.class, () -> value.getLimits().put("other", 123L));
        reject(UnsupportedOperationException.class, () -> value.getMarkers().remove(-0.0));
        reject(UnsupportedOperationException.class, () -> value.getTrail().clear());
        reject(UnsupportedOperationException.class, () -> value.getPreferred().add(badge("other")));
        reject(UnsupportedOperationException.class, () -> value.getPool().add(badge("other")));
    }

    private static void verifyEquality() {
        var a = ImmutablePortfolio.newBuilder().build();
        var b = ImmutablePortfolio.newBuilder().build();
        var child = ExtendedPortfolio.newBuilder().build();
        var one = IdentityPortfolio.newBuilder().build();
        var two = IdentityPortfolio.newBuilder().build();
        check(a.equals(b) && !a.equals(child) && !child.equals(a) && one.equals(one) && !one.equals(two)
                && !one.equals(a) && !a.equals(one), "immutable equality uses exact type and the declared policy");

        var left = badge("left");
        var right = badge("right");
        check(ExtendedPortfolio.newBuilder().withCurrent(left).withTrail(left, null, left).build().equals(
                ExtendedPortfolio.newBuilder().withCurrent(badge("left")).withTrail(badge("left"), null, badge("left")).build()),
                "structural equality compares reference content across differing aliases");
        var ordered = ExtendedPortfolio.newBuilder().withPreferred(left, right).build();
        var reversed = ExtendedPortfolio.newBuilder().withPreferred(right, left).build();
        check(!ordered.equals(reversed), "ordered identity set content comparison follows order");
        var unordered = ExtendedPortfolio.newBuilder().withPool(left, right).build();
        var reordered = ExtendedPortfolio.newBuilder().withPool(badge("right"), badge("left")).build();
        check(unordered.equals(reordered) && unordered.hashCode() == reordered.hashCode(), "unordered identity sets compare content as multisets");
    }

    private static void verifyBuilderValidation() {
        var badge = badge("repeat");
        reject(IllegalArgumentException.class, () -> ExtendedPortfolio.newBuilder().withSteps("x", "x").build());
        reject(IllegalArgumentException.class, () -> ExtendedPortfolio.newBuilder().withCounts(1, 2, 3, 4).build());
        reject(IllegalArgumentException.class, () -> ExtendedPortfolio.newBuilder().withPreferred(badge, badge).build());
        reject(IllegalArgumentException.class, () -> ExtendedPortfolio.newBuilder().withPool(badge, badge).build());
        reject(NullPointerException.class, () -> ExtendedPortfolio.newBuilder().withPreferred(badge, null).build());
        check(ExtendedPortfolio.newBuilder().withLevels(+0.0, +0.0).build().getLevels().size() == 1,
                "native unordered scalar set builder coalesces duplicates");
    }

    private static void verifyDefaults(ObjectMapper mapper) throws IOException {
        var missing = mapper.readValue("{}", ExtendedPortfolio.class);
        check("ready".equals(missing.getStatus()) && Double.doubleToRawLongBits(missing.getDefaultProbe()) == Long.MIN_VALUE,
                "missing fields initialize inherited defaults on deserialization");
        var cleared = ExtendedPortfolio.newBuilder().withStatus(null).withDefaultProbe(null).build();
        String wire = mapper.writeValueAsString(cleared);
        var json = mapper.readTree(wire);
        check(json.path("portfolio_status").isNull() && json.path("defaultProbe").isNull(), "cleared values emitted as JSON null");
        var restored = mapper.readValue(wire, ExtendedPortfolio.class);
        check(restored.getStatus() == null && restored.getDefaultProbe() == null, "explicit null survives both profiles");
    }

    private static ImmutableBadge badge(String text) { return ImmutableBadge.newBuilder().withText(text).build(); }

    private static void reject(Class<? extends RuntimeException> type, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException failure) {
            if (type.isInstance(failure)) return;
            throw failure;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
