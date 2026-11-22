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
package eu.mihosoft.vmf.runtime.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * An immutable view of the VMF types exported by the models registered for an application.
 * Type names are fully qualified, and object membership also checks Java class identity.
 */
public final class ModelRegistry {
    private final Map<String, Type> typesByName;
    private final List<Type> types;

    /**
     * Composes the exported type lists of independently generated VMF models.
     *
     * @param models model type lists, in registration order
     * @throws IllegalArgumentException if a descriptor is not an exported model type
     *                                  or different Java classes claim the same type name
     */
    public ModelRegistry(List<? extends List<Type>> models) {
        Map<String, Type> registered = new LinkedHashMap<>();
        for (List<Type> model : models) {
            for (Type type : model) {
                if (!type.isModelType() || type.isListType() || type.javaClass() == null
                        || !VObject.class.isAssignableFrom(type.javaClass())) {
                    throw new IllegalArgumentException("Expected a model type: " + type);
                }
                Type previous = registered.putIfAbsent(type.getName(), type);
                if (previous != null && previous.javaClass() != type.javaClass()) {
                    throw new IllegalArgumentException("Conflicting VMF type: " + type.getName());
                }
            }
        }
        types = List.copyOf(registered.values());
        typesByName = Map.copyOf(registered);
    }

    /**
     * Returns all registered types, including interface-only types, in registration order.
     * @return the registered types
     */
    public List<Type> allTypes() {
        return types;
    }

    /**
     * Looks up a registered type by its fully qualified name.
     * @param name fully qualified type name
     * @return the registered descriptor, if present
     */
    public Optional<Type> findType(String name) {
        return Optional.ofNullable(typesByName.get(name));
    }

    /**
     * Returns the Java interface retained by a registered type.
     * @param name fully qualified type name
     * @return registered model class, if present
     */
    public Optional<Class<? extends VObject>> findJavaType(String name) {
        return findType(name).map(type -> type.javaClass().asSubclass(VObject.class));
    }

    /**
     * Resolves the exact registered model type of an object or its read-only view.
     * @param object VMF object whose type to resolve
     * @return the registered descriptor, if the object's model type is registered
     */
    public Optional<Type> typeOf(VObject object) {
        Type actual = object.vmf().reflect().type();
        return findType(actual.getName()).filter(type -> type.javaClass() == actual.javaClass());
    }

    /**
     * Returns the registered types whose Java model interfaces implement or extend the given
     * contract, including the contract itself if registered.
     * @param contract Java class or interface to query
     * @return matching types, in registration order
     */
    public List<Type> typesAssignableTo(Class<?> contract) {
        Objects.requireNonNull(contract, "contract");
        return types.stream()
                .filter(type -> contract.isAssignableFrom(type.javaClass()))
                .collect(Collectors.toUnmodifiableList());
    }
}
