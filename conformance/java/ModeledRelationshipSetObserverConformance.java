package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.opposites.Endpoint;
import eu.mihosoft.vmf.conformance.values.KeyedAssignment;
import eu.mihosoft.vmf.conformance.values.KeyedInventory;
import eu.mihosoft.vmf.conformance.values.KeyedPart;
import eu.mihosoft.vmf.runtime.core.Changes;
import eu.mihosoft.vmf.runtime.core.RefSet;
import eu.mihosoft.vmf.runtime.core.VObject;
import vjavax.observer.Subscription;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

final class ModeledRelationshipSetObserverConformance {
    private ModeledRelationshipSetObserverConformance() {}

    public static void main(String[] args) throws IOException {
        var expected = new ObjectMapper().readValue(Path.of(args[0]).toFile(),
                new TypeReference<Map<String, List<String>>>() {});
        for (String timing : List.of("late", "early", "dormant")) {
            verify(expected, "ordered-keyed-" + timing, keyed(), timing, true);
        }
        for (boolean opposite : new boolean[]{false, true}) {
            for (String timing : List.of("late", "early", "dormant", "fresh")) {
                verify(expected, "reciprocal-" + (opposite ? "opposite-" : "primary-") + timing,
                        reciprocal(opposite), timing, true);
            }
        }
        for (String timing : List.of("dormant", "fresh")) {
            verify(expected, "reciprocal-primary-only-" + timing, reciprocal(false), timing, false);
        }
        System.out.println("Java verified 13 shared modeled ordered-keyed and reciprocal set relay cases.");
    }

    private static void verify(Map<String, List<String>> expected, String name, Fixture f, String timing,
                               boolean mutableObservers) {
        var trace = new ArrayList<String>();
        Runnable activate = () -> f.watch(true, false, () -> trace.add("Rnew"));
        if (mutableObservers) {
            f.watch(false, false, () -> {
                trace.add("M0");
                if (f.opposite == null && timing.equals("dormant")) activate.run();
            });
        }
        f.projectedMembers.get();
        if (timing.equals("early")) f.watch(true, false, () -> trace.add("R0"));
        if (timing.equals("dormant")) {
            f.watch(true, false, () -> { throw new AssertionError("Disposed listener was reactivated"); })
                    .unsubscribe();
        }
        if (mutableObservers) f.watch(false, true, () -> trace.add("M1"));
        if (timing.equals("late")) f.watch(true, false, () -> trace.add("R0"));
        if (timing.equals("early") || timing.equals("late")) f.watch(true, true, () -> trace.add("R1"));
        if (f.opposite != null) {
            f.target.vmf().changes().addListener(change -> {
                if (!f.opposite.equals(change.propertyName())) return;
                trace.add("opposite");
                if (timing.equals("dormant") || timing.equals("fresh")) activate.run();
            }, false);
        }
        f.edit.run();
        if (!matchesByKind(expected.get(name), trace)) {
            throw new AssertionError(name + ": expected " + expected.get(name) + ", got " + trace);
        }
    }

    private static Fixture keyed() {
        var owner = KeyedInventory.newInstance();
        var key = KeyedPart.newInstance();
        owner.getParts().add(key);
        var target = KeyedAssignment.newBuilder().withPart(key).build();
        var view = owner.asReadOnly();
        return new Fixture(owner, view, "assignments", owner.getAssignments(), view::getAssignments,
                target, target.asReadOnly(), List.of(0), null, () -> {
                    owner.getAssignments().add(target);
                    if (owner.findAssignmentsByPart(key).orElseThrow() != target) {
                        throw new AssertionError("The key did not resolve to the attached assignment");
                    }
                });
    }

    private static Fixture reciprocal(boolean editOpposite) {
        var owner = Endpoint.newInstance();
        var target = Endpoint.newInstance();
        var view = owner.asReadOnly();
        return new Fixture(owner, view, "members", owner.getMembers(), view::getMembers,
                target, target.asReadOnly(), List.of(), "groups", () -> {
                    if (editOpposite) target.getGroups().add(owner);
                    else owner.getMembers().add(target);
                    if (!owner.getMembers().contains(target) || !target.getGroups().contains(owner)) {
                        throw new AssertionError("Reciprocal membership is disconnected");
                    }
                });
    }

    private static final class Fixture {
        final VObject owner;
        final VObject view;
        final String property;
        final RefSet<?> members;
        final Supplier<RefSet<?>> projectedMembers;
        final VObject target;
        final VObject projectedTarget;
        final List<Integer> indices;
        final String opposite;
        final Runnable edit;
        private Changes projectedChanges;

        Fixture(VObject owner, VObject view, String property, RefSet<?> members,
                Supplier<RefSet<?>> projectedMembers, VObject target, VObject projectedTarget,
                List<Integer> indices, String opposite, Runnable edit) {
            this.owner = owner;
            this.view = view;
            this.property = property;
            this.members = members;
            this.projectedMembers = projectedMembers;
            this.target = target;
            this.projectedTarget = projectedTarget;
            this.indices = indices;
            this.opposite = opposite;
            this.edit = edit;
        }

        Subscription watch(boolean projected, boolean recursive, Runnable callback) {
            if (projected && projectedChanges == null) projectedChanges = view.vmf().changes();
            Changes changes = projected ? projectedChanges : owner.vmf().changes();
            return changes.addListener(change -> {
                if (!property.equals(change.propertyName())) return;
                var delta = change.referenceSetChange().orElseThrow();
                if (change.object() != (projected ? view : owner)
                        || delta.source() != (projected ? projectedMembers.get() : members)
                        || delta.added().size() != 1 || delta.added().get(0) != (projected ? projectedTarget : target)
                        || !delta.removed().isEmpty() || !delta.indices().equals(indices)) {
                    throw new AssertionError("Unexpected modeled set edit on " + property + ": " + delta.indices());
                }
                callback.run();
            }, recursive);
        }
    }

    // The portable contract orders the listeners of one object (E7) and an object before its ancestors (E8), not
    // listeners of different kinds: model listeners, listeners added through a read-only view, and raw collection
    // listeners. A label names its kind by its first letter (M0, R1, C0, P0, Rnew); any other label is its own kind.
    // A trace matches when every kind receives its calls in the expected order and no call is missing or repeated.
    private static boolean matchesByKind(List<String> expected, List<String> actual) {
        return expected != null && byKind(expected).equals(byKind(actual));
    }

    private static java.util.Map<String, List<String>> byKind(List<String> trace) {
        var kinds = new java.util.TreeMap<String, List<String>>();
        for (String label : trace) {
            String name = label.contains(":") ? label.substring(0, label.indexOf(':')) : label;
            String kind = name.matches("[A-Z]([0-9]+|new|[a-z]+)") ? name.substring(0, 1) : name;
            kinds.computeIfAbsent(kind, key -> new java.util.ArrayList<>()).add(label);
        }
        return kinds;
    }

}
