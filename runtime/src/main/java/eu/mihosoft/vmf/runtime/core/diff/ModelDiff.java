package eu.mihosoft.vmf.runtime.core.diff;

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vcollections.VListChangeEvent;
import eu.mihosoft.vmf.runtime.core.*;
import eu.mihosoft.vmf.runtime.core.internal.ContainmentList;
import eu.mihosoft.vmf.runtime.core.internal.ManagedMaps;
import eu.mihosoft.vmf.runtime.core.internal.ReferenceCollections;
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternal;
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternalModifiable;

import java.util.*;

/**
 * Utility class that can compute differences between two VMF objects, apply
 * them and merge objects.
 * @deprecated Diffs are bound to their source graph. For replay on an independent
 * graph, use {@link eu.mihosoft.vmf.runtime.core.journal.JournalReplayer}.
 */
@Deprecated
public final class ModelDiff {
    private static final String SOURCE_REQUIRED = "ModelDiff can only apply changes to their recorded source graph. "
            + "Use JournalReplayer for another model.";

    private ModelDiff() {}

    /** Represents a property change used for diffing. */
    public static class PropChange implements Change, PropertyChange {
        private final VObject source;
        private final VObject object;
        private final String propertyName;
        private final Object oldValue;
        private final Object newValue;
        private final long timestamp;
        private List<DiffCopyReferences.Edit> copyReferences = List.of();

        public PropChange(VObject object, String propertyName, Object oldValue, Object newValue) {
            this(object, propertyName, oldValue, newValue, System.nanoTime());
        }

        public PropChange(VObject object, String propertyName, Object oldValue, Object newValue, long timestamp) {
            this(object, object, propertyName, oldValue, newValue, timestamp);
        }

        private PropChange(VObject source, VObject object, String propertyName, Object oldValue, Object newValue,
                           long timestamp) {
            this.source = source;
            this.object = object;
            this.propertyName = propertyName;
            this.oldValue = oldValue;
            this.newValue = newValue;
            this.timestamp = timestamp;
        }

        @Override
        public VObject object() {
            return object;
        }

        @Override
        public String propertyName() {
            return propertyName;
        }

        public Object oldValue() { return oldValue; }
        public Object newValue() { return newValue; }

        @Override
        public void undo() {
            if (!isUndoable()) return;
            assign(oldValue);
            for (int index = copyReferences.size() - 1; index >= 0; index--) copyReferences.get(index).undo();
        }

        @Override
        public void apply(VObject obj) {
            if (obj != object) throw new IllegalArgumentException(SOURCE_REQUIRED);
            copyReferences.forEach(DiffCopyReferences.Edit::apply);
            assign(newValue);
        }

        private void assign(Object value) {
            VObjectInternalModifiable internal = (VObjectInternalModifiable) object;
            int pid = internal._vmf_getPropertyIdByName(propertyName);
            if (value instanceof VList && internal._vmf_getPropertyValueById(pid) instanceof VList) {
                VList<Object> target = (VList<Object>) internal._vmf_getPropertyValueById(pid);
                VList<Object> replacement = (VList<Object>) value;
                if (!ContainmentList.replaceRecordedContents(target, replacement)) {
                    target.clear();
                    target.addAll(replacement);
                }
            } else {
                internal._vmf_setPropertyValueById(pid, value);
            }
        }

        @Override
        public boolean isUndoable() {
            VObjectInternalModifiable internal = (VObjectInternalModifiable) object;
            int pid = internal._vmf_getPropertyIdByName(propertyName);
            Object current = internal._vmf_getPropertyValueById(pid);
            if (newValue instanceof Immutable) return newValue == current;
            if (newValue instanceof VObject) {
                var type = object.vmf().reflect().propertyByName(propertyName).orElseThrow().getType();
                if (type.isModelType() && !type.isListType() && !type.isReferenceSetType()) return newValue == current;
            }
            if (newValue instanceof List && !(newValue instanceof VList)) {
                Property property = object.vmf().reflect().propertyByName(propertyName).orElseThrow();
                if (property.getType().isReferenceSetType()) {
                    return ReferenceCollections.sameMembers((Iterable<?>) newValue, (Iterable<?>) current,
                            property.getMultiplicity().isOrdered());
                }
            }
            if (newValue instanceof VList && current instanceof VList) {
                Property property = object.vmf().reflect().propertyByName(propertyName).orElseThrow();
                return property.getType().isModelType()
                        ? ReferenceCollections.sameMembers((VList<?>) newValue, (VList<?>) current, true)
                        : listsEqual((VList<?>) newValue, (VList<?>) current);
            }
            return Objects.equals(newValue, current);
        }

        @Override
        public Optional<PropertyChange> propertyChange() {
            return Optional.of(this);
        }

        @Override
        public Optional<VListChangeEvent<Object>> listChange() {
            return Optional.empty();
        }

        @Override
        public ChangeType getType() {
            return ChangeType.PROPERTY;
        }

        @Override
        public long getTimestamp() {
            return timestamp;
        }
    }

    /**
     * Computes the list of changes required to transform {@code a} into
     * {@code b}. The changes are bound to the original graph rooted at {@code a}.
     */
    public static List<Change> diff(VObject a, VObject b) {
        List<Change> result = new ArrayList<>();
        if (a == null || b == null) return result;
        VObject desired = ((VObjectInternal) b)._vmf_getMutableObject();
        Comparison comparison = new Comparison(a, desired);
        diffObjects(a, a, desired, result, comparison);
        for (int index = 0; index < comparison.referenceMatches.size(); index++) {
            comparison.referenceMatches.get(index).run();
        }
        comparison.referenceEdits.forEach(Runnable::run);
        List<Change> staged = stageTransfers(result);
        if (!comparison.copies.isEmpty()) {
            var copies = new DiffCopyReferences(comparison.copies, comparison.counterparts);
            for (Change change : staged) {
                var edit = (PropChange) change;
                Property property = edit.object.vmf().reflect().propertyByName(edit.propertyName).orElseThrow();
                if (property.getType().isModelType() || property.getType().isReferenceSetType()) {
                    edit.copyReferences = copies.introductions(edit.newValue);
                }
            }
        }
        return staged;
    }

    private static final class Comparison {
        final Set<VObjectInternal.EqualsPair> visited = new HashSet<>();
        final List<Runnable> referenceMatches = new ArrayList<>();
        final List<Runnable> referenceEdits = new ArrayList<>();
        final IdentityHashMap<Object, Object> counterparts = new IdentityHashMap<>();
        final DiffOwnership ownership;
        // Clone into an isolated graph. Generated opposite setters must never see the source during diff().
        final IdentityHashMap<Object, Object> copies = new IdentityHashMap<>();

        Comparison(VObject source, VObject desired) {
            ownership = new DiffOwnership(source, desired, counterparts);
            counterparts.putIfAbsent(desired, source);
        }

        Object target(Object desired) {
            if (desired == null || desired instanceof Immutable) return desired;
            Object prior = counterparts.get(desired);
            if (prior != null) return prior;
            return copyMember(desired, copies);
        }
    }

    private static void diffObjects(VObject source, VObject a, VObject b, List<Change> out, Comparison comparison) {
        if (a == b || !comparison.visited.add(new VObjectInternal.EqualsPair(a, b))) return;
        Reflect ra = a.vmf().reflect();
        Reflect rb = b.vmf().reflect();
        for (Property pa : ra.properties()) {
            Optional<Property> pbOpt = rb.propertyByName(pa.getName());
            if (pbOpt.isEmpty() || isContainer(a, pa)) continue;
            Property pb = pbOpt.get();

            Object va = pa.get();
            Object vb = pb.get();
            boolean owning = isContainment(a, pa);

            if (pa.getType().isReferenceSetType()) {
                if (!pa.getType().getDescriptor().equals(pb.getType().getDescriptor())) {
                    throw new IllegalArgumentException("Incompatible reference collection " + pa.getName());
                }
                if (owning) diffMatchedMembers(source, pa, (RefSet<?>) vb, out, comparison);
                // Preserve the established ordering of set edits, including owning sets.
                comparison.referenceEdits.add(() -> {
                    List<Object> previous = new ArrayList<>();
                    ((RefSet<?>) va).forEach(previous::add);
                    List<Object> next = new ArrayList<>();
                    ((RefSet<?>) vb).forEach(child -> next.add(comparison.target(child)));
                    if (!ReferenceCollections.sameMembers(previous, next, pa.getMultiplicity().isOrdered())) {
                        out.add(new PropChange(source, a, pa.getName(), List.copyOf(previous),
                                List.copyOf(next), System.nanoTime()));
                    }
                });
            } else if (pa.getType().isMapType()) {
                if (!pa.getType().getDescriptor().equals(pb.getType().getDescriptor())) {
                    throw new IllegalArgumentException("Incompatible managed map property " + pa.getName());
                }
                if (!Objects.equals(va, vb)) {
                    out.add(new PropChange(source, a, pa.getName(),
                            ManagedMaps.snapshot((Map<?, ?>) va, Object.class, Object.class),
                            ManagedMaps.snapshot((Map<?, ?>) vb, Object.class, Object.class), System.nanoTime()));
                }
            } else if (pa.getType().isSetType()) {
                if (!pa.getType().getDescriptor().equals(pb.getType().getDescriptor())) {
                    throw new IllegalArgumentException("Incompatible managed set property " + pa.getName());
                }
                if (!Objects.equals(va, vb)) {
                    out.add(new PropChange(source, a, pa.getName(), Set.copyOf((Set<?>) va),
                            Set.copyOf((Set<?>) vb), System.nanoTime()));
                }
            } else if (pa.getType().isListType()) {
                VList<?> previous = (VList<?>) va;
                VList<?> next = (VList<?>) vb;
                if (pa.getType().isModelType()) {
                    Runnable edit = () -> {
                        VList<Object> mapped = VList.newInstance(new ArrayList<>());
                        for (Object member : next) mapped.add(comparison.target(member));
                        if (!ReferenceCollections.sameMembers(previous, mapped, true)) {
                            out.add(new PropChange(source, a, pa.getName(), snapshotList(previous), mapped, System.nanoTime()));
                        }
                    };
                    if (owning) {
                        diffMatchedMembers(source, pa, next, out, comparison);
                        edit.run();
                    } else {
                        comparison.referenceEdits.add(edit);
                    }
                } else if (!listsEqual(previous, next)) {
                    out.add(new PropChange(source, a, pa.getName(), cloneList(previous), cloneList(next), System.nanoTime()));
                }
            } else if (pa.getType().isModelType()) {
                if (owning) {
                    Object next = comparison.target(vb);
                    if (va != next) out.add(new PropChange(source, a, pa.getName(), va, next, System.nanoTime()));
                    else if (va instanceof VObject && !(va instanceof Immutable)) {
                        diffObjects(source, (VObject) va, (VObject) vb, out, comparison);
                    }
                } else if (va != vb) {
                    comparison.referenceMatches.add(() -> matchReference(source, va, vb, out, comparison));
                    comparison.referenceEdits.add(() -> {
                        Object next = comparison.target(vb);
                        if (va != next) out.add(new PropChange(source, a, pa.getName(), va, next, System.nanoTime()));
                    });
                }
            } else if (!Objects.equals(va, vb)) {
                out.add(new PropChange(source, a, pa.getName(), va, vb, System.nanoTime()));
            }
        }
    }

    private static void diffMatchedMembers(VObject source, Property property, Iterable<?> desired,
                                         List<Change> out, Comparison comparison) {
        boolean orderedSet = property.getType().isReferenceSetType() && property.getMultiplicity().isOrdered();
        if (!orderedSet && property.annotationByKey("vmf:collection:key").isEmpty()) return;
        // Content equality selects a counterpart. It can ignore fields that still belong in a patch.
        for (Object child : desired) {
            Object paired = comparison.counterparts.get(child);
            if (paired instanceof VObject) diffObjects(source, (VObject) paired, (VObject) child, out, comparison);
        }
    }

    private static void matchReference(VObject source, Object before, Object desired,
                                       List<Change> out, Comparison comparison) {
        // Owned objects are matched solely by their ownership slots. Ordinary links only use that result.
        if (before instanceof VObject && desired instanceof VObject
                && !(before instanceof Immutable) && !(desired instanceof Immutable)
                && !comparison.ownership.ownsSource(before) && !comparison.ownership.ownsDesired(desired)
                && comparison.ownership.pairDetached((VObject) before, (VObject) desired)) {
            diffObjects(source, (VObject) before, (VObject) desired, out, comparison);
        }
    }

    private static VList<?> snapshotList(List<?> members) {
        return VList.newInstance(new ArrayList<>(members));
    }

    private static Object copyMember(Object child, IdentityHashMap<Object,Object> counterparts) {
        if (child == null) return null;
        Object match = counterparts.get(child);
        if (match != null) return match;
        Object copy = ((VObjectInternal) child)._vmf_deepCopy(counterparts);
        counterparts.put(child, copy);
        return copy;
    }

    private static boolean isContainment(VObject owner, Property property) {
        var internal = (VObjectInternal) owner;
        int id = internal._vmf_getPropertyIdByName(property.getName());
        for (int child : internal._vmf_getChildrenIndices()) if (child == id) return true;
        return false;
    }

    private static boolean isContainer(VObject owner, Property property) {
        var internal = (VObjectInternal) owner;
        int id = internal._vmf_getPropertyIdByName(property.getName());
        for (int parent : internal._vmf_getParentIndices()) if (parent == id) return true;
        return false;
    }

    private static List<Change> stageTransfers(List<Change> changes) {
        Set<PropChange> owning = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Object, PropChange> destinations = new IdentityHashMap<>();
        for (Change change : changes) {
            var edit = (PropChange) change;
            Property property = edit.object.vmf().reflect().propertyByName(edit.propertyName).orElseThrow();
            if (!isContainment(edit.object, property)) continue;
            owning.add(edit);
            for (Object member : ownershipMembers(edit.newValue)) destinations.put(member, edit);
        }

        var removals = new ArrayList<Change>();
        var edits = new ArrayList<Change>();
        for (Change change : changes) {
            var edit = (PropChange) change;
            if (!owning.contains(edit)) {
                edits.add(edit);
                continue;
            }

            List<?> before = ownershipMembers(edit.oldValue);
            List<Object> retained = new ArrayList<>();
            for (Object member : before) {
                var destination = destinations.get(member);
                if (destination == null || destination == edit) retained.add(member);
            }
            if (retained.size() == before.size()) {
                edits.add(edit);
                continue;
            }

            // An explicit detach makes applying and reversing a swap independent of field order.
            Object intermediate = edit.oldValue instanceof VList ? snapshotList(retained)
                    : edit.oldValue instanceof List ? List.copyOf(retained) : null;
            removals.add(new PropChange(edit.source, edit.object, edit.propertyName, edit.oldValue, intermediate, edit.timestamp));
            if (!ReferenceCollections.sameMembers(retained, ownershipMembers(edit.newValue), true)) {
                edits.add(new PropChange(edit.source, edit.object, edit.propertyName, intermediate, edit.newValue, edit.timestamp));
            }
        }
        if (removals.isEmpty()) return changes;
        removals.addAll(edits);
        return removals;
    }

    private static List<?> ownershipMembers(Object value) {
        if (value instanceof List) return (List<?>) value;
        return value == null ? List.of() : List.of(value);
    }

    private static boolean listsEqual(VList<?> a, VList<?> b) {
        if(a == null && b == null) return true;
        if(a == null || b == null) return false;
        if(a.size() != b.size()) return false;
        for(int i=0;i<a.size();i++) {
            Object va = a.get(i);
            Object vb = b.get(i);
            if(!Objects.equals(va, vb)) return false;
        }
        return true;
    }

    private static Object cloneValue(Object v) {
        if(v == null) return null;
        if(v instanceof VObject) {
            return ((VObject) v).clone();
        }
        if(v instanceof VList) {
            return cloneList((VList<?>) v);
        }
        return v;
    }

    private static VList<?> cloneList(VList<?> list) {
        if(list == null) return null;
        VList<Object> result = VList.newInstance(new ArrayList<>());
        for(Object o : list) {
            result.add(cloneValue(o));
        }
        return result;
    }

    /**
     * Applies the diff in place to the exact source root used to create it.
     * All affected objects must still be reachable from that root. Standalone
     * changes are accepted when their affected object is the supplied target.
     * @throws IllegalArgumentException if a change belongs to a different source graph
     */
    public static void apply(VObject target, List<Change> diff) {
        List<Change> changes = List.copyOf(diff);
        Set<VObject> affected = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Change change : changes) {
            VObject source = change instanceof PropChange ? ((PropChange) change).source : change.object();
            if (source != target) throw new IllegalArgumentException(SOURCE_REQUIRED);
            affected.add(change.object());
        }

        affected.remove(target);
        if (!affected.isEmpty()) {
            Iterator<VObject> nodes = target.vmf().content().iterator(VIterator.IterationStrategy.UNIQUE_NODE);
            while (!affected.isEmpty() && nodes.hasNext()) affected.remove(nodes.next());
            if (!affected.isEmpty()) throw new IllegalArgumentException(SOURCE_REQUIRED);
        }

        for (Change change : changes) change.apply(change.object());
    }

    /**
     * Merges two VMF objects. The override object's set properties replace those
     * of the template. List properties are appended. A read-only view of a
     * mutable template yields a read-only view of the merged copy. Copied model
     * values retain their aliases across fields. For roots of the same concrete
     * type, references to the override's root point to the merged copy.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T extends VObject> T merge(T template, T override) {
        if (template == null) return override == null ? null : (T) override.clone();
        if (override == null) return (T) template.clone();

        VObject result = template.clone();
        // Apply the overlay to the copied model while retaining the clone's public projection.
        VObject mergeTarget = result instanceof VObjectInternal
                ? ((VObjectInternal) result)._vmf_getMutableObject() : result;

        IdentityHashMap<Object, Object> copies = new IdentityHashMap<>();
        VObject overlayIdentity = ((VObjectInternal) override)._vmf_getMutableObject();
        boolean rebindRoot = mergeTarget instanceof VObjectInternalModifiable
                && mergeTarget.getClass() == overlayIdentity.getClass();
        var owner = (VObjectInternal) overlayIdentity;
        if (rebindRoot) copies.put(overlayIdentity, mergeTarget);
        for (int id : owner._vmf_getChildrenIndices()) {
            Object children = owner._vmf_getPropertyValueById(id);
            if (children instanceof List) {
                for (Object child : (List<?>) children) copyMergeValue(child, copies);
            } else {
                copyMergeValue(children, copies);
            }
        }
        override.vmf().reflect().properties().forEach(p -> {
            Optional<Property> targetPropOpt = mergeTarget.vmf().reflect().propertyByName(p.getName());
            if (!targetPropOpt.isPresent()) {
                return;
            }
            Property targetProp = targetPropOpt.get();

            if (p.getType().isReferenceSetType()) {
                RefSet<VObject> target = (RefSet<VObject>) targetProp.get();
                for (Object child : (RefSet<?>) p.get()) {
                    target.add((VObject) copyMergeValue(child, copies));
                }
            } else if (!p.getType().isListType()) {
                if (p.isSet()) {
                    targetProp.set(copyMergeValue(p.get(), copies));
                }
            } else {
                List src = (List) p.get();
                if (src == null) {
                    return;
                }
                List tgtList = (List) targetProp.get();
                for(Object item : src) {
                    tgtList.add(copyMergeValue(item, copies));
                }
            }
        });

        return (T) result;
    }

    private static Object copyMergeValue(Object value, IdentityHashMap<Object, Object> copies) {
        // Generated projections share the identity of their mutable backing model.
        if (value instanceof VObjectInternal) {
            return copyMember(((VObjectInternal) value)._vmf_getMutableObject(), copies);
        }
        return value instanceof VObject ? ((VObject) value).clone() : value;
    }
}
