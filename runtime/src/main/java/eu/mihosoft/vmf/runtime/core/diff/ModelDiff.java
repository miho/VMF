package eu.mihosoft.vmf.runtime.core.diff;

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vcollections.VListChangeEvent;
import eu.mihosoft.vmf.runtime.core.*;
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
            if(!isUndoable()) return;
            VObjectInternalModifiable internal = (VObjectInternalModifiable) object;
            int pid = internal._vmf_getPropertyIdByName(propertyName);
            if(oldValue instanceof VList && internal._vmf_getPropertyValueById(pid) instanceof VList) {
                VList<Object> target = (VList<Object>) internal._vmf_getPropertyValueById(pid);
                target.clear();
                target.addAll((VList<Object>) oldValue);
            } else {
                internal._vmf_setPropertyValueById(pid, oldValue);
            }
        }

        @Override
        public void apply(VObject obj) {
            if (obj != object) throw new IllegalArgumentException(SOURCE_REQUIRED);
            VObjectInternalModifiable internal = (VObjectInternalModifiable) obj;
            int pid = internal._vmf_getPropertyIdByName(propertyName);
            if(newValue instanceof VList && internal._vmf_getPropertyValueById(pid) instanceof VList) {
                VList<Object> target = (VList<Object>) internal._vmf_getPropertyValueById(pid);
                target.clear();
                target.addAll((VList<Object>) newValue);
            } else {
                internal._vmf_setPropertyValueById(pid, newValue);
            }
        }

        @Override
        public boolean isUndoable() {
            VObjectInternalModifiable internal = (VObjectInternalModifiable) object;
            int pid = internal._vmf_getPropertyIdByName(propertyName);
            Object current = internal._vmf_getPropertyValueById(pid);
            if (newValue instanceof List && !(newValue instanceof VList)) {
                Property property = object.vmf().reflect().propertyByName(propertyName).orElseThrow();
                if (property.getType().isReferenceSetType()) {
                    return ReferenceCollections.sameMembers((Iterable<?>) newValue, (Iterable<?>) current,
                            property.getMultiplicity().isOrdered());
                }
            }
            if(newValue instanceof VList && current instanceof VList) {
                return listsEqual((VList<?>) newValue, (VList<?>) current);
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
        if(a == null || b == null) return result;
        Comparison comparison = new Comparison();
        diffObjects(a, a, b, result, comparison);
        comparison.referenceEdits.forEach(Runnable::run);
        return result;
    }

    private static final class Comparison {
        final Set<VObjectInternal.EqualsPair> visited = new HashSet<>();
        final List<Runnable> referenceEdits = new ArrayList<>();
        final IdentityHashMap<Object,Object> counterparts = new IdentityHashMap<>();
        // Clone into an isolated graph. Its generated opposite setters must never see the source graph during diff().
        final IdentityHashMap<Object,Object> copies = new IdentityHashMap<>();
    }

    private static void diffObjects(VObject source, VObject a, VObject b, List<Change> out, Comparison comparison) {
        if (!comparison.visited.add(new VObjectInternal.EqualsPair(a,b))) return;
        comparison.counterparts.putIfAbsent(b, a);
        Reflect ra = a.vmf().reflect();
        Reflect rb = b.vmf().reflect();
        for(Property pa : ra.properties()) {
            Optional<Property> pbOpt = rb.propertyByName(pa.getName());
            if(pbOpt.isEmpty()) continue;
            Property pb = pbOpt.get();

            Object va = pa.get();
            Object vb = pb.get();

            if (pa.getType().isReferenceSetType()) {
                if (!pa.getType().getDescriptor().equals(pb.getType().getDescriptor())) {
                    throw new IllegalArgumentException("Incompatible reference collection " + pa.getName());
                }
                List<Object> previous = new ArrayList<>();
                ((RefSet<?>) va).forEach(previous::add);
                var desired = (RefSet<?>) vb;
                if (isContainment(a, pa)) {
                    matchContainedMembers(previous, desired, comparison);
                }
                // Relationships may precede their targets' containment properties in reflection order.
                comparison.referenceEdits.add(() -> {
                    List<Object> next = new ArrayList<>(desired.size());
                    desired.forEach(child -> next.add(comparison.counterparts.getOrDefault(child, child)));
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
                    // Generated setters update this map in place. The diff must own both snapshots.
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
            } else if(!pa.getType().isListType()) {
                if(pa.getType().isModelType()) {
                    if(va == null && vb == null) {
                        continue;
                    } else if(va == null || vb == null) {
                        Object next = isContainment(a, pa) ? copyMember(vb, comparison.copies) : cloneValue(vb);
                        if (isContainment(a, pa)) rememberChildren(vb, next, comparison.counterparts);
                        out.add(new PropChange(source, a, pa.getName(), va, next, System.nanoTime()));
                    } else {
                        diffObjects(source, (VObject) va, (VObject) vb, out, comparison);
                    }
                } else {
                    if(!Objects.equals(va, vb)) {
                        out.add(new PropChange(source, a, pa.getName(), va, vb, System.nanoTime()));
                    }
                }
            } else {
                VList<?> la = (VList<?>) va;
                VList<?> lb = (VList<?>) vb;
                VList<?> next = la;
                if(!listsEqual(la, lb)) {
                    next = pa.getType().isModelType() && isContainment(a, pa)
                            ? copyMembers(lb, comparison.copies) : cloneList(lb);
                    out.add(new PropChange(source, a, pa.getName(), cloneList(la), next, System.nanoTime()));
                }
                if (pa.getType().isModelType() && isContainment(a, pa)) {
                    rememberChildren(lb, next, comparison.counterparts);
                }
            }
        }
    }

    private static void matchContainedMembers(List<?> previous, RefSet<?> desired, Comparison comparison) {
        // Reserve exact matches first, then pair equal-content children one to one across graphs.
        var available = new ArrayList<>(previous);
        available.removeIf(desired::contains);
        Set<Object> original = Collections.newSetFromMap(new IdentityHashMap<>());
        original.addAll(previous);
        for (VObject child : desired) {
            if (original.contains(child)) {
                rememberChildren(child, child, comparison.counterparts);
                continue;
            }
            int i = 0;
            while (i < available.size() && !child.vmf().content().equals(available.get(i))) i++;
            if (i < available.size()) rememberChildren(child, available.remove(i), comparison.counterparts);
        }
        for (VObject child : desired) {
            if (!comparison.counterparts.containsKey(child)) {
                rememberChildren(child, copyMember(child, comparison.copies), comparison.counterparts);
            }
        }
    }

    private static Object copyMember(Object child, IdentityHashMap<Object,Object> counterparts) {
        if (child == null) return null;
        Object match = counterparts.get(child);
        if (match != null) return match;
        Object copy = ((VObjectInternal) child)._vmf_deepCopy(counterparts);
        counterparts.put(child, copy);
        return copy;
    }

    private static VList<?> copyMembers(List<?> source, IdentityHashMap<Object,Object> counterparts) {
        VList<Object> result = VList.newInstance(new ArrayList<>());
        for (Object member : source) result.add(copyMember(member, counterparts));
        return result;
    }

    /** Remember contained descendants so a separate reference can point back into a copied subtree. */
    private static void rememberChildren(Object from, Object to, Map<Object,Object> counterparts) {
        if (from instanceof List && to instanceof List) {
            List<?> src = (List<?>) from, dst = (List<?>) to;
            for (int i = 0; i < src.size(); i++) rememberChildren(src.get(i), dst.get(i), counterparts);
        } else if (from instanceof VObjectInternal && to instanceof VObjectInternal) {
            if (counterparts.put(from, to) == to) return;
            var src = (VObjectInternal) from;
            var dst = (VObjectInternal) to;
            for (int id : src._vmf_getChildrenIndices()) {
                rememberChildren(src._vmf_getPropertyValueById(id), dst._vmf_getPropertyValueById(id), counterparts);
            }
        }
    }

    private static boolean isContainment(VObject owner, Property property) {
        var internal = (VObjectInternal) owner;
        int id = internal._vmf_getPropertyIdByName(property.getName());
        for (int child : internal._vmf_getChildrenIndices()) if (child == id) return true;
        return false;
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
     * of the template. List properties are appended.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T extends VObject> T merge(T template, T override) {
        if (template == null) return override == null ? null : (T) override.clone();
        if (override == null) return (T) template.clone();

        VObject result = template.clone();

        IdentityHashMap<Object, Object> containedCopies = new IdentityHashMap<>();
        var owner = (VObjectInternal) override;
        for (int id : owner._vmf_getChildrenIndices()) {
            Object children = owner._vmf_getPropertyValueById(id);
            if (children instanceof List) {
                for (Object child : (List<?>) children) copyMember(child, containedCopies);
            } else {
                copyMember(children, containedCopies);
            }
        }
        override.vmf().reflect().properties().forEach(p -> {
            Optional<Property> targetPropOpt = result.vmf().reflect().propertyByName(p.getName());
            if (!targetPropOpt.isPresent()) {
                return;
            }
            Property targetProp = targetPropOpt.get();

            if (p.getType().isReferenceSetType()) {
                RefSet<VObject> target = (RefSet<VObject>) targetProp.get();
                for (Object child : (RefSet<?>) p.get()) target.add((VObject) containedCopies.getOrDefault(child, child));
            } else if (!p.getType().isListType()) {
                if (p.isSet()) {
                    Object val = p.get();
                    if (val instanceof VObject) {
                        targetProp.set(containedCopies.containsKey(val) ? containedCopies.get(val) : ((VObject) val).clone());
                    } else {
                        targetProp.set(val);
                    }
                }
            } else {
                List src = (List) p.get();
                if (src == null) {
                    return;
                }
                List tgtList = (List) targetProp.get();
                for(Object item : src) {
                    if (item instanceof VObject) {
                        tgtList.add(containedCopies.containsKey(item) ? containedCopies.get(item) : ((VObject) item).clone());
                    } else {
                        tgtList.add(item);
                    }
                }
            }
        });

        return (T) result;
    }
}
