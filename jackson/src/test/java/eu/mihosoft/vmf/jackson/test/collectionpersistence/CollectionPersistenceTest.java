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
package eu.mihosoft.vmf.jackson.test.collectionpersistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.diff.ModelDiff;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CollectionPersistenceTest {
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "catalog"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) { return ((Identified) object).getId(); }
        @Override public String type(VObject object) { return object.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) {
            if (type.equals(Entry.class.getName())) return Entry.newBuilder().withId(id).build();
            if (type.equals(Item.class.getName())) return Item.newBuilder().withId(id).build();
            throw new IllegalArgumentException("Unknown type " + type);
        }
    };

    private static final VMFJacksonModule.RUNTIME_TYPE MODE = VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL;

    @Test void allModelFormatsPreserveTypedKeysMembershipAndEntryReferences() throws Exception {
        Catalog source = Catalog.newBuilder().withId("catalog").build();
        source.getTags().addAll(List.of("", "a\r\u0001", "other"));
        var item = Item.newBuilder().withId("id-1").withCode("first").build();
        source.getItems().add(item);
        var entry = Entry.newBuilder().withId("entry").withTarget(item).build();
        entry.getFlags().addAll(Set.of(2, 5));
        entry.getBits().put(2, null);
        source.getEntries().add(entry);
        source.getPriority().addAll(List.of("b", "a"));
        source.getRatings().put(-0.0, null);
        source.getRatings().put(+0.0, "zero");
        source.getRatings().put(Double.NaN, "missing");
        var byValue = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE));
        assertThrows(IOException.class, () -> byValue.writeValueAsString(source));
        var references = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE).withReferences(true));
        assertThrows(IOException.class, () -> references.readValue("{\"entries\":[{\"id\":\"entry\"}]}", Catalog.class));
        assertThrows(IOException.class, () -> references.readValue("{\"entries\":[{\"target\":\"unknown\"}]}", Catalog.class));
        String duplicateTarget = "{\"entries\":[{\"target\":7},{\"target\":\"7\"}],"
                + "\"items\":[{\"@vmf-id\":\"7\",\"code\":\"sku\"}]}";
        assertThrows(IOException.class, () -> references.readValue(duplicateTarget, Catalog.class));
        for (ObjectMapper mapper : List.of(new ObjectMapper(), new YAMLMapper(), new XmlMapper())) {
            mapper.registerModule(VMFJacksonModule.newInstance(MODE).withReferences(true));
            String document = mapper.writeValueAsString(source);
            Catalog result = mapper.readValue(document, Catalog.class);
            assertSame(result.getItems().get(0), result.getEntries().get(0).getTarget());
            assertSame(result.getItems().get(0), result.findItemsByCode("first").orElseThrow());
            assertEquals(source.getTags(), result.getTags());
            assertEquals(source.getPriority(), result.getPriority());
            assertEquals(source.getRatings(), result.getRatings());
            assertEquals(entry.getFlags(), result.getEntries().get(0).getFlags());
            assertEquals(entry.getBits(), result.getEntries().get(0).getBits());
        }
    }

    @Test void v4RecordsMembershipTypedKeysAndRealEntryCreations() throws IOException {
        Catalog working = Catalog.newBuilder().withId("catalog").build();
        working.getTags().add("original");
        var checkpoint = working.clone();
        ChangeJournal journal = JournalRecorder.recordFrame(working, BINDING, "initial", 0, catalog -> {
            catalog.getTags().replaceContents(Set.of("new"));
            catalog.getRatings().put(-0.0, null);
            catalog.getRatings().put(0.0, "positive");
            var item = Item.newBuilder().withId("item").withCode("old").build();
            catalog.getItems().add(item);
            var entry = Entry.newBuilder().withId("entry").withTarget(item).build();
            entry.getFlags().add(12);
            entry.getBits().put(7, true);
            catalog.getEntries().add(entry);
            item.setCode("new");
            entry.getBits().put(7, false);
            catalog.getPriority().add("high");
        });
        assertEquals(4, journal.wireVersion());
        var operations = journal.transactions().get(0).operations();
        assertTrue(operations.stream().anyMatch(op -> op.kind() == ChangeJournal.Kind.SET_EDIT));
        assertTrue(operations.stream().anyMatch(op -> op.kind() == ChangeJournal.Kind.TYPED_MAP_EDIT));
        for (var format : VMFDocumentFormat.values()) {
            String encoded = VMFJournalCodec.write(journal, format);
            var decoded = VMFJournalCodec.read(encoded, format);
            assertEquals(encoded, VMFJournalCodec.write(decoded, format));
            var result = JournalReplayer.replay(checkpoint, BINDING, "initial", decoded);
            assertEquals(Set.of("new"), result.getTags());
            assertEquals(working.getRatings(), result.getRatings());
            assertSame(result.getItems().get(0), result.findItemsByCode("new").orElseThrow());
            assertSame(result.getItems().get(0), result.getEntries().get(0).getTarget());
            assertEquals(Set.of(12), result.getEntries().get(0).getFlags());
            assertEquals(Map.of(7, false), result.getEntries().get(0).getBits());
            assertEquals(List.of("high"), result.getPriority());
        }
        assertEquals(Set.of("original"), checkpoint.getTags());
    }

    @Test void rejectsMalformedCollectionsAndEmitsCollectionSchemas() throws IOException {
        var mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE));
        for (String json : List.of("{\"tags\":[\"a\",\"a\"]}", "{\"tags\":[null]}", "{\"tags\":{}}",
                "{\"priority\":[\"a\",\"a\"]}", "{\"ratings\":{\"1\":\"bad\"}}",
                "{\"ratings\":[{\"key\":1,\"key\":2,\"value\":null}]}",
                "{\"ratings\":[{\"key\":1,\"value\":\"a\",\"value\":\"b\"}]}",
                "{\"ratings\":[{\"key\":1e999,\"value\":null}]}",
                "{\"ratings\":[{\"key\":1e-999,\"value\":null}]}",
                "{\"ratings\":[{\"key\":2,\"value\":\"a\"},{\"key\":2.0,\"value\":null}]}")) {
            assertThrows(IOException.class, () -> mapper.readValue(json, Catalog.class), json);
        }
        var schema = mapper.readTree(VMFJsonSchemaGenerator.newInstance(MODE).generateSchemaAsString(Catalog.class));
        assertTrue(schema.at("/properties/tags/uniqueItems").booleanValue());
        assertTrue(schema.at("/properties/priority/uniqueItems").booleanValue());
        assertEquals(3, schema.at("/properties/priority/maxItems").intValue());
        assertEquals("array", schema.at("/properties/ratings/type").textValue());
        assertEquals(4, schema.at("/properties/ratings/maxItems").intValue());
        assertEquals(2, schema.at("/properties/notes/maxProperties").intValue());
        assertEquals("target", schema.at("/properties/entries/items/allOf/1/required/0").textValue());
    }

    @Test void duplicateDetectionPreservesCallerParserFeatures() throws IOException {
        var mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE));
        var feature = com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION;
        for (boolean strict : List.of(false, true)) {
            for (String json : List.of("{\"tags\":[\"ok\"]}", "{\"items\":[{\"code\":\"a\",\"code\":\"b\"}]}")) {
                try (var parser = mapper.createParser(json)) {
                    parser.configure(feature, strict);
                    if (json.contains("items")) assertThrows(IOException.class, () -> mapper.readValue(parser, Catalog.class));
                    else assertEquals(Set.of("ok"), mapper.readValue(parser, Catalog.class).getTags());
                    assertEquals(strict, parser.isEnabled(feature));
                }
            }
        }
    }

    @Test void rejectsOutOfRangeTypedYamlKeys() {
        var mapper = new YAMLMapper().registerModule(VMFJacksonModule.newInstance(MODE));
        for (String literal : List.of("1.0e+999", "1.0e-999")) {
            String document = "ratings:\n  - key: " + literal + "\n    value: null\n";
            assertThrows(IOException.class, () -> mapper.readValue(document, Catalog.class), document);
        }
    }

    @Test void rejectsWrongVersionDuplicateKeysAndStaleMembershipPreconditions() throws IOException {
        Catalog working = Catalog.newBuilder().withId("catalog").build();
        var journal = JournalRecorder.recordFrame(working, BINDING, "base", 0, catalog -> {
            catalog.getRatings().put(1.5, null);
            catalog.getTags().add("new");
        });
        var mapper = new ObjectMapper();
        var envelope = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(VMFJournalCodec.write(journal));
        envelope.put("version", 3);
        assertThrows(IOException.class, () -> VMFJournalCodec.read(envelope.toString()));
        envelope.put("version", 4);
        var entries = (com.fasterxml.jackson.databind.node.ArrayNode) envelope.at("/transactions/0/operations/0/afterEntries");
        entries.add(entries.get(0).deepCopy());
        assertThrows(IOException.class, () -> VMFJournalCodec.read(envelope.toString()));
        entries.remove(1);
        var members = (com.fasterxml.jackson.databind.node.ArrayNode) envelope.at("/transactions/0/operations/1/after");
        members.add(members.get(0).deepCopy());
        assertThrows(IOException.class, () -> VMFJournalCodec.read(envelope.toString()));
        Catalog wrongBase = Catalog.newBuilder().withId("catalog").build();
        wrongBase.getTags().add("new");
        assertThrows(IllegalArgumentException.class, () -> JournalReplayer.replay(wrongBase, BINDING, "base", journal));
        assertTrue(wrongBase.getRatings().isEmpty());
    }

    @Test void diffUsesSetMembershipAndOwnsItsSnapshots() {
        var source = Catalog.newInstance();
        var target = Catalog.newInstance();
        source.getTags().addAll(List.of("a", "b"));
        target.getTags().addAll(List.of("b", "a"));
        assertTrue(ModelDiff.diff(source, target).isEmpty());
        target.getTags().remove("a");
        var changes = ModelDiff.diff(source, target);
        target.getTags().clear();
        ModelDiff.apply(source, changes);
        assertEquals(Set.of("b"), source.getTags());
        changes.get(0).undo();
        assertEquals(Set.of("a", "b"), source.getTags());
    }

    @Test void recordedOrderedSetInsertionsRequireExactMembership() {
        var checkpoint = Catalog.newBuilder().withId("catalog").withPriority("a").build();
        var a = new ChangeJournal.Value(ChangeJournal.ValueKind.STRING, "a");
        var b = new ChangeJournal.Value(ChangeJournal.ValueKind.STRING, "b");
        var insertion = new ChangeJournal.Operation(ChangeJournal.Kind.LIST_ADD, "catalog", "priority",
                List.of(1, 2), List.of(), List.of(b, a), List.of());
        var frame = new ChangeJournal.Frame(1, List.of(insertion));
        var journal = new ChangeJournal("catalog", 1, "catalog", "base", 0, List.of(frame));
        assertThrows(IllegalArgumentException.class, () -> JournalReplayer.replay(checkpoint, BINDING, "base", journal));
        assertEquals(List.of("a"), checkpoint.getPriority());
    }

    @Test void sparsePermutationReplaysWithoutTransientDuplicates() {
        var checkpoint = Catalog.newBuilder().withId("catalog").withPriority("a", "b", "c").build();
        var a = new ChangeJournal.Value(ChangeJournal.ValueKind.STRING, "a");
        var c = new ChangeJournal.Value(ChangeJournal.ValueKind.STRING, "c");
        var swap = new ChangeJournal.Operation(ChangeJournal.Kind.LIST_SET, "catalog", "priority",
                List.of(0, 2), List.of(a, c), List.of(c, a), List.of());
        var frame = new ChangeJournal.Frame(1, List.of(swap));
        var journal = new ChangeJournal("catalog", 1, "catalog", "base", 0, List.of(frame));
        var result = JournalReplayer.replay(checkpoint, BINDING, "base", journal);
        assertEquals(List.of("c", "b", "a"), result.getPriority());
        assertEquals(List.of("a", "b", "c"), checkpoint.getPriority());
    }

    @Test void genericImmutableModelBindingsPersistByValueWithReferencesOnAndOff() throws IOException {
        var history = LabelHistory.newInstance();
        history.setCurrent(Label.newBuilder().withText("current").build());
        history.getPrevious().add(Label.newBuilder().withText("old").build());
        for (boolean references : List.of(false, true)) {
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE).withReferences(references));
            var copy = mapper.readValue(mapper.writeValueAsString(history), LabelHistory.class);
            assertEquals("current", copy.getCurrent().getText());
            assertEquals("old", copy.getPrevious().get(0).getText());
        }
    }
}
