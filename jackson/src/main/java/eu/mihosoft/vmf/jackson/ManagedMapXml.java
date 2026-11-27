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
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Maps the fixed XML entry vocabulary onto a VMF scalar map. */
final class ManagedMapXml {
    private static final Pattern DECIMAL = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

    private ManagedMapXml() {}

    static void write(Map<?, ?> entries, JsonGenerator generator) throws IOException {
        generator.writeStartObject();
        generator.writeArrayFieldStart("entry");
        for (var entry : entries.entrySet()) {
            generator.writeStartObject();
            writeText("key", entry.getKey().toString(), generator);
            Object value = entry.getValue();
            if (value == null) {
                generator.writeBooleanField("null", true);
            } else if (value instanceof String || value instanceof Character) {
                writeText("value", value.toString(), generator);
            } else {
                generator.writeObjectField("value", value);
            }
            generator.writeEndObject();
        }
        generator.writeEndArray();
        generator.writeEndObject();
    }

    static void writeText(String field, String value, JsonGenerator generator) throws IOException {
        if (value.codePoints().allMatch(VMFJournalXml::preservesAsXmlText)) {
            generator.writeStringField(field, value);
        } else {
            // Encode UTF-16 code units exactly, including an isolated surrogate or a CR.
            ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(2, value.length()));
            bytes.asCharBuffer().put(value);
            generator.writeStringField(field + "64", Base64.getEncoder().encodeToString(bytes.array()));
        }
    }

    static Map<Object, Object> read(JsonNode node, JavaType mapType, DeserializationContext context)
            throws IOException {
        Map<Object, Object> result = new LinkedHashMap<>();
        // XmlMapper represents an empty element as empty text or null, depending on parser settings.
        if (node.isNull() || (node.isTextual() && node.textValue().isEmpty())) return result;
        if (!node.isObject() || node.size() != 1 || !node.has("entry")) {
            throw new IOException("Expected managed map XML entries");
        }
        JsonNode entries = node.get("entry");
        if (entries.isArray()) {
            for (JsonNode entry : entries) readEntry(entry, mapType, context, result);
        } else {
            readEntry(entries, mapType, context, result);
        }
        return result;
    }

    private static void readEntry(JsonNode entry, JavaType mapType, DeserializationContext context,
                                  Map<Object, Object> result) throws IOException {
        if (!entry.isObject() || entry.size() != 2) throw new IOException("Expected a map key and value");
        String keyText = text(entry, "key");
        Object key = readScalarText(keyText, mapType.getKeyType(), context);
        if (result.containsKey(key)) throw new IOException("Duplicate managed map key");
        JavaType valueType = mapType.getContentType();
        Object value;
        if (entry.has("null")) {
            if (!"true".equals(entry.get("null").asText())) throw new IOException("Expected a true null marker");
            value = null;
        } else if (valueType.hasRawClass(String.class) || valueType.hasRawClass(Character.class)) {
            String string = text(entry, "value");
            if (valueType.hasRawClass(Character.class)) {
                if (string.length() != 1) throw new IOException("Expected one UTF-16 code unit for a character");
                value = string.charAt(0);
            } else {
                value = string;
            }
        } else {
            JsonNode scalar = entry.get("value");
            if (scalar == null || !scalar.isValueNode() || scalar.isNull() || scalar.asText().isEmpty()) {
                throw new IOException("Expected a scalar map value");
            }
            if (valueType.hasRawClass(Boolean.class)) {
                String literal = scalar.asText();
                if (!"true".equals(literal) && !"false".equals(literal)) throw new IOException("Expected a boolean map value");
                value = Boolean.valueOf(literal);
            } else {
                value = isFloating(valueType) ? readFloating(scalar.asText(), valueType, context)
                        : TypedMapEntries.xmlNumber(scalar.asText(), valueType, context);
            }
            if (value == null) throw new IOException("Expected an explicit null marker");
        }
        result.put(key, value);
    }

    static Object readScalarText(String text, JavaType type, DeserializationContext context) throws IOException {
        if (type.hasRawClass(String.class)) return text;
        if (isFloating(type)) return readFloating(text, type, context);
        Object value = context.readTreeAsValue(com.fasterxml.jackson.databind.node.TextNode.valueOf(text), type);
        if (value == null || !text.equals(value.toString())) throw new IOException("Invalid managed scalar value for " + type);
        return value;
    }

    private static boolean isFloating(JavaType type) {
        return type.hasRawClass(Float.class) || type.hasRawClass(Double.class);
    }

    private static Object readFloating(String text, JavaType type, DeserializationContext context) throws IOException {
        // Float/Double.toString need not choose the same decimal across JVMs or language targets.
        // Read at the declared precision and keep typed duplicate and range checks.
        if (!"NaN".equals(text) && !"Infinity".equals(text) && !"-Infinity".equals(text)
                && !DECIMAL.matcher(text).matches()) {
            throw new IOException("Invalid floating-point value for " + type);
        }
        return TypedMapEntries.xmlNumber(text, type, context);
    }

    static String text(JsonNode entry, String field) throws IOException {
        JsonNode plain = entry.get(field);
        if (plain != null) {
            if (plain.isNull()) return "";
            if (plain.isTextual()) return plain.textValue();
            throw new IOException("Expected XML text in map " + field);
        }
        JsonNode encoded = entry.get(field + "64");
        if (encoded == null || !encoded.isTextual()) throw new IOException("Missing managed map " + field);
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded.textValue());
            if ((bytes.length & 1) != 0) throw new IOException("Invalid UTF-16 length for map " + field);
            return ByteBuffer.wrap(bytes).asCharBuffer().toString();
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid base64 for map " + field, exception);
        }
    }
}
