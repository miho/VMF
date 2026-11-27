package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.values.ArchiveOwnedLeaf;
import eu.mihosoft.vmf.conformance.values.ArchiveOwner;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.conformance.values.DefaultSettings;
import eu.mihosoft.vmf.conformance.values.ScalarSample;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.util.List;
import java.util.Map;

final class ReflectionDefaultsConformance {
    private ReflectionDefaultsConformance() {
    }

    public static void main(String[] args) {
        checkPrimitiveDefaults();
        var settings = DefaultSettings.newBuilder().withName(null).withLimit(null).build();
        var name = property(settings, "name");
        var limit = property(settings, "limit");
        check("idle\nready".equals(name.getDefault()) && Integer.valueOf(7).equals(limit.getDefault()),
                "Inherited defaults have their declared values");
        check(name.isSet() && limit.isSet(), "Explicit null clears non-null defaults");
        name.unset();
        limit.unset();
        check(!name.isSet() && !limit.isSet() && "idle\nready".equals(settings.getName()) && settings.getLimit() == 7,
                "Reset reflects the native model state");
        check(Double.doubleToRawLongBits((Double) property(settings, "baseline").getDefault()) == Long.MIN_VALUE,
                "Declared default retains the sign bit");

        var collections = CollectionProfile.newInstance();
        collections.getOptions().put("empty", null);
        collections.getLevels().add(-0.0);
        collections.getCounts().add(2);
        check(property(collections, "options").getDefault().equals(Map.of()), "Empty map default");
        for (String field : List.of("options", "levels", "counts")) {
            var member = property(collections, field);
            check(member.isSet(), "Set managed field");
            member.unset();
            check(!member.isSet(), "Reset managed field");
        }

        var owner = ArchiveOwner.newInstance();
        var leaf = ArchiveOwnedLeaf.newBuilder().withLabel("part").build();
        leaf.setOwner(owner);
        var projected = property(leaf.asReadOnly(), "owner");
        check(projected.isSet() && projected.getDefault() == null, "Inverse has a null default");
        try {
            projected.unset();
            throw new AssertionError("Expected a read-only failure");
        } catch (RuntimeException expected) {
            check(leaf.getOwner() == owner, "Readonly reset leaves the owner unchanged");
        }
        property(leaf, "owner").unset();
        check(owner.getNodes().isEmpty() && leaf.getOwner() == null, "Reset detaches through the native inverse");
        System.out.println("Java verified reflected defaults, present values, and reset behavior.");
    }

    private static void checkPrimitiveDefaults() {
        var sample = ScalarSample.newInstance();
        var view = sample.asReadOnly();
        var names = List.of("active", "byteValue", "shortValue", "intValue", "longValue", "charValue",
                "floatValue", "doubleValue");
        var defaults = List.of(false, (byte) 0, (short) 0, 0, 0L, '\0', 0.0f, 0.0d);
        var values = List.of(true, (byte) -8, (short) 13, 17, 23L, 'x', 1.5f, 2.5d);
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            var field = property(sample, name);
            var viewField = property(view, name);
            var expected = defaults.get(i);
            check(expected.equals(field.getDefault()) && expected.equals(viewField.getDefault())
                    && !field.isSet() && !viewField.isSet(), "Typed primitive default for " + name);
            field.set(values.get(i));
            check(field.isSet() && viewField.isSet(), "Changing " + name + " leaves its primitive default");
            field.unset();
            check(expected.equals(field.get()) && !field.isSet() && !viewField.isSet(),
                    "Resetting " + name + " restores its typed primitive default");
        }
    }

    private static Property property(VObject model, String name) {
        return model.vmf().reflect().propertyByName(name).orElseThrow();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
