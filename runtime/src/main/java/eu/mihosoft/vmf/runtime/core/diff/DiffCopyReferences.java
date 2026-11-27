package eu.mihosoft.vmf.runtime.core.diff;

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VIterator;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.internal.ReferenceCollections;
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternalModifiable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Captures unopposed outbound links that need a live target only when the copy enters the patch. */
final class DiffCopyReferences {
    private final IdentityHashMap<Object, VObject> originals = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Object> counterparts;
    private final Set<VObject> planned = Collections.newSetFromMap(new IdentityHashMap<>());

    DiffCopyReferences(IdentityHashMap<Object, Object> copies, IdentityHashMap<Object, Object> counterparts) {
        this.counterparts = counterparts;
        copies.forEach((original, copy) -> {
            if (copy != original && copy instanceof VObjectInternalModifiable) originals.put(copy, (VObject) original);
        });
    }

    List<Edit> introductions(Object value) {
        var edits = new ArrayList<Edit>();
        if (value instanceof Iterable) {
            for (Object member : (Iterable<?>) value) plan(member, edits);
        } else {
            plan(value, edits);
        }
        return edits;
    }

    private void plan(Object member, List<Edit> edits) {
        if (!originals.containsKey(member)) return;
        VIterator.of((VObject) member, VIterator.IterationStrategy.CONTAINMENT_TREE).forEachRemaining(copy -> {
            VObject original = originals.get(copy);
            if (original == null || !planned.add(copy)) return;
            for (Property field : copy.vmf().reflect().properties()) {
                if (!isOrdinaryReference(field)) continue;
                Object value = original.vmf().reflect().propertyByName(field.getName()).orElseThrow().get();
                if (field.getType().isListType()) {
                    planList(field, (List<?>) value, edits);
                } else {
                    Object target = counterparts.get(value);
                    if (target == null) continue;
                    Object before = field.get();
                    if (before != target) edits.add(new Edit(field, before, target));
                }
            }
        });
    }

    private void planList(Property property, List<?> original, List<Edit> edits) {
        List<?> before = (List<?>) property.get();
        if (original.size() != before.size()) return;

        var after = new ArrayList<Object>(before);
        for (int index = 0; index < original.size(); index++) {
            Object target = counterparts.get(original.get(index));
            if (target != null) after.set(index, target);
        }
        if (!ReferenceCollections.sameMembers(before, after, true)) {
            edits.add(new Edit(property, snapshot(before), snapshot(after)));
        }
    }

    private static VList<?> snapshot(List<?> values) {
        return VList.newInstance(new ArrayList<>(values));
    }

    private static boolean isOrdinaryReference(Property property) {
        return property.getType().isModelType() && !property.getType().isReferenceSetType()
                && property.annotationByKey("vmf:property:containment-info")
                    .map(annotation -> annotation.getValue().equals("none")).orElse(false)
                && property.annotationByKey("vmf:property:reference-opposite")
                    .map(annotation -> annotation.getValue().isEmpty()).orElse(true);
    }

    static final class Edit {
        private final Property property;
        private final Object before;
        private final Object after;

        Edit(Property property, Object before, Object after) {
            this.property = property;
            this.before = before;
            this.after = after;
        }

        void apply() { assign(after); }

        @SuppressWarnings("unchecked")
        private void assign(Object value) {
            if (property.getType().isListType()) {
                // Unbounded ordinary lists expose a managed getter without a reflected setter.
                var list = (VList<Object>) property.get();
                list.clear();
                list.addAll((List<?>) value);
            } else {
                property.set(value);
            }
        }

        void undo() {
            Object current = property.get();
            // A caller may have edited the new branch after apply. Preserve those edits.
            if (after instanceof List
                    ? ReferenceCollections.sameMembers((List<?>) after, (List<?>) current, true)
                    : after == current) {
                assign(before);
            }
        }
    }
}
