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

import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.EntryKey;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Prepares live read-only interfaces and their hidden, identity-preserving implementations. */
final class CSharpReadOnlyRenderModel {
    private static final String LIST = "global::System.Collections.Generic.IReadOnlyList";
    private static final String REFERENCE_SET = "global::Vmf.Runtime.IReadOnlyReferenceSet";
    private static final String ORDERED_REFERENCE_SET = "global::Vmf.Runtime.IReadOnlyOrderedReferenceSet";

    private final CSharpRenderModel source;

    CSharpReadOnlyRenderModel(CSharpRenderModel source) {
        this.source = source;
    }

    String interfaceName(Type model) {
        return "IReadOnly" + source.typeName(model);
    }

    List<String> traitGetters(Type trait) {
        return getterDeclarations(trait).stream()
                .map(property -> {
                    var rendered = property(trait, property, true);
                    String name = rendered.get("name");
                    return rendered.get("type") + " " + interfaceName(trait) + "." + name + " => AsReadOnly()." + name + ";";
                }).collect(Collectors.toUnmodifiableList());
    }

    private List<Property> getterDeclarations(Type type) {
        var duplicates = source.multiplyInheritedNames(type);
        return type.getEffectiveProperties().stream()
                .filter(property -> type.getProperties().stream().anyMatch(member -> member.getName().equals(property.getName()))
                        || duplicates.contains(property.getName()))
                .collect(Collectors.toUnmodifiableList());
    }

    Map<String, Object> prepare(Type model, String owner, boolean hasBase, boolean hasSubtypes) {
        String name = interfaceName(model);
        var parameters = model.getTypeParameters();
        var declaration = CSharpRenderModel.withTypeArguments(name, parameters.stream()
                .map(parameter -> (CSharpRenderModel.isScalarParameter(parameter) ? "" : "out ")
                        + CSharpRenderModel.identifier(parameter.getName(), model.getQualifiedName()))
                .collect(Collectors.toList()));
        var returnType = CSharpRenderModel.withTypeArguments(name, parameters.stream()
                .map(parameter -> CSharpRenderModel.isScalarParameter(parameter)
                        ? CSharpRenderModel.identifier(parameter.getName(), model.getQualifiedName())
                        : readOnlyType(parameter.getBound(), false)).collect(Collectors.toList()));
        var interfaces = model.getSuperTypeApplications().stream()
                .map(value -> readOnlySuperType(model, value)).collect(Collectors.toList());
        String base = interfaces.isEmpty() ? " : global::Vmf.Runtime.IReadOnlyModelView"
                : " : " + String.join(", ", interfaces);
        var constraints = parameters.stream().filter(parameter -> parameter.getBound() != null).map(parameter ->
                "where " + CSharpRenderModel.identifier(parameter.getName(), model.getQualifiedName())
                        + " : " + (parameter.isNumberBound() ? "struct"
                                : readOnlyConstraint(parameter.getBound())))
                .collect(Collectors.toUnmodifiableList());

        var inheritedNames = source.inheritedNames(model);
        var duplicateNames = source.multiplyInheritedNames(model);
        var declarations = getterDeclarations(model);
        var declared = declarations.stream()
                .map(property -> {
                    var rendered = new java.util.HashMap<>(property(model, property, true));
                    rendered.put("modifier", inheritedNames.contains(property.getName()) ? "new " : "");
                    return Map.copyOf(rendered);
                }).collect(Collectors.toUnmodifiableList());
        var effective = model.getEffectiveProperties().stream()
                .map(property -> property(model, property, false)).collect(Collectors.toUnmodifiableList());
        var collections = effective.stream().filter(property -> !property.get("initializer").isEmpty())
                .collect(Collectors.toUnmodifiableList());
        var exposedLookups = declarations.stream()
                .filter(property -> !inheritedNames.contains(property.getName()) || duplicateNames.contains(property.getName()))
                .collect(Collectors.toUnmodifiableList());
        var forwarded = covariantBaseGetters(model);
        String modifier = hasBase ? "override " : hasSubtypes ? "virtual " : "";
        return Map.ofEntries(
                Map.entry("declaration", "public interface " + declaration + base),
                Map.entry("constraints", constraints),
                Map.entry("returnType", returnType),
                Map.entry("modifier", modifier),
                Map.entry("owner", owner),
                Map.entry("declared", declared),
                Map.entry("effective", effective),
                Map.entry("declaredLookups", lookups(model, exposedLookups, true, duplicateNames)),
                Map.entry("effectiveLookups", lookups(model, model.getEffectiveProperties(), false, Set.of())),
                Map.entry("collections", collections),
                Map.entry("covariantBaseGetters", forwarded));
    }

    List<String> covariantBaseGetters(Type type) {
        var forwarding = new java.util.ArrayList<String>();
        var parents = new java.util.LinkedHashSet<Type>();
        collectAncestors(type, parents);
        for (Type parent : parents) {
            for (Property original : getterDeclarations(parent)) {
                if (!source.projectsKeyedSequenceBase(type, parent, original)
                        && !source.projectsObjectAlternativeGetter(type, parent, original)
                        && type.getEffectiveProperties().stream().noneMatch(property ->
                            CSharpHierarchyRenderModel.narrowsGetter(property, original))) continue;
                var view = property(parent, original, true);
                String name = view.get("name");
                forwarding.add(view.get("type") + " " + interfaceName(parent) + "." + name + " => " + name + ";");
            }
        }
        return List.copyOf(forwarding);
    }

    private void collectAncestors(Type type, java.util.Set<Type> parents) {
        for (String name : type.getSuperTypes()) {
            Type parent = source.findType(name);
            if (parents.add(parent)) collectAncestors(parent, parents);
        }
    }

    private List<Map<String, String>> lookups(Type owner, List<Property> properties, boolean keepParameter, Set<String> hidden) {
        return properties.stream().flatMap(property -> property.getEntryKey().stream()
                        .map(key -> lookup(owner, property, key, keepParameter, hidden.contains(property.getName()))))
                .collect(Collectors.toUnmodifiableList());
    }

    private Map<String, String> lookup(Type owner, Property property, EntryKey key, boolean keepParameter, boolean hidesInherited) {
        ValueType value = key.getValueType();
        String name = CSharpKeyedRenderModel.lookupName(owner, property, key);
        String argument = key.usesIdentity()
                ? "ModelSnapshot.UnwrapReadOnly(key) as " + source.typeName(value)
                        + " ?? throw new global::System.ArgumentException(\"Expected a generated model key.\", nameof(key))"
                : "key";
        String target = "model." + name + "(" + argument + ")";
        String projectedType = readOnlyType(property.getValueType(), keepParameter);
        String projection = source.usesGetterContract(property.getValueType())
                ? projectTrait(projectedType, target) : target + "?.AsReadOnly()";
        return Map.of("name", name, "keyType", readOnlyType(value, keepParameter),
                "modifier", hidesInherited ? "new " : "",
                "returnType", projectedType + "?",
                "expression", projection);
    }

    private Map<String, String> property(Type owner, Property property, boolean keepParameter) {
        String name = CSharpRenderModel.propertyName(owner, property);
        if (CSharpScalarCollections.isScalarCollection(property)
                || (property.isCollection() && property.getKind() == Property.Kind.REFERENCE
                        && source.usesUnboundedStorageArgument(property, false))) {
            return CSharpScalarCollections.readOnlyMember(source, property, name);
        }
        ValueType value = property.getValueType();
        String readOnly = readOnlyType(value, keepParameter);
        String target = "model." + name;
        if (property.isCollection()) {
            return collectionProperty(property, name, value, readOnly, target);
        }
        String type = value.isPrimitive() ? readOnly : readOnly + "?";
        String projected = property.getKind() == Property.Kind.SCALAR ? target
                : source.usesGetterContract(value) ? projectTrait(readOnly, target) : target + "?.AsReadOnly()";
        return Map.of("name", name, "type", type, "initializer", "", "getter", "=> " + projected + ";");
    }

    private Map<String, String> collectionProperty(Property property, String name, ValueType value,
                                                   String readOnly, String target) {
        String contract = LIST;
        String adapter = "ReadOnlyList";
        String nullable = CSharpRenderModel.isOrdinaryContainment(property) ? "?" : "";
        if (CSharpRenderModel.isUnorderedContainment(property)) {
            contract = REFERENCE_SET;
            adapter = "ReadOnlyReferenceSet";
        } else if (property.getKind() == Property.Kind.REFERENCE) {
            if (!property.isUnique()) {
                nullable = "?";
            } else if (property.isOrdered()) {
                contract = ORDERED_REFERENCE_SET;
                adapter = "ReadOnlyOrderedReferenceSet";
            } else {
                contract = REFERENCE_SET;
                adapter = "ReadOnlyReferenceSet";
            }
        }
        String project = source.usesGetterContract(value) ? projectTrait(readOnly, "child") + (nullable.isEmpty() ? "!" : "")
                : "child" + nullable + ".AsReadOnly()";
        String initializer = "new global::Vmf.Runtime." + adapter + "<" + source.propertyValueTypeName(property) + nullable + ", "
                + readOnly + nullable + ">(" + target + ", child => " + project + ")";
        String type = contract + "<" + readOnly + nullable + ">";
        if (CSharpRenderModel.isOrdinaryContainment(property)
                || (property.getEntryKey().isPresent() && !property.isUnique())
                || (property.getKind() == Property.Kind.REFERENCE && !property.isUnique())
                || CSharpRenderModel.sharesShallowSequence(property)) {
            String storage = shallowViewStorage(name);
            return Map.of("name", name, "type", type, "initializer", "", "lazyStorage", storage,
                    "getter", "=> " + storage + " ??= " + initializer + ";");
        }
        return Map.of("name", name, "type", type,
                "initializer", initializer, "getter", "{ get; }");
    }

    static String shallowViewStorage(String name) {
        return "__vmfLazy" + name;
    }

    private static String projectTrait(String readOnly, String target) {
        return "ModelSnapshot.ProjectReadOnly<" + readOnly + ">(" + target + ")";
    }

    private String readOnlyConstraint(ValueType bound) {
        if (bound.getKind() != ValueType.Kind.PARAMETER && source.findType(bound.getModelType()).isImmutable()) {
            return readOnlyType(bound, true);
        }
        return "class, " + readOnlyType(bound, true);
    }

    private String readOnlySuperType(Type context, ValueType application) {
        Type target = source.findType(application.getModelType());
        var arguments = source.renderGenericArguments(context, target, application,
                value -> readOnlyType(value, true));
        String name = target.isImmutable() ? source.typeName(target) : interfaceName(target);
        return CSharpRenderModel.withTypeArguments(name, arguments);
    }

    private String readOnlyType(ValueType value, boolean keepParameter) {
        switch (value.getKind()) {
            case PARAMETER:
                if (keepParameter || value.getModelType() == null) return source.typeName(value);
                Type bound = source.findType(value.getModelType());
                return bound.isImmutable() ? source.typeName(bound) : interfaceName(bound);
            case MODEL:
                Type target = source.findType(value.getModelType());
                if (target.isImmutable()) {
                    return source.typeName(value);
                }
                List<String> arguments = value.getArguments().stream()
                        .map(argument -> readOnlyType(argument, keepParameter)).collect(Collectors.toList());
                return CSharpRenderModel.withTypeArguments(interfaceName(target), arguments);
            default:
                return source.typeName(value);
        }
    }
}
