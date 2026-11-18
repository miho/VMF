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
package eu.mihosoft.vmf.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Creation;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Frame;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Kind;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Operation;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Value;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.ValueKind;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Reads and writes the versioned JSON format of a portable {@link ChangeJournal}.
 *
 * <p>The envelope contains {@code format}, {@code version}, {@code model} with
 * an {@code id} and {@code version}, {@code root}, {@code base} with a
 * {@code checkpoint} and {@code sequence}, and a {@code transactions} array.
 * Each transaction contains its {@code sequence} and an ordered {@code operations} array.
 *
 * <p>Each operation contains {@code kind}, {@code target}, {@code property},
 * {@code indices}, {@code before}, {@code after}, and {@code created}. A creation
 * contains its {@code id}, {@code type}, and {@code properties}, an object keyed
 * by property name with an ordered array of values for each property. Each value
 * uses {@code kind} and {@code text}. The text is a JSON string for every kind
 * except {@code NULL}, which requires JSON null. Kind tags are the names of
 * the corresponding {@link Kind} and {@link ValueKind} constants.
 *
 * <p>The codec writes fixed field order, sorts the keys of creation properties,
 * and escapes non-ASCII text for reproducible output.
 * It reads objects in any field order. Every documented field is required.
 */
public final class VMFJournalCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final DefaultIndenter INDENTER = new DefaultIndenter("  ", "\n");
    private static final ObjectWriter WRITER = MAPPER.writer(new DefaultPrettyPrinter()
            .withObjectIndenter(INDENTER).withArrayIndenter(INDENTER))
            .with(JsonWriteFeature.ESCAPE_NON_ASCII);

    private static final Set<String> ENVELOPE_FIELDS = Set.of("format", "version", "model", "root", "base", "transactions");
    private static final Set<String> MODEL_FIELDS = Set.of("id", "version");
    private static final Set<String> BASE_FIELDS = Set.of("checkpoint", "sequence");
    private static final Set<String> FRAME_FIELDS = Set.of("sequence", "operations");
    private static final Set<String> OPERATION_FIELDS = Set.of("kind", "target", "property", "indices", "before", "after", "created");
    private static final Set<String> CREATION_FIELDS = Set.of("id", "type", "properties");
    private static final Set<String> VALUE_FIELDS = Set.of("kind", "text");

    private VMFJournalCodec() {
    }

    /**
     * Writes a journal as JSON using the current wire version.
     * @param journal journal to encode
     * @return the journal JSON
     * @throws IOException when writing fails
     */
    public static String write(ChangeJournal journal) throws IOException {
        Objects.requireNonNull(journal, "journal");
        ObjectNode envelope = NODES.objectNode()
                .put("format", ChangeJournal.FORMAT)
                .put("version", ChangeJournal.VERSION);
        envelope.set("model", NODES.objectNode().put("id", journal.model()).put("version", journal.modelVersion()));
        envelope.put("root", journal.root());
        envelope.set("base", NODES.objectNode().put("checkpoint", journal.checkpoint()).put("sequence", journal.baseSequence()));
        envelope.set("transactions", writeArray(journal.transactions(), VMFJournalCodec::writeFrame));
        return WRITER.writeValueAsString(envelope);
    }

    /**
     * Reads one JSON journal with strict wire and journal-record validation.
     * @param json the complete journal JSON
     * @return the decoded journal
     * @throws IOException when the JSON or journal contents are invalid or the format is unsupported
     */
    public static ChangeJournal read(String json) throws IOException {
        if (json == null) throw invalid("$", "expected journal JSON");
        ObjectNode envelope = object(MAPPER.readTree(json), "$");
        String format = text(envelope.get("format"), "$.format");
        if (!ChangeJournal.FORMAT.equals(format)) throw invalid("$.format", "unsupported journal format " + format);
        int version = integer(envelope.get("version"), "$.version");
        if (version != ChangeJournal.VERSION) throw invalid("$.version", "unsupported journal version " + version);
        fields(envelope, "$", ENVELOPE_FIELDS);

        ObjectNode model = object(envelope.get("model"), "$.model", MODEL_FIELDS);
        ObjectNode base = object(envelope.get("base"), "$.base", BASE_FIELDS);
        try {
            return new ChangeJournal(text(model.get("id"), "$.model.id"), integer(model.get("version"), "$.model.version"),
                    text(envelope.get("root"), "$.root"), text(base.get("checkpoint"), "$.base.checkpoint"),
                    longInteger(base.get("sequence"), "$.base.sequence"),
                    readArray(envelope.get("transactions"), "$.transactions", VMFJournalCodec::readFrame));
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw invalid("$", exception);
        }
    }

    private static ObjectNode writeFrame(Frame frame) {
        ObjectNode node = NODES.objectNode().put("sequence", frame.sequence());
        node.set("operations", writeArray(frame.operations(), VMFJournalCodec::writeOperation));
        return node;
    }

    private static ObjectNode writeOperation(Operation operation) {
        ObjectNode node = NODES.objectNode().put("kind", operation.kind().name())
                .put("target", operation.target()).put("property", operation.property());
        node.set("indices", writeArray(operation.indices(), index -> NODES.numberNode(index.intValue())));
        node.set("before", writeArray(operation.before(), VMFJournalCodec::writeValue));
        node.set("after", writeArray(operation.after(), VMFJournalCodec::writeValue));
        node.set("created", writeArray(operation.created(), VMFJournalCodec::writeCreation));
        return node;
    }

    private static ObjectNode writeCreation(Creation creation) {
        ObjectNode node = NODES.objectNode().put("id", creation.id()).put("type", creation.type());
        ObjectNode properties = NODES.objectNode();
        new TreeMap<>(creation.properties()).forEach((name, values) ->
                properties.set(name, writeArray(values, VMFJournalCodec::writeValue)));
        node.set("properties", properties);
        return node;
    }

    private static ObjectNode writeValue(Value value) {
        return NODES.objectNode().put("kind", value.kind().name()).put("text", value.text());
    }

    private static <T> ArrayNode writeArray(List<T> values, Function<T, JsonNode> writer) {
        ArrayNode node = NODES.arrayNode();
        values.forEach(value -> node.add(writer.apply(value)));
        return node;
    }

    private static Frame readFrame(JsonNode json, String path) throws IOException {
        ObjectNode node = object(json, path, FRAME_FIELDS);
        return new Frame(longInteger(node.get("sequence"), path + ".sequence"),
                readArray(node.get("operations"), path + ".operations", VMFJournalCodec::readOperation));
    }

    private static Operation readOperation(JsonNode json, String path) throws IOException {
        ObjectNode node = object(json, path, OPERATION_FIELDS);
        return new Operation(kind(Kind.class, node.get("kind"), path + ".kind"),
                text(node.get("target"), path + ".target"), text(node.get("property"), path + ".property"),
                readArray(node.get("indices"), path + ".indices", VMFJournalCodec::integer),
                readArray(node.get("before"), path + ".before", VMFJournalCodec::readValue),
                readArray(node.get("after"), path + ".after", VMFJournalCodec::readValue),
                readArray(node.get("created"), path + ".created", VMFJournalCodec::readCreation));
    }

    private static Creation readCreation(JsonNode json, String path) throws IOException {
        ObjectNode node = object(json, path, CREATION_FIELDS);
        ObjectNode properties = object(node.get("properties"), path + ".properties");
        Map<String, List<Value>> values = new LinkedHashMap<>();
        var entries = properties.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            values.put(entry.getKey(), readArray(entry.getValue(), path + ".properties." + entry.getKey(),
                    VMFJournalCodec::readValue));
        }
        return new Creation(text(node.get("id"), path + ".id"), text(node.get("type"), path + ".type"), values);
    }

    private static Value readValue(JsonNode json, String path) throws IOException {
        ObjectNode node = object(json, path, VALUE_FIELDS);
        ValueKind kind = kind(ValueKind.class, node.get("kind"), path + ".kind");
        JsonNode payload = node.get("text");
        if (kind == ValueKind.NULL) {
            if (!payload.isNull()) throw invalid(path + ".text", "expected JSON null");
            return new Value(kind, null);
        }
        return new Value(kind, text(payload, path + ".text"));
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(JsonNode node, String path) throws IOException;
    }

    private static <T> List<T> readArray(JsonNode node, String path, Reader<T> reader) throws IOException {
        if (node == null || !node.isArray()) throw invalid(path, "expected an array");
        List<T> items = new ArrayList<>(node.size());
        for (int index = 0; index < node.size(); index++) {
            String itemPath = path + "[" + index + "]";
            try {
                items.add(reader.read(node.get(index), itemPath));
            } catch (IllegalArgumentException | ArithmeticException exception) {
                throw invalid(itemPath, exception);
            }
        }
        return items;
    }

    private static ObjectNode object(JsonNode node, String path) throws IOException {
        if (node == null || !node.isObject()) throw invalid(path, "expected an object");
        return (ObjectNode) node;
    }

    private static ObjectNode object(JsonNode node, String path, Set<String> expected) throws IOException {
        ObjectNode object = object(node, path);
        fields(object, path, expected);
        return object;
    }

    private static void fields(ObjectNode node, String path, Set<String> expected) throws IOException {
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!expected.contains(name)) throw invalid(path, "unknown field " + name);
        }
        for (String name : expected) {
            if (!node.has(name)) throw invalid(path, "missing field " + name);
        }
    }

    private static String text(JsonNode node, String path) throws IOException {
        if (node == null || !node.isTextual()) throw invalid(path, "expected a string");
        return node.textValue();
    }

    private static int integer(JsonNode node, String path) throws IOException {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalid(path, "expected a 32-bit integer");
        }
        return node.intValue();
    }

    private static long longInteger(JsonNode node, String path) throws IOException {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalid(path, "expected a 64-bit integer");
        }
        return node.longValue();
    }

    private static <E extends Enum<E>> E kind(Class<E> type, JsonNode node, String path) throws IOException {
        String name = text(node, path);
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException exception) {
            throw new IOException(path + " unknown kind " + name, exception);
        }
    }

    private static IOException invalid(String path, String reason) {
        return new IOException(path + " " + reason);
    }

    private static IOException invalid(String path, RuntimeException exception) {
        return new IOException(path + " " + exception.getMessage(), exception);
    }
}
