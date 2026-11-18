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
 * M. Hoffer, C. Poliwoda, & W. Wuttke. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * In Proceedings of the 2013 ACM workshop on Domain-specific modeling (DSM '13).
 * ACM, New York, NY, USA, 15-20. doi:10.1145/2541928.2541935
 */
package eu.mihosoft.vmf.runtime.core.journal;

import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Value;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Immutable registry of journal codecs for external values, scoped to a {@link JournalBinding}. */
public final class JournalValueCodecs {
    private static final JournalValueCodecs STANDARD = new JournalValueCodecs(Map.of(), Map.of())
            .withImmutable("java.uuid/v1", UUID.class, UUID::toString, UUID::fromString);

    private final Map<Class<?>, Codec<?>> types;
    private final Map<String, Codec<?>> ids;

    private JournalValueCodecs(Map<Class<?>, Codec<?>> types, Map<String, Codec<?>> ids) {
        this.types = Map.copyOf(types);
        this.ids = Map.copyOf(ids);
    }

    /** Returns the standard registry. Its external value types currently consist of {@link UUID}. */
    public static JournalValueCodecs standard() {
        return STANDARD;
    }

    /**
     * Returns a registry with a codec for one exact runtime class. Registering a class asserts
     * that its instances and their reachable state are immutable, so copies may safely share them.
     * Both functions must be deterministic and free of side effects. Encoding must give equal
     * values the same Unicode text and decoding must restore an equal instance of the exact class.
     * Use a stable, versioned codec ID and keep its meaning when reading old journals.
     * @param id stable wire identifier
     * @param type concrete immutable value class
     * @param encode function producing canonical text
     * @param decode function restoring a value from that text
     * @param <T> the value type
     * @return a new registry, leaving this registry unchanged
     * @throws IllegalArgumentException if the ID or class is already registered or the class is not an external value class
     */
    public <T> JournalValueCodecs withImmutable(String id, Class<T> type, Function<T, String> encode,
                                               Function<String, T> decode) {
        JournalSupport.requireName(id, "external codec ID");
        Objects.requireNonNull(type, "external value class");
        if (type.isPrimitive() || type.isArray() || Modifier.isAbstract(type.getModifiers())
                || VObject.class.isAssignableFrom(type) || JournalSupport.isScalarType(type)) {
            throw new IllegalArgumentException("Expected a concrete external value class: " + type.getName());
        }
        if (types.containsKey(type) || ids.containsKey(id)) {
            throw new IllegalArgumentException("External codec ID or class is already registered: " + id);
        }
        Codec<T> codec = new Codec<>(id, type, Objects.requireNonNull(encode, "encoder"),
                Objects.requireNonNull(decode, "decoder"));
        Map<Class<?>, Codec<?>> byType = new HashMap<>(types);
        Map<String, Codec<?>> byId = new HashMap<>(ids);
        byType.put(type, codec);
        byId.put(id, codec);
        return new JournalValueCodecs(byType, byId);
    }

    Value encode(Object value) {
        Codec<?> codec = types.get(value.getClass());
        if (codec == null) {
            throw new IllegalArgumentException("Unsupported journal value type " + value.getClass().getName());
        }
        return Value.external(codec.id, codec.text(value));
    }

    Object decode(Value value) {
        Codec<?> codec = ids.get(value.codecId());
        if (codec == null) throw new IllegalArgumentException("Unknown external codec " + value.codecId());
        Object decoded = codec.decode.apply(value.text());
        if (decoded == null || decoded.getClass() != codec.type || !value.text().equals(codec.text(decoded))) {
            throw new IllegalArgumentException("Invalid or noncanonical value for external codec " + codec.id);
        }
        return decoded;
    }

    private static final class Codec<T> {
        private final String id;
        private final Class<T> type;
        private final Function<T, String> encode;
        private final Function<String, T> decode;

        private Codec(String id, Class<T> type, Function<T, String> encode, Function<String, T> decode) {
            this.id = id;
            this.type = type;
            this.encode = encode;
            this.decode = decode;
        }

        private String text(Object value) {
            return encode.apply(type.cast(value));
        }
    }
}
