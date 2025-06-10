package eu.mihosoft.vmf.runtime.core.diff;

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vcollections.VListChangeEvent;
import eu.mihosoft.vmf.runtime.core.*;
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternalModifiable;

import java.util.*;

/**
 * Utility class that can compute differences between two VMF objects, apply
 * them and merge objects.
 */
@Deprecated
public final class ModelDiff {
    private ModelDiff() {}

    /** Represents a property change used for diffing. */
    public static class PropChange implements Change, PropertyChange {
        private final VObject object;
        private final String propertyName;
        private final Object oldValue;
        private final Object newValue;
        private final long timestamp;

        public PropChange(VObject object, String propertyName, Object oldValue, Object newValue) {
            this(object, propertyName, oldValue, newValue, System.nanoTime());
        }

        public PropChange(VObject object, String propertyName, Object oldValue, Object newValue, long timestamp) {
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
            VObjectInternalModifiable internal = (VObjectInternalModifiable) object;
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
     * {@code b}.
     */
    public static List<Change> diff(VObject a, VObject b) {
        List<Change> result = new ArrayList<>();
        if(a == null || b == null) return result;
        diffObjects(a, b, result);
        return result;
    }

    private static void diffObjects(VObject a, VObject b, List<Change> out) {
        Reflect ra = a.vmf().reflect();
        Reflect rb = b.vmf().reflect();
        for(Property pa : ra.properties()) {
            Optional<Property> pbOpt = rb.propertyByName(pa.getName());
            if(pbOpt.isEmpty()) continue;
            Property pb = pbOpt.get();

            Object va = pa.get();
            Object vb = pb.get();

            if(!pa.getType().isListType()) {
                if(pa.getType().isModelType()) {
                    if(va == null && vb == null) {
                        continue;
                    } else if(va == null || vb == null) {
                        out.add(new PropChange(a, pa.getName(), va, cloneValue(vb)));
                    } else {
                        diffObjects((VObject) va, (VObject) vb, out);
                    }
                } else {
                    if(!Objects.equals(va, vb)) {
                        out.add(new PropChange(a, pa.getName(), va, vb));
                    }
                }
            } else {
                VList<?> la = (VList<?>) va;
                VList<?> lb = (VList<?>) vb;
                if(!listsEqual(la, lb)) {
                    out.add(new PropChange(a, pa.getName(), cloneList(la), cloneList(lb)));
                }
            }
        }
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

    /** Applies the specified diff to the given target object. */
    public static void apply(VObject target, List<Change> diff) {
        for(Change c : diff) {
            c.apply(target);
        }
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

        override.vmf().reflect().properties().forEach(p -> {
            Optional<Property> targetPropOpt = result.vmf().reflect().propertyByName(p.getName());
            if (!targetPropOpt.isPresent()) {
                return;
            }
            Property targetProp = targetPropOpt.get();

            if (!p.getType().isListType()) {
                if (p.isSet()) {
                    Object val = p.get();
                    if (val instanceof VObject) {
                        targetProp.set(((VObject) val).clone());
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
                        tgtList.add(((VObject) item).clone());
                    } else {
                        tgtList.add(item);
                    }
                }
            }
        });

        return (T) result;
    }
}