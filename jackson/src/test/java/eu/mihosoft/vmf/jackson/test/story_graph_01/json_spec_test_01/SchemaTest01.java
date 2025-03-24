package eu.mihosoft.vmf.jackson.test.story_graph_01.json_spec_test_01;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;

import eu.mihosoft.vmf.jackson.test.story_graph_01.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class SchemaTest01 {

    @org.junit.jupiter.api.Test
    void testModelSpec() throws IOException {
        Map<String, Object> schema = VMFJsonSchemaGenerator.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
//                    .withTypeAlias("person", Person.class.getName())
//                    .withTypeAlias("employee", Employee.class.getName())
//                    .withTypeAlias("my-model", MyModel.class.getName())
                .generateSchema(StoryNode.class);
        ObjectMapper schemaMapper = new ObjectMapper();
        String jsonSchema = schemaMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);

        System.out.println(jsonSchema);

        // write schema to file
        Path schemaPath = Path.of("story-node-schema.json");
        Files.writeString(schemaPath, jsonSchema);
    }

}
