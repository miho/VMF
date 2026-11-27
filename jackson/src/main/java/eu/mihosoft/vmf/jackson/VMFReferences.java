package eu.mihosoft.vmf.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.runtime.core.Annotation;
import eu.mihosoft.vmf.runtime.core.Builder;
import eu.mihosoft.vmf.runtime.core.Immutable;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.internal.ChangeInternal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Non-containment references for {@link VMFJacksonModule#withReferences(boolean)}.
 *
 * <p>A reference property is a property with a model type (or a list of a model type) that neither
 * contains its value nor is the container of its object: a {@code @Refers} property (with opposite)
 * or a plain model-typed property. Writing starts a session at the outermost VMF object: every
 * object in its containment tree that is the target of a reference gets an id ({@code @vmf-id},
 * {@code vmf-id} in XML), and references are written as these ids. Reading collects the ids and
 * the references while the containment tree is built and assigns the references when the
 * outermost VMF object is complete, so forward references and references to ancestors resolve.
 * An immutable target shared by multiple properties or held by a unique reference end is
 * defined inline once with an id. A single-use ordinary immutable value stays inline.</p>
 *
 * <p>Sessions are thread-local because the module writes and reads nested values through Jackson,
 * which may use a new serializer provider or deserialization context per nested value.</p>
 */
final class VMFReferences {

    /** Field that holds the id of a referenced object. */
    static final String ID_FIELD = "@vmf-id";
    /** Field that holds the id of a referenced object in XML. */
    static final String ID_FIELD_XML = "vmf-id";

    private static final String CONTAINMENT_INFO = "vmf:property:containment-info";

    private static final ThreadLocal<WriteSession> WRITE_SESSION = new ThreadLocal<>();
    private static final ThreadLocal<ReadSession> READ_SESSION = new ThreadLocal<>();

    private static final ClassValue<Map<String, ReferenceProperty>> REFERENCE_PROPERTIES =
            new ClassValue<>() {
                @Override
                protected Map<String, ReferenceProperty> computeValue(Class<?> modelClass) {
                    return findReferenceProperties(modelClass);
                }
            };

    private VMFReferences() {
        throw new AssertionError("Don't instantiate me");
    }

    // -------------------------------------------------------------------------------------------
    // property classification
    // -------------------------------------------------------------------------------------------

    /**
     * Indicates whether the specified property is a non-containment reference (single or list).
     * @param p the property to check
     * @return {@code true} if the property has a model type and is neither contained nor a container
     */
    static boolean isReferenceProperty(Property p) {
        if (!p.getType().isModelType()) {
            return false;
        }
        Optional<Annotation> info = p.annotationByKey(CONTAINMENT_INFO);
        return info.isPresent() && "none".equals(info.get().getValue());
    }

    /**
     * Mutable reference targets are written as ids of objects in the containment tree.
     */
    static boolean isWrittenAsReference(Object value) {
        return value instanceof VObject && !(value instanceof Immutable);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> valuesOf(Object value) {
        if (value == null) {
            return Collections.emptyList();
        } else if (value instanceof List) {
            return (List<Object>) value;
        } else if (value instanceof eu.mihosoft.vmf.runtime.core.RefSet) {
            List<Object> members = new ArrayList<>();
            ((Iterable<?>) value).forEach(members::add);
            return members;
        } else {
            return Collections.singletonList(value);
        }
    }

    private static String typeName(VObject o) {
        return o.vmf().reflect().type().getName();
    }

    // -------------------------------------------------------------------------------------------
    // writing
    // -------------------------------------------------------------------------------------------

    /**
     * Document-scoped ids for mutable and immutable reference targets.
     */
    static final class WriteSession {
        private final Map<Object, String> ids = new IdentityHashMap<>();
        private final Set<Object> writtenImmutables = Collections.newSetFromMap(new IdentityHashMap<>());

        boolean alreadyWroteImmutable(VObject value) {
            return value instanceof Immutable && ids.containsKey(value) && !writtenImmutables.add(value);
        }

        /**
         * Returns the id of the specified object or {@code null} if it is not referenced.
         */
        String idOf(Object o) {
            return ids.get(o);
        }
    }

    /**
     * Returns the write session of the current thread, or {@code null} if no session is active.
     */
    static WriteSession currentWriteSession() {
        return WRITE_SESSION.get();
    }

    /**
     * Starts a write session for the outermost object being written. Walks its containment tree,
     * checks that mutable references point into that tree and assigns ids to their targets.
     * Shared immutable values and immutable reference-set members receive ids after those targets.
     * @param root the outermost VMF object being written
     * @param gen the generator (for error reporting)
     * @return the new session
     * @throws JsonMappingException if a reference points to an object outside the containment tree
     */
    static WriteSession startWriteSession(VObject root, JsonGenerator gen) throws JsonMappingException {

        // containment tree in pre-order (the order in which the objects are written)
        List<VObject> preOrder = new ArrayList<>();
        Set<Object> tree = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<VObject> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            VObject o = stack.pop();
            if (!tree.add(o)) {
                continue;
            }
            preOrder.add(o);
            List<VObject> children = new ArrayList<>();
            for (Property p : o.vmf().reflect().properties()) {
                if (!VMFTypeUtils.isParentOfPropContainer(p)) {
                    continue;
                }
                for (Object child : valuesOf(p.get())) {
                    if (child instanceof VObject) {
                        children.add((VObject) child);
                    }
                }
            }
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }

        // referenced objects (must be part of the tree)
        Set<Object> targets = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> seenImmutable = Collections.newSetFromMap(new IdentityHashMap<>());
        List<VObject> immutableOrder = new ArrayList<>();
        if (root instanceof Immutable) seenImmutable.add(root);
        // Inspect an immutable's outgoing edges just once even when several properties share it.
        List<VObject> owners = new ArrayList<>(preOrder);
        for (int i = 0; i < owners.size(); i++) {
            VObject o = owners.get(i);
            for (Property p : o.vmf().reflect().properties()) {
                if (!isReferenceProperty(p)) {
                    continue;
                }
                for (Object target : valuesOf(p.get())) {
                    if (target instanceof Immutable) {
                        if (seenImmutable.add(target)) {
                            immutableOrder.add((VObject) target);
                            owners.add((VObject) target);
                        } else {
                            targets.add(target);
                        }
                        if (p.getType().isReferenceSetType()) targets.add(target);
                        continue;
                    }
                    if (!isWrittenAsReference(target)) continue;
                    if (!tree.contains(target)) {
                        throw JsonMappingException.from(gen, "Cannot write reference '" + typeName(o) + "."
                                + p.getName() + "': the referenced " + typeName((VObject) target)
                                + " is not contained in the " + typeName(root) + " that is written. With references"
                                + " enabled, every referenced object must be part of the containment tree of the"
                                + " outermost object being written.");
                    }
                    targets.add(target);
                }
            }
        }

        WriteSession session = new WriteSession();
        int nextId = 1;
        for (VObject o : preOrder) {
            if (targets.contains(o)) {
                session.ids.put(o, Integer.toString(nextId++));
            }
        }

        // Allocate immutable ids after the containment ids used by existing documents.
        for (VObject value : immutableOrder) {
            if (targets.contains(value)) session.ids.put(value, Integer.toString(nextId++));
        }
        WRITE_SESSION.set(session);
        return session;
    }

    /**
     * Ends the write session of the current thread.
     */
    static void endWriteSession() {
        WRITE_SESSION.remove();
    }

    /**
     * Converts mutable targets to their ids. The model serializer writes an immutable's
     * first definition or its id at the point where Jackson visits the value.
     * @param session the active write session
     * @param owner the object that holds the property
     * @param p the reference property
     * @param value the value of the property (single object or list)
     * @param gen the generator (for error reporting)
     * @return the id (single reference), the value itself (immutable value) or a list of ids and
     * immutable values (list property)
     * @throws JsonMappingException if a referenced object has no id (it is not part of the tree)
     */
    static Object referenceValue(WriteSession session, VObject owner, Property p, Object value,
                                 JsonGenerator gen) throws JsonMappingException {
        if (value instanceof List || p.getType().isReferenceSetType()) {
            List<Object> result = new ArrayList<>();
            for (Object e : (Iterable<?>) value) {
                result.add(isWrittenAsReference(e) ? idOrFail(session, owner, p, e, gen) : e);
            }
            return result;
        } else if (isWrittenAsReference(value)) {
            return idOrFail(session, owner, p, value, gen);
        } else {
            return value;
        }
    }

    private static String idOrFail(WriteSession session, VObject owner, Property p, Object target,
                                   JsonGenerator gen) throws JsonMappingException {
        String id = session.idOf(target);
        if (id == null) {
            throw JsonMappingException.from(gen, "Cannot write reference '" + typeName(owner) + "."
                    + p.getName() + "': the " + typeName(owner) + " is not part of the containment tree of"
                    + " the outermost object being written, so its references cannot be resolved.");
        }
        return id;
    }

    // -------------------------------------------------------------------------------------------
    // reading
    // -------------------------------------------------------------------------------------------

    /**
     * A reference property of a model class, as seen by the reader.
     */
    static final class ReferenceProperty {
        /** name of the VMF property */
        final String name;
        /** name of the JSON field (after {@code vmf:jackson:rename}) */
        final String fieldName;
        /** whether the property is a list */
        final boolean list;
        final boolean uniqueReferences;
        final boolean hasDeclaredDefault;
        /** property type (single) or element type (list), for values written by value */
        final Class<?> valueClass;

        ReferenceProperty(String name, String fieldName, boolean list, boolean uniqueReferences,
                          boolean hasDeclaredDefault, Class<?> valueClass) {
            this.name = name;
            this.fieldName = fieldName;
            this.list = list;
            this.uniqueReferences = uniqueReferences;
            this.hasDeclaredDefault = hasDeclaredDefault;
            this.valueClass = valueClass;
        }
    }

    /**
     * Returns the reference properties of the specified model class, by JSON field name.
     */
    static Map<String, ReferenceProperty> referenceProperties(Class<?> modelClass) {
        return REFERENCE_PROPERTIES.get(modelClass);
    }

    private static List<Property> inspectLegacyReferenceProperties(Class<?> modelClass) {
        try {
            Class<?> builderClass = VMFTypeUtils.getBuilderClass(modelClass);
            Builder builder = (Builder) builderClass.getDeclaredMethod("newInstance").invoke(null);
            return ((VObject) builder.build()).vmf().reflect().properties();
        } catch (Exception e) {
            throw new RuntimeException("Cannot inspect the properties of " + modelClass.getName(), e);
        }
    }

    private static Map<String, ReferenceProperty> findReferenceProperties(Class<?> modelClass) {
        List<Property> declarations = VMFTypeUtils.generatedProperties(modelClass)
                .orElseGet(() -> inspectLegacyReferenceProperties(modelClass));
        Map<String, ReferenceProperty> result = new LinkedHashMap<>();
        for (Property p : declarations) {
            if (!isReferenceProperty(p)) {
                continue;
            }
            boolean list = p.getType().isListType() || p.getType().isReferenceSetType();
            String valueTypeName = list ? p.getType().getElementTypeName().orElse(null) : p.getType().getName();
            Class<?> valueClass = Object.class;
            if (valueTypeName != null) {
                try {
                    valueClass = Class.forName(valueTypeName, false, modelClass.getClassLoader());
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Cannot load the type " + valueTypeName + " of property "
                            + modelClass.getName() + "." + p.getName(), e);
                }
            }
            String fieldName = VMFTypeUtils.getFieldNameForProperty(p);
            boolean unique = p.getType().isReferenceSetType();
            boolean hasDefault = unique && p.annotationByKey("vmf:property:reference-default")
                    .filter(annotation -> "true".equals(annotation.getValue())).isPresent();
            result.put(fieldName, new ReferenceProperty(p.getName(), fieldName, list, unique, hasDefault, valueClass));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Indicates whether the node holds an id (a non-empty string or an integer).
     */
    static boolean isIdNode(JsonNode n) {
        return n != null && (n.isIntegralNumber() || (n.isTextual() && !n.asText().isEmpty()));
    }

    /**
     * Returns the elements of a list value, including the XML shapes: a wrapper object whose
     * values are the elements (or arrays of them) and an empty element for an empty list.
     */
    static List<JsonNode> elementsOf(JsonNode value, boolean isXml) {
        List<JsonNode> result = new ArrayList<>();
        if (value == null || value.isNull()) {
            return result;
        }
        if (!value.isContainerNode()) {
            if (!(value.isTextual() && value.asText().isEmpty())) {
                result.add(value);
            }
            return result;
        }
        for (JsonNode element : value) {
            if (isXml && element.isArray()) {
                element.forEach(result::add);
            } else {
                result.add(element);
            }
        }
        return result;
    }

    /**
     * Indicates whether the value of a reference property holds references (ids) that are
     * resolved after the containment tree has been read. Values without ids (immutable values
     * written by value, {@code null}) are read as before.
     */
    static boolean holdsReferences(JsonNode value, ReferenceProperty property, boolean isXml) {
        if (property.uniqueReferences) return true;
        if (value == null || value.isNull()) {
            return false;
        }
        if (!property.list) {
            return isIdNode(value);
        }
        for (JsonNode e : elementsOf(value, isXml)) {
            if (isIdNode(e)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the id of the object node, or {@code null} if it has none.
     */
    static String idOf(JsonNode node, boolean isXml, JsonParser p) throws JsonMappingException {
        JsonNode id = node.get(ID_FIELD);
        if ((id == null || id.isNull()) && isXml) {
            id = node.get(ID_FIELD_XML);
        }
        if (id == null) return null;
        if (!isIdNode(id)) throw JsonMappingException.from(p, "Expected a non-empty string or integer for " + ID_FIELD);
        return id.asText();
    }

    /**
     * Placeholder for a referenced object that is resolved when the outermost object is complete.
     */
    static final class IdSlot {
        final String id;

        IdSlot(String id) {
            this.id = id;
        }
    }

    private static final class PendingReference {
        final VObject owner;
        final ReferenceProperty property;
        final List<Object> slots;

        PendingReference(VObject owner, ReferenceProperty property, List<Object> slots) {
            this.owner = owner;
            this.property = property;
            this.slots = slots;
        }

        String describe() {
            return typeName(owner) + "." + property.name;
        }
    }

    /**
     * Objects by id and the references to assign, for the outermost object being read.
     */
    interface ImmutableLoader {
        Object load(String id) throws java.io.IOException;
    }

    static final class ReadSession {
        private final Map<String, Object> objectsById = new HashMap<>();
        private final Set<String> buildingImmutable = new java.util.HashSet<>();
        private final ImmutableLoader immutableLoader;

        ReadSession(ImmutableLoader immutableLoader) { this.immutableLoader = immutableLoader; }

        Object immutable(String id, Class<?> expected, JsonParser parser) throws java.io.IOException {
            Object result = objectsById.get(id);
            if (result == null) {
                if (!buildingImmutable.add(id)) throw JsonMappingException.from(parser, "Cyclic immutable definition '" + id + "'");
                try {
                    result = immutableLoader.load(id);
                } finally {
                    buildingImmutable.remove(id);
                }
            }
            if (!(result instanceof Immutable) || !expected.isInstance(result))
                throw JsonMappingException.from(parser, "Wrong immutable target type for reference '" + id + "'");
            return result;
        }
        private final List<PendingReference> pending = new ArrayList<>();
        private final List<Runnable> pendingKeyedContents = new ArrayList<>();

        @SuppressWarnings("unchecked")
        void addKeyedContents(VObject owner, String name, List<Object> entries) {
            pendingKeyedContents.add(() -> {
                var property = owner.vmf().reflect().propertyByName(name).orElseThrow();
                property.set(entries);
            });
        }

        /**
         * Registers an object that has been read with an id.
         * @throws JsonMappingException if the id is used by another object
         */
        void register(String id, Object o, JsonParser p) throws JsonMappingException {
            Object previous = objectsById.putIfAbsent(id, o);
            if (previous != null && previous != o) {
                throw JsonMappingException.from(p, "Duplicate " + ID_FIELD + " '" + id + "'.");
            }
        }

        /**
         * Adds a reference to assign when the outermost object is complete.
         * @param owner the object that holds the reference property
         * @param property the reference property
         * @param slots the referenced objects as {@link IdSlot}s and values (immutable values, null)
         */
        void addReference(VObject owner, ReferenceProperty property, List<Object> slots) {
            pending.add(new PendingReference(owner, property, slots));
        }

        /**
         * Assigns the collected references. Single references and missing list elements are assigned
         * through the model API, so the opposites of {@code @Refers} properties are maintained as usual.
         * Then every reference list gets exactly the elements and the order that were written; this
         * reordering does not touch the opposite side (its elements are the same).
         * @param p the parser (for error reporting)
         * @throws JsonMappingException if an id cannot be resolved or a reference cannot be assigned
         */
        void resolve(JsonParser p) throws JsonMappingException {

            List<List<Object>> resolved = new ArrayList<>(pending.size());
            for (PendingReference r : pending) {
                List<Object> targets = new ArrayList<>(r.slots.size());
                for (Object slot : r.slots) {
                    if (slot instanceof IdSlot) {
                        String id = ((IdSlot) slot).id;
                        Object target = objectsById.get(id);
                        if (target == null) {
                            throw JsonMappingException.from(p, "Unresolved reference '" + id + "' in '"
                                    + r.describe() + "': no object with " + ID_FIELD + " '" + id
                                    + "' has been read.");
                        }
                        if (!r.property.valueClass.isInstance(target)) {
                            throw JsonMappingException.from(p, "Wrong target type for reference '" + r.describe() + "'");
                        }
                        targets.add(target);
                    } else {
                        targets.add(slot);
                    }
                }
                if (r.property.uniqueReferences) {
                    var identities = Collections.newSetFromMap(new IdentityHashMap<>());
                    for (Object target : targets) {
                        if (target == null || !identities.add(target))
                            throw JsonMappingException.from(p, "Null or duplicate identity in '" + r.describe() + "'");
                    }
                }
                resolved.add(targets);
            }

            // 1. assign through the model API (maintains the opposites)
            for (int i = 0; i < pending.size(); i++) {
                PendingReference r = pending.get(i);
                List<Object> targets = resolved.get(i);
                Property property = propertyOf(r, p);
                try {
                    if (r.property.list) {
                        List<Object> list = listOf(r, property, p);
                        for (Object target : targets) {
                            if (target != null && !containsIdentical(list, target)) {
                                list.add(target);
                            }
                        }
                    } else {
                        Object target = targets.isEmpty() ? null : targets.get(0);
                        if (property.get() != target) {
                            property.set(target);
                        }
                    }
                } catch (RuntimeException e) {
                    throw JsonMappingException.from(p, "Cannot assign reference '" + r.describe() + "': "
                            + e.getMessage(), e);
                }
            }

            // Entry admission needs the resolved reference key, including forward references.
            try {
                pendingKeyedContents.forEach(Runnable::run);
            } catch (RuntimeException failure) {
                throw JsonMappingException.from(p, "Cannot restore keyed containment entries: " + failure.getMessage(), failure);
            }

            // 2. lists get exactly the elements and the order that were written
            for (int i = 0; i < pending.size(); i++) {
                PendingReference r = pending.get(i);
                if (!r.property.list) {
                    continue;
                }
                List<Object> list = listOf(r, propertyOf(r, p), p);
                List<Object> desired = new ArrayList<>(resolved.get(i));
                if (r.property.uniqueReferences) {
                    Set<Object> expected = Collections.newSetFromMap(new IdentityHashMap<>());
                    expected.addAll(desired);
                    if (list.size() != desired.size() || !expected.containsAll(list))
                        throw JsonMappingException.from(p, "Contradictory reference memberships in '" + r.describe() + "'");
                    // Only ordered ends have positions to restore. The checked setter preserves the opposites.
                    var property = propertyOf(r, p);
                    if (property.getMultiplicity().isOrdered() && !sameElements(list, desired)) property.set(desired);
                    continue;
                }
                // elements that the opposite side added but this side did not list (inconsistent
                // input) stay, after the written ones
                for (Object e : list) {
                    if (!containsIdentical(desired, e)) {
                        desired.add(e);
                    }
                }
                if (!sameElements(list, desired)) {
                    replaceElementsKeepingOpposites(list, desired);
                }
            }
        }

        private static Property propertyOf(PendingReference r, JsonParser p) throws JsonMappingException {
            Optional<Property> property = r.owner.vmf().reflect().propertyByName(r.property.name);
            if (property.isEmpty()) {
                throw JsonMappingException.from(p, "Cannot assign reference '" + r.describe()
                        + "': unknown property.");
            }
            return property.get();
        }

        @SuppressWarnings("unchecked")
        private static List<Object> listOf(PendingReference r, Property property, JsonParser p)
                throws JsonMappingException {
            Object value;
            if (r.property.uniqueReferences) {
                var internal = (eu.mihosoft.vmf.runtime.core.internal.VObjectInternal) r.owner;
                value = internal._vmf_getPropertyValueById(internal._vmf_getPropertyIdByName(property.getName()));
            } else {
                value = property.get();
            }
            if (!(value instanceof List)) {
                throw JsonMappingException.from(p, "Cannot assign reference '" + r.describe()
                        + "': the property does not hold a list.");
            }
            return (List<Object>) value;
        }
    }

    /**
     * Returns the read session of the current thread, or {@code null} if no session is active.
     */
    static ReadSession currentReadSession() {
        return READ_SESSION.get();
    }

    /**
     * Starts a read session for the outermost object being read.
     */
    static ReadSession startReadSession(ImmutableLoader loader) {
        ReadSession session = new ReadSession(loader);
        READ_SESSION.set(session);
        return session;
    }

    /**
     * Ends the read session of the current thread.
     */
    static void endReadSession() {
        READ_SESSION.remove();
    }

    private static boolean containsIdentical(List<?> list, Object o) {
        for (Object e : list) {
            if (e == o) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameElements(List<?> a, List<?> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Replaces the elements of a reference list. VMF's cross-reference lists ignore changes that
     * are tagged as cross-reference updates, so the opposite side is not touched.
     */
    @SuppressWarnings("unchecked")
    private static void replaceElementsKeepingOpposites(List<Object> list, List<Object> elements) {
        if (list instanceof VList) {
            VList<Object> vList = (VList<Object>) list;
            String eventInfo = vList.getEventInfo();
            vList.setEventInfo(ChangeInternal.CHANGE_TYPE_CROSS_REF);
            try {
                vList.clear();
                vList.addAll(elements);
            } finally {
                vList.setEventInfo(eventInfo);
            }
        } else {
            list.clear();
            list.addAll(elements);
        }
    }
}
