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

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.AnnotationInfo;
import eu.mihosoft.vmf.core.DelegationInfo;
import eu.mihosoft.vmf.core.ExternalType;
import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.ModelType;
import eu.mihosoft.vmf.core.Prop;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableMethod;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableParameter;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableType;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Callables;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ConstructionHook;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.EntryKey;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Equality;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Feature;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.MetadataAnnotation;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.TypeParameter;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class ResolvedModelProjection {
    private static final Map<String, ValueType.Kind> SCALAR_KINDS = Map.ofEntries(
            Map.entry("boolean", ValueType.Kind.BOOLEAN),
            Map.entry("java.lang.Boolean", ValueType.Kind.BOOLEAN),
            Map.entry("byte", ValueType.Kind.INT8),
            Map.entry("java.lang.Byte", ValueType.Kind.INT8),
            Map.entry("short", ValueType.Kind.INT16),
            Map.entry("java.lang.Short", ValueType.Kind.INT16),
            Map.entry("int", ValueType.Kind.INT32),
            Map.entry("java.lang.Integer", ValueType.Kind.INT32),
            Map.entry("long", ValueType.Kind.INT64),
            Map.entry("java.lang.Long", ValueType.Kind.INT64),
            Map.entry("char", ValueType.Kind.CHAR),
            Map.entry("java.lang.Character", ValueType.Kind.CHAR),
            Map.entry("float", ValueType.Kind.FLOAT32),
            Map.entry("java.lang.Float", ValueType.Kind.FLOAT32),
            Map.entry("double", ValueType.Kind.FLOAT64),
            Map.entry("java.lang.Double", ValueType.Kind.FLOAT64),
            Map.entry("java.lang.String", ValueType.Kind.STRING));

    private final Map<String, Class<?>> declarations = new HashMap<>();
    private final Set<String> entryKeyFields;

    private ResolvedModelProjection(Model model, Class<?>[] sourceDeclarations) {
        for (var declaration : sourceDeclarations) {
            declarations.put(declaration.getSimpleName(), declaration);
        }
        // Effective keys can be marked on an inherited copy. Mark the originating declaration as
        // well so targets that share a base setter have a single place to guard the mutation.
        entryKeyFields = model.getTypes().stream().flatMap(type -> type.getImplementation().getProperties().stream())
                .filter(Prop::isKeyField).map(ResolvedModelProjection::propertyIdentity).collect(Collectors.toSet());
    }

    static ResolvedModel project(Model model, Class<?>... sourceDeclarations) {
        var projection = new ResolvedModelProjection(model, sourceDeclarations);
        var types = model.getTypes().stream()
                .map(projection::projectType)
                .collect(Collectors.toList());
        var external = new HashMap<String, List<MetadataAnnotation>>();
        for (Class<?> declaration : sourceDeclarations) {
            var type = declaration.getAnnotation(ExternalType.class);
            if (type == null) continue;
            var annotations = Arrays.stream(declaration.getDeclaredAnnotationsByType(Annotation.class))
                    .map(annotation -> new MetadataAnnotation(annotation.key(), annotation.value()))
                    .collect(Collectors.toUnmodifiableList());
            external.put(type.pkgName() + "." + declaration.getSimpleName(), annotations);
        }
        return new ResolvedModel(model.getPackageName(), types, external);
    }

    private Type projectType(ModelType type) {
        Class<?> source = declarations.get(type.getTypeName());
        if (source == null && (!type.getTypeParameters().isEmpty() || type.hasParameterizedSuperTypes())) {
            throw new IllegalArgumentException("Missing source declaration for " + type.getFullTypeName());
        }

        var parameters = projectParameters(type, source);
        var superTypes = source == null
                ? type.getImplementz().stream().map(parent -> ValueType.model(parent.getTypeName(), List.of()))
                        .collect(Collectors.toList())
                : Arrays.stream(source.getGenericInterfaces()).map(parent -> projectTypeUse(type.getModel(), parent))
                        .collect(Collectors.toList());
        var properties = projectProperties(type.getProperties(), parameters);
        var effectiveProperties = projectProperties(type.getImplementation().getProperties(), parameters);

        var features = new ArrayList<Feature>();
        if (type.isEqualsAndHashCode()) features.add(Feature.CUSTOM_EQUALITY);
        if (!type.getDelegations().isEmpty()) features.add(Feature.DELEGATION);

        String wireType = annotationValue(type.getAnnotations(), "vmf:wire:type", type.getFullTypeName());
        return new Type(type.getTypeName(), type.getFullTypeName(), wireType, superTypes, parameters,
                properties, effectiveProperties, type.isInterfaceOnly(), projectsGetterOnlyTrait(type, !effectiveProperties.isEmpty()),
                type.isImmutable(), equality(type), features, projectCallables(type), typeAnnotations(type));
    }

    private static List<MetadataAnnotation> typeAnnotations(ModelType type) {
        var annotations = projectAnnotations(type.getImplementation().getAnnotations());
        if (type.isImmutable()) annotations.add(new MetadataAnnotation("vmf:type:immutable", ""));
        if (type.isInterfaceOnly()) annotations.add(new MetadataAnnotation("vmf:type:interface-only", ""));
        return annotations;
    }

    private static List<MetadataAnnotation> projectAnnotations(Collection<AnnotationInfo> source) {
        return source.stream().map(annotation -> new MetadataAnnotation(annotation.getKey(), annotation.getValue()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static Callables projectCallables(ModelType type) {
        var constructor = type.getImplementation().getConstructorDelegations().stream().findFirst()
                .map(hook -> new ConstructionHook(hook.getMethodName(), hook.getSourceDeclaration().getSimpleName(),
                        hook.getFullTypeName())).orElse(null);
        return new Callables(projectMethods(type.getModel(), type.getMethodDelegations()),
                projectMethods(type.getModel(), type.getImplementation().getMethodDelegations()), constructor);
    }

    private static List<CallableMethod> projectMethods(Model model, List<DelegationInfo> delegations) {
        return delegations.stream().sorted(Comparator.comparing(DelegationInfo::getMethodSignature))
                .map(delegation -> {
                    var method = delegation.getSourceMethod();
                    var parameters = method.getParameters();
                    var arguments = new ArrayList<CallableParameter>(parameters.length);
                    for (int i = 0; i < parameters.length; i++) {
                        var parameter = parameters[i];
                        arguments.add(new CallableParameter(parameter.getName(), projectCallableType(model, parameter.getParameterizedType()),
                                method.isVarArgs() && i == parameters.length - 1));
                    }
                    boolean checkedExceptions = Arrays.stream(method.getExceptionTypes())
                            .anyMatch(error -> !RuntimeException.class.isAssignableFrom(error) && !Error.class.isAssignableFrom(error));
                    return new CallableMethod(method.getName(), method.getDeclaringClass().getSimpleName(),
                            projectCallableType(model, method.getGenericReturnType()), arguments, delegation.getMethodSignature(),
                            delegation.getFullTypeName(),
                            Modifier.isStatic(method.getModifiers()), checkedExceptions);
                }).collect(Collectors.toList());
    }

    private static CallableType projectCallableType(Model model, java.lang.reflect.Type type) {
        if (type == void.class) return CallableType.noReturn();
        if (type instanceof Class<?> && ((Class<?>) type).isArray()) {
            return CallableType.array(projectCallableType(model, ((Class<?>) type).getComponentType()));
        }
        if (type instanceof GenericArrayType) {
            return CallableType.array(projectCallableType(model, ((GenericArrayType) type).getGenericComponentType()));
        }
        if (type instanceof ParameterizedType) {
            var applied = (ParameterizedType) type;
            var raw = applied.getRawType();
            if (raw == List.class || raw.getTypeName().equals("eu.mihosoft.vcollections.VList")) {
                return CallableType.list(projectCallableType(model, applied.getActualTypeArguments()[0]));
            }
        }
        return CallableType.value(projectTypeUse(model, type));
    }

    private static boolean projectsGetterOnlyTrait(ModelType type, boolean hasProperties) {
        if (!type.getTypeParameters().isEmpty() || !isGetterOnlyTraitDeclaration(type)) return false;
        var ancestors = type.getAllInheritedTypes();
        if (!ancestors.stream().allMatch(ResolvedModelProjection::isGetterOnlyTraitDeclaration)) return false;
        // Empty markers and named generic closures need a shared nominal view for immutable implementations.
        return hasProperties && ancestors.stream().allMatch(parent -> parent.getTypeParameters().isEmpty())
                || isDataOnlyTraitUsedByImmutableModel(type);
    }

    private static boolean isGetterOnlyTraitDeclaration(ModelType type) {
        return !type.isImmutable() && type.isInterfaceOnlyWithGettersOnly();
    }

    private static boolean isDataOnlyTraitUsedByImmutableModel(ModelType type) {
        var implementation = type.getImplementation();
        return implementation.getMethodDelegations().isEmpty() && implementation.getConstructorDelegations().isEmpty()
                && type.getModel().getTypes().stream().anyMatch(candidate -> candidate.isImmutable()
                        && !candidate.isInterfaceOnly() && candidate.extendsType(type));
    }

    private static List<TypeParameter> projectParameters(ModelType type, Class<?> source) {
        return source == null ? List.of() : Arrays.stream(source.getTypeParameters())
                .map(parameter -> projectParameter(type.getModel(), parameter))
                .collect(Collectors.toList());
    }

    private static Equality equality(ModelType type) {
        if (type.isEqualsAndHashCodeALL()) return Equality.ALL;
        if (type.isEqualsAndHashCodeCONTAINMENT_AND_EXTERNAL()) return Equality.CONTAINMENT_AND_EXTERNAL;
        return Equality.INSTANCE;
    }

    private static TypeParameter projectParameter(Model model, TypeVariable<?> variable) {
        var bounds = Arrays.stream(variable.getBounds())
                .filter(bound -> bound != Object.class)
                .map(bound -> projectTypeUse(model, bound))
                .collect(Collectors.toList());
        return new TypeParameter(variable.getName(), bounds);
    }

    private static ValueType projectTypeUse(Model model, java.lang.reflect.Type type) {
        if (type instanceof TypeVariable<?>) {
            TypeVariable<?> variable = (TypeVariable<?>) type;
            String modelBound = Arrays.stream(variable.getBounds()).map(bound -> terminalModelBound(model, bound))
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            return ValueType.parameter(variable.getName(), modelBound);
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType applied = (ParameterizedType) type;
            if (applied.getRawType() instanceof Class<?> && isSourceModelType(model, (Class<?>) applied.getRawType())) {
                var arguments = Arrays.stream(applied.getActualTypeArguments())
                        .map(argument -> projectTypeUse(model, argument))
                        .collect(Collectors.toList());
                return ValueType.model(((Class<?>) applied.getRawType()).getSimpleName(), arguments);
            }
        }
        if (type instanceof Class<?>) {
            Class<?> declaration = (Class<?>) type;
            if (isSourceModelType(model, declaration)) {
                return ValueType.model(declaration.getSimpleName(), List.of());
            }
            ExternalType external = declaration.getAnnotation(ExternalType.class);
            if (external != null && declaration.getPackageName().equals(model.getPackageName() + ".vmfmodel")) {
                return scalarType(external.pkgName() + "." + declaration.getSimpleName());
            }
            if (declaration.getEnclosingClass() != null && declaration.getCanonicalName() != null) {
                return scalarType(declaration.getCanonicalName());
            }
        }
        return scalarType(type.getTypeName());
    }

    private static String terminalModelBound(Model model, java.lang.reflect.Type bound) {
        if (bound instanceof TypeVariable<?>) {
            return Arrays.stream(((TypeVariable<?>) bound).getBounds()).map(parent -> terminalModelBound(model, parent))
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        }
        return bound instanceof Class<?> && isSourceModelType(model, (Class<?>) bound)
                ? ((Class<?>) bound).getSimpleName() : null;
    }

    private static boolean isSourceModelType(Model model, Class<?> type) {
        return type.getPackageName().equals(model.getPackageName() + ".vmfmodel")
                && model.resolveType(model.getPackageName() + "." + type.getSimpleName()).isPresent();
    }

    private List<Property> projectProperties(List<Prop> properties, List<TypeParameter> parameters) {
        var parametersByName = indexParameters(parameters);
        return properties.stream().map(property -> projectProperty(property, parametersByName))
                .collect(Collectors.toList());
    }

    private static Map<String, TypeParameter> indexParameters(List<TypeParameter> parameters) {
        return parameters.stream().collect(Collectors.toMap(TypeParameter::getName, parameter -> parameter));
    }

    private Property projectProperty(Prop prop, Map<String, TypeParameter> parameters) {
        var valueType = prop.isMapType()
                ? projectMapComponent(prop.getMapValueTypeName(), parameters)
                : projectValueType(prop, parameters);
        var keyType = prop.isMapType() ? projectMapComponent(prop.getMapKeyTypeName(), parameters) : null;
        var kind = propertyKind(prop, valueType);
        boolean collection = prop.isCollectionType() || prop.isManagedValues() || prop.isMapType();
        boolean allowsNullElements = prop.isMapType()
                || (prop.isCollectionType() && !prop.isUnique() && !prop.isKeyed());
        String defaultSource = prop.getDefaultValueAsString();
        boolean hasDefault = prop.hasDeclaredDefaultValue() || !"null".equals(defaultSource);
        var scalarDefault = hasDefault && kind == Property.Kind.SCALAR && !collection && valueType.isPortableScalar()
                ? JavaScalarDefaults.parse(defaultSource, valueType) : null;
        var collectionDefault = hasDefault && collection && valueType.isPortableScalar()
                && (kind == Property.Kind.SCALAR && !allowsNullElements || kind == Property.Kind.MAP && keyType.isPortableScalar())
                ? JavaScalarDefaults.parseCollection(defaultSource, valueType, keyType, prop.isOrdered()) : null;
        var referenceSetDefault = prop.hasReferenceDefault() && kind == Property.Kind.REFERENCE
                && prop.isUnique() && oppositeName(prop).isEmpty() && valueType.getKind() == ValueType.Kind.MODEL
                ? JavaReferenceDefaults.parse(defaultSource, prop.getRelationshipTarget()) : null;
        String wireName = annotationValue(prop.getAnnotations(), "vmf:jackson:rename", prop.getName());
        String propertyId = propertyIdentity(prop);
        var entryKey = prop.isKeyed() ? projectEntryKey(prop, propertyId) : null;
        var annotations = propertyAnnotations(prop, kind);
        boolean getterOnly = prop.isGetterOnly() || kind == Property.Kind.CONTAINER && prop.isReadOnly();
        return new Property(prop.getName(), wireName, prop.getParent().getTypeName(), valueType, keyType,
                entryKey, entryKeyFields.contains(propertyId), kind,
                collection, allowsNullElements, prop.getLowerBound(), prop.getUpperBound(), prop.isOrdered(),
                prop.isUnique(), oppositeName(prop), getterOnly, prop.isIgnoredForEquals(),
                prop.isIgnoredForToString(), hasDefault, scalarDefault, collectionDefault, referenceSetDefault,
                hasDefault ? defaultSource : null, prop.getConstraints(), propertyFeatures(prop), annotations);
    }

    private static List<MetadataAnnotation> propertyAnnotations(Prop prop, Property.Kind kind) {
        var annotations = projectAnnotations(prop.getAnnotations());
        String opposite = oppositeName(prop);
        if (!prop.isContainmentProperty() && (prop.getType() != null || prop.getReferenceInfo() != null)) {
            annotations.add(new MetadataAnnotation("vmf:property:reference-opposite", opposite));
        }
        if (prop.hasReferenceDefault()) annotations.add(new MetadataAnnotation("vmf:property:reference-default", "true"));
        String containment = kind == Property.Kind.CONTAINER ? "container"
                : kind == Property.Kind.CONTAINMENT ? "contained" : "none";
        annotations.add(new MetadataAnnotation("vmf:property:containment-info",
                prop.isContainmentProperty() && !opposite.isEmpty() ? containment + ":" + opposite : containment));
        return annotations;
    }

    private EntryKey projectEntryKey(Prop containment, String relationshipId) {
        var entryType = containment.getRelationshipTarget();
        var key = containment.getEntryKey();
        var parameters = projectParameters(entryType, declarations.get(entryType.getTypeName()));
        return new EntryKey(relationshipId, key.getName(), projectValueType(key, indexParameters(parameters)));
    }

    private static String propertyIdentity(Prop prop) {
        return prop.getParent().getFullTypeName() + "." + prop.getName();
    }

    private static String annotationValue(Collection<AnnotationInfo> annotations, String key, String fallback) {
        return annotations.stream().filter(annotation -> key.equals(annotation.getKey()))
                .map(AnnotationInfo::getValue).findFirst().orElse(fallback);
    }

    private static Property.Kind propertyKind(Prop prop, ValueType valueType) {
        if (prop.isMapType()) return Property.Kind.MAP;
        if (isShadowedScalarContainment(prop, valueType)) return Property.Kind.SCALAR;
        if (prop.isContained()) return Property.Kind.CONTAINMENT;
        if (prop.isContainer()) return Property.Kind.CONTAINER;
        if (prop.isCrossRefProperty() || valueType.getModelType() != null) {
            return Property.Kind.REFERENCE;
        }
        return Property.Kind.SCALAR;
    }

    private static boolean isShadowedScalarContainment(Prop prop, ValueType valueType) {
        ModelType owner = prop.getParent();
        if (!valueType.isPortableScalar() || !owner.isInterfaceOnly() || !prop.isContained()
                || !prop.isCollectionType() || !prop.isOrdered() || prop.isUnique() || prop.isKeyed()
                || !oppositeName(prop).isEmpty()) return false;
        var implementations = owner.getModel().getTypes().stream()
                .filter(type -> !type.isInterfaceOnly() && type.extendsType(owner)).collect(Collectors.toList());
        // Java emits a list getter on the trait. Only its concrete effective declarations have storage.
        return !implementations.isEmpty() && implementations.stream().allMatch(type ->
                type.getImplementation().getProperties().stream().anyMatch(actual ->
                        actual.getName().equals(prop.getName()) && actual.getParent() != owner
                                && actual.getParent().extendsType(owner)
                                && !actual.isContainmentProperty() && !actual.isCrossRefProperty()
                                && actual.getTypeNameForInterface().equals(prop.getTypeNameForInterface())
                                && actual.isOrdered() == prop.isOrdered() && actual.isUnique() == prop.isUnique()
                                && actual.getLowerBound() == prop.getLowerBound() && actual.getUpperBound() == prop.getUpperBound()));
    }

    private static String oppositeName(Prop prop) {
        if (prop.isContainmentProperty() && prop.getContainmentInfo().getOpposite() != null) {
            return prop.getContainmentInfo().getOpposite().getName();
        }
        if (prop.isCrossRefProperty() && prop.getReferenceInfo().getOpposite() != null) {
            return prop.getReferenceInfo().getOpposite().getName();
        }
        if (prop.isCrossRefProperty() && prop.getReferenceInfo().getOpposite() != null) {
            return prop.getReferenceInfo().getOpposite().getName();
        }
        return "";
    }

    private static List<Feature> propertyFeatures(Prop prop) {
        var features = new ArrayList<Feature>();
        if (prop.isMapType()) features.add(Feature.MAP);
        if (prop.isKeyed()) features.add(Feature.KEYED);
        if (!"null".equals(prop.getDefaultValueAsString())) features.add(Feature.DEFAULT_VALUE);
        if (!prop.getConstraints().isEmpty()) features.add(Feature.VALUE_CONSTRAINT);
        if (prop.isReadOnly()) features.add(Feature.READ_ONLY);
        if (prop.getSyncInfo() != null) features.add(Feature.SYNCHRONIZATION);
        return features;
    }

    private static ValueType projectValueType(Prop prop, Map<String, TypeParameter> parameters) {
        // Host bindings use Java source names. JVM reflection retains its separate binary metadata.
        String typeName = prop.getTypeName();
        if (prop.isCollectionType()) {
            typeName = prop.getElementTypeName();
        } else if (prop.isManagedValues()) {
            typeName = prop.getConstrainedElementType();
        }

        var modelType = prop.getRelationshipTarget();
        var parameter = parameters.get(typeName);
        if (parameter != null) {
            return ValueType.parameter(parameter.getName(), modelType == null ? null : modelType.getTypeName());
        }

        if (modelType == null) {
            modelType = prop.isCollectionType() ? prop.getGenericType() : prop.getType();
        }
        if (modelType != null) {
            return ValueType.model(modelType.getTypeName(), List.of());
        }
        return scalarType(typeName);
    }

    private static ValueType projectMapComponent(String typeName, Map<String, TypeParameter> parameters) {
        return parameters.containsKey(typeName) ? ValueType.parameter(typeName, null) : scalarType(typeName);
    }

    private static ValueType scalarType(String typeName) {
        ValueType.Kind kind = SCALAR_KINDS.getOrDefault(typeName, ValueType.Kind.EXTERNAL);
        boolean primitive = kind != ValueType.Kind.EXTERNAL && typeName.indexOf('.') < 0;
        return new ValueType(kind, null, typeName, primitive);
    }
}
