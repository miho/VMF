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
package eu.mihosoft.vmf.jackson.test.immutablejournal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.Builder;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.History;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.function.Function;


import static org.junit.jupiter.api.Assertions.*;

class ImmutableJournalTest {
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "notebook"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) {
            return object instanceof Notebook ? ((Notebook) object).getId() : ((Note) object).getId();
        }
        @Override public String type(VObject object) { return object instanceof Notebook ? "book" : "note"; }
        @Override public VObject create(String type, String id) {
            if ("book".equals(type)) return Notebook.newBuilder().withId(id).build();
            if ("note".equals(type)) return Note.newBuilder().withId(id).build();
            throw new IllegalArgumentException("Unknown model type " + type);
        }
    };

    private static Note note(String id) {
        return Note.newBuilder().withText("Inspect").withId(id).build();
    }

    private static Notebook checkpoint(boolean reverse) {
        var first = note("1");
        var twin = note("2");
        assertEquals(first, twin);
        var model = Notebook.newBuilder().withId("root").withCurrent(first).build();
        model.getBookmarks().addAll(reverse ? List.of(twin, first) : List.of(first, twin));
        model.getFavorites().addAll(List.of(first, twin));
        return model;
    }

    private static ChangeJournal edit(Notebook source) {
        var first = source.getCurrent();
        var twin = source.getFavorites().get(1);
        var newLeaf = note("3");
        var nested = Note.newBuilder().withId("nested").withParent(newLeaf).withSources(newLeaf, twin)
                .withTags("review").withScores(Map.of("inspection", 2.5)).withCodes(Map.of(7, "OK")).build();
        return JournalRecorder.recordFrame(source, BINDING, "notebook-v1", 0, model -> {
            model.getBookmarks().remove(first);
            model.setCurrent(nested);
            model.getFavorites().add(nested);
            model.getBookmarks().add(newLeaf);
        });
    }

    @Test void allJournalFormatsPreserveIntroducedAndCheckpointIdentity() throws IOException {
        var journal = edit(checkpoint(false));
        assertEquals(1, journal.wireVersion());
        for (VMFDocumentFormat format : VMFDocumentFormat.values()) {
            String bytes = VMFJournalCodec.write(journal, format);
            var restored = VMFJournalCodec.read(bytes, format);
            assertEquals(bytes, VMFJournalCodec.write(restored, format));
            var independent = checkpoint(true);
            var model = JournalReplayer.replay(independent, BINDING, "notebook-v1", restored);
            var nested = model.getCurrent();
            assertSame(nested, model.getFavorites().get(2));
            assertEquals(2.5, nested.getScores().get("inspection"));
            assertEquals("OK", nested.getCodes().get(7));
            assertTrue(nested.getTags().contains("review"));
            var newLeaf = nested.getParent();
            assertSame(newLeaf, nested.getSources().get(0));
            assertSame(model.getFavorites().get(1), nested.getSources().get(1));
            assertTrue(model.getBookmarks().contains(newLeaf));
            assertFalse(model.getBookmarks().contains(model.getFavorites().get(0)));
            assertSame(independent.getCurrent(), independent.getFavorites().get(0));
            var history = new History<Notebook>(BINDING);
            history.addSegment("notebook-v1", independent, restored);
            assertEquals("1", history.seek(0).getCurrent().getId());
            assertEquals("nested", history.seek(1).getCurrent().getId());
        }
    }

    @Test void savedCheckpointAndJournalRestoreTheSameReferenceGraph() throws IOException {
        for (VMFDocumentFormat format : VMFDocumentFormat.values()) {
            ObjectMapper mapper;
            switch (format) {
                case JSON: mapper = new ObjectMapper(); break;
                case YAML: mapper = new YAMLMapper(); break;
                case XML: mapper = new XmlMapper(); break;
                default: throw new AssertionError(format);
            }
            mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
            var source = checkpoint(false);
            String savedCheckpoint = mapper.writeValueAsString(source);
            String savedJournal = VMFJournalCodec.write(edit(source), format);
            var base = mapper.readValue(savedCheckpoint, Notebook.class);
            assertNotSame(source.getFavorites().get(0), base.getCurrent());
            assertSame(base.getCurrent(), base.getFavorites().get(0));
            assertNotSame(base.getCurrent(), base.getFavorites().get(1));
            var replayed = JournalReplayer.replay(base, BINDING, "notebook-v1", VMFJournalCodec.read(savedJournal, format));
            assertSame(replayed.getCurrent(), replayed.getFavorites().get(2));
            assertSame(replayed.getCurrent().getParent(), replayed.getCurrent().getSources().get(0));
            assertEquals(mapper.readTree(mapper.writeValueAsString(source)), mapper.readTree(mapper.writeValueAsString(replayed)));
        }
    }

    @Test void rejectsStaleTwinCollisionAndKindDowngrade() throws IOException {
        var journal = edit(checkpoint(false));
        var stale = checkpoint(true);
        stale.getBookmarks().remove(stale.getCurrent());
        assertThrows(IllegalArgumentException.class, () -> JournalReplayer.replay(stale, BINDING, "notebook-v1", journal));
        assertEquals(1, stale.getBookmarks().size());
        var collision = checkpoint(false);
        collision.getBookmarks().add(note("1"));
        assertThrows(IllegalArgumentException.class, () -> JournalRecorder.start(collision, BINDING, "notebook-v1", 0));
        String encoded = VMFJournalCodec.write(journal);
        var wrongKind = VMFJournalCodec.read(encoded.replaceFirst("IMMUTABLE_REFERENCE", "REFERENCE"));
        assertThrows(IllegalArgumentException.class, () -> JournalReplayer.replay(checkpoint(true), BINDING, "notebook-v1", wrongKind));
    }

    @Test void undoRestoresTheOriginalImmutableTargetsByIdentity() {
        var source = checkpoint(false);
        var first = source.getCurrent();
        var twin = source.getFavorites().get(1);
        edit(source);
        source.vmf().changes().transactions().get(0).undo();
        assertSame(first, source.getCurrent());
        assertSame(first, source.getFavorites().get(0));
        assertSame(twin, source.getFavorites().get(1));
        assertEquals(2, source.getBookmarks().size());
        assertTrue(source.getBookmarks().contains(first));
        assertTrue(source.getBookmarks().contains(twin));
    }

    @Test void acceptsForwardImmutableDefinitionsAndRejectsCycles() {
        var journal = edit(checkpoint(false));
        var operations = new ArrayList<>(journal.transactions().get(0).operations());
        var defining = operations.get(1);
        var records = new ArrayList<>(defining.created());
        assertEquals(2, records.size());
        Collections.reverse(records);
        operations.set(1, new ChangeJournal.Operation(defining.kind(), defining.target(), defining.property(),
                defining.indices(), defining.before(), defining.after(), records));
        var forward = withOperations(journal, operations);
        assertEquals("3", JournalReplayer.replay(checkpoint(true), BINDING, "notebook-v1", forward).getCurrent().getParent().getId());

        var nested = records.get(0);
        var properties = new java.util.LinkedHashMap<>(nested.properties());
        properties.put("parent", List.of(new ChangeJournal.Value(ChangeJournal.ValueKind.IMMUTABLE_REFERENCE, nested.id())));
        records.set(0, new ChangeJournal.Creation(nested.id(), nested.type(), properties,
                nested.mapProperties(), nested.typedMapProperties(), nested.setProperties()));
        operations.set(1, new ChangeJournal.Operation(defining.kind(), defining.target(), defining.property(),
                defining.indices(), defining.before(), defining.after(), records));
        assertThrows(IllegalArgumentException.class, () ->
                JournalReplayer.replay(checkpoint(false), BINDING, "notebook-v1", withOperations(journal, operations)));
    }

    @Test void bindingCanRegisterAnExternalIdAfterConstruction() {
        Map<VObject, String> ids = new IdentityHashMap<>();
        var bound = new JournalBinding() {
            @Override public String modelId() { return BINDING.modelId(); }
            @Override public int modelVersion() { return 1; }
            @Override public String type(VObject object) { return BINDING.type(object); }
            @Override public String id(VObject object) {
                return object instanceof Notebook ? ((Notebook) object).getId() : ids.get(object);
            }
            @Override public VObject create(String type, String id) { throw new AssertionError(type); }
            @Override public VObject createImmutable(String type, String id, Function<Builder, VObject> initialize) {
                if (!"note".equals(type)) throw new IllegalArgumentException(type);
                VObject result = initialize.apply(Note.newBuilder());
                ids.put(result, id);
                return result;
            }
        };
        var first = note(null);
        var twin = note(null);
        var independentFirst = note(null);
        assertEquals(first, twin);
        ids.put(first, "a");
        ids.put(independentFirst, "a");
        ids.put(twin, "b");
        var source = Notebook.newBuilder().withId("root").withCurrent(first).build();
        var checkpoint = Notebook.newBuilder().withId("root").withCurrent(independentFirst).build();
        var journal = JournalRecorder.recordFrame(source, bound, "base", 0, working -> {
            working.getBookmarks().add(working.getCurrent());
            working.getBookmarks().add(twin);
            working.setCurrent(twin);
        });
        var copy = JournalReplayer.replay(checkpoint, bound, "base", journal);
        assertEquals(2, copy.getBookmarks().size());
        assertSame(independentFirst, copy.getBookmarks().iterator().next());
        assertEquals("b", ids.get(copy.getCurrent()));
        assertNotSame(twin, copy.getCurrent());
    }

    private static ChangeJournal withOperations(ChangeJournal journal, List<ChangeJournal.Operation> operations) {
        return new ChangeJournal(journal.model(), journal.modelVersion(), journal.root(), journal.checkpoint(),
                journal.baseSequence(), List.of(new ChangeJournal.Frame(1, operations)));
    }
}
