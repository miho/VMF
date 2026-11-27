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
 */
package eu.mihosoft.vmf.runtime.core.internal;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vcollections.VMapChangeListener;
import eu.mihosoft.vcollections.VSet;
import eu.mihosoft.vcollections.VSetChangeEvent;
import vjavax.observer.Subscription;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
import java.util.function.Consumer;

/** Connects to the optional public VCollections preparation API at subscription time. */
final class MembershipPreparation {
    private static final MethodType CALLBACK = MethodType.methodType(Optional.class, Object.class, Object.class);
    private static final Hooks HOOKS = link();

    private MembershipPreparation() {}

    static boolean available() {
        return HOOKS != null;
    }

    static <K, V> Optional<Subscription> observe(VMap<K, V> map, VMapChangeListener<K, V> listener) {
        return subscribe(HOOKS.map, map, listener);
    }

    static <E> Optional<Subscription> observe(VSet<E> set, Consumer<? super VSetChangeEvent<E>> listener) {
        return subscribe(HOOKS.set, set, listener);
    }

    private static Hooks link() {
        try {
            Class<?> preparation = Class.forName("eu.mihosoft.vcollections.VCollectionPreparation",
                    false, VMap.class.getClassLoader());
            return new Hooks(hook(preparation, VMap.class, VMapChangeListener.class),
                    hook(preparation, VSet.class, Consumer.class));
        } catch (ReflectiveOperationException | SecurityException unavailable) {
            return null;
        }
    }

    private static MethodHandle hook(Class<?> preparation, Class<?> source, Class<?> listener)
            throws ReflectiveOperationException {
        return MethodHandles.publicLookup().findStatic(preparation, "observe",
                MethodType.methodType(Optional.class, source, listener)).asType(CALLBACK);
    }

    @SuppressWarnings("unchecked")
    private static Optional<Subscription> subscribe(MethodHandle hook, Object source, Object listener) {
        try {
            return (Optional<Subscription>) hook.invokeExact(source, listener);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not register a VCollections preparation listener", failure);
        }
    }

    private static final class Hooks {
        final MethodHandle map;
        final MethodHandle set;

        Hooks(MethodHandle map, MethodHandle set) {
            this.map = map;
            this.set = set;
        }
    }
}
