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
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/** Explicit member elements distinguish an empty set from a set containing the empty string. */
final class ManagedSetXml {
    private ManagedSetXml() {}

    static void write(Set<?> set, JsonGenerator generator) throws IOException {
        generator.writeStartObject();
        generator.writeArrayFieldStart("member");
        for (Object member : set) {
            generator.writeStartObject();
            ManagedMapXml.writeText("value", member.toString(), generator);
            generator.writeEndObject();
        }
        generator.writeEndArray();
        generator.writeEndObject();
    }

    static Set<Object> read(JsonNode node, JavaType elementType, DeserializationContext context) throws IOException {
        Set<Object> result = new LinkedHashSet<>();
        if (node.isNull() || (node.isTextual() && node.textValue().isEmpty())) return result;
        if (!node.isObject() || node.size() != 1 || !node.has("member")) throw new IOException("Expected set members");
        JsonNode members = node.get("member");
        if (members.isArray()) {
            for (JsonNode member : members) readMember(member, elementType, context, result);
        } else {
            readMember(members, elementType, context, result);
        }
        return result;
    }

    private static void readMember(JsonNode member, JavaType type, DeserializationContext context, Set<Object> result)
            throws IOException {
        if (!member.isObject() || member.size() != 1) throw new IOException("Expected a set member value");
        String text = ManagedMapXml.text(member, "value");
        Object decoded = type.hasRawClass(String.class) ? text : context.readTreeAsValue(TextNode.valueOf(text), type);
        if (decoded == null || !text.equals(decoded.toString()) || !result.add(decoded)) {
            throw new IOException("Invalid or duplicate set member");
        }
    }
}
