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
package eu.mihosoft.vmf.core;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Resolves Java model type parameters in the scope of one model declaration. */
final class GenericModelTypes {
    private final Class<?> owner;
    private final Model model;
    private final Map<TypeVariable<?>, Type> bindings = new HashMap<>();
    private Map<String, List<Method>> propertyDeclarations;

    GenericModelTypes(Model model, Class<?> owner) {
        this.model = model;
        this.owner = owner;
        if (owner.getTypeParameters().length != 0
                && (!owner.isAnnotationPresent(InterfaceOnly.class) || owner.isAnnotationPresent(Immutable.class))) {
            throw invalid("generic declarations require @InterfaceOnly without @Immutable; declare a named concrete subtype");
        }
        for (TypeVariable<?> variable : owner.getTypeParameters()) {
            if (variable.getBounds().length > 1 && modelBound(variable) != null) {
                throw invalid("model parameter " + variable.getName() + " has an intersection bound; define one named"
                        + " model interface extending the required interfaces and use it as the upper bound");
            }
            bindings.put(variable, variable);
            for (Type bound : variable.getBounds()) checkArgument(bound);
        }
        collect(owner, new HashSet<>());
    }

    private void collect(Class<?> declaration, Set<Class<?>> visited) {
        if (!visited.add(declaration)) return;
        for (Type parent : declaration.getGenericInterfaces()) {
            if (parent instanceof Class<?>) {
                Class<?> raw = (Class<?>) parent;
                if (raw.getTypeParameters().length != 0) {
                    throw invalid("raw generic supertype " + raw.getTypeName() + " requires type arguments");
                }
                collect(raw, visited);
                continue;
            }
            ParameterizedType applied = (ParameterizedType) parent;
            Class<?> raw = (Class<?>) applied.getRawType();
            TypeVariable<?>[] parameters = raw.getTypeParameters();
            Type[] arguments = applied.getActualTypeArguments();
            for (int i = 0; i < parameters.length; i++) {
                checkArgument(arguments[i]);
                Type bound = resolve(arguments[i]);
                if (mutableModelBound(bound) != null && mutableModelBound(parameters[i]) == null) {
                    throw invalid("cannot bind value parameter " + parameters[i].getName() + " of " + raw.getTypeName()
                            + " to " + bound.getTypeName() + "; give the parameter a named VMF model upper bound"
                            + " to generate its read-only projection");
                }
                bindings.put(parameters[i], bound);
            }
            collect(raw, visited);
        }
    }

    private void checkArgument(Type argument) {
        if (argument instanceof TypeVariable<?>) return;
        if (argument instanceof Class<?>) {
            Class<?> c = (Class<?>) argument;
            if (!c.isArray() && c.getTypeParameters().length == 0
                    && !Collection.class.isAssignableFrom(c) && !Map.class.isAssignableFrom(c)) return;
        }
        throw invalid("unsupported model type argument or bound " + argument.getTypeName()
                + "; use a named value type, model type or type parameter");
    }

    /** All declarations of an accessor, including overridden and diamond-inherited getters. */
    List<Method> propertyDeclarations(String name) {
        if (propertyDeclarations == null) {
            propertyDeclarations = new HashMap<>();
            var pending = new ArrayDeque<Class<?>>();
            var visited = new HashSet<Class<?>>();
            pending.add(owner);
            while (!pending.isEmpty()) {
                Class<?> current = pending.remove();
                if (!visited.add(current)) continue;
                for (Method method : current.getDeclaredMethods()) {
                    if (!method.isBridge() && method.getParameterCount() == 0) {
                        propertyDeclarations.computeIfAbsent(method.getName(), ignored -> new ArrayList<>()).add(method);
                    }
                }
                Arrays.stream(current.getInterfaces()).sorted(Comparator.comparing(Class::getName)).forEach(pending::add);
            }
        }
        return propertyDeclarations.getOrDefault(name, List.of());
    }

    Type resolve(Type type) {
        if (!(type instanceof TypeVariable<?>)) return type;
        Type result = bindings.get(type);
        if (result == null) throw invalid("unknown type parameter " + type.getTypeName());
        return result;
    }

    String name(Type type) {
        if (type instanceof Class<?>) {
            Class<?> c = (Class<?>) type;
            // External nested classes use their enclosing class names in Java source.
            if (c.getEnclosingClass() != null && !c.getPackageName().equals(owner.getPackageName())) {
                return c.getCanonicalName();
            }
            return model.convertModelTypeToDestination(c);
        }
        return type.getTypeName();
    }

    Class<?> modelBound(Type type) {
        if (type instanceof TypeVariable<?>) {
            for (Type bound : ((TypeVariable<?>) type).getBounds()) {
                Class<?> modelBound = modelBound(bound);
                if (modelBound != null) return modelBound;
            }
            return null;
        }
        if (!(type instanceof Class<?>)) return null;
        Class<?> candidate = (Class<?>) type;
        return model.declares(candidate) ? candidate : null;
    }

    /** Mutable targets have distinct Java types in writable and read-only APIs. */
    private Class<?> mutableModelBound(Type type) {
        Class<?> bound = modelBound(type);
        return bound != null && !bound.isAnnotationPresent(Immutable.class) ? bound : null;
    }

    private String readOnlyName(Type type) {
        if (!(type instanceof Class<?>) || mutableModelBound(type) == null) return name(type);
        return model.getPackageName() + ".ReadOnly" + ((Class<?>) type).getSimpleName();
    }

    boolean hasModelParameters() {
        return bindings.values().stream().anyMatch(t -> mutableModelBound(t) != null);
    }

    String projectedArguments() {
        return angle(Arrays.stream(owner.getTypeParameters()).map(p -> {
            Class<?> bound = mutableModelBound(p);
            return bound == null ? p.getName() : "? extends " + readOnlyName(bound);
        }).collect(Collectors.toList()));
    }

    String typeArguments() {
        return angle(Arrays.stream(owner.getTypeParameters()).map(TypeVariable::getName).collect(Collectors.toList()));
    }

    String parameters(boolean readOnly) {
        return angle(Arrays.stream(owner.getTypeParameters()).map(p -> {
            String suffix = Arrays.stream(p.getBounds()).filter(b -> b != Object.class)
                    .map(b -> readOnly ? readOnlyName(b) : name(b)).collect(Collectors.joining(" & "));
            return suffix.isEmpty() ? p.getName() : p.getName() + " extends " + suffix;
        }).collect(Collectors.toList()));
    }

    String arguments(Class<?> supertype) {
        return arguments(supertype, this::name);
    }

    String readOnlyArguments(Class<?> supertype) { return arguments(supertype, this::readOnlyName); }

    String metadataArguments(Class<?> supertype) {
        return arguments(supertype, this::binaryName);
    }

    private String arguments(Class<?> supertype, Function<Type, String> name) {
        return angle(Arrays.stream(supertype.getTypeParameters()).map(this::resolve)
                .map(name).collect(Collectors.toList()));
    }

    String binaryName(Type type) {
        if (type instanceof Class<?> && ((Class<?>) type).getEnclosingClass() != null
                && !((Class<?>) type).getPackageName().equals(owner.getPackageName())) {
            return ((Class<?>) type).getName();
        }
        return name(type);
    }

    String argumentsOf(GenericModelTypes supertype) {
        return arguments(supertype.owner);
    }

    String wildcards() {
        return angle(Collections.nCopies(owner.getTypeParameters().length, "?"));
    }

    private static String angle(List<String> names) {
        return names.isEmpty() ? "" : "<" + String.join(", ", names) + ">";
    }

    static boolean containsParameter(Type type) {
        return containsType(type, candidate -> candidate instanceof TypeVariable<?>);
    }

    static final String REF_SET = "eu.mihosoft.vmf.runtime.core.RefSet";
    static final String ORDERED_REF_SET = "eu.mihosoft.vmf.runtime.core.OrderedRefSet";

    static boolean containsReferenceFacade(Type type) {
        return containsType(type, candidate -> candidate instanceof Class<?>
                && (REF_SET.equals(candidate.getTypeName()) || ORDERED_REF_SET.equals(candidate.getTypeName())));
    }

    private static boolean containsType(Type type, java.util.function.Predicate<Type> match) {
        if (match.test(type)) return true;
        if (type instanceof Class<?> && ((Class<?>) type).isArray())
            return containsType(((Class<?>) type).getComponentType(), match);
        if (type instanceof GenericArrayType)
            return containsType(((GenericArrayType) type).getGenericComponentType(), match);
        if (type instanceof ParameterizedType) {
            ParameterizedType applied = (ParameterizedType) type;
            return match.test(applied.getRawType())
                    || (applied.getOwnerType() != null && containsType(applied.getOwnerType(), match))
                    || Arrays.stream(applied.getActualTypeArguments()).anyMatch(t -> containsType(t, match));
        }
        if (type instanceof WildcardType) {
            WildcardType wildcard = (WildcardType) type;
            return Stream.concat(Arrays.stream(wildcard.getUpperBounds()), Arrays.stream(wildcard.getLowerBounds()))
                    .anyMatch(t -> containsType(t, match));
        }
        return false;
    }

    static IllegalArgumentException unsupportedProperty(Method method, String reason) {
        return new IllegalArgumentException("Invalid VMF property " + method.getDeclaringClass().getTypeName() + "."
                + method.getName() + "() with type " + method.getGenericReturnType().getTypeName() + ": " + reason);
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid VMF type " + owner.getTypeName() + ": " + message);
    }
}
