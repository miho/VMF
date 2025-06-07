package eu.mihosoft.vmf.jackson.test.external_types02;

import org.junit.jupiter.api.Test;

public class ExternalTypeModel02Test {
    @Test
    void testModel() {

        ExternalTypeModel02 model = ExternalTypeModel02.newInstance();

        model.getStringMap().put("key1", "value1");
        model.getObjectMap().put("key2", 42);

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(
                eu.mihosoft.vmf.jackson.VMFJacksonModule.newInstance(
                        eu.mihosoft.vmf.jackson.VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                        .withTypeAlias("external-type-model-02", ExternalTypeModel02.class.getName())
        );

        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);
            System.out.println(json);

            // deserialize
            ExternalTypeModel02 model2 = mapper.readValue(json, ExternalTypeModel02.class);

            // serialize again
            String json2 = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(model2);
            System.out.println(json2);

            assert json.equals(json2) : "JSON strings are not equal";

            // check if deserialization worked
            assert model.vmf().content().equals(model2) : "Models are not equal";

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            e.printStackTrace();
        }

    }
}
