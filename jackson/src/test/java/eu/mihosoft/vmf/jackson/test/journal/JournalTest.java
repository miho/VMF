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
package eu.mihosoft.vmf.jackson.test.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Kind;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalTest {
    private static final String CHECKPOINT = "document-at-40";
    private static final long BASE_SEQUENCE = 40L;

    private static final JournalBinding BINDING = new JournalBinding() {
        @Override
        public String modelId() {
            return "test.document";
        }

        @Override
        public int modelVersion() {
            return 1;
        }

        @Override
        public String id(VObject object) {
            if (object instanceof Document) return ((Document) object).getId();
            if (object instanceof Item) return ((Item) object).getId();
            throw new IllegalArgumentException("Unknown document model object");
        }

        @Override
        public String type(VObject object) {
            if (object instanceof Document) return "document";
            if (object instanceof Item) return "item";
            throw new IllegalArgumentException("Unknown document model object");
        }

        @Override
        public VObject create(String type, String id) {
            switch (type) {
                case "document": return Document.newBuilder().withId(id).build();
                case "item": return Item.newBuilder().withId(id).build();
                default: throw new IllegalArgumentException("Unknown document model type " + type);
            }
        }

        @Override
        public String propertyKey(VObject object, String propertyName) {
            return object instanceof Item && "label".equals(propertyName) ? "item-label" : propertyName;
        }

        @Override
        public String propertyName(VObject object, String wireKey) {
            return object instanceof Item && "item-label".equals(wireKey) ? "label" : wireKey;
        }
    };

    private static ObjectMapper checkpointMapper() {
        return new ObjectMapper().registerModule(
                VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    }

    private static Document document(String title) {
        return Document.newBuilder().withId("document").withTitle(title).build();
    }

    private static Item item(String id, String label) {
        return Item.newBuilder().withId(id).withLabel(label).build();
    }

    private static List<String> itemIds(Document document) {
        return document.getItems().stream().map(Item::getId).collect(Collectors.toList());
    }

    @Test
    void replayRemapsNewContainmentAndSelectionToIndependentInstances() throws Exception {
        Document source = document("a");
        ObjectMapper mapper = checkpointMapper();
        String checkpointJson = mapper.writeValueAsString(source);

        JournalRecorder recorder = JournalRecorder.start(source, BINDING, CHECKPOINT, BASE_SEQUENCE);
        Item original = item("i1", "original");
        source.getItems().add(original);
        source.setSelectedItem(original);
        ChangeJournal journal = recorder.finish();

        Document before = mapper.readValue(checkpointJson, Document.class);
        Document result = JournalReplayer.replay(before, BINDING, CHECKPOINT, journal);
        assertNotSame(before, result);
        assertEquals(List.of("i1"), itemIds(result));
        Item replayed = result.getItems().get(0);
        assertNotSame(original, replayed);
        assertSame(replayed, result.getSelectedItem());
        assertSame(result, replayed.getDocument());
        assertSame(result, replayed.getSelectedBy());

        assertEquals(List.of("i1"), itemIds(source));
        assertSame(original, source.getItems().get(0));
        assertSame(original, source.getSelectedItem());
        assertSame(source, original.getDocument());
        assertSame(source, original.getSelectedBy());
        assertTrue(before.getItems().isEmpty());
        assertNull(before.getSelectedItem());
    }

    @Test
    void implicitFrameWithScalarAndCreationRoundTripsThroughJson() throws Exception {
        Document source = document("a");
        ObjectMapper mapper = checkpointMapper();
        String checkpointJson = mapper.writeValueAsString(source);

        JournalRecorder recorder = JournalRecorder.start(source, BINDING, CHECKPOINT, BASE_SEQUENCE);
        source.setTitle("b");
        Item original = item("i1", "recorded label");
        source.getItems().add(original);
        ChangeJournal journal = recorder.finish();
        assertEquals(1, journal.transactions().size());
        assertEquals(BASE_SEQUENCE + 1, journal.transactions().get(0).sequence());

        source.setTitle("after recording");
        original.setLabel("after recording");
        String json = VMFJournalCodec.write(journal);
        ChangeJournal decoded = VMFJournalCodec.read(json);
        var operations = decoded.transactions().get(0).operations();
        assertEquals(2, operations.size());
        var rename = operations.get(0);
        assertEquals(Kind.SET, rename.kind());
        assertEquals("document", rename.target());
        assertEquals("title", rename.property());
        assertEquals("a", rename.before().get(0).text());
        assertEquals("b", rename.after().get(0).text());
        var add = operations.get(1);
        assertEquals(Kind.LIST_ADD, add.kind());
        assertEquals("items", add.property());
        assertEquals(List.of(0), add.indices());
        assertEquals("i1", add.created().get(0).id());

        Document before = mapper.readValue(checkpointJson, Document.class);
        Document result = JournalReplayer.replay(before, BINDING, CHECKPOINT, decoded);

        assertEquals("b", result.getTitle());
        assertEquals(List.of("i1"), itemIds(result));
        assertEquals("recorded label", result.getItems().get(0).getLabel());
        assertSame(result, result.getItems().get(0).getDocument());
        assertEquals("a", before.getTitle());
        assertTrue(before.getItems().isEmpty());
        assertEquals("after recording", original.getLabel());
        assertSame(source, original.getDocument());
    }

    @Test
    void explicitFramesReplayNestedScalarAndSparseSortInOrder() throws Exception {
        Document source = document("a");
        Item left = item("left", "original label");
        Item middle = item("middle", "middle label");
        Item right = item("right", "right label");
        source.getItems().addAll(List.of(left, middle, right));
        source.setSelectedItem(left);
        ObjectMapper mapper = checkpointMapper();
        String checkpointJson = mapper.writeValueAsString(source);

        JournalRecorder recorder = JournalRecorder.start(source, BINDING, CHECKPOINT, BASE_SEQUENCE);
        var changes = source.vmf().changes();
        changes.startTransaction();
        left.setLabel("recorded nested edit");
        changes.publishTransaction();
        changes.startTransaction();
        Map<String, Integer> ranks = Map.of("right", 0, "middle", 1, "left", 2);
        source.getItems().sort(Comparator.comparingInt(value -> ranks.get(value.getId())));
        changes.publishTransaction();

        ChangeJournal journal = VMFJournalCodec.read(VMFJournalCodec.write(recorder.finish()));
        assertEquals(2, journal.transactions().size());
        var firstFrame = journal.transactions().get(0);
        var secondFrame = journal.transactions().get(1);
        assertEquals(BASE_SEQUENCE + 1, firstFrame.sequence());
        assertEquals(BASE_SEQUENCE + 2, secondFrame.sequence());
        assertEquals(1, firstFrame.operations().size());
        assertEquals("left", firstFrame.operations().get(0).target());
        assertEquals("item-label", firstFrame.operations().get(0).property());
        assertEquals(1, secondFrame.operations().size());
        assertEquals(Kind.LIST_SET, secondFrame.operations().get(0).kind());
        assertEquals("document", secondFrame.operations().get(0).target());
        assertEquals("items", secondFrame.operations().get(0).property());
        assertEquals(List.of(0, 2), secondFrame.operations().get(0).indices());

        Document before = mapper.readValue(checkpointJson, Document.class);
        Document result = JournalReplayer.replay(before, BINDING, CHECKPOINT, journal);
        assertEquals(List.of("right", "middle", "left"), itemIds(result));
        assertEquals("recorded nested edit", result.getItems().get(2).getLabel());
        assertSame(result.getItems().get(2), result.getSelectedItem());
        assertSame(result, result.getSelectedItem().getSelectedBy());
        result.getItems().forEach(child -> assertSame(result, child.getDocument()));
        assertNotSame(left, result.getSelectedItem());
        assertNotSame(before.getItems().get(0), result.getSelectedItem());
        assertEquals(List.of("left", "middle", "right"), itemIds(before));
        assertEquals("original label", before.getItems().get(0).getLabel());
        assertSame(left, source.getSelectedItem());
    }

    @Test
    void replayRejectsWrongCheckpointAndLaterPreconditionWithoutMutatingInputs() {
        Document source = document("a");
        Item original = item("i1", "original label");
        source.getItems().add(original);
        Document before = source.vmf().content().deepCopy();

        JournalRecorder recorder = JournalRecorder.start(source, BINDING, CHECKPOINT, BASE_SEQUENCE);
        var changes = source.vmf().changes();
        changes.startTransaction();
        source.setTitle("b");
        changes.publishTransaction();
        changes.startTransaction();
        original.setLabel("changed label");
        changes.publishTransaction();
        ChangeJournal journal = recorder.finish();

        assertThrows(IllegalArgumentException.class,
                () -> JournalReplayer.replay(before, BINDING, "another checkpoint", journal));
        assertEquals("a", before.getTitle());

        Item wrongItem = before.getItems().get(0);
        wrongItem.setLabel("different base state");
        assertThrows(IllegalArgumentException.class,
                () -> JournalReplayer.replay(before, BINDING, CHECKPOINT, journal));
        assertEquals("a", before.getTitle());
        assertEquals("different base state", wrongItem.getLabel());
        assertSame(before, wrongItem.getDocument());
        assertEquals("b", source.getTitle());
        assertEquals("changed label", original.getLabel());
        assertSame(source, original.getDocument());
    }

    @Test
    void keepsUngroupedEditsAndResetsTheNativeCursorBetweenRecordings() {
        Document source = document("before");
        Document checkpoint = source.vmf().content().deepCopy();
        var changes = source.vmf().changes();
        ChangeJournal journal;
        try (var recorder = JournalRecorder.start(source, BINDING, CHECKPOINT, BASE_SEQUENCE)) {
            source.setTitle("outside of group");
            changes.startTransaction();
            source.setTitle("inside group");
            source.getItems().add(item("i1", "new item"));
            changes.publishTransaction();
            journal = recorder.finish();
        }
        assertEquals(2, journal.transactions().size());
        assertEquals(1, journal.transactions().get(0).operations().size());
        assertEquals(2, journal.transactions().get(1).operations().size());
        assertEquals("inside group", JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, journal).getTitle());

        try (var recorder = JournalRecorder.start(source, BINDING, "next-checkpoint", BASE_SEQUENCE + 2)) {
            source.setTitle("new recording");
            ChangeJournal second = recorder.finish();
            assertEquals(1, changes.transactions().size());
            assertEquals(BASE_SEQUENCE + 3, second.transactions().get(0).sequence());
        }
    }

    @Test
    void readsVersionOneFixtureAndRejectsAnUnknownVersionOrDuplicateKey() throws Exception {
        String fixture = "{\"format\":\"vmf-journal\",\"version\":1,"
                + "\"model\":{\"id\":\"test.document\",\"version\":1},\"root\":\"document\","
                + "\"base\":{\"checkpoint\":\"document-at-40\",\"sequence\":40},"
                + "\"transactions\":[{\"sequence\":41,\"operations\":[{\"kind\":\"SET\","
                + "\"target\":\"document\",\"property\":\"title\",\"indices\":[],"
                + "\"before\":[{\"kind\":\"STRING\",\"text\":\"a\"}],"
                + "\"after\":[{\"kind\":\"STRING\",\"text\":\"b\"}],\"created\":[]}]}]}";
        ChangeJournal journal = VMFJournalCodec.read(fixture);
        assertEquals("b", JournalReplayer.replay(document("a"), BINDING, CHECKPOINT, journal).getTitle());
        assertThrows(IOException.class, () -> VMFJournalCodec.read(fixture.replaceFirst("\"version\":1", "\"version\":2")));
        assertThrows(IOException.class, () -> VMFJournalCodec.read(fixture.replaceFirst("\"version\":1", "\"version\":1,\"version\":1")));
    }

    @Test
    void refusesARecordingThatMissedAnEditWhileAChildWasDetached() {
        Document source = document("a");
        Item original = item("i1", "before");
        source.getItems().add(original);
        try (var recorder = JournalRecorder.start(source, BINDING, CHECKPOINT, BASE_SEQUENCE)) {
            source.vmf().changes().startTransaction();
            source.getItems().remove(0);
            original.setLabel("edited while detached");
            source.getItems().add(original);
            source.vmf().changes().publishTransaction();

            assertThrows(IllegalStateException.class, recorder::finish);
            assertSame(original, source.getItems().get(0));
            assertEquals("edited while detached", original.getLabel());
        }
    }
}
