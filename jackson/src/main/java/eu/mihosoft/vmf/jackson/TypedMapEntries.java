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
 * Proceedings of the 2013 ACM workshop on Domain-Specific Modeling - DSM '13.
 * doi:10.1145/2541928.2541935
 */
package eu.mihosoft.vmf.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** A JSON/YAML entry array retains key types and permits duplicate-key validation. */
final class TypedMapEntries {
    private TypedMapEntries() {}

    static void write(Map<?, ?> map, JsonGenerator generator) throws IOException {
        generator.writeStartArray();
        for (var entry : map.entrySet()) {
            generator.writeStartObject();
            generator.writeObjectField("key", entry.getKey());
            generator.writeObjectField("value", entry.getValue());
            generator.writeEndObject();
        }
        generator.writeEndArray();
    }

    static Map<Object, Object> read(JsonNode node, JavaType type, DeserializationContext context) throws IOException {
        if (!node.isArray()) throw new IOException("Expected an array of managed map entries");
        Map<Object, Object> map = new LinkedHashMap<>();
        for (JsonNode entry : node) {
            if (!entry.isObject() || entry.size() != 2 || !entry.has("key") || !entry.has("value")) {
                throw new IOException("Expected a map entry with key and value");
            }
            Object key = scalar(entry.get("key"), type.getKeyType(), context);
            if (key == null || map.containsKey(key)) throw new IOException("Null or duplicate managed map key");
            map.put(key, scalar(entry.get("value"), type.getContentType(), context));
        }
        return map;
    }

    static Object scalar(JsonNode node, JavaType type, DeserializationContext context) throws IOException {
        if (node.isNull()) return null;
        if (type.hasRawClass(String.class) || type.hasRawClass(Character.class)) {
            if (!node.isTextual() || (type.hasRawClass(Character.class) && node.textValue().length() != 1)) {
                throw new IOException("Expected text for " + type);
            }
        } else if (type.hasRawClass(Boolean.class)) {
            if (!node.isBoolean()) throw new IOException("Expected a boolean");
        } else if (type.hasRawClass(Float.class) || type.hasRawClass(Double.class)) {
            if (!node.isNumber() && !(node.isTextual() && ("NaN".equals(node.textValue())
                    || "Infinity".equals(node.textValue()) || "-Infinity".equals(node.textValue())))) {
                throw new IOException("Expected a floating-point value");
            }
            if (node.isNumber()) {
                double rounded = type.hasRawClass(Float.class) ? node.floatValue() : node.doubleValue();
                if (!Double.isFinite(rounded) || (rounded == 0d && node.decimalValue().signum() != 0)) {
                    throw new IOException("Number outside the range of " + type);
                }
            }
        } else {
            if (!node.isIntegralNumber()) throw new IOException("Expected an integer for " + type);
            try {
                var integer = node.bigIntegerValue();
                if (type.hasRawClass(Byte.class)) return integer.byteValueExact();
                if (type.hasRawClass(Short.class)) return integer.shortValueExact();
                if (type.hasRawClass(Integer.class)) return integer.intValueExact();
                if (type.hasRawClass(Long.class)) return integer.longValueExact();
            } catch (ArithmeticException outOfRange) {
                throw new IOException("Integer outside the range of " + type, outOfRange);
            }
        }
        return context.readTreeAsValue(node, type);
    }

    static Object xmlNumber(String literal, JavaType type, DeserializationContext context) throws IOException {
        var nodes = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        try {
            if (type.hasRawClass(Float.class) || type.hasRawClass(Double.class)) {
                if ("NaN".equals(literal) || "Infinity".equals(literal) || "-Infinity".equals(literal)) {
                    return scalar(nodes.textNode(literal), type, context);
                }
                var decimal = new java.math.BigDecimal(literal);
                if (decimal.signum() == 0 && literal.startsWith("-")) {
                    return scalar(nodes.numberNode(-0.0d), type, context);
                }
                return scalar(nodes.numberNode(decimal), type, context);
            }
            return scalar(nodes.numberNode(new java.math.BigInteger(literal)), type, context);
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid number for " + type, invalid);
        }
    }
}
