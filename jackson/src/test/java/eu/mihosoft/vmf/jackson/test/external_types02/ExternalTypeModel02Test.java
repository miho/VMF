package eu.mihosoft.vmf.jackson.test.external_types02;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips a model that stores {@code Map<String,String>} and {@code Map<String,Object>}
 * properties. Previously this used bare {@code assert} statements and swallowed serialization
 * exceptions, so it could pass without actually verifying anything.
 */
public class ExternalTypeModel02Test {

    private ObjectMapper mapper() {
        var mapper = new ObjectMapper();
        mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withTypeAlias("external-type-model-02", ExternalTypeModel02.class.getName()));
        return mapper;
    }

    @Test
    void mapsRoundTripThroughJson() throws JsonProcessingException {
        ExternalTypeModel02 model = ExternalTypeModel02.newInstance();
        model.getStringMap().put("key1", "value1");
        model.getObjectMap().put("key2", 42);

        ObjectMapper mapper = mapper();

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);

        ExternalTypeModel02 model2 = mapper.readValue(json, ExternalTypeModel02.class);

        // re-serialization must be stable
        String json2 = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(model2);
        assertEquals(json, json2, "re-serialized JSON must match the original");

        // deep content equality
        assertTrue(model.vmf().content().equals(model2), "deserialized model must equal the original");

        // and the actual map entries must survive
        assertEquals("value1", model2.getStringMap().get("key1"));
        assertEquals(42, model2.getObjectMap().get("key2"));
    }
}
