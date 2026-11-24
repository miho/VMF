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
package eu.mihosoft.vmf.jackson.test.genericrelationships;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;
import eu.mihosoft.vmf.jackson.VMFTypeUtils;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GenericRelationshipsPersistenceTest {
    private static final VMFJacksonModule.RUNTIME_TYPE MODE = VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL;
    private static final String SUPPLIER_JSON = "{\"id\":\"group\",\"entries\":["
            + "{\"id\":\"a\",\"name\":\"Intake\"},{\"id\":\"b\",\"name\":\"Intake\"}]}";
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "stations"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) { return ((Identified) object).getId(); }
        @Override public String type(VObject object) {
            if (object instanceof StationGroup) return "group";
            if (object instanceof Station) return "station";
            throw new IllegalArgumentException("Unknown type " + object.getClass());
        }
        @Override public VObject create(String type, String id) {
            switch (type) {
                case "group": return StationGroup.newBuilder().withId(id).build();
                case "station": return Station.newBuilder().withId(id).build();
                default: throw new IllegalArgumentException("Unknown wire type " + type);
            }
        }
    };

    @Test void existingJsonUsesItsOwnFieldNamesAndPlainNestedObjects() throws Exception {
        var mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE));
        StationGroup group = mapper.readValue(SUPPLIER_JSON, StationGroup.class);
        Selection<Station> selection = group;
        ReadOnlySelection<ReadOnlyStation> view = group.asReadOnly();
        assertSame(selection.getOwned().get(0), selection.findOwnedById("a").orElseThrow());
        assertSame(view.getOwned().get(1), view.findOwnedById("b").orElseThrow());
        var encoded = mapper.readTree(mapper.writeValueAsBytes(group));
        assertEquals(mapper.readTree(SUPPLIER_JSON).get("entries"), encoded.get("entries"));
        assertFalse(encoded.has("owned"));
        assertFalse(encoded.has("@vmf-id"));
        var schema = mapper.readTree(VMFJsonSchemaGenerator.newInstance(MODE).generateSchemaAsString(StationGroup.class));
        var validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schema);
        assertTrue(validator.validate(mapper.readTree(SUPPLIER_JSON)).isEmpty());
        assertTrue(validator.validate(encoded).isEmpty());
    }

    @Test void existingJsonResolvesTheGenericElementInTheRequestedClassLoader() throws Exception {
        String modelPackage = StationGroup.class.getPackageName() + ".";
        ClassLoader parent = new ClassLoader(StationGroup.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(modelPackage)) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        var classes = StationGroup.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{classes}, parent)) {
            Class<? extends VObject> groupClass = loader.loadClass(StationGroup.class.getName()).asSubclass(VObject.class);
            Class<?> stationClass = loader.loadClass(Station.class.getName());
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE));
            var restored = mapper.readValue(SUPPLIER_JSON, groupClass);
            var owned = (List<?>) restored.vmf().reflect().propertyByName("owned").orElseThrow().get();
            assertTrue(stationClass.isInstance(owned.get(0)));
            assertFalse(Station.class.isInstance(owned.get(0)));
            var actualType = restored.vmf().reflect().type();
            assertFalse(actualType.isModelSubtypeOf(Forwarded.type()));
            assertTrue(actualType.isModelSubtypeOf(VMFTypeUtils.forClass(loader.loadClass(Forwarded.class.getName()))));
        }
    }

    @Test void inheritedImmutableTargetsSupportInlineJsonAndSharedIdentity() throws Exception {
        var plain = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE));
        var input = plain.readTree("{\"selected\":{\"text\":\"ok\"},\"labels\":[{\"text\":\"ok\"}]}");
        var review = plain.treeToValue(input, LabelReview.class);
        assertEquals(input, plain.readTree(plain.writeValueAsBytes(review)));
        assertFalse(review.getLabels().contains(review.getSelected()));
        review.getLabels().add(review.getSelected());

        for (ObjectMapper mapper : List.of(new ObjectMapper(), new YAMLMapper(), new XmlMapper())) {
            mapper.registerModule(VMFJacksonModule.newInstance(MODE).withReferences(true));
            var copy = mapper.readValue(mapper.writeValueAsBytes(review), LabelReview.class);
            ReadOnlyReview<Label> view = copy.asReadOnly();
            assertEquals(2, view.getLabels().size());
            assertTrue(view.getLabels().contains(view.getSelected()));
        }
    }

    @Test void inheritedReferencesRetainIdentityAndOrderInSnapshotsAndPortableReplay() throws Exception {
        var plain = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE));
        StationGroup working = plain.readValue(SUPPLIER_JSON, StationGroup.class);
        var first = working.getOwned().get(0);
        var second = working.getOwned().get(1);
        working.setCurrent(first);
        working.getQueue().addAll(List.of(first, second));
        working.getFavorites().add(second);
        StationGroup checkpoint = working.clone();
        ChangeJournal journal = JournalRecorder.recordFrame(working, BINDING, "imported", 0, group -> {
            group.getQueue().move(1, 0);
            group.setCurrent(second);
            group.getFavorites().add(first);
        });

        for (ObjectMapper mapper : List.of(new ObjectMapper(), new YAMLMapper(), new XmlMapper())) {
            mapper.registerModule(VMFJacksonModule.newInstance(MODE).withReferences(true));
            verify(mapper.readValue(mapper.writeValueAsBytes(working), StationGroup.class));
            StationGroup reloaded = mapper.readValue(mapper.writeValueAsBytes(checkpoint), StationGroup.class);
            for (VMFDocumentFormat format : VMFDocumentFormat.values()) {
                String wire = VMFJournalCodec.write(journal, format);
                ChangeJournal decoded = VMFJournalCodec.read(wire, format);
                assertEquals(wire, VMFJournalCodec.write(decoded, format));
                if (format == VMFDocumentFormat.JSON) assertEquals(wire, VMFJournalCodec.write(decoded));
                verify(JournalReplayer.replay(reloaded, BINDING, "imported", decoded));
            }
        }
    }

    private static void verify(StationGroup group) {
        Station first = group.findOwnedById("a").orElseThrow();
        Station second = group.findOwnedById("b").orElseThrow();
        assertSame(second, group.getCurrent());
        assertSame(second, group.getQueue().get(0));
        assertSame(first, group.getQueue().get(1));
        assertEquals(2, group.getFavorites().size());
        assertTrue(group.getFavorites().contains(first));
        assertTrue(group.getFavorites().contains(second));
    }
}
