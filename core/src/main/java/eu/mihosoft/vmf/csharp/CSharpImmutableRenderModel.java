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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Constructor and registry data for immutable VMF values. */
final class CSharpImmutableRenderModel {
    private static final String RUNTIME = "global::Vmf.Runtime.";
    private static final String GENERIC = "global::System.Collections.Generic.";
    private static final String COLLECTIONS = RUNTIME + "ImmutableCollections.";
    private final CSharpRenderModel source;

    CSharpImmutableRenderModel(CSharpRenderModel source) {
        this.source = source;
    }

    List<Map<String, Object>> constructorProperties(Type type, List<Property> storageProperties) {
        Set<String> ownProperties = storageProperties.stream().map(Property::getName).collect(Collectors.toSet());
        return type.getEffectiveProperties().stream()
                .map(property -> constructorProperty(type, property, ownProperties.contains(property.getName())))
                .collect(Collectors.toUnmodifiableList());
    }

    String baseArguments(Type owner, Type parent, List<Property> redeclarations) {
        var redeclared = redeclarations.stream().collect(Collectors.toMap(Property::getName, property -> property));
        return " : base(" + parent.getEffectiveProperties().stream()
                .map(property -> baseArgument(owner, property, redeclared.get(property.getName())))
                .collect(Collectors.joining(", ")) + ")";
    }

    private String baseArgument(Type owner, Property inherited, Property redeclared) {
        String parameter = parameterName(inherited);
        if (redeclared == null) return parameter;
        String value = CSharpCapabilities.bindsExecutableDefault(redeclared)
                ? parameter + ".GetValueOrCreate(" + CSharpExecutableDefaultRenderModel.accessor(
                        CSharpRenderModel.propertyName(owner, redeclared)) + ")"
                : redeclared.getScalarDefault().map(scalar -> parameter + ".GetValueOrDefault("
                        + CSharpDefaultRenderModel.literal(scalar) + ")").orElse(parameter);
        return CSharpExecutableDefaultRenderModel.hasDefault(inherited)
                ? "new " + constructorType(inherited) + "(" + value + ")" : value;
    }

    Map<String, Object> builder(Type type, String declarationName, boolean hasBase) {
        var fields = type.getEffectiveProperties().stream().map(property -> builderField(type, property))
                .collect(Collectors.toUnmodifiableList());
        var imports = new ArrayList<Map<String, Object>>();
        var candidates = new LinkedHashMap<String, Type>();
        candidates.put(declarationName, type);
        candidates.putAll(source.builderSourceApplications(type));
        var propertiesByName = type.getEffectiveProperties().stream()
                .collect(Collectors.toMap(Property::getName, property -> property));
        for (var candidate : candidates.entrySet()) {
            var members = candidate.getValue().getEffectiveProperties();
            boolean sharesSlots = members.stream().allMatch(property -> {
                var target = propertiesByName.get(property.getName());
                return target != null && target.getDeclaringType().equals(property.getDeclaringType());
            });
            if (!sharesSlots) continue;
            var statements = members.stream().map(property -> {
                var target = propertiesByName.get(property.getName());
                String name = CSharpRenderModel.propertyName(type, target);
                String value = "source." + CSharpRenderModel.propertyName(candidate.getValue(), property);
                return (target.isCollection() ? "__vmfImport" : "With") + name + "(" + value + ");";
            }).collect(Collectors.toUnmodifiableList());
            imports.add(Map.of("type", candidate.getKey(), "statements", statements));
        }
        return Map.of("modifier", hasBase ? "new " : "", "fields", fields,
                "imports", List.copyOf(imports),
                "hasCollections", type.getEffectiveProperties().stream().anyMatch(Property::isCollection),
                "arguments", fields.stream().map(field -> (String) field.get("argument")).collect(Collectors.joining(", ")));
    }

    private String appendBuilderCollection(Property property, String name, String field) {
        String combined = "global::System.Linq.Enumerable.Concat(" + field + ", value)";
        if (property.getKind() == Property.Kind.MAP) {
            return field + " = " + COLLECTIONS + "AppendScalarMap(" + field + ", value, "
                    + property.getUpperBound() + ");";
        }
        if (property.getKind() == Property.Kind.SCALAR && property.isUnique() && property.isOrdered()) {
            return field + " = " + COLLECTIONS + "AppendOrderedScalarSet(" + field + ", value, "
                    + property.getUpperBound() + ");";
        }
        if (property.getKind() == Property.Kind.REFERENCE && property.isUnique()) {
            combined = "global::System.Linq.Enumerable.Distinct<" + elementType(property) + ">(" + combined
                    + ", " + GENERIC + "ReferenceEqualityComparer.Instance)";
        }
        return "With" + name + "(" + combined + ");";
    }

    private Map<String, Object> builderField(Type type, Property property) {
        String name = CSharpRenderModel.propertyName(type, property);
        String fieldName = builderFieldName(type, property);
        String value = property.isCollection() ? copyCollection(property, "value") : "value";
        var nested = CSharpNestedBuilderRenderModel.render(source, property, name);
        return Map.of("name", name, "field", fieldName, "type", constructorType(property),
                "inputType", property.isCollection() ? collectionInputType(property)
                        : propertyType(property),
                "initializer", CSharpCapabilities.bindsExecutableDefault(property)
                        || CSharpCapabilities.hasManagedCollectionDefault(property)
                        ? " = " + CSharpExecutableDefaultRenderModel.accessor(name) + "()" : "",
                "assignment", fieldName + " = " + value + ";",
                "referenceDefault", CSharpCapabilities.hasReferenceSetDefault(property)
                        ? CSharpReferenceDefaultRenderModel.cache(name) : "",
                "append", property.isCollection() ? appendBuilderCollection(property, name, fieldName) : "",
                "nested", nested,
                "argument", nested.isEmpty() ? fieldName
                        : nested.get("field") + " is null ? " + fieldName + " : " + nested.get("build"));
    }

    private static String builderFieldName(Type type, Property property) {
        return "__vmfBuilder" + CSharpRenderModel.propertyName(type, property);
    }

    List<Map<String, String>> equalityFields(Type type) {
        return type.getEffectiveProperties().stream()
                .filter(property -> !property.isIgnoredForEquals())
                .map(property -> equalityField(type, property)).collect(Collectors.toUnmodifiableList());
    }

    private Map<String, String> equalityField(Type type, Property property) {
        String name = CSharpRenderModel.propertyName(type, property);
        String other = "other." + name;
        if (property.isCollection()) {
            return collectionEquality(property, name, other);
        }
        if (property.getKind() == Property.Kind.REFERENCE) {
            return Map.of(
                    "comparison", "(" + name + " is null ? " + other + " is null : "
                            + name + ".ContentEquals(" + other + "))",
                    "hashValue", name + "?.ContentHashCode()");
        }
        String value = source.typeName(property.getValueType());
        String propertyType = property.getValueType().isPrimitive() ? value : value + "?";
        String comparison = "global::System.Collections.Generic.EqualityComparer<" + propertyType
                + ">.Default.Equals(" + name + ", " + other + ")";
        String bitConversion;
        switch (property.getValueType().getKind()) {
            case FLOAT32:
                bitConversion = "SingleToInt32Bits";
                break;
            case FLOAT64:
                bitConversion = "DoubleToInt64Bits";
                break;
            default:
                return Map.of("comparison", comparison, "hashValue", name);
        }
        // Java's boxed floating-point equality distinguishes negative from positive zero.
        boolean primitive = property.getValueType().isPrimitive();
        String suffix = primitive ? "" : ".GetValueOrDefault()";
        String bitConverter = "global::System.BitConverter." + bitConversion;
        comparison += " && (" + name + " != 0 || " + bitConverter + "(" + name + suffix + ") == "
                + bitConverter + "(" + other + suffix + "))";
        return Map.of("comparison", comparison, "hashValue", name);
    }

    String snapshotFactory(Type type) {
        if (type.getEffectiveProperties().stream().anyMatch(property -> source.inheritsGetterTraitDefault(type, property))) {
            return "values => { " + snapshotBuilder(type) + snapshotBuilderApply(type) + " }";
        }
        var args = type.getEffectiveProperties().stream()
                .map(property -> snapshotArgument(type, property)).collect(Collectors.toList());
        return "values => new " + source.typeName(type) + "(" + String.join(", ", args) + ")";
    }

    String snapshotBuilderStage(Type type) {
        if (type.getEffectiveProperties().stream().noneMatch(CSharpCapabilities::bindsExecutableDefault)) return "";
        return " { StageImmutableBuilder = static () => { " + snapshotBuilder(type)
                + " return values => {" + snapshotBuilderApply(type) + " }; } }";
    }

    private String snapshotBuilder(Type type) {
        return "var builder = " + source.typeName(type) + ".NewBuilder();";
    }

    private String snapshotBuilderApply(Type type) {
        return type.getEffectiveProperties().stream().map(property -> {
            String name = CSharpRenderModel.propertyName(type, property);
            String field = "value" + name;
            String value = property.isCollection() ? snapshotCollection(property, field)
                    : valueCast(propertyType(property), field);
            return " if (values.TryGetValue(" + CSharpSnapshotRenderModel.literal(property.getWireName())
                    + ", out var " + field + ")) builder.With" + name + "(" + value + ");";
        }).collect(Collectors.joining()) + " return builder.Build();";
    }

    private Map<String, Object> constructorProperty(Type type, Property property, boolean declared) {
        String name = CSharpRenderModel.propertyName(type, property);
        String parameter = parameterName(property);
        String initialization = property.isCollection() ? copyCollection(property, parameter)
                : property.getScalarDefault().map(value -> parameter + ".GetValueOrDefault("
                        + CSharpDefaultRenderModel.literal(value) + ")").orElse(parameter);
        if (CSharpCapabilities.bindsExecutableDefault(property)) {
            initialization = parameter + ".GetValueOrCreate(" + CSharpExecutableDefaultRenderModel.accessor(name) + ")";
        } else if (CSharpCapabilities.hasManagedCollectionDefault(property)) {
            initialization = copyCollection(property, parameter + " ?? " + CSharpCollectionDefaultRenderModel.evaluate(name));
        } else if (CSharpCapabilities.hasReferenceSetDefault(property)) {
            initialization = copyCollection(property, parameter + " ?? " + CSharpReferenceDefaultRenderModel.cache(name));
        }
        return Map.of("name", name, "type", propertyType(property), "declared", declared,
                "parameter", constructorType(property) + " " + parameter + " = " + omittedValue(property),
                "parameterWithoutDefault", constructorType(property) + " " + parameter, "parameterName", parameter,
                "initialization", initialization);
    }

    private String snapshotArgument(Type type, Property property) {
        String member = "value" + CSharpRenderModel.propertyName(type, property);
        String read = "values.TryGetValue(" + CSharpSnapshotRenderModel.literal(property.getWireName())
                + ", out var " + member + ")";
        String value = property.isCollection() ? snapshotCollection(property, member)
                : valueCast(propertyType(property), member);
        if (CSharpExecutableDefaultRenderModel.hasDefault(property)) {
            value = "(" + constructorType(property) + ")" + value;
        }
        return read + " ? " + value + " : " + omittedValue(property);
    }

    private static String parameterName(Property property) {
        return "@" + property.getName();
    }

    private static String omittedValue(Property property) {
        if (CSharpExecutableDefaultRenderModel.hasDefault(property)) {
            return "default";
        }
        if (!property.getValueType().isPrimitive()) {
            return "null";
        }
        switch (property.getValueType().getKind()) {
            case BOOLEAN:
                return "false";
            case CHAR:
                return "'\\0'";
            case INT8:
            case INT16:
            case INT32:
            case INT64:
            case FLOAT32:
            case FLOAT64:
                return "0";
            default:
                throw new IllegalStateException("Unsupported C# primitive " + property.getValueType().getKind());
        }
    }

    private String constructorType(Property property) {
        if (property.isCollection()) {
            return collectionInputType(property) + "?";
        }
        String type = propertyType(property);
        return CSharpExecutableDefaultRenderModel.hasDefault(property) ? RUNTIME + "OptionalValue<" + type + ">" : type;
    }

    private String collectionInputType(Property property) {
        return GENERIC + "IEnumerable<" + elementType(property) + ">";
    }

    String propertyType(Property property) {
        if (!property.isCollection()) {
            String value = source.typeName(property.getValueType());
            return property.getValueType().isPrimitive() ? value : value + "?";
        }
        if (property.getKind() != Property.Kind.REFERENCE) {
            return CSharpScalarCollections.readOnlyMember(source, property, "").get("type");
        }
        String contract;
        switch (CSharpRenderModel.referenceCollectionName(property)) {
            case "ReferenceList":
                contract = GENERIC + "IReadOnlyList";
                break;
            case "OrderedReferenceSet":
                contract = RUNTIME + "IReadOnlyOrderedReferenceSet";
                break;
            default:
                contract = RUNTIME + "IReadOnlyReferenceSet";
        }
        return contract + "<" + elementType(property) + ">";
    }

    private String elementType(Property property) {
        String value = source.typeName(property.getValueType()) + (property.allowsNullElements() ? "?" : "");
        return property.getKind() == Property.Kind.MAP ? mapEntryType(property, value) : value;
    }

    private String mapEntryType(Property property, String value) {
        return GENERIC + "KeyValuePair<" + source.typeName(property.getKeyType()) + ", " + value + ">";
    }

    private String copyCollection(Property property, String input) {
        String call;
        if (property.getKind() == Property.Kind.REFERENCE) {
            call = "Copy" + CSharpRenderModel.referenceCollectionName(property) + "(" + input + ")";
        } else {
            String kind = CSharpScalarCollections.snapshotKind(property);
            if (kind.equals("OrderedScalarSet")) kind = "ScalarList";
            call = "Copy" + kind + "(" + input + ", " + CSharpScalarCollections.constructorArguments(property) + ")";
        }
        return COLLECTIONS + call;
    }

    private String snapshotCollection(Property property, String field) {
        String list = "((" + GENERIC + "IReadOnlyList<object?>)" + field + "!)";
        String cast = valueCast(elementType(property), "entry");
        if (property.getKind() == Property.Kind.MAP) {
            list = "((" + GENERIC + "IReadOnlyList<" + GENERIC + "KeyValuePair<object, object?>>)" + field + "!)";
            cast = "new " + elementType(property) + "((" + source.typeName(property.getKeyType()) + ")entry.Key, "
                    + valueCast(CSharpScalarCollections.valueType(source, property), "entry.Value") + ")";
        }
        return "global::System.Linq.Enumerable.Select(" + list + ", static entry => " + cast + ")";
    }

    private static String valueCast(String type, String expression) {
        return "(" + type + ")" + expression + (type.endsWith("?") ? "" : "!");
    }

    private static Map<String, String> collectionEquality(Property property, String name, String other) {
        String kind;
        String compare = "";
        String hash = "";
        if (property.getKind() == Property.Kind.REFERENCE) {
            kind = property.isOrdered() ? "OrderedReference" : "UnorderedReference";
            compare = ", static (left, right) => left.ContentEquals(right)";
            hash = ", static value => value.ContentHashCode()";
        } else if (property.getKind() == Property.Kind.MAP) {
            kind = "ScalarMap";
        } else {
            kind = property.isOrdered() ? "OrderedScalar" : "ScalarSet";
        }
        return Map.of("comparison", COLLECTIONS + kind + "Equals(" + name + ", " + other + compare + ")",
                "hashValue", COLLECTIONS + kind + "Hash(" + name + hash + ")");
    }
}
