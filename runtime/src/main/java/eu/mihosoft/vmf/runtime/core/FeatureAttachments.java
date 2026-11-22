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

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Associates independently modeled features with objects from one application model registry.
 * The exact owner reference identifies the group, including when it is a read-only view.
 * Applications manage these associations alongside their VMF object graphs and persistence.
 */
public final class FeatureAttachments {
    private final ModelRegistry registry;
    private final Map<VObject, Map<Class<?>, VObject>> byOwner = new IdentityHashMap<>();

    /**
     * Creates an empty attachment collection for the registered models.
     * @param registry registry of permitted owner and feature types
     */
    public FeatureAttachments(ModelRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Attaches a live feature to a registered owner. Each owner has one attachment per
     * concrete VMF feature type. Attaching the same reference again has no effect.
     *
     * @param owner object that receives the feature
     * @param feature object to attach
     * @throws IllegalArgumentException if either type is unregistered or another feature
     *                                  of the same type is already attached to this owner
     */
    public synchronized void attach(VObject owner, VObject feature) {
        requireRegistered(owner);
        Class<?> featureType = requireRegistered(feature).javaClass();
        Map<Class<?>, VObject> features = byOwner.computeIfAbsent(owner, key -> new LinkedHashMap<>());
        VObject previous = features.putIfAbsent(featureType, feature);
        if (previous != null && previous != feature) {
            throw new IllegalArgumentException("Feature already attached: " + featureType.getName());
        }
    }

    /**
     * Finds the first attached feature that implements the requested Java type.
     * @param owner object that received the feature
     * @param featureType Java type to match
     * @param <T> requested feature type
     * @return the live feature, if present
     */
    public synchronized <T extends VObject> Optional<T> find(VObject owner, Class<T> featureType) {
        Objects.requireNonNull(featureType, "featureType");
        return byOwner.getOrDefault(owner, Map.of()).values().stream()
                .filter(featureType::isInstance)
                .findFirst()
                .map(featureType::cast);
    }

    /**
     * Returns an immutable list of the owner's live features in attachment order.
     * @param owner object whose attachments to list
     * @return the attached features
     */
    public synchronized List<VObject> all(VObject owner) {
        return List.copyOf(byOwner.getOrDefault(owner, Map.of()).values());
    }

    /**
     * Removes the given feature instance from this owner. Removing the last feature releases
     * the owner from this collection.
     * @param owner object that received the feature
     * @param feature exact feature instance to detach
     * @return true if the feature was attached to this owner
     */
    public synchronized boolean detach(VObject owner, VObject feature) {
        Map<Class<?>, VObject> features = byOwner.get(owner);
        if (features == null) {
            return false;
        }
        boolean removed = features.values().removeIf(attached -> attached == feature);
        if (features.isEmpty()) {
            byOwner.remove(owner);
        }
        return removed;
    }

    private Type requireRegistered(VObject object) {
        return registry.typeOf(object).orElseThrow(() -> new IllegalArgumentException(
                "Unregistered model type: " + object.vmf().reflect().type().getName()));
    }
}
