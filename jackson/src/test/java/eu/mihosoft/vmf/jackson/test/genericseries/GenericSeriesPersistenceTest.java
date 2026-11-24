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
package eu.mihosoft.vmf.jackson.test.genericseries;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;
import eu.mihosoft.vmf.jackson.VMFTypeUtils;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenericSeriesPersistenceTest {
    // Each archive contains exactly one object of each of these named types.
    private final JournalBinding binding = new JournalBinding() {
        public String modelId() { return "generic-series"; }
        public int modelVersion() { return 1; }
        public String id(VObject object) { return type(object); }
        public String type(VObject object) {
            if (object instanceof Archive) return "archive";
            if (object instanceof Numbers) return "numbers";
            if (object instanceof Labels) return "labels";
            throw new IllegalArgumentException("Unknown series model object " + object);
        }
        public VObject create(String type, String id) {
            switch (type) {
                case "archive": return Archive.newInstance();
                case "numbers": return Numbers.newInstance();
                case "labels": return Labels.newInstance();
                default: throw new IllegalArgumentException("Unknown series wire type " + type);
            }
        }
    };

    @Test
    void polymorphicPropertyFindsImplementationsThroughAGenericInterface() throws Exception {
        var moduleType = VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL;
        var mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(moduleType));
        Archive archive = Archive.newInstance();
        archive.setSelected(Labels.newBuilder().withLatest("ok").build());
        String json = mapper.writeValueAsString(archive);
        assertEquals(Labels.class.getName(), mapper.readTree(json).at("/selected/@vmf-type").asText());
        var restored = mapper.readValue(json, Archive.class);
        assertEquals("ok", ((Labels) restored.getSelected()).getLatest());

        assertTrue(Labels.type().isModelSubtypeOf(Labels.type().superTypes().get(0)));
        assertFalse(Labels.type().isModelSubtypeOf(Numbers.type().superTypes().get(0)));
        assertTrue(VMFTypeUtils.isTypeExtendedByModelType(archive, Series.type()));
        assertTrue(VMFTypeUtils.isTypeExtendedByModelType(archive, Tagged.type()));
        var schema = VMFJsonSchemaGenerator.newInstance(moduleType).generateSchemaAsString(Archive.class);
        var choices = mapper.readTree(schema).at("/properties/selected/oneOf");
        assertTrue(choices.isArray());
        Set<String> refs = new HashSet<>();
        choices.forEach(option -> refs.add(option.at("/allOf/0/$ref").asText()));
        assertEquals(Set.of("#/definitions/" + Numbers.class.getName(), "#/definitions/" + Labels.class.getName()), refs);
    }

    @Test
    void isolatedModelLoaderResolvesItsOwnPolymorphicInstancesAndSchemaChoices() throws Exception {
        String pkg = Archive.class.getPackageName() + ".";
        // Hide this fixture from parent delegation to load a second model with identical binary names.
        ClassLoader parent = new ClassLoader(Archive.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(pkg)) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        java.net.URL location = Archive.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{location}, parent)) {
            Class<? extends VObject> archiveClass = loader.loadClass(Archive.class.getName()).asSubclass(VObject.class);
            Class<?> labelsClass = loader.loadClass(Labels.class.getName());
            Class<?> taggedClass = loader.loadClass(Tagged.class.getName());
            VObject archive = (VObject) archiveClass.getMethod("newInstance").invoke(null);
            VObject labels = (VObject) labelsClass.getMethod("newInstance").invoke(null);
            archiveClass.getMethod("setSelected", taggedClass).invoke(archive, labels);
            assertFalse(labels.vmf().reflect().type().isModelSubtypeOf(Tagged.type()));
            assertTrue(labels.vmf().reflect().type().isModelSubtypeOf(VMFTypeUtils.forClass(taggedClass)));

            var mode = VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL;
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(mode));
            VObject restored = mapper.readValue(mapper.writeValueAsString(archive), archiveClass);
            Object selected = archiveClass.getMethod("getSelected").invoke(restored);
            assertTrue(labelsClass.isInstance(selected));
            assertFalse(selected instanceof Labels);
            String schema = VMFJsonSchemaGenerator.newInstance(mode).generateSchemaAsString(archiveClass);
            assertEquals(2, mapper.readTree(schema).at("/properties/selected/oneOf").size());
        }
    }

    @Test
    void checkpointAndRecordedCreationsPreserveConcreteTypeArguments() throws Exception {
        Archive original = Archive.newInstance();
        ObjectMapper mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(
                VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL));
        String checkpoint = mapper.writeValueAsString(original);
        ChangeJournal journal;
        try (JournalRecorder recorder = JournalRecorder.start(original, binding, "empty", 0)) {
            original.setNumbers(Numbers.newBuilder().withLatest(0.5).withSamples(1.0).build());
            original.setLabels(Labels.newBuilder().withLatest("ok").withSamples("1.0").build());
            original.getNumbers().getByName().put("first", 1.0);
            original.getLabels().getByName().put("first", "1.0");
            original.getNumbers().getSamples().add(2.5);
            original.getLabels().getSamples().add("2.5");
            journal = recorder.finish();
        }
        Archive restored = mapper.readValue(mapper.writeValueAsString(original), Archive.class);
        assertBindings(restored);
        Archive replayed = JournalReplayer.replay(mapper.readValue(checkpoint, Archive.class), binding, "empty",
                VMFJournalCodec.read(VMFJournalCodec.write(journal)));
        assertBindings(replayed);
    }

    private void assertBindings(Archive archive) {
        assertEquals(Map.of("first", 1.0), archive.getNumbers().getByName());
        assertEquals(Map.of("first", "1.0"), archive.getLabels().getByName());
        assertEquals(Double.valueOf(0.5), archive.getNumbers().getLatest());
        assertEquals(List.of(1.0, 2.5), archive.getNumbers().getSamples());
        assertEquals("ok", archive.getLabels().getLatest());
        assertEquals(List.of("1.0", "2.5"), archive.getLabels().getSamples());
    }
}