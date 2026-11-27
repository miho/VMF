package eu.mihosoft.vmf.conformance.referencedefaults;

import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;
import java.util.List;
import java.util.Map;

final class MixedDefaultConformance {
    private MixedDefaultConformance() { }

    static void verify() {
        verifyMutable();
        verifyImmutable();
        System.out.println("Java verified mixed scalar, managed and reference default lifecycles.");
    }

    private static void verifyMutable() {
        MixedDefaultBindings.events.clear();
        var model = MixedDefaultPicks.newInstance();
        calls("caption", "favorites", "numbers", "options");
        var original = model.getFavorites().iterator().next();
        var reference = field(model, "favorites");
        var originalDefault = reference.getDefault();
        check(!reference.isSet() && ((List<?>) originalDefault).get(0) == original,
                "Mutable reference presence uses the captured seed");
        check(((List<?>) field(model.asReadOnly(), "favorites").getDefault()).get(0) == original.asReadOnly(),
                "Read-only reflection exposes the cached alias");
        calls();

        model.setCaption("source");
        field(model, "numbers").setDefault(List.of(13));
        model.getNumbers().clear();
        model.getNumbers().add(9);
        model.getOptions().put("port", null);
        MixedDefaultBindings.events.clear();

        MixedDefaultPicks deep = model.vmf().content().deepCopy();
        calls("caption", "numbers", "options");
        MixedDefaultPicks shallow = model.vmf().content().shallowCopy();
        calls("caption", "numbers", "options");
        var copied = deep.getFavorites().iterator().next();
        check(copied != original && ((List<?>) field(deep, "favorites").getDefault()).get(0) == copied
                        && shallow.getFavorites().iterator().next() == original
                        && field(shallow, "favorites").getDefault() == originalDefault,
                "Deep and shallow copy preserve their usual reference identities");
        for (var copy : List.of(deep, shallow)) {
            check(copy.getCaption().equals("source") && copy.getNumbers().equals(List.of(9))
                            && field(copy, "numbers").getDefault().equals(List.of(13))
                            && copy.getOptions().containsKey("port") && copy.getOptions().get("port") == null,
                    "Copies retain scalar values, managed default overrides and present null map entries");
        }
        calls();

        var builder = MixedDefaultPicks.newBuilder().withCaption(null)
                .withFavorites(List.of()).withNumbers(List.of()).withOptions(Map.of());
        calls("favorites", "caption", "numbers", "options");
        var first = builder.build();
        calls("caption", "numbers", "options");
        var second = builder.build();
        calls("caption", "numbers", "options");
        check(first.getCaption() == null && first.getFavorites().isEmpty() && first.getNumbers().isEmpty()
                        && first.getOptions().isEmpty()
                        && field(first, "favorites").getDefault() == field(second, "favorites").getDefault(),
                "Builder keeps explicit empty values and the captured fallback across builds");

        var staged = MixedDefaultPicks.newBuilder();
        calls("favorites", "caption", "numbers", "options");
        staged.appendCollections(false).applyFrom(first).applyTo(model);
        calls();
        check(model.getCaption() == null && model.getFavorites().isEmpty() && model.getNumbers().isEmpty()
                        && model.getOptions().isEmpty() && reference.isSet() && reference.getDefault() == originalDefault,
                "Applying the draft preserves the target default independently of current values");
        reference.unset();
        check(model.getFavorites().iterator().next() == original, "Reset uses the target identity");
        calls();

        MixedDefaultBindings.duplicateSeed = true;
        try {
            ReferenceDefaultConformance.expect(IllegalArgumentException.class, MixedDefaultPicks::newInstance);
            calls("caption", "favorites");
            ReferenceDefaultConformance.expect(IllegalArgumentException.class, MixedDefaultPicks::newBuilder);
            calls("favorites");
        } finally {
            MixedDefaultBindings.duplicateSeed = false;
        }
    }

    private static void verifyImmutable() {
        var staged = MixedFrozenPicks.newBuilder().withCaption(null).withNumbers(List.of());
        calls("tokens", "caption", "numbers");
        var first = staged.build();
        var second = staged.build();
        calls();
        var original = first.getFavorites().iterator().next();
        check(first.getCaption() == null && first.getNumbers().isEmpty()
                        && second.getFavorites().iterator().next() == original
                        && ((List<?>) field(first, "favorites").getDefault()).get(0) == original
                        && !field(first, "favorites").isSet() && first.asReadOnly() == first
                        && first.vmf().content().deepCopy() == first,
                "Immutable construction retains its cached identity and explicit values");
        calls();
    }

    private static Property field(VObject owner, String name) {
        return owner.vmf().reflect().propertyByName(name).orElseThrow();
    }

    private static void calls(String... expected) {
        check(MixedDefaultBindings.events.equals(List.of(expected)), "Provider order: " + MixedDefaultBindings.events);
        MixedDefaultBindings.events.clear();
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
