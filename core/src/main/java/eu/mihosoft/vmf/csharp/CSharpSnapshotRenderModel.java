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
package eu.mihosoft.vmf.csharp;

import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Prepares the snapshot descriptor for every closed, instantiable VMF type. */
final class CSharpSnapshotRenderModel {
    private final List<Map<String, Object>> registrations;
    private final List<Map<String, String>> aliases;

    CSharpSnapshotRenderModel(CSharpRenderModel source, List<Type> types, Set<String> polymorphicTypes) {
        List<Type> concrete = types.stream().filter(type -> !type.isInterfaceOnly())
                .collect(Collectors.toList());
        registrations = concrete.stream().map(type -> prepareRegistration(source, type,
                polymorphicTypes.contains(type.getName())))
                .collect(Collectors.toUnmodifiableList());
        aliases = concrete.stream().filter(type -> !type.getWireTypeName().equals(type.getQualifiedName()))
                .map(type -> Map.of("name", literal(type.getWireTypeName()), "target", literal(type.getQualifiedName())))
                .collect(Collectors.toUnmodifiableList());
    }

    List<Map<String, Object>> getRegistrations() {
        return registrations;
    }

    List<Map<String, String>> getAliases() {
        return aliases;
    }

    private static Map<String, Object> prepareRegistration(CSharpRenderModel source, Type type, boolean writeType) {
        boolean staged = source.stagesExecutableCollectionDefaults(type);
        List<Map<String, String>> properties = type.getEffectiveProperties().stream()
                .filter(property -> property.getKind() != Property.Kind.CONTAINER)
                .map(property -> prepareProperty(source, type, property, staged))
                .collect(Collectors.toUnmodifiableList());
        return Map.of("name", source.typeName(type), "wireName", literal(type.getQualifiedName()),
                "factory", type.isImmutable() ? source.getImmutable().snapshotFactory(type)
                        : "() => new " + source.typeName(type) + "()",
                "writeType", Boolean.toString(writeType), "properties", properties,
                "builderStage", type.isImmutable() ? source.getImmutable().snapshotBuilderStage(type) : "");
    }

    private static Map<String, String> prepareProperty(CSharpRenderModel source, Type type, Property property,
                                                       boolean staged) {
        String name = CSharpRenderModel.propertyName(type, property);
        String owner = "((" + source.typeName(type) + ")owner).";
        String member = owner + name;
        ValueType value = property.getValueType();
        String valueType = source.propertyValueTypeName(property);
        boolean objectValue = source.isUnboundObject(value);
        var external = value.getKind() == ValueType.Kind.EXTERNAL && !objectValue ? source.snapshotExternalBinding(value) : null;
        boolean scalarCollection = CSharpScalarCollections.isScalarCollection(property);
        boolean nullableScalar = scalarCollection ? property.allowsNullElements()
                : property.getKind() == Property.Kind.SCALAR && !value.isPrimitive();
        boolean valueTypeCanBeNullable = !objectValue && value.getKind() != ValueType.Kind.STRING
                && (external == null || external.isValueType());
        String runtimeType = nullableScalar && valueTypeCanBeNullable ? valueType + "?" : valueType;
        String evaluate = "static () => " + source.typeName(type) + "."
                + CSharpExecutableDefaultRenderModel.accessor(name) + "()";
        String stagedDefault = staged && (CSharpCapabilities.hasManagedCollectionDefault(property)
                || CSharpCapabilities.bindsExecutableDefault(property))
                ? " { StageBuilderDefault = " + evaluate + " }" : "";
        if (type.isImmutable() && value.isPortableScalar() && !value.isPrimitive()
                && CSharpCapabilities.bindsExecutableDefault(property)) {
            stagedDefault = " { DeclaredDefaultForNullWrite = " + evaluate + " }";
        }
        return Map.ofEntries(
                Map.entry("wireName", literal(property.getWireName())),
                Map.entry("modelName", literal(property.getName())),
                Map.entry("kind", propertyKind(property)),
                Map.entry("runtimeType", runtimeType),
                Map.entry("getter", "owner => " + member),
                Map.entry("explicitNull", CSharpDefaultRenderModel.writesExplicitNull(property) ? ", writeExplicitNull: true" : ""),
                Map.entry("schema", CSharpSchemaRenderModel.propertyMetadata(property)),
                Map.entry("external", external != null && !external.isBuiltIn()
                        ? ", externalType: " + literal(value.getExternalType()) : ""),
                Map.entry("reciprocal", CSharpRenderModel.isReciprocal(property)
                        ? ", reciprocal: static owner => " + owner + CSharpRenderModel.reciprocalEndpoint(property, name) : ""),
                Map.entry("mutator", (type.isImmutable() ? "" : ", " + prepareMutator(source, property, member, valueType))
                        + collectionMetadata(source, property)),
                Map.entry("stagedDefault", stagedDefault));
    }

    private static String propertyKind(Property property) {
        if (CSharpScalarCollections.isScalarCollection(property)) {
            return CSharpScalarCollections.snapshotKind(property);
        }
        switch (property.getKind()) {
            case SCALAR:
                return "Scalar";
            case CONTAINMENT:
                if (CSharpRenderModel.isUnorderedContainment(property)) return "ContainmentSet";
                return CSharpRenderModel.isOrdinaryContainment(property) ? "ContainmentSequence"
                        : property.isCollection() ? "ContainmentList" : "Containment";
            case REFERENCE:
                return property.isCollection() ? CSharpRenderModel.referenceCollectionName(property) : "Reference";
            default:
                throw new IllegalStateException("Unsupported snapshot property " + property.getKind());
        }
    }

    private static String prepareMutator(CSharpRenderModel source, Property property, String member, String valueType) {
        if (CSharpScalarCollections.isScalarCollection(property)) {
            return prepareScalarCollectionMutator(source, property, member, valueType);
        }
        if (property.isCollection()) {
            boolean nullableMember = CSharpRenderModel.isOrdinaryContainment(property)
                    || property.getKind() == Property.Kind.REFERENCE && !property.isUnique();
            String cast = "(" + valueType + (nullableMember ? "?)value" : ")value!");
            return "add: (owner, value) => " + member + ".Add(" + cast + ")";
        }
        boolean primitive = property.getValueType().isPrimitive();
        String cast = "(" + valueType + (primitive ? "" : "?") + ")value" + (primitive ? "!" : "");
        return "set: (owner, value) => " + member + " = " + cast;
    }

    private static String prepareScalarCollectionMutator(CSharpRenderModel source, Property property,
                                                         String member, String valueType) {
        if (property.getKind() == Property.Kind.MAP) {
            String key = source.typeName(property.getKeyType());
            return "put: (owner, key, value) => " + member + ".Add((" + key + ")key, (" + valueType + "?)value)";
        }
        boolean nullable = property.allowsNullElements();
        String cast = "(" + valueType + (nullable ? "?)value" : ")value!");
        return "add: (owner, value) => " + member + ".Add(" + cast + ")";
    }

    private static String collectionMetadata(CSharpRenderModel source, Property property) {
        if (!CSharpScalarCollections.isScalarCollection(property)) {
            return "";
        }
        int storageUpperBound = CSharpScalarCollections.storageUpperBound(property);
        String upper = storageUpperBound == -1 ? "" : ", upperBound: " + storageUpperBound;
        if (property.getKind() == Property.Kind.MAP) {
            return ", keyType: typeof(" + source.typeName(property.getKeyType()) + ")" + upper;
        }
        boolean nullable = property.allowsNullElements();
        String nullElements = nullable ? ", allowNullElements: true" : "";
        return nullElements + upper;
    }

    static String literal(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == '"' || value == '\\') {
                escaped.append('\\').append(value);
            } else if (value < 0x20 || Character.isSurrogate(value) || value == '\u0085'
                    || value == '\u2028' || value == '\u2029') {
                escaped.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) value));
            } else {
                escaped.append(value);
            }
        }
        return escaped.append('"').toString();
    }
}
