/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.core.codegen;

import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.PropertyConstraint;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Internal semantic model shared by target backends. Names are logical model names;
 * backends choose their own source identifiers, imports, and file layout.
 */
public final class ResolvedModel {
    private final String namespace;
    private final List<Type> types;
    private final Map<String, List<MetadataAnnotation>> externalAnnotations;

    ResolvedModel(String namespace, List<Type> types, Map<String, List<MetadataAnnotation>> externalAnnotations) {
        this.namespace = namespace;
        this.types = List.copyOf(types);
        this.externalAnnotations = Map.copyOf(externalAnnotations);
    }

    public static ResolvedModel from(Model model, Class<?>... sourceDeclarations) {
        return ResolvedModelProjection.project(model, sourceDeclarations);
    }

    public String getNamespace() {
        return namespace;
    }

    public List<Type> getTypes() {
        return types;
    }

    public Optional<Type> findType(String simpleName) {
        return types.stream().filter(type -> type.getName().equals(simpleName)).findFirst();
    }

    /** Annotations from the declaration of an external host type, keyed by its source name. */
    public List<MetadataAnnotation> externalAnnotations(String sourceName) {
        return externalAnnotations.getOrDefault(sourceName, List.of());
    }

    /** Concrete simple names that use a discriminator in a VMF graph snapshot. */
    public Set<String> polymorphicWireTypeNames() {
        var concrete = types.stream().filter(type -> !type.isInterfaceOnly()).collect(Collectors.toList());
        var concreteNames = concrete.stream().map(Type::getName).collect(Collectors.toSet());
        var propertyTargets = concrete.stream().flatMap(type -> type.getEffectiveProperties().stream())
                .map(Property::getValueType)
                .filter(value -> value.getKind() == ValueType.Kind.MODEL)
                .map(ValueType::getModelType)
                .collect(Collectors.toSet());
        var polymorphicNames = new HashSet<String>();
        for (var type : concrete) {
            for (String target : propertyTargets) {
                if (isSubtypeOf(type, target)) {
                    polymorphicNames.add(type.getName());
                    if (concreteNames.contains(target)) {
                        polymorphicNames.add(target);
                    }
                }
            }
        }
        return Set.copyOf(polymorphicNames);
    }

    boolean isSubtypeOf(Type type, String superTypeName) {
        return type.getSuperTypes().stream().anyMatch(parentName -> parentName.equals(superTypeName)
                || findType(parentName).filter(parent -> isSubtypeOf(parent, superTypeName)).isPresent());
    }

    public enum Equality {
        INSTANCE, CONTAINMENT_AND_EXTERNAL, ALL
    }

    public static final class Type {
        private final String name;
        private final String qualifiedName;
        private final String wireTypeName;
        private final List<ValueType> superTypeApplications;
        private final List<TypeParameter> typeParameters;
        private final List<Property> properties;
        private final List<Property> effectiveProperties;
        private final boolean interfaceOnly;
        private final boolean getterOnlyTrait;
        private final boolean immutable;
        private final Equality equality;
        private final List<Feature> features;
        private final Callables callables;
        private final List<MetadataAnnotation> annotations;

        Type(String name, String qualifiedName, String wireTypeName, List<ValueType> superTypeApplications,
             List<TypeParameter> typeParameters, List<Property> properties, List<Property> effectiveProperties,
             boolean interfaceOnly, boolean getterOnlyTrait, boolean immutable, Equality equality, List<Feature> features,
             Callables callables, List<MetadataAnnotation> annotations) {
            this.name = name;
            this.qualifiedName = qualifiedName;
            this.wireTypeName = wireTypeName;
            this.superTypeApplications = List.copyOf(superTypeApplications);
            this.typeParameters = List.copyOf(typeParameters);
            this.properties = List.copyOf(properties);
            this.effectiveProperties = List.copyOf(effectiveProperties);
            this.interfaceOnly = interfaceOnly;
            this.getterOnlyTrait = getterOnlyTrait;
            this.immutable = immutable;
            this.equality = equality;
            this.features = List.copyOf(features);
            this.callables = callables;
            this.annotations = List.copyOf(annotations);
        }

        public String getName() {
            return name;
        }

        public String getQualifiedName() {
            return qualifiedName;
        }

        public String getWireTypeName() {
            return wireTypeName;
        }

        public List<String> getSuperTypes() {
            return superTypeApplications.stream().map(ValueType::getModelType).collect(Collectors.toUnmodifiableList());
        }

        public List<ValueType> getSuperTypeApplications() {
            return superTypeApplications;
        }

        public List<TypeParameter> getTypeParameters() {
            return typeParameters;
        }

        /** The properties declared directly on this type. */
        public List<Property> getProperties() {
            return properties;
        }

        /** The model resolver's properties after inheritance and application of type arguments. */
        public List<Property> getEffectiveProperties() {
            return effectiveProperties;
        }

        public List<CallableMethod> getDeclaredMethods() {
            return callables.declared;
        }

        public List<CallableMethod> getEffectiveMethods() {
            return callables.effective;
        }

        /** The effective reflection annotations in their runtime order. */
        public List<MetadataAnnotation> getAnnotations() {
            return annotations;
        }

        public Optional<ConstructionHook> getConstructionHook() {
            return Optional.ofNullable(callables.hook);
        }

        public boolean isInterfaceOnly() {
            return interfaceOnly;
        }

        /** Whether the nongeneric declaration and its ancestors form a getter-only trait contract. */
        public boolean isGetterOnlyTrait() {
            return getterOnlyTrait;
        }

        public boolean isImmutable() {
            return immutable;
        }

        public Equality getEquality() {
            return equality;
        }

        /** Uses model content for public equality instead of instance identity. */
        public boolean isStructuralEquality() {
            return equality != Equality.INSTANCE;
        }

        List<Feature> getFeatures() {
            return features;
        }
    }

    public static final class MetadataAnnotation {
        private final String key;
        private final String value;

        MetadataAnnotation(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public String getKey() { return key; }
        public String getValue() { return value; }
    }

    static final class Callables {
        private final List<CallableMethod> declared;
        private final List<CallableMethod> effective;
        private final ConstructionHook hook;

        Callables(List<CallableMethod> declared, List<CallableMethod> effective, ConstructionHook hook) {
            this.declared = List.copyOf(declared);
            this.effective = List.copyOf(effective);
            this.hook = hook;
        }
    }

    /** A callable declared by the model. The Java binding is source metadata for that language. */
    public static final class CallableMethod {
        private final String name;
        private final String declaringType;
        private final CallableType returnType;
        private final String sourceSignature;
        private final List<CallableParameter> parameters;
        private final String javaBinding;
        private final boolean staticMethod;
        private final boolean checkedExceptions;

        CallableMethod(String name, String declaringType, CallableType returnType, List<CallableParameter> parameters,
                       String sourceSignature, String javaBinding, boolean staticMethod, boolean checkedExceptions) {
            this.name = name;
            this.declaringType = declaringType;
            this.returnType = returnType;
            this.sourceSignature = sourceSignature;
            this.parameters = List.copyOf(parameters);
            this.javaBinding = javaBinding;
            this.staticMethod = staticMethod;
            this.checkedExceptions = checkedExceptions;
        }

        public String getName() { return name; }
        public String getDeclaringType() { return declaringType; }
        public CallableType getReturnType() { return returnType; }
        /** The source generator's signature key before target type adaptation. */
        public String getSourceSignature() { return sourceSignature; }
        public List<CallableParameter> getParameters() { return parameters; }
        public String getJavaBinding() { return javaBinding; }
        public boolean isStatic() { return staticMethod; }
        public boolean hasCheckedExceptions() { return checkedExceptions; }
    }

    public static final class CallableParameter {
        private final String name;
        private final CallableType type;
        private final boolean varArg;

        CallableParameter(String name, CallableType type, boolean varArg) {
            this.name = name;
            this.type = type;
            this.varArg = varArg;
        }

        public String getName() { return name; }
        public CallableType getType() { return type; }
        public boolean isVarArg() { return varArg; }
    }

    public static final class CallableType {
        public enum Kind { VOID, VALUE, ARRAY, LIST }

        private final Kind kind;
        private final ValueType valueType;
        private final CallableType elementType;

        private CallableType(Kind kind, ValueType valueType, CallableType elementType) {
            this.kind = kind;
            this.valueType = valueType;
            this.elementType = elementType;
        }

        static CallableType noReturn() { return new CallableType(Kind.VOID, null, null); }
        static CallableType value(ValueType type) { return new CallableType(Kind.VALUE, type, null); }
        static CallableType array(CallableType element) { return new CallableType(Kind.ARRAY, null, element); }
        static CallableType list(CallableType element) { return new CallableType(Kind.LIST, null, element); }

        public Kind getKind() { return kind; }
        public ValueType getValueType() { return valueType; }
        public CallableType getElementType() { return elementType; }
    }

    /** The type-level hook selected by the source model's inheritance rules. */
    public static final class ConstructionHook {
        private final String name;
        private final String declaringType;
        private final String javaBinding;

        ConstructionHook(String name, String declaringType, String javaBinding) {
            this.name = name;
            this.declaringType = declaringType;
            this.javaBinding = javaBinding;
        }

        public String getName() { return name; }
        public String getDeclaringType() { return declaringType; }
        public String getJavaBinding() { return javaBinding; }
    }

    public static final class TypeParameter {
        private final String name;
        private final List<ValueType> bounds;

        TypeParameter(String name, List<ValueType> bounds) {
            this.name = name;
            this.bounds = List.copyOf(bounds);
        }

        public String getName() {
            return name;
        }

        public ValueType getBound() {
            return bounds.isEmpty() ? null : bounds.get(0);
        }

        /** Whether the single upper bound is the Java numeric scalar family. */
        public boolean isNumberBound() {
            return bounds.size() == 1 && Number.class.getName().equals(getBound().getExternalType());
        }

        public List<ValueType> getBounds() {
            return bounds;
        }
    }

    public static final class Property {
        public enum Kind {
            SCALAR, CONTAINMENT, CONTAINER, REFERENCE, MAP
        }

        private final String name;
        private final String wireName;
        private final String declaringType;
        private final ValueType valueType;
        private final ValueType keyType;
        private final EntryKey entryKey;
        private final boolean usedAsEntryKey;
        private final Kind kind;
        private final boolean collection;
        private final boolean allowsNullElements;
        private final int lowerBound;
        private final int upperBound;
        private final boolean ordered;
        private final boolean unique;
        private final String oppositeName;
        private final boolean getterOnly;
        private final boolean ignoredForEquals;
        private final boolean ignoredForToString;
        private final boolean declaredDefault;
        private final ScalarDefault scalarDefault;
        private final CollectionDefault collectionDefault;
        private final ReferenceSetDefault referenceSetDefault;
        private final String defaultExpression;
        private final List<PropertyConstraint> constraints;
        private final List<Feature> features;
        private final List<MetadataAnnotation> annotations;

        Property(String name, String wireName, String declaringType, ValueType valueType, ValueType keyType,
                 EntryKey entryKey, boolean usedAsEntryKey, Kind kind, boolean collection,
                 boolean allowsNullElements, int lowerBound, int upperBound,
                 boolean ordered, boolean unique,
                 String oppositeName, boolean getterOnly, boolean ignoredForEquals, boolean ignoredForToString,
                 boolean declaredDefault, ScalarDefault scalarDefault, CollectionDefault collectionDefault,
                 ReferenceSetDefault referenceSetDefault, String defaultExpression,
                 List<PropertyConstraint> constraints, List<Feature> features, List<MetadataAnnotation> annotations) {
            this.name = name;
            this.wireName = wireName;
            this.declaringType = declaringType;
            this.valueType = valueType;
            this.keyType = keyType;
            this.entryKey = entryKey;
            this.usedAsEntryKey = usedAsEntryKey;
            this.kind = kind;
            this.collection = collection;
            this.allowsNullElements = allowsNullElements;
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
            this.ordered = ordered;
            this.unique = unique;
            this.oppositeName = oppositeName;
            this.getterOnly = getterOnly;
            this.ignoredForEquals = ignoredForEquals;
            this.ignoredForToString = ignoredForToString;
            this.declaredDefault = declaredDefault;
            this.scalarDefault = scalarDefault;
            this.collectionDefault = collectionDefault;
            this.referenceSetDefault = referenceSetDefault;
            this.defaultExpression = defaultExpression;
            this.constraints = List.copyOf(constraints);
            this.features = List.copyOf(features);
            this.annotations = List.copyOf(annotations);
        }

        public String getName() {
            return name;
        }

        public String getWireName() {
            return wireName;
        }

        public String getDeclaringType() {
            return declaringType;
        }

        /** Type of the scalar, relationship target, collection element, or map value. */
        public ValueType getValueType() {
            return valueType;
        }

        /** Type of a map key, or null for any other property. */
        public ValueType getKeyType() {
            return keyType;
        }

        /** The key contract of an owning collection, if it declares a key on its entries. */
        public Optional<EntryKey> getEntryKey() {
            return Optional.ofNullable(entryKey);
        }

        /** True when this property declaration supplies the key for a containment relationship. */
        public boolean isUsedAsEntryKey() {
            return usedAsEntryKey;
        }

        public Kind getKind() {
            return kind;
        }

        public boolean isCollection() {
            return collection;
        }

        /** Whether a collection permits null elements, or a map permits null values. */
        public boolean allowsNullElements() {
            return allowsNullElements;
        }

        public int getLowerBound() {
            return lowerBound;
        }

        /** An upper bound of -1 means unbounded. */
        public int getUpperBound() {
            return upperBound;
        }

        public boolean isOrdered() {
            return ordered;
        }

        public boolean isUnique() {
            return unique;
        }

        public boolean isRequired() {
            return lowerBound > 0;
        }

        public String getOppositeName() {
            return oppositeName;
        }

        /** True when the declaration exposes only a getter. */
        public boolean isGetterOnly() {
            return getterOnly;
        }

        /** True for a read-only container whose owning field is selected at runtime. */
        public boolean isSharedContainer() {
            return kind == Kind.CONTAINER && getterOnly && oppositeName.isEmpty();
        }

        public List<String> getAnnotationValues(String key) {
            return annotations.stream().filter(annotation -> key.equals(annotation.getKey()))
                    .map(MetadataAnnotation::getValue).collect(Collectors.toUnmodifiableList());
        }

        /** The effective reflection annotations in their runtime order. */
        public List<MetadataAnnotation> getAnnotations() {
            return annotations;
        }

        /** VMF's effective content equality exclusion, including the owner's equality policy. */
        public boolean isIgnoredForEquals() {
            return ignoredForEquals;
        }

        /** True when this field is excluded from a generated diagnostic object summary. */
        public boolean isIgnoredForToString() {
            return ignoredForToString;
        }

        /** True for an explicit default, including null and declarations without portable literal semantics. */
        public boolean hasDeclaredDefault() {
            return declaredDefault;
        }

        /** The typed scalar value, when the declaration resolves to a supported literal. */
        public Optional<ScalarDefault> getScalarDefault() {
            return Optional.ofNullable(scalarDefault);
        }

        /** Typed values from a supported literal collection initializer. */
        public Optional<CollectionDefault> getCollectionDefault() {
            return Optional.ofNullable(collectionDefault);
        }

        /** A closed model construction recipe for the default member of an identity reference set. */
        public Optional<ReferenceSetDefault> getReferenceSetDefault() {
            return Optional.ofNullable(referenceSetDefault);
        }

        /** The declared Java default expression as written, when the property declares one. */
        public Optional<String> getDefaultExpression() {
            return Optional.ofNullable(defaultExpression);
        }

        public List<PropertyConstraint> getConstraints() {
            return constraints;
        }

        List<Feature> getFeatures() {
            return features;
        }
    }

    public static final class CollectionDefault {
        private final List<ScalarDefault> keys;
        private final List<ScalarDefault> values;

        CollectionDefault(List<ScalarDefault> keys, List<ScalarDefault> values) {
            this.keys = List.copyOf(keys);
            this.values = List.copyOf(values);
        }

        public List<ScalarDefault> getKeys() { return keys; }
        public List<ScalarDefault> getValues() { return values; }
    }

    public static final class ReferenceSetDefault {
        private final String modelType;
        private final Map<String, ScalarDefault> assignments;

        ReferenceSetDefault(String modelType, Map<String, ScalarDefault> assignments) {
            this.modelType = modelType;
            this.assignments = Map.copyOf(assignments);
        }

        public String getModelType() { return modelType; }

        /** Effective logical field names and their typed values. Empty uses default construction. */
        public Map<String, ScalarDefault> getAssignments() { return assignments; }
    }

    public static final class EntryKey {
        private final String relationshipId;
        private final String propertyName;
        private final ValueType valueType;

        EntryKey(String relationshipId, String propertyName, ValueType valueType) {
            this.relationshipId = relationshipId;
            this.propertyName = propertyName;
            this.valueType = valueType;
        }

        /** Qualified declaring model type and logical name of the owning collection. */
        public String getRelationshipId() {
            return relationshipId;
        }

        /** Logical property name on the entry. */
        public String getPropertyName() {
            return propertyName;
        }

        public ValueType getValueType() {
            return valueType;
        }

        /** Model reference keys use object identity. Scalar keys use value equality. */
        public boolean usesIdentity() {
            return valueType.getModelType() != null;
        }
    }

    public static final class ScalarDefault {
        private final ValueType.Kind kind;
        private final Object value;

        ScalarDefault(ValueType.Kind kind, Object value) {
            this.kind = kind;
            this.value = value;
        }

        public ValueType.Kind getKind() {
            return kind;
        }

        /** The declared-width boxed value, with a null value represented explicitly. */
        public Object getValue() {
            return value;
        }

        public boolean isNull() {
            return value == null;
        }
    }

    public static final class ValueType {
        public enum Kind {
            BOOLEAN, INT8, INT16, INT32, INT64, CHAR, FLOAT32, FLOAT64, STRING, MODEL, PARAMETER, EXTERNAL
        }

        private final Kind kind;
        private final String modelType;
        private final String sourceType;
        private final boolean primitive;
        private final String parameterName;
        private final List<ValueType> arguments;

        ValueType(Kind kind, String modelType, String sourceType, boolean primitive) {
            this(kind, modelType, sourceType, primitive, null, List.of());
        }

        private ValueType(Kind kind, String modelType, String sourceType, boolean primitive,
                          String parameterName, List<ValueType> arguments) {
            this.kind = kind;
            this.modelType = modelType;
            this.sourceType = sourceType;
            this.primitive = primitive;
            this.parameterName = parameterName;
            this.arguments = List.copyOf(arguments);
        }

        static ValueType model(String name, List<ValueType> arguments) {
            return new ValueType(Kind.MODEL, name, null, false, null, arguments);
        }

        static ValueType parameter(String name, String modelBound) {
            return new ValueType(Kind.PARAMETER, modelBound, null, false, name, List.of());
        }

        public Kind getKind() {
            return kind;
        }

        /** Simple declaration name of a model type or the model bound of a parameter. */
        public String getModelType() {
            return modelType;
        }

        /** Name that must be mapped by a target for an EXTERNAL type. */
        public String getExternalType() {
            return kind == Kind.EXTERNAL ? sourceType : null;
        }

        String getSourceType() {
            return sourceType;
        }

        public String getParameterName() {
            return parameterName;
        }

        public List<ValueType> getArguments() {
            return arguments;
        }

        public boolean isPrimitive() {
            return primitive;
        }

        /** Whether this is a supported scalar from the Java Number family. */
        public boolean isNumericScalar() {
            switch (kind) {
                case INT8:
                case INT16:
                case INT32:
                case INT64:
                case FLOAT32:
                case FLOAT64:
                    return true;
                default:
                    return false;
            }
        }

        public boolean isPortableScalar() {
            return kind != Kind.EXTERNAL && kind != Kind.MODEL && kind != Kind.PARAMETER;
        }
    }

    enum Feature {
        CUSTOM_EQUALITY("structural model equality"),
        DELEGATION("behavior delegation"),
        MAP("map properties"),
        KEYED("keyed relationships"),
        DEFAULT_VALUE("declared default expressions"),
        VALUE_CONSTRAINT("declared value constraints"),
        READ_ONLY("read-only properties"),
        SYNCHRONIZATION("synchronized properties");

        private final String description;

        Feature(String description) {
            this.description = description;
        }

        String getDescription() {
            return description;
        }
    }
}
