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

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManagedMapXmlTest {
    private static XmlMapper mapper(boolean references) {
        XmlMapper mapper = new XmlMapper();
        mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(references));
        return mapper;
    }

    @Test
    void preservesArbitraryKeysAndScalarPayloadsWithAndWithoutReferences() throws IOException {
        Labels labels = Labels.newInstance();
        labels.getNames().clear();
        labels.getNames().put("", "");
        labels.getNames().put("9 <unit>:/prefix", "  \t\n ");
        labels.getNames().put("\r\0\u0001\ud800", "null");
        labels.getNames().put("null", null);
        labels.getNames().put("temperature \ud83d\ude80", "\r\0\ud83d\ude80");
        labels.getCharacters().put("surrogate", '\ud800');
        labels.getCharacters().put("zero", '\0');
        labels.getNumbers().put("nan", Double.NaN);
        labels.getNumbers().put("negative-zero", -0.0);

        for (boolean references : List.of(false, true)) {
            XmlMapper mapper = mapper(references);
            String xml = mapper.writeValueAsString(labels);
            Labels restored = mapper.readValue(xml, Labels.class);
            assertEquals(labels.getNames(), restored.getNames());
            assertEquals(labels.getCharacters(), restored.getCharacters());
            assertEquals(labels.getNumbers(), restored.getNumbers());
            assertEquals(xml, mapper.writeValueAsString(restored));

            Ledger root = Ledger.newInstance();
            root.getCounts().put("a:b", 4);
            var child = Ledger.newInstance();
            child.getCounts().put("", null);
            root.getChildren().add(child);
            Ledger graph = mapper.readValue(mapper.writeValueAsString(root), Ledger.class);
            assertEquals(root.getCounts(), graph.getCounts());
            assertEquals(1, graph.getChildren().size());
            assertEquals(child.getCounts(), graph.getChildren().get(0).getCounts());
        }
    }

    @Test
    void preservesOrdinaryMapXmlShape() throws IOException {
        Labels labels = Labels.newInstance();
        labels.getExternal().put("zip", "01234");
        XmlMapper mapper = mapper(false);
        String xml = mapper.writeValueAsString(labels);
        assertTrue(xml.contains("<zip>01234</zip>"));
        assertEquals(labels.getExternal(), mapper.readValue(xml, Labels.class).getExternal());
    }

    @Test
    void emptyMapOverridesDefaultAndMalformedEntriesAreRejected() throws IOException {
        XmlMapper mapper = mapper(false);
        Labels empty = Labels.newInstance();
        empty.getNames().clear();
        assertTrue(mapper.readValue(mapper.writeValueAsString(empty), Labels.class).getNames().isEmpty());
        String entry = "<entry><key>x</key><value></value></entry>";
        for (String invalid : List.of(entry + entry, "<entry><value>x</value></entry>",
                "<entry><key>x</key><null>false</null></entry>",
                "<entry><key64>AA==</key64><value>x</value></entry>",
                "<entry><key>x</key><value>x</value><null>true</null></entry>")) {
            assertThrows(JsonMappingException.class,
                    () -> mapper.readValue("<Labels><names>" + invalid + "</names></Labels>", Labels.class));
        }
    }
}
