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
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;
import org.junit.jupiter.api.Test;

import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFFeatureCodec;
import eu.mihosoft.vmf.runtime.core.FeatureAttachments;
import eu.mihosoft.vmf.runtime.core.ModelRegistry;
import eu.mihosoft.vmf.runtime.core.VObject;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Locale;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImmutableReferencePersistenceTest {
    private static final VMFJacksonModule.RUNTIME_TYPE MODE = VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL;

    @Test void referencesPreserveAliasesAndEqualTwinsInEveryFormat() throws IOException {
        Label a = Label.newBuilder().withText("A").build();
        Label b = Label.newBuilder().withText("A").build();
        LabelSelection source = LabelSelection.newBuilder().withCurrent(a).withHistory(b, a, b)
                .withSelected(a, b).withOrdered(b, a).build();
        for (ObjectMapper mapper : List.of(new ObjectMapper(), new YAMLMapper(), new XmlMapper())) {
            mapper.registerModule(VMFJacksonModule.newInstance(MODE).withReferences(true));
            String document = mapper.writeValueAsString(source);
            LabelSelection projected = mapper.readValue(mapper.writeValueAsString(source.asReadOnly()), LabelSelection.class);
            assertSame(projected.getCurrent(), projected.getHistory().get(1));
            LabelSelection copy = mapper.readValue(document, LabelSelection.class);
            Label first = copy.getCurrent(), second = copy.getHistory().get(0);
            assertEquals(first, second);
            assertNotSame(first, second);
            assertSame(first, copy.getHistory().get(1));
            assertSame(second, copy.getHistory().get(2));
            assertSame(first, copy.getOrdered().get(1));
            assertSame(second, copy.getOrdered().get(0));
            assertTrue(copy.getSelected().contains(first));
            assertTrue(copy.getSelected().contains(second));
            assertEquals(2, copy.getSelected().size());
            assertEquals(document, mapper.writeValueAsString(copy));
            if (mapper.getClass() == ObjectMapper.class) validate(mapper, LabelSelection.class, document, true);
        }
    }

    @Test void ordinaryExternalArraysAreValidByValueWithoutVmfFields() throws IOException {
        String json = "{\"current\":{\"text\":\"old\"},\"selected\":[{\"text\":\"same\"},{\"text\":\"same\"}]}";
        for (boolean references : List.of(false, true)) {
            ObjectMapper mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE).withReferences(references));
            LabelSelection selection = mapper.readValue(json, LabelSelection.class);
            var iterator = selection.getSelected().iterator();
            Label first = iterator.next(), second = iterator.next();
            assertEquals(first, second);
            assertNotSame(first, second);
            validate(mapper, LabelSelection.class, json, references);
            if (!references) {
                String encoded = mapper.writeValueAsString(selection);
                assertFalse(encoded.contains("vmf-id"));
                assertFalse(encoded.contains("vmf-type"));
                assertEquals(mapper.readTree(json).get("selected"), mapper.readTree(encoded).get("selected"));
                validate(mapper, LabelSelection.class, encoded, false);
            }
        }
    }

    @Test void immutableRootsCanResolveForwardAndNestedDefinitions() throws IOException {
        ObjectMapper mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE).withReferences(true));
        String forward = "{\"first\":\"parent\",\"selected\":[\"leaf\"],\"nodes\":[{\"@vmf-id\":\"parent\",\"next\":\"leaf\",\"label\":\"label\"},"
                + "{\"@vmf-id\":\"leaf\",\"label\":{\"@vmf-id\":\"label\",\"text\":\"shared\"}}]}";
        LabelGraph graph = mapper.readValue(forward, LabelGraph.class);
        assertSame(graph.getFirst(), graph.getNodes().get(0));
        assertSame(graph.getFirst().getNext(), graph.getNodes().get(1));
        assertSame(graph.getFirst().getLabel(), graph.getNodes().get(1).getLabel());
        for (ObjectMapper format : List.of(new ObjectMapper(), new YAMLMapper(), new XmlMapper())) {
            format.registerModule(VMFJacksonModule.newInstance(MODE).withReferences(true));
            var copy = format.readValue(format.writeValueAsString(graph), LabelGraph.class);
            assertSame(copy.getFirst(), copy.getNodes().get(0));
            assertSame(copy.getFirst().getNext(), copy.getNodes().get(1));
            assertSame(copy.getFirst().getLabel(), copy.getNodes().get(1).getLabel());
            assertSame(copy.getNodes().get(1), copy.getSelected().iterator().next());
        }
    }

    @Test void xmlPreservesEachEmptyImmutableObjectAndEmptyCollections() throws IOException {
        for (boolean references : List.of(false, true)) {
            ObjectMapper mapper = new XmlMapper().registerModule(VMFJacksonModule.newInstance(MODE).withReferences(references));
            for (int count = 0; count <= 2; count++) {
                var source = LabelSelection.newBuilder().withCurrent(Label.newBuilder().build()).build();
                for (int i = 0; i < count; i++) {
                    source.getHistory().add(Label.newBuilder().build());
                    source.getSelected().add(Label.newBuilder().build());
                    source.getOrdered().add(Label.newBuilder().build());
                }
                String xml = mapper.writeValueAsString(source);
                var restored = mapper.readValue(xml, LabelSelection.class);
                assertNotNull(restored.getCurrent(), xml);
                assertNull(restored.getCurrent().getText(), xml);
                assertEquals(count, restored.getHistory().size(), xml);
                assertEquals(count, restored.getSelected().size(), xml);
                assertEquals(count, restored.getOrdered().size(), xml);
                assertTrue(restored.getHistory().stream().allMatch(v -> v != null && v.getText() == null), xml);
                assertTrue(restored.getSelected().stream().allMatch(v -> v != null && v.getText() == null), xml);
                assertTrue(restored.getOrdered().stream().allMatch(v -> v != null && v.getText() == null), xml);
            }
        }
    }

    @Test void xmlDistinguishesNullSlotsFromEmptyObjectsAndReferenceIds() throws IOException {
        var shared = Label.newBuilder().withText("shared").build();
        var source = LabelSelection.newBuilder().withCurrent(shared)
                .withHistory(Label.newBuilder().build(), shared).build();
        source.getHistory().add(1, null);
        for (boolean references : List.of(false, true)) {
            var mapper = new XmlMapper().registerModule(VMFJacksonModule.newInstance(MODE).withReferences(references));
            var xml = mapper.writeValueAsString(source);
            var restored = mapper.readValue(xml, LabelSelection.class);
            assertTrue(xml.contains("nil=\"true\""), xml);
            assertEquals(3, restored.getHistory().size(), xml);
            assertNotNull(restored.getHistory().get(0), xml);
            assertNull(restored.getHistory().get(0).getText(), xml);
            assertNull(restored.getHistory().get(1), xml);
            assertEquals("shared", restored.getHistory().get(2).getText(), xml);
            if (references) assertSame(restored.getCurrent(), restored.getHistory().get(2), xml);
        }
    }

    @Test void registryBoundCheckpointsPreserveAnEmptySharedImmutable(@TempDir Path dir)
            throws IOException {
        var registry = new ModelRegistry(List.of(LabelSelection.type().reflect().allTypes()));
        var codec = new VMFFeatureCodec(registry);
        var attachments = new FeatureAttachments(registry);
        var empty = Label.newBuilder().build();
        var model = LabelSelection.newBuilder().withCurrent(empty).withSelected(empty).build();
        for (var format : VMFDocumentFormat.values()) {
            var path = dir.resolve("checkpoint." + format.name().toLowerCase(Locale.ROOT));
            codec.save(path, model, attachments, format);
            var restored = codec.load(path, LabelSelection.class, format).owner();
            assertSame(restored.getCurrent(), restored.getSelected().iterator().next());
        }
    }

    @Test void malformedDefinitionsCannotCreatePartialOrAmbiguousImmutableGraphs() {
        ObjectMapper mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE).withReferences(true));
        for (String json : List.of(
                "{\"first\":\"missing\"}",
                "{\"first\":{\"@vmf-id\":\"loop\",\"next\":\"loop\"}}",
                "{\"first\":{\"@vmf-id\":\"self\",\"label\":\"self\"}}",
                "{\"first\":{\"@vmf-id\":\"x\"},\"nodes\":[{\"@vmf-id\":\"x\"}]}",
                "{\"first\":{\"label\":\"n\"},\"nodes\":[{\"@vmf-id\":\"n\"}]}",
                "{\"first\":{\"@vmf-id\":false}}",
                "{\"first\":{\"@vmf-id\":\"\"}}",
                "{\"first\":\"fake\",\"unmodeled\":{\"@vmf-id\":\"fake\"}}")) {
            assertThrows(IOException.class, () -> mapper.readValue(json, LabelGraph.class), json);
        }
        assertThrows(IOException.class, () -> mapper.readValue(
                "{\"selected\":[{\"@vmf-id\":\"x\"},\"x\"]}", LabelSelection.class));
        ObjectMapper plain = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE));
        assertThrows(IOException.class, () -> plain.readValue("{\"current\":\"x\"}", LabelSelection.class));
    }

    private static void validate(ObjectMapper mapper, Class<? extends VObject> model,
                                 String document, boolean references) throws IOException {
        var schema = VMFJsonSchemaGenerator.newInstance(MODE).withReferences(references).generateSchemaAsString(model);
        var errors = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(mapper.readTree(schema))
                .validate(mapper.readTree(document));
        assertTrue(errors.isEmpty(), errors.toString());
    }
}
