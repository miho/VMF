package eu.mihosoft.vmf.jackson.test.renameprops01;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RenamePropsModelTest {

    @org.junit.jupiter.api.Test
    void testModel() throws JsonProcessingException {

        RenamePropsModel model = RenamePropsModel.newBuilder()
                .withCOMPort("COM1")
                .build();

        var mapper = new ObjectMapper();
        mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withTypeAlias("rename-props-model", RenamePropsModel.class.getName())
        );


        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);
        System.out.println(json);

        // deserialize
        RenamePropsModel model2 = mapper.readValue(json, RenamePropsModel.class);

        // serialize again
        String json2 = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(model2);
        System.out.println(json2);

        assertEquals(json, json2);

        // check if deserialization worked
        assertTrue(model.vmf().content().equals(model2));


    }
}
