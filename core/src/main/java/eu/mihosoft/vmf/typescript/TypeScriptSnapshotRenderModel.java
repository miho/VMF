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
package eu.mihosoft.vmf.typescript;

import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Prepares snapshot metadata with fully resolved, concrete property bindings. */
final class TypeScriptSnapshotRenderModel {
    private final List<Map<String, Object>> registrations;
    private final List<Map<String, String>> aliases;

    TypeScriptSnapshotRenderModel(TypeScriptRenderModel source, List<Type> types, Set<String> polymorphic) {
        List<Type> concrete = types.stream().filter(type -> !type.isInterfaceOnly()).collect(Collectors.toList());
        registrations = concrete.stream()
                .map(type -> prepareType(source, type, polymorphic.contains(type.getName())))
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

    private static Map<String, Object> prepareType(TypeScriptRenderModel source, Type type, boolean writeType) {
        List<Map<String, String>> properties = type.getEffectiveProperties().stream()
                .filter(property -> property.getKind() != Property.Kind.CONTAINER)
                .map(property -> prepareProperty(source, type, property))
                .collect(Collectors.toUnmodifiableList());
        return Map.of("name", source.typeName(type), "wireName", literal(type.getQualifiedName()),
                "writeType", Boolean.toString(writeType), "properties", properties, "immutable", type.isImmutable());
    }

    private static Map<String, String> prepareProperty(TypeScriptRenderModel source, Type type, Property property) {
        String name = TypeScriptRenderModel.propertyName(type, property);
        String member = "(owner as " + source.typeName(type) + ")." + name;
        ValueType value = property.getValueType();
        String valueType = source.typeName(value);
        String data = "name: " + literal(name) + ", ";
        if (property.getKind() == Property.Kind.MAP) {
            data += "keyType: \"" + scalarKind(property.getKeyType()) + "\", scalarType: \"" + scalarKind(value)
                    + "\", nullable: true";
        } else if (property.getKind() == Property.Kind.SCALAR) {
            data += "scalarType: \"" + scalarKind(value) + "\""
                    + ", nullable: " + (property.isCollection() ? property.allowsNullElements() : !value.isPrimitive());
        } else {
            data += "modelType: () => " + source.runtimeTypeName(value);
            if (property.getKind() == Property.Kind.REFERENCE && !property.getOppositeName().isEmpty()) {
                data += ", opposite: " + literal(property.getOppositeName());
            }
            // Entries keyed by a model reference are attached once a reader has resolved the references.
            if (property.getEntryKey().filter(ResolvedModel.EntryKey::usesIdentity).isPresent()) {
                data += ", keyedByReference: true";
            }
        }
        String edit = property.getKind() == Property.Kind.MAP ? "put" : property.isCollection() ? "add" : "set";
        return Map.of("wireName", literal(property.getWireName()), "kind", propertyKind(property),
                "data", data, "getter", "get: owner => " + member,
                "mutator", type.isImmutable() ? edit + ": vmf.rejectEdit"
                        : prepareMutator(source, property, member, valueType));
    }

    private static String scalarKind(ValueType value) {
        return value.getKind().name().toLowerCase(Locale.ROOT);
    }

    private static String propertyKind(Property property) {
        switch (property.getKind()) {
            case SCALAR:
                if (!property.isCollection()) {
                    return "scalar";
                }
                return property.isOrdered() ? "scalarList" : "scalarSet";
            case MAP:
                return "scalarMap";
            case CONTAINMENT:
                if (!property.isCollection()) {
                    return "containment";
                }
                return property.isOrdered() ? "containmentList" : "containmentSet";
            case REFERENCE:
                if (!property.isCollection()) {
                    return "reference";
                }
                return property.isOrdered() ? "referenceList" : "referenceSet";
            default:
                throw new IllegalStateException("Unsupported snapshot property " + property.getKind());
        }
    }

    private static String prepareMutator(TypeScriptRenderModel source, Property property, String member,
                                         String valueType) {
        if (property.getKind() == Property.Kind.MAP) {
            return "put: (owner, key, value) => { " + member + ".put(key as " + source.typeName(property.getKeyType())
                    + ", value as " + valueType + " | null); }";
        }
        if (property.isCollection()) {
            String element = valueType + (property.getKind() == Property.Kind.SCALAR && property.allowsNullElements()
                    ? " | null" : "");
            return "add: (owner, value) => { " + member + ".add(value as " + element + "); }";
        }
        String cast = "value as " + valueType + (property.getValueType().isPrimitive() ? "" : " | null");
        return "set: (owner, value) => { " + member + " = " + cast + "; }";
    }

    static String literal(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == '"' || value == '\\') {
                escaped.append('\\').append(value);
            } else if (value < 0x20 || Character.isSurrogate(value) || value == '\u2028' || value == '\u2029') {
                escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) value));
            } else {
                escaped.append(value);
            }
        }
        return escaped.append('"').toString();
    }
}
