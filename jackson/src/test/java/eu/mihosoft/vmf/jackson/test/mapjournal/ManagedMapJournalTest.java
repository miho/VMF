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
package eu.mihosoft.vmf.jackson.test.mapjournal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Operation;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Value;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.ValueKind;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ManagedMapJournalTest {
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "ledger"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject value) { return ((Ledger) value).getId(); }
        @Override public String type(VObject value) { return "ledger"; }
        @Override public Ledger create(String type, String id) {
            if (!"ledger".equals(type)) throw new IllegalArgumentException(type);
            return ledger(id);
        }
    };

    private static Ledger ledger(String id) {
        return Ledger.newBuilder().withId(id).build();
    }

    private static ChangeJournal journal(Operation op) {
        return new ChangeJournal("ledger", 1, "root", "base", 0,
                List.of(new ChangeJournal.Frame(1, List.of(op))));
    }

    @Test
    void replaysMixedBulkEditsNullsAndArbitraryStringKeys() throws IOException {
        Ledger working = ledger("root");
        working.getCounts().put("", null);
        working.getCounts().put("remove", 42);
        Ledger checkpoint = working.clone();
        String textKey = "special<&/\\\0\r\n\u2028\ud83d\ude80";
        ChangeJournal journal = JournalRecorder.recordFrame(working, BINDING, "base", 0, root -> {
            Map<String, Integer> mixed = new LinkedHashMap<>();
            mixed.put("", 4);
            mixed.put(textKey, null);
            root.getCounts().putAll(mixed);
            root.getCounts().remove("remove");
        });
        assertEquals(ChangeJournal.MAPS_VERSION, journal.wireVersion());
        var operations = journal.transactions().get(0).operations();
        assertEquals(2, operations.size());
        var bulk = operations.get(0);
        assertEquals(ChangeJournal.Kind.MAP_EDIT, bulk.kind());
        assertEquals(Map.of("", new Value(ValueKind.NULL, null)), bulk.beforeEntries());
        var nullEntry = new Value(ValueKind.NULL, null);
        assertEquals(Map.of("", new Value(ValueKind.INT, "4"), textKey, nullEntry), bulk.afterEntries());
        assertEquals(Map.of("remove", new Value(ValueKind.INT, "42")), operations.get(1).beforeEntries());

        for (VMFDocumentFormat format : VMFDocumentFormat.values()) {
            String document = VMFJournalCodec.write(journal, format);
            var decoded = VMFJournalCodec.read(document, format);
            assertEquals(document, VMFJournalCodec.write(decoded, format));
            var result = JournalReplayer.replay(checkpoint, BINDING, "base", decoded);
            assertEquals(working.getCounts(), result.getCounts());
            assertEquals(List.of("", textKey), new ArrayList<>(result.getCounts().keySet()));
            assertTrue(result.getCounts().containsKey(textKey));
            assertNull(result.getCounts().get(textKey));
            assertFalse(checkpoint.getCounts().containsKey(textKey));
            assertNull(checkpoint.getCounts().get(""));
        }
    }

    @Test
    void snapshotsManagedMapsWhenAnObjectIsCreated() throws IOException {
        Ledger working = ledger("root");
        Ledger checkpoint = working.clone();
        var journal = JournalRecorder.recordFrame(working, BINDING, "base", 0, root -> {
            var child = ledger("child");
            child.getCounts().put("initial", 1);
            root.getChildren().add(child);
        });
        assertEquals(3, journal.wireVersion());
        var creation = journal.transactions().get(0).operations().get(0).created().get(0);
        assertEquals(Map.of("counts", Map.of("initial", new Value(ValueKind.INT, "1"))), creation.mapProperties());
        for (VMFDocumentFormat format : VMFDocumentFormat.values()) {
            var decoded = VMFJournalCodec.read(VMFJournalCodec.write(journal, format), format);
            var replayed = JournalReplayer.replay(checkpoint, BINDING, "base", decoded);
            assertEquals(Map.of("initial", 1), replayed.getChildren().get(0).getCounts());
            assertTrue(checkpoint.getChildren().isEmpty());
        }
    }

    @Test
    void rejectsWrongTypesMissingKeysAndUnexpectedNullMappings() {
        Ledger checkpoint = ledger("root");
        checkpoint.getCounts().put("present", null);
        var nullValue = new Value(ValueKind.NULL, null);
        var wrong = new Value(ValueKind.STRING, "4");
        var insertExisting = Operation.mapEdit("root", "counts", Map.of(), Map.of("present", nullValue));
        var removeAbsent = Operation.mapEdit("root", "counts", Map.of("absent", nullValue), Map.of());
        var wrongValue = Operation.mapEdit("root", "counts", Map.of(), Map.of("absent", wrong));
        var wrongProperty = Operation.mapEdit("root", "id", Map.of(), Map.of("absent", nullValue));
        for (var rejected : List.of(insertExisting, removeAbsent, wrongValue, wrongProperty)) {
            assertThrows(IllegalArgumentException.class,
                    () -> JournalReplayer.replay(checkpoint, BINDING, "base", journal(rejected)));
        }
        assertEquals(1, checkpoint.getCounts().size());
        assertTrue(checkpoint.getCounts().containsKey("present"));
    }

    @Test
    void rejectsReentrantDeliveryThatReordersMapInsertions() {
        Ledger working = ledger("root");
        var subscription = working.getCounts().addChangeListener(event -> {
            if (event.added().entries().containsKey("first")) working.getCounts().put("second", 2);
        });
        try {
            assertThrows(IllegalStateException.class,
                    () -> JournalRecorder.recordFrame(working, BINDING, "base", 0,
                            root -> root.getCounts().put("first", 1)));
            assertEquals(List.of("first", "second"), new ArrayList<>(working.getCounts().keySet()));
        } finally {
            subscription.unsubscribe();
        }
    }

    @Test
    void rejectsMalformedMapEntriesInXml() throws IOException {
        var op = Operation.mapEdit("root", "counts", Map.of(), Map.of("1", new Value(ValueKind.INT, "2")));
        String xml = VMFJournalCodec.write(journal(op), VMFDocumentFormat.XML);
        String entry = xml.substring(xml.indexOf("<entry>"), xml.indexOf("</entry>") + "</entry>".length());
        for (String malformed : List.of(
                xml.replace(entry, entry + entry),
                xml.replace("<key>1</key>", "<key null=\"true\"/>"),
                xml.replace("<key>1</key>", "<key><key>1</key></key>"),
                xml.replace("<version>3</version>", "<version>2</version>"),
                xml.replace("<key>1</key>", "<key>1</key><key>2</key>"))) {
            assertThrows(IOException.class, () -> VMFJournalCodec.read(malformed, VMFDocumentFormat.XML));
        }
    }

    @Test
    void readsVersionThreeGoldenDocument() throws IOException {
        String json;
        try (var input = getClass().getResourceAsStream("map-edit-v3.json")) {
            assertNotNull(input);
            json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        ChangeJournal decoded = VMFJournalCodec.read(json);
        var checkpoint = ledger("root");
        checkpoint.getCounts().put("replace", null);
        var result = JournalReplayer.replay(checkpoint, BINDING, "base", decoded);
        assertEquals(Map.of("replace", 42), result.getCounts());
        assertEquals(Map.of("initial", 8), result.getChildren().get(0).getCounts());
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(mapper.readTree(json), mapper.readTree(VMFJournalCodec.write(decoded)));
    }

    @Test
    void rejectsDuplicateKeysUnsupportedPayloadsAndDowngradedVersions() throws IOException {
        var op = Operation.mapEdit("root", "counts", Map.of(), Map.of("1", new Value(ValueKind.INT, "2")));
        ObjectNode document = (ObjectNode) new ObjectMapper().readTree(VMFJournalCodec.write(journal(op)));
        var operations = (ArrayNode) document.at("/transactions/0/operations");
        var entries = (ArrayNode) operations.get(0).get("afterEntries");
        var entry = (ObjectNode) entries.get(0);

        document.put("version", 2);
        assertThrows(IOException.class, () -> VMFJournalCodec.read(document.toString()));
        document.put("version", 3);
        entries.add(entry.deepCopy());
        assertThrows(IOException.class, () -> VMFJournalCodec.read(document.toString()));
        entries.remove(1);
        entry.put("key", 1);
        assertThrows(IOException.class, () -> VMFJournalCodec.read(document.toString()));
        entry.put("key", "1");
        var payload = (ObjectNode) entry.get("value");
        payload.put("kind", "REFERENCE");
        assertThrows(IOException.class, () -> VMFJournalCodec.read(document.toString()));
        entries.removeAll();
        assertThrows(IOException.class, () -> VMFJournalCodec.read(document.toString()));
    }
}
