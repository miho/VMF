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
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.JsonParserDelegate;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Creation;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Frame;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Kind;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Operation;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Value;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.ValueKind;
import org.yaml.snakeyaml.DumperOptions;

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
 * Reads and writes versioned portable {@link ChangeJournal} records in JSON, YAML, or XML.
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
 * uses {@code kind} and {@code text}. JSON and YAML use strings for every kind
 * except {@code NULL}, which requires null. Kind tags are the names of
 * the corresponding {@link Kind} and {@link ValueKind} constants. An
 * {@code EXTERNAL} value requires version 2 and adds a {@code codec} string
 * identifying the binding's value codec. This class preserves the codec ID
 * and text for replay. YAML uses the same maps and sequences as JSON. XML projects
 * the same records onto elements with explicit list wrappers.
 *
 * <p>The writer uses fixed field order and sorts the keys of creation properties.
 * The JSON writer also escapes non-ASCII text. The reader accepts fields in any
 * order and requires every documented field.
 */
public final class VMFJournalCodec {
    /** The transport format of a journal document. */
    public enum Format { JSON, YAML, XML }

    private static final ObjectMapper JSON_MAPPER = strictMapper(new ObjectMapper());
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final DefaultIndenter INDENTER = new DefaultIndenter("  ", "\n");
    private static final ObjectWriter JSON_WRITER = JSON_MAPPER.writer(new DefaultPrettyPrinter()
            .withObjectIndenter(INDENTER).withArrayIndenter(INDENTER))
            .with(JsonWriteFeature.ESCAPE_NON_ASCII);

    private static final Set<String> ENVELOPE_FIELDS = Set.of("format", "version", "model", "root", "base", "transactions");
    private static final Set<String> MODEL_FIELDS = Set.of("id", "version");
    private static final Set<String> BASE_FIELDS = Set.of("checkpoint", "sequence");
    private static final Set<String> FRAME_FIELDS = Set.of("sequence", "operations");
    private static final Set<String> OPERATION_FIELDS = Set.of("kind", "target", "property", "indices", "before", "after", "created");
    private static final Set<String> CREATION_FIELDS = Set.of("id", "type", "properties");
    private static final Set<String> VALUE_FIELDS = Set.of("kind", "text");
    private static final Set<String> EXTERNAL_VALUE_FIELDS = Set.of("kind", "text", "codec");

    private VMFJournalCodec() {
    }

    /**
     * Writes a journal as JSON using the wire version required by its values.
     * @param journal journal to encode
     * @return the journal JSON
     * @throws IOException when writing fails
     */
    public static String write(ChangeJournal journal) throws IOException {
        return write(journal, Format.JSON);
    }

    /**
     * Writes a journal in the selected format using the wire version its values require.
     * @param journal journal to encode
     * @param format the document format
     * @return the journal document
     * @throws IOException when writing fails
     */
    public static String write(ChangeJournal journal, Format format) throws IOException {
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(format, "format");
        ObjectNode envelope = NODES.objectNode()
                .put("format", ChangeJournal.FORMAT)
                .put("version", journal.wireVersion());
        envelope.set("model", NODES.objectNode().put("id", journal.model()).put("version", journal.modelVersion()));
        envelope.put("root", journal.root());
        envelope.set("base", NODES.objectNode().put("checkpoint", journal.checkpoint()).put("sequence", journal.baseSequence()));
        envelope.set("transactions", writeArray(journal.transactions(), VMFJournalCodec::writeFrame));
        switch (format) {
            case JSON: return JSON_WRITER.writeValueAsString(envelope);
            case YAML: return Yaml.MAPPER.writeValueAsString(envelope);
            case XML: return VMFJournalXml.write(envelope);
            default: throw new AssertionError(format);
        }
    }

    /**
     * Reads one JSON journal with strict wire and journal-record validation.
     * @param json the complete journal JSON
     * @return the decoded journal
     * @throws IOException when the JSON or journal contents are invalid or the format is unsupported
     */
    public static ChangeJournal read(String json) throws IOException {
        return read(json, Format.JSON);
    }

    /**
     * Reads one document in the specified format and validates its version and records.
     * @param document the complete journal document
     * @param format the document format
     * @return the decoded journal
     * @throws IOException when the document or journal contents are invalid or unsupported
     */
    public static ChangeJournal read(String document, Format format) throws IOException {
        Objects.requireNonNull(format, "format");
        if (document == null) throw invalid("$", "expected journal " + format);
        JsonNode tree;
        switch (format) {
            case JSON: tree = JSON_MAPPER.readTree(document); break;
            case YAML: tree = Yaml.read(document); break;
            case XML: tree = VMFJournalXml.read(document); break;
            default: throw new AssertionError(format);
        }
        return readEnvelope(object(tree, "$"));
    }

    private static ChangeJournal readEnvelope(ObjectNode envelope) throws IOException {
        String format = text(envelope.get("format"), "$.format");
        if (!ChangeJournal.FORMAT.equals(format)) throw invalid("$.format", "unsupported journal format " + format);
        int version = integer(envelope.get("version"), "$.version");
        if (version != ChangeJournal.VERSION && version != ChangeJournal.EXTERNAL_VALUES_VERSION) {
            throw invalid("$.version", "unsupported journal version " + version);
        }
        fields(envelope, "$", ENVELOPE_FIELDS);

        ObjectNode model = object(envelope.get("model"), "$.model", MODEL_FIELDS);
        ObjectNode base = object(envelope.get("base"), "$.base", BASE_FIELDS);
        try {
            ChangeJournal journal = new ChangeJournal(text(model.get("id"), "$.model.id"), integer(model.get("version"), "$.model.version"),
                    text(envelope.get("root"), "$.root"), text(base.get("checkpoint"), "$.base.checkpoint"),
                    longInteger(base.get("sequence"), "$.base.sequence"),
                    readArray(envelope.get("transactions"), "$.transactions", VMFJournalCodec::readFrame));
            if (journal.wireVersion() > version) {
                throw invalid("$.version", "external values require journal version " + journal.wireVersion());
            }
            return journal;
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw invalid("$", exception);
        }
    }

    private static ObjectMapper strictMapper(ObjectMapper mapper) {
        return mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    private static final class Yaml {
        private static final YAMLFactory FACTORY = factory();
        private static final ObjectMapper MAPPER = strictMapper(new ObjectMapper(FACTORY));

        private static YAMLFactory factory() {
            DumperOptions options = new DumperOptions();
            // Escape Unicode line separators in map keys as well as values.
            options.setAllowUnicode(false);
            return YAMLFactory.builder().dumperOptions(options).build();
        }

        private static JsonNode read(String document) throws IOException {
            YAMLParser yaml = FACTORY.createParser(document);
            try (JsonParser parser = new JsonParserDelegate(yaml) {
                @Override
                public JsonToken nextToken() throws IOException {
                    JsonToken token = super.nextToken();
                    if (token != null && yaml.isCurrentAlias()) {
                        throw invalid("$", "YAML aliases are unsupported in journals");
                    }
                    return token;
                }
            }) {
                return MAPPER.readTree(parser);
            }
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
        ObjectNode node = NODES.objectNode().put("kind", value.kind().name()).put("text", value.text());
        if (value.kind() == ValueKind.EXTERNAL) node.put("codec", value.codecId());
        return node;
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
        return new Operation(kind(Kind.class, node.get("kind"), path, ".kind"),
                text(node.get("target"), path, ".target"), text(node.get("property"), path, ".property"),
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
        return new Creation(text(node.get("id"), path, ".id"), text(node.get("type"), path, ".type"), values);
    }

    private static Value readValue(JsonNode json, String path) throws IOException {
        ObjectNode node = object(json, path);
        ValueKind kind = kind(ValueKind.class, node.get("kind"), path, ".kind");
        if (kind == ValueKind.EXTERNAL) {
            fields(node, path, EXTERNAL_VALUE_FIELDS);
            return Value.external(text(node.get("codec"), path, ".codec"), text(node.get("text"), path, ".text"));
        }
        fields(node, path, VALUE_FIELDS);
        JsonNode payload = node.get("text");
        if (kind == ValueKind.NULL) {
            if (!payload.isNull()) throw invalid(path + ".text", "expected JSON null");
            return new Value(kind, null);
        }
        return new Value(kind, text(payload, path, ".text"));
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(JsonNode node, String path) throws IOException;
    }

    private static <T> List<T> readArray(JsonNode node, String path, Reader<T> reader) throws IOException {
        if (node == null || !node.isArray()) throw invalid(path, "expected an array");
        int size = node.size();
        if (size == 0) return List.of();
        List<T> items = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
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
        // The object has unique keys. A known subset of the same size is complete.
        if (node.size() == expected.size()) return;
        for (String name : expected) {
            if (!node.has(name)) throw invalid(path, "missing field " + name);
        }
    }

    private static String text(JsonNode node, String path) throws IOException {
        return text(node, path, "");
    }

    private static String text(JsonNode node, String path, String field) throws IOException {
        if (node == null || !node.isTextual()) throw invalid(path + field, "expected a string");
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

    private static <E extends Enum<E>> E kind(Class<E> type, JsonNode node, String path, String field) throws IOException {
        String name = text(node, path, field);
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException exception) {
            throw new IOException(path + field + " unknown kind " + name, exception);
        }
    }

    private static IOException invalid(String path, String reason) {
        return new IOException(path + " " + reason);
    }

    private static IOException invalid(String path, RuntimeException exception) {
        return new IOException(path + " " + exception.getMessage(), exception);
    }
}
