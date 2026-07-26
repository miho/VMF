package eu.mihosoft.vmf.jackson.test.external_types02;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression coverage for issue #75 (VMF Jackson external-types parsing problem).
 *
 * <p>The deserializer pre-reads each object into a {@code JsonNode} via
 * {@code p.readValueAsTree()}, which leaves the source parser positioned at
 * end-of-input. External / unhandled property types (notably {@code Map}) must
 * therefore be delegated to Jackson <em>from the tree node</em>
 * ({@code treeToValue(node, type)}), never from the exhausted parser. These
 * tests exercise maps whose values are themselves complex (nested objects and
 * arrays) so the tree-based delegation cannot silently regress.</p>
 */
@SuppressWarnings("unchecked")
public class ExternalTypeMapNestedTest {

    private ObjectMapper mapper() {
        var mapper = new ObjectMapper();
        mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withTypeAlias("external-type-model-02", ExternalTypeModel02.class.getName()));
        return mapper;
    }

    @Test
    void objectMapWithNestedObjectValueRoundTrips() throws Exception {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("inner", "x");
        nested.put("count", 7);

        ExternalTypeModel02 model = ExternalTypeModel02.newInstance();
        model.getObjectMap().put("meta", nested);
        model.getStringMap().put("k", "v");

        ObjectMapper mapper = mapper();
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);
        ExternalTypeModel02 model2 = mapper.readValue(json, ExternalTypeModel02.class);

        // stable re-serialization and surviving entries
        assertEquals(json, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(model2));
        assertEquals("v", model2.getStringMap().get("k"));
        assertEquals(nested, model2.getObjectMap().get("meta"));
    }

    @Test
    void objectMapWithArrayValueRoundTrips() throws Exception {
        ExternalTypeModel02 model = ExternalTypeModel02.newInstance();
        model.getObjectMap().put("tags", new ArrayList<>(List.of("a", "b", "c")));

        ObjectMapper mapper = mapper();
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);
        ExternalTypeModel02 model2 = mapper.readValue(json, ExternalTypeModel02.class);

        assertEquals(json, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(model2));
        assertEquals(List.of("a", "b", "c"), model2.getObjectMap().get("tags"));
    }
}
