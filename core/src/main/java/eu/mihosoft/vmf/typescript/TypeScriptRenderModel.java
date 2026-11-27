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
import eu.mihosoft.vmf.core.codegen.TypeScriptCapabilities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Prepares native TypeScript declarations from the analyzed VMF model. */
final class TypeScriptRenderModel {
    private static final Pattern PORTABLE_IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern RESERVED_PATH_COMPONENT = Pattern.compile(
            "(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])");
    private static final Set<String> RESERVED_MEMBERS = Set.of(
            "constructor", "__proto__", "validate", "__vmfValidateInto", "__vmfContainerLink",
            "contentEquals", "contentHashCode", "deepCopy", "vmfChanges", "asReadOnly", "vmfType",
            "hasOwnProperty", "isPrototypeOf", "propertyIsEnumerable", "toLocaleString", "toString", "valueOf");
    private static final String BRAND_PREFIX = "__vmfBrand_";
    private static final String READ_ONLY_PREFIX = "ReadOnly";
    private static final String INTERNAL_PREFIX = "__vmf";

    private final ResolvedModel model;
    private final String outputPath;
    private final Map<String, String> typeNames = new LinkedHashMap<>();
    private final List<Map<String, Object>> types;
    private final List<Map<String, String>> supertypeDeclarations = new ArrayList<>();
    private final List<Map<String, Object>> readOnlyTypes;
    private final List<String> modelTypes;
    private final TypeScriptSnapshotRenderModel snapshots;
    /** Getter-only properties of interface-only types that each subtype declares with its own storage. */
    private final Map<String, Set<String>> abstractSlots = new HashMap<>();
    /** The properties whose storage a class declares or inherits from its base classes. */
    private final Map<String, Set<String>> storage = new HashMap<>();
    /** The type whose class is being prepared. */
    private Type preparing;
    /** Concrete types that other classes implement besides their base class; their brand is a public symbol. */
    private final Set<String> symbolBrands = new java.util.TreeSet<>();
    /** External types: UUID, untyped values, literal unions and host types with their imports. */
    private final TypeScriptExternals externals;
    /** Delegated methods and construction hooks. */
    private final TypeScriptBehaviorRenderModel behaviors;
    /** Mutable classes whose declared defaults are applied after the construction hook, as in Java. */
    private final Set<String> deferredDefaults = new HashSet<>();
    /** Bound defaults by declaring type and property: the TypeScript type of the value. */
    private final Map<String, Map<String, String>> boundDefaults = new java.util.TreeMap<>();
    /** External types whose document codec the bindings supply, by member name. */
    private final Map<String, String> codecs = new java.util.TreeMap<>();
    private final Set<String> emittedNames;
    private final List<String> behaviorDeclarations;
    private final List<String> behaviorInterfaces;

    TypeScriptRenderModel(ResolvedModel model) {
        this.model = model;
        outputPath = Arrays.stream(model.getNamespace().split("\\.", -1))
                .map(part -> namespacePart(part, model.getNamespace()))
                .collect(Collectors.joining("/")) + "/model.g.ts";

        emittedNames = new HashSet<>();
        emittedNames.add("ModelSnapshot");
        for (Type type : model.getTypes()) {
            String emitted = typeIdentifier(type.getName(), type.getQualifiedName());
            if (!emittedNames.add(emitted)) {
                throw invalidName(type.getQualifiedName(), emitted, "conflicts with another TypeScript declaration");
            }
            typeNames.put(type.getName(), emitted);
        }
        for (Type type : model.getTypes()) {
            String view = READ_ONLY_PREFIX + typeNames.get(type.getName());
            if (!type.isImmutable() && !emittedNames.add(view)) {
                throw invalidName(type.getQualifiedName(), view, "conflicts with the read-only view of another type");
            }
        }

        behaviors = new TypeScriptBehaviorRenderModel(model, this, emittedNames);
        externals = new TypeScriptExternals(model, emittedNames);

        List<Type> ordered = parentFirst(model);
        for (Type type : ordered) {
            prepareStorage(type);
            for (String secondary : secondarySupertypes(type)) {
                if (!model.findType(secondary).orElseThrow().isInterfaceOnly()) {
                    symbolBrands.add(secondary);
                }
            }
        }
        prepareDeferredDefaults(ordered);
        types = ordered.stream().map(this::prepareType).collect(Collectors.toUnmodifiableList());
        readOnlyTypes = ordered.stream().filter(type -> !type.isImmutable()).map(this::prepareReadOnly)
                .collect(Collectors.toUnmodifiableList());
        modelTypes = ordered.stream().map(this::modelTypeDeclaration).collect(Collectors.toUnmodifiableList());
        snapshots = new TypeScriptSnapshotRenderModel(this, ordered, model.polymorphicWireTypeNames());
        behaviorDeclarations = behaviors.getDeclarations(ordered);
        behaviorInterfaces = behaviors.getInterfaces();
        if (needsBindings() && (!emittedNames.add("ModelBindings") || !emittedNames.add("installModelBindings"))) {
            throw invalidName(model.getNamespace(), "ModelBindings", "conflicts with another TypeScript declaration");
        }
    }

    /**
     * A construction hook runs before the declared defaults, as in Java: the hook sees the zero values, and the
     * defaults replace what it set. Every class of a base class family with a hook therefore starts with zero
     * values and applies its defaults in __vmfApplyDefaults(), which the constructor of the concrete class calls
     * after the hook.
     */
    private void prepareDeferredDefaults(List<Type> ordered) {
        Set<String> roots = new HashSet<>();
        for (Type type : ordered) {
            if (!type.isInterfaceOnly() && !type.isImmutable() && behaviors.hasHook(type)) {
                roots.add(familyRoot(type).getName());
            }
        }
        for (Type type : ordered) {
            if (!type.isImmutable() && !type.isGetterOnlyTrait() && roots.contains(familyRoot(type).getName())) {
                deferredDefaults.add(type.getName());
            }
        }
    }

    private Type familyRoot(Type type) {
        Type base = primaryType(type);
        return base == null ? type : familyRoot(base);
    }

    /** True if the model needs host code: external codecs, bound defaults or behaviors. */
    boolean needsBindings() {
        return !codecs.isEmpty() || !boundDefaults.isEmpty() || !behaviors.getBindings().isEmpty();
    }

    /** The imports of external types from their modules. */
    List<String> getExternalImports() {
        return externals.getImports();
    }

    /** The type aliases of external types that are unions of string literals. */
    List<String> getExternalAliases() {
        return externals.getAliases();
    }

    /** The behavior interfaces of the types that declare delegated methods or construction hooks. */
    List<String> getBehaviorInterfaces() {
        return behaviorInterfaces;
    }

    /** vmf.declareBehavior calls for the classes with delegated methods. */
    List<String> getBehaviorDeclarations() {
        return behaviorDeclarations;
    }

    /** The ModelBindings interface, the binding slot and the behavior factories, or nothing if none is needed. */
    Map<String, Object> getBindings() {
        if (!needsBindings()) {
            return Map.of();
        }
        List<String> members = new ArrayList<>();
        List<String> required = new ArrayList<>();
        if (!codecs.isEmpty()) {
            members.add("    /** Document codecs of the external value types: how documents write and read their values. */");
            members.add("    readonly externals: {");
            codecs.forEach((key, type) -> {
                members.add("        readonly " + key + ": vmf.ExternalCodec<" + type + ">;");
                required.add("externals." + key);
            });
            members.add("    };");
        }
        if (!boundDefaults.isEmpty()) {
            members.add("    /** Declared defaults bound with " + TypeScriptCapabilities.BIND_DEFAULT_ANNOTATION
                    + ": called for each new object. */");
            members.add("    readonly defaults: {");
            boundDefaults.forEach((owner, properties) -> {
                members.add("        readonly " + owner + ": {");
                properties.forEach((property, type) -> {
                    members.add("            readonly " + property + ": () => " + type + ";");
                    required.add("defaults." + owner + "." + property);
                });
                members.add("        };");
            });
            members.add("    };");
        }
        var behaviorBindings = behaviors.getBindings();
        if (!behaviorBindings.isEmpty()) {
            members.add("    /** Default behaviors: each object gets its own, created when it first needs it. */");
            members.add("    readonly behaviors: {");
            behaviorBindings.forEach((owner, type) -> {
                members.add("        readonly " + owner + ": " + type + ";");
                required.add("behaviors." + owner);
            });
            members.add("    };");
        }
        return Map.of("members", List.copyOf(members),
                "slot", "const __vmfBindings = new vmf.ModelBindingSlot<ModelBindings>(" + quoted(model.getNamespace())
                        + ", [" + required.stream().map(TypeScriptRenderModel::quoted).collect(Collectors.joining(", "))
                        + "]);",
                "factories", behaviors.getFactories());
    }

    private static List<Type> parentFirst(ResolvedModel model) {
        List<Type> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        model.getTypes().stream().sorted(Comparator.comparing(Type::getName))
                .forEach(type -> appendType(model, type, visited, ordered));
        return ordered;
    }

    private static void appendType(ResolvedModel model, Type type, Set<String> visited, List<Type> ordered) {
        if (visited.add(type.getName())) {
            for (String parent : type.getSuperTypes()) {
                appendType(model, model.findType(parent).orElseThrow(), visited, ordered);
            }
            ordered.add(type);
        }
    }

    String getOutputPath() {
        return outputPath;
    }

    List<Map<String, Object>> getTypes() {
        return types;
    }

    TypeScriptSnapshotRenderModel getSnapshots() {
        return snapshots;
    }

    /** The read-only view interfaces of the mutable model types and getter-only traits. */
    List<Map<String, Object>> getReadOnlyTypes() {
        return readOnlyTypes;
    }

    /** The reflection declarations of all model types. */
    List<String> getModelTypes() {
        return modelTypes;
    }

    /** Model supertypes a class implements besides its base class, for {@code instanceof}. */
    List<Map<String, String>> getSupertypeDeclarations() {
        return supertypeDeclarations;
    }

    /** The brand symbols of concrete types that other classes implement besides their base class. */
    List<String> getBrandSymbols() {
        return symbolBrands.stream().map(name -> "declare const " + BRAND_PREFIX + typeNames.get(name) + ": unique symbol;")
                .collect(Collectors.toUnmodifiableList());
    }

    private Type primaryType(Type type) {
        ValueType primary = primarySupertype(type);
        return primary == null ? null : model.findType(primary.getModelType()).orElseThrow();
    }

    /** The supertypes of a class that are not on the chain of its base classes. */
    private Set<String> secondarySupertypes(Type type) {
        ValueType primary = primarySupertype(type);
        Set<String> secondary = supertypeClosure(type.getName());
        if (primary != null) {
            secondary.removeAll(supertypeClosure(primary.getModelType()));
            secondary.remove(primary.getModelType());
        }
        return secondary;
    }

    /**
     * Records which properties a class stores. A class stores what its base classes store and declares the rest;
     * an interface-only type leaves a getter-only property abstract when a subtype declares it again with other
     * storage (a narrower reference, or the containment or container end of a relationship), so each subtype
     * declares its own. Getter-only traits store nothing.
     */
    private void prepareStorage(Type type) {
        Type base = primaryType(type);
        Set<String> inherited = base == null ? Set.of() : storage.get(base.getName());
        Set<String> abstracted = new HashSet<>();
        Set<String> stored = new HashSet<>(inherited);
        if (!type.isGetterOnlyTrait()) {
            for (Property property : type.getEffectiveProperties()) {
                if (inherited.contains(property.getName())) continue;
                if (type.isInterfaceOnly() && !type.isImmutable()
                        && (property.isGetterOnly() && redeclaredWithOtherStorage(type, property)
                            || mergedWithOtherStorage(type, property))) {
                    abstracted.add(property.getName());
                } else {
                    stored.add(property.getName());
                }
            }
        } else {
            stored.clear();
        }
        abstractSlots.put(type.getName(), abstracted);
        storage.put(type.getName(), stored);
    }

    private boolean redeclaredWithOtherStorage(Type owner, Property property) {
        for (Type candidate : model.getTypes()) {
            if (candidate == owner || !isSubtype(candidate, owner.getName())) continue;
            for (Property declared : candidate.getProperties()) {
                if (declared.getName().equals(property.getName()) && !sameStorage(declared, property)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a subtype merges a property of an interface-only type into other storage without declaring it again,
     * as when a plain reference list becomes the keyed containment that another supertype declares. Properties
     * typed by a type parameter only change with the type arguments, which the storage of the base class keeps.
     */
    private boolean mergedWithOtherStorage(Type owner, Property property) {
        if (property.getValueType().getKind() == ValueType.Kind.PARAMETER) return false;
        for (Type candidate : model.getTypes()) {
            if (candidate == owner || !isSubtype(candidate, owner.getName())) continue;
            Optional<Property> merged = effectiveProperty(candidate, property.getName());
            if (merged.isPresent() && !sameStorage(merged.get(), property)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameStorage(Property first, Property second) {
        return first.getKind() == second.getKind() && first.isCollection() == second.isCollection()
                && first.isOrdered() == second.isOrdered() && first.isUnique() == second.isUnique()
                && first.allowsNullElements() == second.allowsNullElements()
                && first.getOppositeName().equals(second.getOppositeName())
                && sameValueType(first.getValueType(), second.getValueType());
    }

    private static boolean sameValueType(ValueType first, ValueType second) {
        if (first.getKind() != second.getKind() || first.isPrimitive() != second.isPrimitive()
                || !java.util.Objects.equals(first.getModelType(), second.getModelType())
                || !java.util.Objects.equals(first.getExternalType(), second.getExternalType())
                || !java.util.Objects.equals(first.getParameterName(), second.getParameterName())
                || first.getArguments().size() != second.getArguments().size()) {
            return false;
        }
        for (int i = 0; i < first.getArguments().size(); i++) {
            if (!sameValueType(first.getArguments().get(i), second.getArguments().get(i))) return false;
        }
        return true;
    }

    private Optional<Property> effectiveProperty(Type type, String name) {
        return type.getEffectiveProperties().stream().filter(property -> property.getName().equals(name)).findFirst();
    }

    /**
     * The supertype a class extends. TypeScript classes have one base class; the other supertypes must be
     * interface-only and are implemented. A mutable class extends the first supertype that is not interface-only,
     * else the first interface-only one; an immutable class extends its immutable supertype; a getter-only trait
     * extends its first trait. Mutable and immutable classes implement getter-only traits.
     */
    private ValueType primarySupertype(Type type) {
        List<ValueType> applications = type.getSuperTypeApplications();
        if (type.isGetterOnlyTrait()) {
            return applications.isEmpty() ? null : applications.get(0);
        }
        ValueType firstInterfaceOnly = null;
        for (ValueType application : applications) {
            Type parent = model.findType(application.getModelType()).orElseThrow();
            if (parent.isGetterOnlyTrait()) {
                continue;
            }
            if (type.isImmutable()) {
                if (parent.isImmutable()) {
                    return application;
                }
            } else if (!parent.isInterfaceOnly()) {
                return application;
            } else if (firstInterfaceOnly == null) {
                firstInterfaceOnly = application;
            }
        }
        return type.isImmutable() ? null : firstInterfaceOnly;
    }

    private Set<String> supertypeClosure(String typeName) {
        Set<String> closure = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>(List.of(typeName));
        while (!pending.isEmpty()) {
            for (String parent : model.findType(pending.removeFirst()).orElseThrow().getSuperTypes()) {
                if (closure.add(parent)) {
                    pending.add(parent);
                }
            }
        }
        return closure;
    }

    private Map<String, Object> prepareType(Type type) {
        preparing = type;
        String name = typeNames.get(type.getName());
        Set<String> parameterNames = new HashSet<>();
        List<String> parameters = new ArrayList<>();
        List<String> declarations = new ArrayList<>();
        for (var parameter : type.getTypeParameters()) {
            String parameterName = typeIdentifier(parameter.getName(), type.getQualifiedName());
            if (typeNames.containsValue(parameterName) || !parameterNames.add(parameterName)) {
                throw invalidName(type.getQualifiedName(), parameterName, "conflicts with another type name");
            }
            parameters.add(parameterName);
            declarations.add(parameterDeclaration(parameterName, parameter));
        }

        Set<String> members = new HashSet<>(RESERVED_MEMBERS);
        for (Property property : type.getEffectiveProperties()) {
            String member = propertyName(type, property);
            if (!members.add(member) || member.startsWith(INTERNAL_PREFIX)) {
                throw invalidName(type.getQualifiedName() + "." + property.getName(), member,
                        "conflicts with another TypeScript member");
            }
        }
        for (Property property : type.getEffectiveProperties()) {
            Optional<String> lookup = lookupName(type, property);
            if (lookup.isPresent() && !members.add(lookup.get())) {
                throw invalidName(type.getQualifiedName() + "." + property.getName(), lookup.get(),
                        "conflicts with another TypeScript member");
            }
        }

        ValueType primary = primarySupertype(type);
        Type base = primaryType(type);
        Set<String> inherited = new HashSet<>();
        if (base != null) {
            if (type.isGetterOnlyTrait()) {
                base.getEffectiveProperties().forEach(property -> inherited.add(property.getName()));
            } else {
                inherited.addAll(storage.get(base.getName()));
            }
        }
        Set<String> baseAbstract = base == null ? Set.of() : abstractSlots.get(base.getName());
        List<String> implemented = new ArrayList<>();
        for (ValueType application : type.getSuperTypeApplications()) {
            if (application != primary) {
                implemented.add(typeName(application));
            }
        }
        Set<String> secondary = secondarySupertypes(type);
        if (!implemented.isEmpty()) {
            supertypeDeclarations.add(Map.of("type", name, "supertypes", secondary.stream()
                    .map(typeNames::get).collect(Collectors.joining(", "))));
        }
        // A concrete type's brand keeps structurally equal classes apart. Classes that other classes implement
        // besides their base carry a public symbol brand, which the implementing classes declare too.
        List<String> brands = new ArrayList<>();
        if (!type.isInterfaceOnly()) {
            brands.add(symbolBrands.contains(type.getName())
                    ? "declare readonly [" + BRAND_PREFIX + name + "]: true;"
                    : "declare protected readonly " + BRAND_PREFIX + name + ": void;");
        }
        for (String implementedType : secondary) {
            if (symbolBrands.contains(implementedType)) {
                brands.add("declare readonly [" + BRAND_PREFIX + typeNames.get(implementedType) + "]: true;");
            }
        }

        List<Map<String, Object>> properties = new ArrayList<>();
        String owner = apply(name, parameters);
        // Properties of supertypes besides the base class are declared on the class itself.
        Set<String> abstracted = abstractSlots.get(type.getName());
        boolean deferred = deferredDefaults.contains(type.getName());
        List<String> defaults = new ArrayList<>();
        for (Property property : type.getEffectiveProperties()) {
            String member = propertyName(type, property);
            if (inherited.contains(property.getName())) {
                // A redeclaration can change the literal default of the inherited storage.
                if (!type.isGetterOnlyTrait() && !type.isImmutable()) {
                    if (deferred) {
                        redeclaredDefault(base, property, member).ifPresent(unused ->
                                defaults.add("this.__vmfValue_" + member + " = " + initialValue(property) + ";"));
                    } else {
                        redeclaredDefault(base, property, member).ifPresent(properties::add);
                    }
                }
                continue;
            }
            boolean override = baseAbstract.contains(property.getName());
            if (abstracted.contains(property.getName())) {
                Property earlier = override ? effectiveProperty(base, property.getName()).orElseThrow() : null;
                if (earlier == null || !sameValueType(earlier.getValueType(), property.getValueType())) {
                    properties.add(field("abstract " + (override ? "override " : "") + "get " + member + "(): "
                            + valueType(property, false) + ";"));
                }
                continue;
            }
            properties.add(prepareMember(owner, member, property, override));
            if (deferred && property.getKind() == Property.Kind.SCALAR && !property.isCollection()
                    && !initialValue(property).equals(zeroValue(property))) {
                defaults.add("this.__vmfValue_" + member + " = " + initialValue(property) + ";");
            }
        }

        boolean hasBase = primary != null;
        String extendsClause = (hasBase ? " extends " + typeName(primary) : "")
                + (implemented.isEmpty() ? "" : " implements " + String.join(", ", implemented));
        String modifier = type.isInterfaceOnly() ? "abstract " : "";
        String readOnlyType = apply(READ_ONLY_PREFIX + name, parameters);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("declaration", "export " + modifier + "class " + apply(name, declarations) + extendsClause);
        // Interface-only types stay structural so that classes can implement them besides their base.
        result.put("brands", List.copyOf(brands));
        result.put("interfaceOnly", type.isInterfaceOnly());
        result.put("hasInstance", type.isInterfaceOnly() || symbolBrands.contains(type.getName()));
        // A subclass's constructor inherits Symbol.hasInstance (from Function at the latest), so redefining it
        // needs the override modifier under noImplicitOverride.
        result.put("staticOverride", base != null ? "override " : "");
        result.put("hasBase", hasBase);
        result.put("readOnlyType", readOnlyType);
        List<String> methods = behaviors.methods(type, base, members);
        if (type.isGetterOnlyTrait()) {
            result.put("kind", "trait");
            List<String> traitMembers = new ArrayList<>(traitMembers(type, base, inherited, hasBase, readOnlyType));
            traitMembers.addAll(methods);
            result.put("members", traitMembers);
        } else {
            // Every class checks the effective properties of its own type (see TypeScriptValidationRenderModel).
            TypeScriptValidationRenderModel.prepare(type, base, result);
            result.put("methods", methods);
            if (type.isImmutable()) {
                result.put("kind", "immutable");
                prepareImmutable(type, base, inherited, result);
            } else {
                result.put("kind", "model");
                result.put("properties", List.copyOf(properties));
                result.put("construct", deferred && !type.isInterfaceOnly() ? construct(type, name, hasBase) : "");
                result.put("applyDefaults", deferred && (base == null || !defaults.isEmpty())
                        ? applyDefaults(base != null, defaults) : "");
                result.put("replace", behaviors.replace(type, base));
            }
        }
        return result;
    }

    /** The constructor of a class whose family has a construction hook: hook first, then the declared defaults. */
    private String construct(Type type, String name, boolean hasBase) {
        String hook = behaviors.hookCall(type);
        return "constructor() {\n"
                + (hasBase ? "        super();\n" : "")
                + "        if (new.target === " + name + ") {\n"
                + (hook.isEmpty() ? "" : "            " + hook + "\n")
                + "            this.__vmfApplyDefaults();\n"
                + "        }\n"
                + "    }";
    }

    private static String applyDefaults(boolean override, List<String> assignments) {
        StringBuilder text = new StringBuilder("/** @internal Applies the declared defaults; constructors call it after the"
                + " construction hook. */\n    " + (override ? "override " : "") + "__vmfApplyDefaults(): void {\n");
        if (override) {
            text.append("        super.__vmfApplyDefaults();\n");
        }
        assignments.forEach(assignment -> text.append("        ").append(assignment).append("\n"));
        return text.append("    }").toString();
    }

    /** A getter-only trait is an abstract contract that mutable and immutable classes implement. */
    private List<String> traitMembers(Type type, Type base, Set<String> inherited, boolean hasBase,
                                      String readOnlyType) {
        List<String> members = new ArrayList<>();
        for (Property property : type.getEffectiveProperties()) {
            if (!inherited.contains(property.getName())) {
                members.add("abstract get " + propertyName(type, property) + "(): " + valueType(property, false) + ";");
            } else if (!sameValueType(effectiveProperty(base, property.getName()).orElseThrow().getValueType(),
                    property.getValueType())) {
                // A trait can narrow the getter it inherits.
                members.add("abstract override get " + propertyName(type, property) + "(): "
                        + valueType(property, false) + ";");
            }
        }
        if (hasBase) {
            members.add("abstract override asReadOnly(): " + readOnlyType + ";");
        } else {
            // Every class that implements a trait validates itself, so trait-typed children can be validated.
            members.add("/** @internal */\n    abstract __vmfValidateInto(issues: vmf.ValidationIssue[], path: string, "
                    + "modelPath: string, pending: vmf.ValidationTask[]): void;");
            members.add("abstract asReadOnly(): " + readOnlyType + ";");
            members.add("abstract contentEquals(other: unknown): boolean;");
            members.add("abstract contentHashCode(): number;");
            members.add("abstract get vmfType(): vmf.ModelType;");
        }
        return members;
    }

    /** An immutable class receives all its values in the constructor and freezes the finished object. */
    private void prepareImmutable(Type type, Type base, Set<String> inherited, Map<String, Object> result) {
        List<String> inputs = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        List<String> assignments = new ArrayList<>();
        List<String> getters = new ArrayList<>();
        for (Property property : type.getEffectiveProperties()) {
            String member = propertyName(type, property);
            String stored = valueType(property, true);
            String input = property.getKind() == Property.Kind.MAP
                    ? "Iterable<readonly [" + typeName(property.getKeyType()) + ", " + mapValueType(property) + "]>"
                    : property.isCollection() ? "Iterable<" + elementType(property) + ">" : stored;
            inputs.add("        readonly " + member + "?: " + input + ";");
            if (inherited.contains(property.getName())) {
                Property earlier = effectiveProperty(base, property.getName()).orElseThrow();
                String field = "__vmfValue_" + member;
                // A redeclaration can change the literal default the base class would store.
                if (!property.isCollection() && !initialValue(property).equals(initialValue(earlier))) {
                    assignments.add("if (values." + member + " === undefined) {\n"
                            + "            (this as { " + field + ": " + stored + " })." + field + " = "
                            + initialValue(property) + ";\n        }");
                }
                // A narrowed reference keeps the base class's field; the constructor only accepts the narrower type.
                if (!sameValueType(earlier.getValueType(), property.getValueType())) {
                    getters.add("override get " + member + "(): " + stored + " {\n        return this." + field
                            + " as " + stored + ";\n    }");
                }
                continue;
            }
            String field = "__vmfValue_" + member;
            fields.add("/** @internal */\n    readonly " + field + ": " + stored + ";");
            boolean uuid = isUuid(property.getValueType());
            assignments.add(property.isCollection()
                    ? "this." + field + " = " + immutableCollection(property, uuid ? "[...(values." + member
                        + " ?? [])].map(value => vmf.canonicalUuid(value)" + (property.allowsNullElements() ? "" : "!")
                        + ")" : "values." + member + " ?? []") + ";"
                    : "this." + field + " = values." + member + " !== undefined ? "
                        + (uuid ? "vmf.canonicalUuid(values." + member + ")" : "values." + member) + " : "
                        + initialValue(property) + ";");
            getters.add("get " + member + "(): " + stored + " {\n        return this." + field + ";\n    }");
        }
        result.put("parameter", inputs.isEmpty() ? "values: Readonly<Record<string, never>> = {}"
                : "values: {\n" + String.join("\n", inputs) + "\n    } = {}");
        result.put("fields", List.copyOf(fields));
        result.put("assignments", List.copyOf(assignments));
        result.put("getters", List.copyOf(getters));
    }

    /**
     * The fixed collection of an immutable value: a list, a set or a map, checked like the collection of a mutable
     * type (bounds, unique and non-null elements).
     */
    private static String immutableCollection(Property property, String input) {
        // Model collections and ordinary lists leave bounds to validation.
        boolean managed = property.getKind() == Property.Kind.MAP
                || property.getKind() == Property.Kind.SCALAR && !property.allowsNullElements();
        String bound = managed && property.getUpperBound() >= 0 ? "upperBound: " + property.getUpperBound() : "";
        if (property.getKind() == Property.Kind.MAP) {
            return "vmf.ReadOnlyMap.of(" + input + options(bound) + ")";
        }
        if (!property.isOrdered()) {
            return "vmf.ReadOnlySet.of(" + input + options(bound) + ")";
        }
        List<String> checks = new ArrayList<>();
        if (property.isUnique()) {
            checks.add("unique: true");
        }
        if (!bound.isEmpty()) {
            checks.add(bound);
        }
        if (property.getKind() == Property.Kind.SCALAR && !property.allowsNullElements()) {
            checks.add("nullable: false");
        }
        return "vmf.ReadOnlyList.of(" + input + options(String.join(", ", checks)) + ")";
    }

    private static String options(String entries) {
        return entries.isEmpty() ? "" : ", { " + entries + " }";
    }

    /** The read-only view interface of a mutable type or getter-only trait. */
    private Map<String, Object> prepareReadOnly(Type type) {
        String name = typeNames.get(type.getName());
        List<String> parameters = new ArrayList<>();
        List<String> declarations = new ArrayList<>();
        for (var parameter : type.getTypeParameters()) {
            String parameterName = typeIdentifier(parameter.getName(), type.getQualifiedName());
            parameters.add(parameterName);
            declarations.add(parameterDeclaration(parameterName, parameter));
        }
        List<String> parents = type.getSuperTypeApplications().stream()
                .map(application -> READ_ONLY_PREFIX + typeName(application)).collect(Collectors.toList());
        List<String> members = new ArrayList<>();
        for (Property property : type.getEffectiveProperties()) {
            members.add("readonly " + propertyName(type, property) + ": " + readOnlyType(property) + ";");
        }
        for (Property property : type.getEffectiveProperties()) {
            lookupName(type, property).ifPresent(lookup -> {
                ValueType key = property.getEntryKey().orElseThrow().getValueType();
                String keyType = key.getKind() == ValueType.Kind.MODEL
                        ? typeName(key) + " | " + readOnlyTypeName(key) : typeName(key);
                members.add(lookup + "(key: " + keyType + "): " + readOnlyTypeName(property.getValueType()) + " | null;");
            });
        }
        members.add("asReadOnly(): " + apply(READ_ONLY_PREFIX + name, parameters) + ";");
        members.add("contentEquals(other: unknown): boolean;");
        members.add("contentHashCode(): number;");
        members.add("readonly vmfType: vmf.ModelType;");
        if (!type.isGetterOnlyTrait()) {
            members.add("readonly vmfChanges: vmf.ReadOnlyModelChanges;");
        }
        return Map.of("type", name,
                "declaration", "export interface " + apply(READ_ONLY_PREFIX + name, declarations)
                        + (parents.isEmpty() ? "" : " extends " + String.join(", ", parents)),
                "members", List.copyOf(members));
    }

    /** Registers the reflection data of a type: name, annotations and properties in VMF property order. */
    private String modelTypeDeclaration(Type type) {
        StringBuilder declaration = new StringBuilder("vmf.declareModelType(").append(typeNames.get(type.getName()))
                .append(", ").append(quoted(type.getQualifiedName())).append(", ").append(type.isImmutable())
                .append(", ").append(annotations(type.getAnnotations())).append(", [");
        for (Property property : type.getEffectiveProperties()) {
            declaration.append("\n    [").append(quoted(propertyName(type, property))).append(", ")
                    .append(annotations(property.getAnnotations())).append("],");
        }
        if (!type.getEffectiveProperties().isEmpty()) {
            declaration.append("\n");
        }
        declaration.append("]");
        List<String> lookups = type.getEffectiveProperties().stream()
                .map(property -> lookupName(type, property)).flatMap(Optional::stream)
                .map(TypeScriptRenderModel::quoted).collect(Collectors.toList());
        if (!lookups.isEmpty()) {
            declaration.append(", [").append(String.join(", ", lookups)).append("]");
        }
        return declaration.append(");").toString();
    }

    private static String annotations(List<ResolvedModel.MetadataAnnotation> annotations) {
        return annotations.stream().map(annotation -> "[" + quoted(annotation.getKey()) + ", "
                + quoted(annotation.getValue()) + "]").collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * The type of a property as a getter returns it. Mutable classes return their live lists, which getter-only
     * traits declare as {@code vmf.ListView}; immutable classes return a {@code vmf.ReadOnlyList}.
     */
    private String valueType(Property property, boolean immutable) {
        if (property.getKind() == Property.Kind.MAP) {
            return (immutable ? "vmf.ReadOnlyMap<" : "vmf.MapView<") + typeName(property.getKeyType()) + ", "
                    + mapValueType(property) + ">";
        }
        if (property.isCollection()) {
            if (immutable) {
                return (property.isOrdered() ? "vmf.ReadOnlyList<" : "vmf.ReadOnlySet<") + elementType(property) + ">";
            }
            return (property.isOrdered() ? "vmf.ListView<" : "vmf.CollectionView<") + elementType(property) + ">";
        }
        ValueType value = property.getValueType();
        return typeName(value) + (value.isPrimitive() ? "" : " | null");
    }

    private String elementType(Property property) {
        return typeName(property.getValueType())
                + (property.getKind() == Property.Kind.SCALAR && property.allowsNullElements() ? " | null" : "");
    }

    /** Map values may be null. */
    private String mapValueType(Property property) {
        return typeName(property.getValueType()) + " | null";
    }

    /** The lookup method of a keyed containment: {@code find<Property>By<Key>}, as in Java. */
    private static Optional<String> lookupName(Type owner, Property property) {
        return property.getEntryKey().map(key -> "find" + capitalized(propertyName(owner, property)) + "By"
                + capitalized(checkedIdentifier(key.getPropertyName(), owner.getQualifiedName() + "." + property.getName()
                        + " key")));
    }

    private static String capitalized(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** The type of a property in a read-only view: model values and lists are read-only too. */
    private String readOnlyType(Property property) {
        ValueType value = property.getValueType();
        if (property.getKind() == Property.Kind.MAP) {
            return valueType(property, false);
        }
        if (property.getKind() == Property.Kind.SCALAR) {
            return property.isCollection()
                    ? (property.isOrdered() ? "vmf.ListView<" : "vmf.CollectionView<") + elementType(property) + ">"
                    : typeName(value) + (value.isPrimitive() ? "" : " | null");
        }
        String element = readOnlyTypeName(value);
        if (!property.isCollection()) {
            return element + " | null";
        }
        return (property.isOrdered() ? "vmf.ListView<" : "vmf.CollectionView<") + element + ">";
    }

    private String readOnlyTypeName(ValueType value) {
        if (value.getKind() == ValueType.Kind.PARAMETER) {
            return "vmf.ReadOnlyOf<" + typeName(value) + ">";
        }
        boolean immutable = model.findType(value.getModelType()).filter(Type::isImmutable).isPresent();
        return immutable ? typeName(value) : READ_ONLY_PREFIX + typeName(value);
    }

    /**
     * The scalar field of an inherited property whose redeclaration changes the literal default; it starts with the
     * new default when the object is created.
     */
    private Optional<Map<String, Object>> redeclaredDefault(Type base, Property property, String member) {
        if (base == null || property.getKind() != Property.Kind.SCALAR || property.isCollection()) {
            return Optional.empty();
        }
        Property earlier = effectiveProperty(base, property.getName()).orElseThrow();
        String initial = initialValue(property);
        if (initial.equals(initialValue(earlier))) {
            return Optional.empty();
        }
        String type = typeName(property.getValueType()) + (property.getValueType().isPrimitive() ? "" : " | null");
        return Optional.of(field("/** @internal */\n    override __vmfValue_" + member + ": " + type + " = "
                + initial + ";"));
    }

    private Map<String, Object> prepareMember(String owner, String member, Property property, boolean override) {
        Map<String, Object> declaration = prepareMember(owner, member, property);
        if (!override) {
            return declaration;
        }
        if (Boolean.TRUE.equals(declaration.get("container"))) {
            Map<String, Object> container = new LinkedHashMap<>(declaration);
            container.put("modifier", "override ");
            return container;
        }
        String text = (String) declaration.get("declaration");
        text = text.replace("    get " + member + "(", "    override get " + member + "(")
                .replace("    set " + member + "(", "    override set " + member + "(");
        if (text.startsWith("readonly " + member + " ")) {
            text = "override " + text;
        }
        return field(text);
    }

    private Map<String, Object> prepareMember(String owner, String member, Property property) {
        String target = typeName(property.getValueType());
        String name = quoted(member);
        switch (property.getKind()) {
            case CONTAINMENT: {
                String containerProperty = property.getOppositeName().isEmpty() ? ""
                        : ", " + quoted(checkedIdentifier(property.getOppositeName(), owner + "." + member + " opposite"));
                // A getter-only trait does not show the containment link that its mutable implementations have.
                String link = ownedByRole(property) ? "vmf.containmentLinkOf" : "child => child.__vmfContainerLink";
                if (property.isCollection()) {
                    return field(containmentCollection(owner, member, property, target, containerProperty, link));
                }
                return field("/** @internal */\n"
                        + "    readonly __vmfSlot_" + member + " = new vmf.ContainmentSlot<" + owner + ", " + target
                        + ">(this, " + name + ", " + link + containerProperty + ");\n\n"
                        + accessor(member, target, "this.__vmfSlot_" + member + ".value",
                                "this.__vmfSlot_" + member + ".set(value);"));
            }
            case CONTAINER:
                return prepareContainer(owner, member, property, target);
            case REFERENCE: {
                // A class that implements a generic supertype besides its base declares the supertype's scalar
                // slot itself; an immutable type argument keeps the slot's storage.
                Optional<Property> slot = scalarSlotOrigin(property);
                if (slot.isPresent()) {
                    if (property.isCollection()) {
                        String element = target + (slot.get().allowsNullElements() ? " | null" : "");
                        return field("readonly " + member + " = new vmf.ScalarList<" + element + ">(this, " + name + ");");
                    }
                    return valueAccessor(member, target + " | null", "null");
                }
                String opposite = property.getOppositeName();
                if (property.isCollection()) {
                    String arguments = "this, " + name + (opposite.isEmpty() ? "" : ", "
                            + quoted(checkedIdentifier(opposite, owner + "." + member + " opposite")));
                    if (!property.isOrdered()) {
                        return field("readonly " + member + " = new vmf.ReferenceSet<" + target + ">(" + arguments + ");");
                    }
                    if (opposite.isEmpty() && property.isUnique()) {
                        arguments += ", undefined, { unique: true }";
                    }
                    return field("readonly " + member + " = new vmf.ReferenceList<" + target + ">(" + arguments + ");");
                }
                if (opposite.isEmpty()) {
                    return valueAccessor(member, target + " | null", "null");
                }
                String oppositeName = quoted(checkedIdentifier(opposite, owner + "." + member + " opposite"));
                return field("/** @internal */\n"
                        + "    readonly __vmfRef_" + member + " = new vmf.ReferenceSlot<" + target + ">(this, " + name + ");\n\n"
                        + accessor(member, target, "this.__vmfRef_" + member + ".value",
                                "vmf.setReference(this, value, " + name + ", " + oppositeName + ");"));
            }
            case SCALAR: {
                ValueType value = property.getValueType();
                if (property.isCollection()) {
                    return field(scalarCollection(member, property, target));
                }
                String write = isUuid(value) ? "vmf.canonicalUuid(value)" : isUntyped(value) ? "vmf.untyped(value)" : "value";
                return valueAccessor(member, target + (value.isPrimitive() ? "" : " | null"),
                        deferredDefaults.contains(preparing.getName()) ? zeroValue(property) : initialValue(property),
                        write);
            }
            case MAP:
                return field("readonly " + member + " = new vmf.ScalarMap<" + typeName(property.getKeyType()) + ", "
                        + mapValueType(property) + ">(this, " + name + upperBound(property) + ");");
            default:
                throw new IllegalStateException("Unsupported property kind " + property.getKind());
        }
    }

    /** The declaration of a property as a scalar slot of a type parameter, if its declaring type has one. */
    private Optional<Property> scalarSlotOrigin(Property property) {
        return model.findType(property.getDeclaringType())
                .flatMap(declaring -> declaring.getProperties().stream()
                        .filter(origin -> origin.getName().equals(property.getName())).findFirst())
                .filter(origin -> origin.getKind() == Property.Kind.SCALAR
                        && origin.getValueType().getKind() == ValueType.Kind.PARAMETER);
    }

    /** Whether the children of a containment property are typed by a getter-only trait (or a parameter bound to one). */
    private boolean ownedByRole(Property property) {
        String target = property.getValueType().getModelType();
        return target != null && model.findType(target).filter(Type::isGetterOnlyTrait).isPresent();
    }

    /**
     * An ordered containment list or an unordered containment set. A keyed one checks the keys of its children and
     * gets a lookup method on the owner, {@code find<Property>By<Key>(key)}.
     */
    private String containmentCollection(String owner, String member, Property property, String target,
                                         String containerProperty, String link) {
        String kind = property.isOrdered() ? "ContainmentList" : "ContainmentSet";
        String name = quoted(member);
        String declaration = "readonly " + member + " = new vmf." + kind + "<" + owner + ", " + target + ">(this, "
                + name + ", " + link;
        Optional<ResolvedModel.EntryKey> key = property.getEntryKey();
        if (key.isEmpty()) {
            return declaration + containerProperty + ");";
        }
        String keyProperty = checkedIdentifier(key.get().getPropertyName(), owner + "." + member + " key");
        String keyType = typeName(key.get().getValueType());
        String lookup = "find" + capitalized(member) + "By" + capitalized(keyProperty);
        return declaration + (containerProperty.isEmpty() ? ", undefined" : containerProperty)
                + ", {\n        key: { property: " + quoted(keyProperty) + ", of: child => child." + keyProperty + " },\n    });\n\n"
                + "    /** The entry of {@link " + member + "} whose " + keyProperty + " is the key, or null. */\n"
                + "    " + lookup + "(key: " + keyType + "): " + target + " | null {\n"
                + "        return this." + member + ".findByKey(key);\n"
                + "    }";
    }

    /**
     * A scalar list or set. Sets, ordered sets ({@code ORDERED_SET}) and lists with an upper bound reject null and
     * edits beyond the bound; other lists accept null and leave bounds to validation.
     */
    private String scalarCollection(String member, Property property, String target) {
        String name = quoted(member);
        if (!property.isOrdered()) {
            return "readonly " + member + " = new vmf.ScalarSet<" + target + ">(this, " + name + upperBound(property)
                    + ");";
        }
        // UUIDs are kept in canonical form and untyped values store undefined as null.
        ValueType value = property.getValueType();
        String normalize = isUuid(value) ? (property.allowsNullElements() ? "normalize: vmf.canonicalUuid"
                : "normalize: value => vmf.canonicalUuid(value)!") : isUntyped(value) ? "normalize: vmf.untyped" : "";
        if (property.allowsNullElements()) {
            return "readonly " + member + " = new vmf.ScalarList<" + target + " | null>(this, " + name
                    + (normalize.isEmpty() ? "" : ", { " + normalize + " }") + ");";
        }
        List<String> options = new ArrayList<>();
        if (property.isUnique()) {
            options.add("unique: true");
        }
        if (property.getUpperBound() >= 0) {
            options.add("upperBound: " + property.getUpperBound());
        }
        options.add("nullable: false");
        if (!normalize.isEmpty()) {
            options.add(normalize);
        }
        return "readonly " + member + " = new vmf.ScalarList<" + target + ">(this, " + name + ", { "
                + String.join(", ", options) + " });";
    }

    private static String upperBound(Property property) {
        return property.getUpperBound() >= 0 ? ", " + property.getUpperBound() : "";
    }

    /** A single value stored in a field and changed through {@code vmf.setValue}, which reports the change. */
    private static Map<String, Object> valueAccessor(String member, String type, String initial) {
        return valueAccessor(member, type, initial, "value");
    }

    /** {@code write} checks and normalizes the new value, for UUIDs and untyped values. */
    private static Map<String, Object> valueAccessor(String member, String type, String initial, String write) {
        return field("/** @internal */\n"
                + "    __vmfValue_" + member + ": " + type + " = " + initial + ";\n\n"
                + "    get " + member + "(): " + type + " {\n"
                + "        return this.__vmfValue_" + member + ";\n"
                + "    }\n\n"
                + "    set " + member + "(value: " + type + ") {\n"
                + "        vmf.setValue(this, " + quoted(member) + ", " + write + ");\n"
                + "    }");
    }

    private static String accessor(String member, String type, String read, String write) {
        return "    get " + member + "(): " + type + " | null {\n"
                + "        return " + read + ";\n"
                + "    }\n\n"
                + "    set " + member + "(value: " + type + " | null) {\n"
                + "        " + write + "\n"
                + "    }";
    }

    /**
     * A container property reads the owner from the containment link. With a declared opposite it can also be set;
     * a shared container (no opposite) accepts every containment property that names it as its opposite.
     */
    private Map<String, Object> prepareContainer(String owner, String member, Property property, String target) {
        Type containerType = model.findType(property.getValueType().getModelType()).orElseThrow();
        List<String> owners = new ArrayList<>();
        String attach = "";
        if (property.getOppositeName().isEmpty()) {
            for (Type candidate : model.getTypes()) {
                if (!isSubtype(candidate, containerType.getName())) continue;
                for (Property containment : candidate.getEffectiveProperties()) {
                    if (containment.getKind() == Property.Kind.CONTAINMENT
                            && containment.getOppositeName().equals(property.getName())
                            && !owners.contains(containment.getName())) {
                        owners.add(containment.getName());
                    }
                }
            }
        } else {
            String opposite = checkedIdentifier(property.getOppositeName(), owner + "." + member + " opposite");
            owners.add(opposite);
            // Further containment properties of the container can name this end as their opposite.
            for (Property alternate : containerType.getEffectiveProperties()) {
                if (alternate.getKind() == Property.Kind.CONTAINMENT && !alternate.getName().equals(opposite)
                        && alternate.getOppositeName().equals(property.getName())) {
                    owners.add(alternate.getName());
                }
            }
            Property inverse = containerType.getEffectiveProperties().stream()
                    .filter(candidate -> candidate.getName().equals(opposite)).findFirst().orElseThrow();
            // The container can have narrowed its end to a subtype of this class (Owner.primary: Owned for an
            // OwnedTarget); the objects that reach this setter are of that subtype.
            String self = inverse.getValueType().getModelType() == null
                    || isSubtype(preparing, inverse.getValueType().getModelType())
                    ? "this" : "this as unknown as " + typeName(inverse.getValueType());
            attach = inverse.isCollection() ? "value." + opposite + ".add(" + self + ")" : "value." + opposite + " = " + self;
        }
        return Map.of("container", true, "name", member, "type", target, "modifier", "",
                "ownerType", typeName(containerType),
                "owners", owners.stream().map(TypeScriptRenderModel::quoted).collect(Collectors.joining(", ")),
                "settable", !attach.isEmpty(), "attach", attach);
    }

    private boolean isSubtype(Type type, String superTypeName) {
        if (type.getName().equals(superTypeName)) return true;
        for (String parent : type.getSuperTypes()) {
            Optional<Type> parentType = model.findType(parent);
            if (parentType.isPresent() && isSubtype(parentType.get(), superTypeName)) return true;
        }
        return false;
    }

    /**
     * The value a new object starts with: the declared literal default, the value of a bound default from the
     * installed bindings, the member of a literal union that the default names, or the zero value.
     */
    private String initialValue(Property property) {
        if (property.hasDeclaredDefault() && property.getScalarDefault().isPresent()) {
            ResolvedModel.ScalarDefault declared = property.getScalarDefault().get();
            return declared.isNull() ? "null" : literal(declared.getKind(), declared.getValue());
        }
        if (TypeScriptCapabilities.bindsDefault(property)) {
            return boundDefault(property);
        }
        var literal = TypeScriptCapabilities.literalDefault(model, property);
        if (literal.isPresent()) {
            return quoted(literal.get());
        }
        return zeroValue(property);
    }

    /** A bound default: the declaring type's binding computes the value for each new object. */
    private String boundDefault(Property property) {
        String owner = property.getDeclaringType();
        String key = "defaults." + owner + "." + property.getName();
        boundDefaults.computeIfAbsent(owner, unused -> new java.util.TreeMap<>()).put(property.getName(),
                typeName(property.getValueType()) + (property.getValueType().isPrimitive() ? "" : " | null"));
        String call = "__vmfBindings.use(" + quoted(key) + ", bindings => bindings." + key + ")()";
        return isUuid(property.getValueType()) ? "vmf.canonicalUuid(" + call + ")" : call;
    }

    /** True if documents write null for the property instead of leaving it out: its default is not null. */
    boolean writesNull(Property property) {
        return !property.isCollection() && !property.getValueType().isPrimitive()
                && (property.getScalarDefault().filter(value -> !value.isNull()).isPresent()
                    || TypeScriptCapabilities.bindsDefault(property)
                    || TypeScriptCapabilities.literalDefault(model, property).isPresent());
    }

    /** The scalar type of a scalar property in the snapshot registry, with the value semantics of externals. */
    String scalarData(Property property) {
        ValueType value = property.getValueType();
        String kind = value.getKind().name().toLowerCase(java.util.Locale.ROOT);
        String extra = "";
        if (value.getKind() == ValueType.Kind.EXTERNAL) {
            var mapping = externals.mapping(value);
            kind = mapping.getScalarType();
            if (mapping.getLiterals() != null) {
                extra = ", literals: [" + mapping.getLiterals().stream().map(TypeScriptRenderModel::quoted)
                        .collect(Collectors.joining(", ")) + "]";
            }
            if (mapping.needsCodec()) {
                codecs.put(mapping.getKey(), mapping.getTypeName());
                String key = "externals." + mapping.getKey();
                extra = ", codec: () => __vmfBindings.use(" + quoted(key) + ", bindings => bindings." + key + ")";
            }
        }
        return "scalarType: \"" + kind + "\", nullable: "
                + (property.isCollection() ? property.allowsNullElements() : !value.isPrimitive()) + extra
                + (writesNull(property) ? ", writeNull: true" : "");
    }

    private static boolean isUuid(ValueType value) {
        return TypeScriptCapabilities.UUID.equals(value.getExternalType());
    }

    private boolean isUntyped(ValueType value) {
        return value.getKind() == ValueType.Kind.EXTERNAL && TypeScriptCapabilities.isUnboundObject(model, value);
    }

    /** The zero value of a property: false, 0, 0n or U+0000 for primitives, else null. */
    private static String zeroValue(Property property) {
        ValueType value = property.getValueType();
        if (!value.isPrimitive()) {
            return "null";
        }
        switch (value.getKind()) {
            case BOOLEAN:
                return "false";
            case INT64:
                return "0n";
            case CHAR:
                return quoted("\u0000");
            default:
                return "0";
        }
    }

    private static String literal(ValueType.Kind kind, Object value) {
        switch (kind) {
            case STRING:
                return quoted(String.valueOf(value));
            case BOOLEAN:
                return String.valueOf(value);
            case INT8:
            case INT16:
            case INT32:
                return String.valueOf(((Number) value).intValue());
            case INT64:
                return ((Number) value).longValue() + "n";
            case CHAR:
                return quoted(String.valueOf(value));
            case FLOAT32: {
                float number = ((Number) value).floatValue();
                if (Float.isNaN(number)) return "Number.NaN";
                if (Float.isInfinite(number)) return number > 0 ? "Number.POSITIVE_INFINITY" : "Number.NEGATIVE_INFINITY";
                return Float.toString(number);
            }
            case FLOAT64: {
                double number = ((Number) value).doubleValue();
                if (Double.isNaN(number)) return "Number.NaN";
                if (Double.isInfinite(number)) return number > 0 ? "Number.POSITIVE_INFINITY" : "Number.NEGATIVE_INFINITY";
                return Double.toString(number);
            }
            default:
                throw new IllegalStateException("Unsupported TypeScript default of kind " + kind);
        }
    }

    static String quoted(String text) {
        return TypeScriptSnapshotRenderModel.literal(text);
    }

    private static Map<String, Object> field(String declaration) {
        return Map.of("container", false, "declaration", declaration);
    }

    String typeName(Type type) {
        return typeNames.get(type.getName());
    }

    /**
     * A type parameter: unbounded and {@code Number}-bounded parameters hold scalars, which are numbers or bigints
     * for {@code Number}; other parameters extend their model or parameter bound.
     */
    private String parameterDeclaration(String name, ResolvedModel.TypeParameter parameter) {
        if (parameter.getBound() == null) {
            return name;
        }
        return name + " extends " + (parameter.isNumberBound() ? "number | bigint" : typeName(parameter.getBound()));
    }

    String typeName(ValueType value) {
        switch (value.getKind()) {
            case BOOLEAN:
                return "boolean";
            case INT8:
            case INT16:
            case INT32:
            case FLOAT32:
            case FLOAT64:
                return "number";
            case INT64:
                return "bigint";
            case CHAR:
            case STRING:
                return "string";
            case PARAMETER:
                return typeIdentifier(value.getParameterName(), "generic parameter");
            case MODEL:
                return apply(typeNames.get(value.getModelType()), value.getArguments().stream().map(this::typeName)
                        .collect(Collectors.toList()));
            case EXTERNAL:
                return externals.mapping(value).getTypeName();
            default:
                throw new IllegalStateException("Unsupported TypeScript type " + value.getKind());
        }
    }

    String runtimeTypeName(ValueType value) {
        return typeNames.get(value.getModelType());
    }

    private static String apply(String name, List<String> arguments) {
        return arguments.isEmpty() ? name : name + "<" + String.join(", ", arguments) + ">";
    }

    static String propertyName(Type owner, Property property) {
        return checkedIdentifier(property.getName(), owner.getQualifiedName() + "." + property.getName());
    }

    private static String namespacePart(String name, String namespace) {
        String declaration = "namespace " + namespace;
        String part = checkedIdentifier(name, declaration);
        if (RESERVED_PATH_COMPONENT.matcher(part).matches()) {
            throw invalidName(declaration, part, "is not a portable output directory name");
        }
        return part;
    }

    private static String typeIdentifier(String name, String declaration) {
        String identifier = checkedIdentifier(name, declaration);
        return Character.toUpperCase(identifier.charAt(0)) + identifier.substring(1);
    }

    private static String checkedIdentifier(String name, String declaration) {
        if (!PORTABLE_IDENTIFIER.matcher(name).matches()) {
            throw invalidName(declaration, name, "must be a portable TypeScript identifier");
        }
        return name;
    }

    private static IllegalArgumentException invalidName(String declaration, String name, String reason) {
        return new IllegalArgumentException("Cannot generate TypeScript for " + declaration
                + ": name '" + name + "' " + reason);
    }
}
